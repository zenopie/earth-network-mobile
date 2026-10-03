package network.erth.wallet.privacy

import com.google.protobuf.ByteString
import cosmos.gov.v1.WeightedVoteOption
import network.erth.earth.proto.allocation.AllocationWeight
import network.erth.earth.proto.assembly.MsgProposeRemoval
import network.erth.earth.proto.assembly.MsgVoteProposal
import network.erth.earth.proto.assembly.MsgVoteRemoval
import network.erth.earth.proto.assembly.VoteOption
import network.erth.earth.proto.dex.MsgAddLiquidityShielded
import network.erth.earth.proto.dex.MsgNoteSwap
import network.erth.earth.proto.dex.MsgRemoveLiquidityShielded
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.MsgSend
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.IdentityRecord
import network.erth.wallet.privacy.sync.PendingRegistration
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.Assembled
import network.erth.wallet.privacy.tx.BundleBuilder
import network.erth.wallet.privacy.tx.BundlePlan
import network.erth.wallet.privacy.tx.MembershipWitnessSpec
import network.erth.wallet.privacy.tx.NoteOut
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.tx.Prover
import network.erth.wallet.privacy.tx.StakePlan
import network.erth.wallet.privacy.tx.StakeSelection
import network.erth.wallet.privacy.tx.TxResult
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import java.math.BigDecimal

/** The chain reads the wallet's private msgs are built from. */
interface PrivacyChainReads {
    data class PersonhoodParams(val caretakerVoteSeconds: Long, val identityRootWindowSeconds: Long)
    data class BallotInputs(val scope: Fr, val excludedDsc: Fr, val excludedCountry: Fr, val maxActivation: Long, val round: Long, val ballotId: Long)
    /**
     * A proposal's stake-vote snapshot: the stake tree's root and size when it
     * entered voting, the block it did (0 when unknown) and rate_v (ERTH per
     * derth) per validator then, what a stake vote's derth weighs.
     */
    data class Snapshot(
        val root: Fr,
        val treeSize: Long,
        val height: Long = 0,
        val rates: Map<String, BigDecimal> = emptyMap(),
    )
    /** A Groundworks position: public, its owner known only by [ownerTag]. */
    data class Position(
        val id: Long,
        val validator: String,
        val derth: Long,
        val ownerTag: Fr,
        val splits: Map<Long, Long> = emptyMap(),
        val createdHeight: Long = 0,
    )

    fun personhoodParams(): PersonhoodParams
    fun ballotInputs(proposalId: Long = 0, optionId: Long = 0): BallotInputs
    fun epochNumber(): Long
    fun snapshot(proposalId: Long): Snapshot
    fun positions(): List<Position>
}

/**
 * One wallet's private side: its notes, stake notes and registration, kept in
 * sync with the chain through the indexer, and every private action the chain
 * offers, laid out as Orchard-style bundles (any number of notes, any asset,
 * padded to at least two actions), proven, signed and broadcast as an
 * unsigned tx whose fee comes out of the bundle's ERTH balance.
 *
 * Blocking throughout: call from an IO thread.
 */
class PrivacyWallet(
    val keys: PrivacyKeys,
    val store: PrivacyStore,
    private val indexer: PrivacyIndexer,
    private val chain: PrivateChain,
    private val reads: PrivacyChainReads,
    prover: Prover,
    val chainId: String,
    /** The chain's own trees, every synced root is checked against (C3). */
    private val roots: ChainRoots,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Sleeps between stake votes (milliseconds); tests pass a no-op. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val engine = PrivateTxEngine(chainId, chain, prover)

    val address: ShieldedAddress get() = keys.address

    @Synchronized
    fun sync(): WalletSync.Result = WalletSync(indexer, store, keys, chainId, roots).sync()

    fun identityStatus(): WalletSync.IdentityStatus = WalletSync(indexer, store, keys, chainId, roots).identityStatus()

    /** A committed registration whose leaf is not matched yet (null: none), and why, if it failed. */
    val pendingRegistration: PendingRegistration? get() = store.state.pendingRegistration

    val notes: List<OwnedNote> get() = store.state.notes.toList()

    val stakeNotes: List<OwnedStakeNote> get() = store.state.stakeNotes.toList()

    /** Spendable pool balance per denom (pending spends excluded). */
    fun poolBalances(): Map<String, Long> =
        store.state.notes.filter { it.unspent && it.pendingAt == null }
            .groupBy { it.note.denom }.mapValues { (_, ns) -> ns.sumOf { it.note.value } }

    /** Stake (derth/<valoper>) and unbonding claims (unbond/<valoper>/<epoch>) per denom: owner-locked, never sendable. */
    fun stakeBalances(): Map<String, Long> =
        store.state.stakeNotes.filter { it.spendable }.groupBy { it.denom }.mapValues { (_, ns) -> ns.sumOf { it.amount } }

    /** Everything held privately: the pool's denoms and the stake denoms. */
    fun balances(): Map<String, Long> = poolBalances() + stakeBalances()

    /** x/shielded max_actions_per_bundle: the most notes (and outputs) one bundle carries. */
    fun maxActions(): Int = chain.maxActionsPerBundle()

    /** A note the chain will mint to us: fresh secrets, their v2 ciphertext to our own address. */
    private fun mint(denom: String): NoteOut = NoteOut.mintToSelf(keys, denom)

    /** A stake note the chain will mint to us: spc_mint's fresh secrets and their blind stake ciphertext. */
    private fun stakeMint(): Pair<Pair<Fr, Fr>, ByteArray> = StakePlan.selfMint(keys)

    private fun today(): Long = now() / SECONDS_PER_DAY

    // ---- running ------------------------------------------------------------

    /**
     * Proves and broadcasts. Only on trees the chain itself vouched for at
     * the last sync (C3): a proof over an indexer's forged tree is refused by
     * the chain anyway, and its notes may not exist.
     */
    @Synchronized
    private fun run(memo: String = "", assemble: (fee: Long) -> Assembled): TxResult {
        requireVerified()
        val (result, a) = engine.run(assemble, memo)
        markPending(a.spends, a.stakeSpends)
        return result
    }

    private fun requireVerified() {
        check(store.state.rootsVerified) {
            store.state.rootsError ?: "the wallet has not checked its notes against the chain yet; sync again"
        }
    }

    /** What [run] would charge, without proving: for the confirm sheet. */
    fun quote(assemble: (fee: Long) -> Assembled): PrivateTxEngine.Quote = engine.quote(assemble)

    private fun markPending(spent: List<OwnedNote>, stake: List<OwnedStakeNote>) {
        val positions = spent.map { it.position }.toSet()
        val stakePositions = stake.map { it.position }.toSet()
        val s = store.state
        val t = now()
        for (i in s.notes.indices) if (s.notes[i].position in positions) s.notes[i] = s.notes[i].copy(pendingAt = t)
        for (i in s.stakeNotes.indices) if (s.stakeNotes[i].position in stakePositions) s.stakeNotes[i] = s.stakeNotes[i].copy(pendingAt = t)
        store.save()
    }

    /**
     * A bundle releasing [release] (per denom, the fee included in uerth) and
     * paying [outputs], from whichever notes cover it; [forced] are spent
     * whatever (a merge), their surplus coming back as change.
     */
    private fun bundle(outputs: List<NoteOut> = emptyList(), release: Map<String, Long>, forced: List<OwnedNote> = emptyList()): BundlePlan =
        BundleBuilder.plan(keys, store.noteTree, store.state.notes, outputs, release, maxActions(), forced = forced)

    /** A bundle whose only balance is [fee] uerth: a private action's fee. */
    private fun feeBundle(fee: Long): BundlePlan = bundle(release = mapOf(FEE to fee))

    private fun plus(a: Map<String, Long>, d: String, v: Long): Map<String, Long> = a + (d to Math.addExact(a[d] ?: 0L, v))

    // ---- membership ---------------------------------------------------------

    private fun identity(): IdentityRecord {
        val id = store.state.identity ?: throw IllegalStateException("this wallet has no registration")
        check(identityStatus() == WalletSync.IdentityStatus.LIVE) { "this wallet's registration is no longer live; register again" }
        return id
    }

    private fun membership(scope: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: Long): MembershipWitnessSpec {
        val id = identity()
        if (id.activatedAt > maxActivation) {
            throw NotYet(maxOf(0, id.activatedAt - maxActivation))
        }
        val tree = store.identityTree
        val path = tree.path(id.leafIndex)
        val root = tree.root()
        return MembershipWitnessSpec { signal ->
            MembershipWitness(
                idSecret = keys.idSecret, dscKey = id.dscKey, country = id.country, activatedAt = id.activatedAt,
                leafIndex = id.leafIndex, siblings = path, root = root, scope = scope, signal = signal,
                excludedDsc = excludedDsc, excludedCountry = excludedCountry, maxActivation = maxActivation,
            )
        }
    }

    /** The identity is too recent for this action; it opens [waitSeconds] from now. */
    class NotYet(val waitSeconds: Long) : Exception("this registration is too recent for this action; try again in ${waitSeconds / 3600 + 1}h")

    // ---- pool ---------------------------------------------------------------

    /** A private send of [amount] [denom] to [to]; the fee comes out of ERTH notes. */
    fun send(to: ShieldedAddress, denom: String, amount: Long, memo: ByteArray = ByteArray(0)): TxResult {
        requireTransferable(denom)
        return run { fee ->
            val b = bundle(listOf(NoteOut.to(to, denom, amount, memo)), mapOf(FEE to fee))
            Assembled(listOf(b)) { bs, _, _ -> MsgSend.newBuilder().setBundle(bs[0]).setFee(fee).build() }
        }
    }

    /**
     * Unshields so that [receiver] gets exactly [amount] [denom], the fee from
     * ERTH notes. With [feeFromAmount] (ERTH only) the receiver gets [amount]
     * less the fee instead: the bundle releases exactly [amount], so the most
     * a wallet holds can leave in one go (Max).
     */
    fun unshield(receiver: String, denom: String, amount: Long, feeFromAmount: Boolean = false, memo: String = ""): TxResult {
        requireTransferable(denom)
        require(!denom.startsWith(LP_PREFIX)) { "LP shares leave the pool only by a withdrawal" }
        require(!feeFromAmount || denom == FEE) { "only an ERTH unshield pays its fee from the amount" }
        // The memo (an exchange's deposit tag) is bound by the sighash.
        return run(memo) { fee ->
            val release = if (feeFromAmount) {
                require(amount > fee) { "the amount must exceed the ${fee}uerth fee" }
                mapOf(FEE to amount)
            } else {
                plus(mapOf(denom to amount), FEE, fee)
            }
            val b = bundle(release = release)
            Assembled(listOf(b)) { bs, _, _ -> MsgSend.newBuilder().setBundle(bs[0]).setReceiver(receiver).setFee(fee).build() }
        }
    }

    /**
     * Consolidates [denom]: its smallest spendable notes (as many as one
     * bundle carries) become one, the fee paid from ERTH notes (for ERTH,
     * from the merged notes themselves). Only needed when a balance is spread
     * over more notes than max_actions_per_bundle.
     */
    fun merge(denom: String): TxResult = run { fee ->
        val budget = if (denom == FEE) maxActions() else maxActions() - 1
        val ns = NoteSelection.spendable(store.state.notes, denom).sortedBy { it.note.value }.take(budget)
        require(ns.size >= 2) { "nothing to merge" }
        if (denom == FEE) require(ns.sumOf { it.note.value } > fee) { "these notes do not cover the ${fee}uerth fee" }
        val b = bundle(release = mapOf(FEE to fee), forced = ns)
        Assembled(listOf(b)) { bs, _, _ -> MsgSend.newBuilder().setBundle(bs[0]).setFee(fee).build() }
    }

    /** Spendable note counts per denom holding more than one note. */
    fun mergeable(): Map<String, Int> =
        store.state.notes.filter { it.unspent && it.pendingAt == null && it.note.value > 0 }
            .groupBy { it.note.denom }.mapValues { it.value.size }
            .filter { (_, n) -> n >= 2 }

    /**
     * A note to self for MsgShield (transparent coins into the pool; signed,
     * so built by the caller's key): the chain mints it, so it carries a v2
     * ciphertext of fresh secrets, which sync opens against the shield's
     * public amount.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shieldOutput(denom: String, amount: Long): NoteOut = mint(denom)

    private fun requireTransferable(denom: String) {
        require(!denom.startsWith(DERTH_PREFIX) && !denom.startsWith(UNBOND_PREFIX)) { "stake is owner-locked: it cannot be sent or unshielded" }
    }

    // ---- personhood ---------------------------------------------------------

    /**
     * The notes a registration pays and the binding its passport proof
     * carries. Hold until [register]. Both notes are chain-minted, so each
     * carries a v2 ciphertext of fresh secrets to our own address; the
     * binding covers those ciphertexts, so they are written here, before the
     * passport is proven, and sent exactly as they are. [gas] is the
     * /gas/register note (its ciphertext is not bound).
     */
    class RegistrationPrep(val anml: NoteOut, val erth: NoteOut, val gas: NoteOut, val affiliate: String, val binding: Fr, val idc: Fr)

    fun prepareRegistration(affiliate: String?): RegistrationPrep {
        val aff = affiliate?.trim().orEmpty()
        val anml = mint("uanml")
        val erth = mint(FEE)
        val gas = mint(FEE)
        val binding = Privacy.registrationBinding(keys.idc, anml.pc, anml.ciphertext, erth.pc, erth.ciphertext, PrivateMsgs.affiliateField(aff))
        return RegistrationPrep(anml, erth, gas, aff, binding, keys.idc)
    }

    /** MsgRegister without its fee bundle: what /gas/register checks. */
    fun registerMsg(prep: RegistrationPrep, proof: ByteArray, publicSignals: List<String>, signatureAlgorithm: String, dscDer: ByteArray): MsgRegister =
        MsgRegister.newBuilder()
            .setProof(ByteString.copyFrom(proof))
            .addAllPublicSignals(publicSignals)
            .setSignatureAlgorithm(signatureAlgorithm)
            .setDscDer(ByteString.copyFrom(dscDer))
            .setIdc(ByteString.copyFrom(prep.idc.toBytes()))
            .setPcAnml(ByteString.copyFrom(prep.anml.pc.toBytes()))
            .setCiphertextAnml(ByteString.copyFrom(prep.anml.ciphertext))
            .setPcErth(ByteString.copyFrom(prep.erth.pc.toBytes()))
            .setCiphertextErth(ByteString.copyFrom(prep.erth.ciphertext))
            .setAffiliate(prep.affiliate)
            .build()

    /**
     * Broadcasts the registration, its fee paid by a fee bundle (the gas
     * grant's note, on a first registration) that also carries the
     * registration record note (PRIVACY_FORMATS.md 3a: a value-0 note to
     * ourselves whose memo lets a wallet restored from the mnemonic find the
     * leaf), and records the registration as pending before anything else
     * (C2), then tries to resolve it. [publicSignals] are the passport
     * proof's: [current_date, address, nullifier, dsc_key].
     */
    fun register(prep: RegistrationPrep, proof: ByteArray, publicSignals: List<String>, signatureAlgorithm: String, dscDer: ByteArray): TxResult {
        require(PrivateMsgs.decimalField(publicSignals[1]) == prep.binding) { "the passport proof is bound to other notes" }
        val base = registerMsg(prep, proof, publicSignals, signatureAlgorithm, dscDer)
        val dscKey = PrivateMsgs.decimalField(publicSignals[3])
        val hint = dscCountry(dscDer)
        val record = NoteOut.to(keys.address, FEE, 0, WalletSync.regMemo(dscKey, hint, now()))
        val result = run { fee ->
            Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee)))) { bs, _, _ -> base.toBuilder().setFee(bs[0]).build() }
        }
        recordRegistration(result, dscKey, publicSignals, hint)
        return result
    }

    /**
     * Persists the committed registration as pending (C2) — its leaf index
     * from the tx's register event, activated_at its block time — before
     * syncing, so a lagging indexer can never lose it; every later sync
     * retries until the leaf is in the local identity tree and matches.
     */
    fun recordRegistration(result: TxResult, dscKey: Fr, publicSignals: List<String>, countryHint: String = "") {
        val index = result.attr("register", "leaf_index")?.toLongOrNull()
            ?: throw IllegalStateException("registration tx ${result.hash} has no leaf_index")
        synchronized(this) {
            store.state.pendingRegistration = PendingRegistration(
                txHash = result.hash, leafIndex = index, dscKey = dscKey, passportNullifier = publicSignals.getOrElse(2) { "" },
                publicSignals = publicSignals, activatedAt = result.time, countryHint = countryHint,
            )
            store.save()
        }
        runCatching { sync() }
    }

    /** Today's ANML. Opens once the identity was activated before yesterday began. */
    fun claimAnml(day: Long = today()): TxResult {
        // Chain-minted: a v2 ciphertext, opened against the mint's public amount.
        val anml = mint("uanml")
        val m = membership(Privacy.claimScope(day), Fr.ZERO, Fr.ZERO, (day - 1) * SECONDS_PER_DAY)
        val r = run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgClaimAnml.newBuilder().setFee(bs[0]).setMembership(mem).setDay(day)
                    .setPc(ByteString.copyFrom(anml.pc.toBytes())).setCiphertext(ByteString.copyFrom(anml.ciphertext)).build()
            }
        }
        store.state.claimedDays.add(day); store.save()
        return r
    }

    fun claimedToday(): Boolean = today() in store.state.claimedDays

    /**
     * When ANML can next be claimed: 0 for now, null without a live
     * registration. A claim for day d needs activated_at <= (d - 1) * 86400,
     * so a fresh registration first claims on the day after next.
     */
    fun claimOpensAt(): Long? {
        if (identityStatus() != WalletSync.IdentityStatus.LIVE) return null
        val id = store.state.identity ?: return null
        val firstDay = id.activatedAt / SECONDS_PER_DAY + 1 + if (id.activatedAt % SECONDS_PER_DAY == 0L) 0 else 1
        val day = maxOf(today() + if (claimedToday()) 1 else 0, firstDay)
        return if (day == today()) 0L else day * SECONDS_PER_DAY
    }

    /**
     * The max_activation a caretaker split or referrer binding names: at most
     * now - R - root window (the chain's bound), rounded down to the hour so it
     * says nothing about when the tx was made, less a margin for clock skew.
     */
    private fun leaseBound(): Long {
        val p = reads.personhoodParams()
        val bound = now() - p.caretakerVoteSeconds - p.identityRootWindowSeconds - CLOCK_MARGIN
        return bound / 3600 * 3600
    }

    /** Casts, refreshes or (empty) clears the caretaker split, option id -> percent. */
    fun setCaretaker(split: Map<Long, Long>): TxResult {
        val maxAct = leaseBound()
        val m = membership(Privacy.caretakerScope(), Fr.ZERO, Fr.ZERO, maxAct)
        val weights = weights(split)
        val r = run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgSetCaretaker.newBuilder().setFee(bs[0]).setMembership(mem).addAllPercentages(weights).setMaxActivation(maxAct).build()
            }
        }
        store.state.caretakerCastAt = now(); store.state.caretakerSplit = split; store.save()
        return r
    }

    /** Whether the split needs refreshing to stay counted: past half of R, or never cast. */
    fun caretakerDue(): Boolean {
        if (store.state.caretakerSplit.isEmpty()) return false
        val r = reads.personhoodParams().caretakerVoteSeconds
        return now() - store.state.caretakerCastAt > r / 2
    }

    /**
     * Binds (or, empty, clears) the transparent address this person's
     * referral rewards are paid to. The binding is public (the address is),
     * the person behind it is not; it lapses after R unless refreshed.
     */
    fun bindReferrer(address: String): TxResult {
        val maxAct = leaseBound()
        val m = membership(Privacy.referrerScope(), Fr.ZERO, Fr.ZERO, maxAct)
        val r = run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgBindReferrer.newBuilder().setFee(bs[0]).setMembership(mem).setAddress(address).setMaxActivation(maxAct).build()
            }
        }
        store.state.referrerAddress = address; store.state.referrerBoundAt = if (address.isEmpty()) 0 else now(); store.save()
        return r
    }

    /** Whether the referrer binding needs refreshing to stay live: past half of R. */
    fun referrerDue(): Boolean {
        if (store.state.referrerAddress.isEmpty()) return false
        val r = reads.personhoodParams().caretakerVoteSeconds
        return now() - store.state.referrerBoundAt > r / 2
    }

    // ---- assembly -----------------------------------------------------------

    /** A human vote on an x/gov proposal. The scope is recomputed here rather than taken from the node. */
    fun voteProposal(proposalId: Long, yes: Boolean): TxResult {
        val b = reads.ballotInputs(proposalId = proposalId)
        check(b.scope == Privacy.proposalScope(proposalId, b.round)) { "the node's ballot scope is not this proposal's" }
        val m = membership(b.scope, b.excludedDsc, b.excludedCountry, b.maxActivation)
        val option = if (yes) VoteOption.VOTE_OPTION_YES else VoteOption.VOTE_OPTION_NO
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgVoteProposal.newBuilder().setFee(bs[0]).setMembership(mem).setProposalId(proposalId).setOption(option).build()
            }
        }
    }

    fun proposeRemoval(optionId: Long): TxResult {
        val day = today()
        val window = reads.personhoodParams().identityRootWindowSeconds
        val m = membership(Privacy.proposeRemovalScope(optionId, day), Fr.ZERO, Fr.ZERO, day * SECONDS_PER_DAY - window)
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgProposeRemoval.newBuilder().setFee(bs[0]).setMembership(mem).setOptionId(optionId).build()
            }
        }
    }

    fun voteRemoval(optionId: Long, yes: Boolean): TxResult {
        val b = reads.ballotInputs(optionId = optionId)
        check(b.scope == Privacy.removalScope(b.ballotId)) { "the node's ballot scope is not this ballot's" }
        val m = membership(b.scope, b.excludedDsc, b.excludedCountry, b.maxActivation)
        val option = if (yes) VoteOption.VOTE_OPTION_YES else VoteOption.VOTE_OPTION_NO
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgVoteRemoval.newBuilder().setFee(bs[0]).setMembership(mem).setOptionId(optionId).setOption(option).build()
            }
        }
    }

    // ---- private staking (owner-locked stake notes) -----------------------------

    /** The stake tree's latest root (anchors a stake proof; unchecked when it spends nothing). */
    private fun stakeAnchor(): Fr = if (store.stakeTree.size == 0L) Fr.ZERO else store.stakeTree.root()

    private fun stakePlan(
        denom: String?,
        spends: List<OwnedStakeNote>,
        outAmounts: List<Long>,
        vOut: Long,
        /** A self-mint (StakePlan.selfMint) for a msg the chain mints a stake note for; null: throwaway secrets, no ciphertext. */
        mint: Pair<Pair<Fr, Fr>, ByteArray>? = null,
        salt: Fr = StakePlan.throwaway().first,
        anchor: Fr = stakeAnchor(),
        paths: List<List<Fr>> = spends.map { store.stakeTree.path(it.position) },
    ): StakePlan = StakePlan(
        keys.nk, denom, spends, paths, outAmounts.filter { it > 0 }.map { StakePlan.out(keys, denom!!, it) },
        mint?.first ?: StakePlan.throwaway(), salt, anchor, vOut, mint?.second ?: ByteArray(0),
    )

    /** Stake notes of [denom] this wallet can spend now. */
    fun spendableStake(denom: String): List<OwnedStakeNote> = store.state.stakeNotes.filter { it.spendable && it.denom == denom }

    /**
     * Stakes [amount] uerth with [validator]: the bundle releases it (and the
     * fee) into the module, the chain mints derth at the live rate as a stake
     * note to our stake self-mint pc, delegated at the epoch's end.
     */
    fun delegate(validator: String, amount: Long): TxResult {
        require(amount > 0)
        val stake = stakePlan(derthDenom(validator), emptyList(), emptyList(), 0, mint = stakeMint())
        return run { fee ->
            // The fee is the bundle's uerth balance less amount.
            val b = bundle(release = mapOf(FEE to Math.addExact(amount, fee)))
            Assembled(listOf(b), stake) { bs, sp, _ ->
                MsgDelegate.newBuilder().setBundle(bs[0]).setValidator(validator).setAmount(amount).setStake(sp).build()
            }
        }
    }

    /**
     * Merges or splits stake notes of [validator]: spends [notes] (1-2) and
     * creates notes of [amounts] (1-2, summing to theirs), all ours. Stake
     * moves in at most two notes a proof, so a balance spread over more is
     * merged first.
     */
    fun restake(validator: String, notes: List<OwnedStakeNote>, amounts: List<Long>): TxResult {
        val denom = derthDenom(validator)
        require(notes.size in 1..2 && amounts.size in 1..2 && amounts.all { it > 0 })
        require(notes.sumOf { it.amount } == amounts.sum()) { "a restake keeps the amount" }
        val stake = stakePlan(denom, notes, amounts, 0)
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgRestake.newBuilder().setBundle(bs[0]).setValidator(validator).setStake(sp).build()
            }
        }
    }

    /** derth denoms held in more than one stake note, with their note counts. */
    fun stakeMergeable(): Map<String, Int> =
        store.state.stakeNotes.filter { it.spendable && it.denom.startsWith(DERTH_PREFIX) }
            .groupBy { it.denom }.mapValues { it.value.size }.filter { (_, n) -> n >= 2 }

    /**
     * Makes [amount] of [denom] spendable by one stake proof (two notes):
     * while the two largest fall short, merges the two smallest (one restake
     * each) and syncs. Returns the merge txs it broadcast.
     */
    fun consolidateStake(denom: String, amount: Long): List<TxResult> {
        val out = ArrayList<TxResult>()
        while (true) {
            val ns = spendableStake(denom)
            if (ns.sortedByDescending { it.amount }.take(2).sumOf { it.amount } >= amount || ns.size < 3) return out
            out.add(mergeStake(denom))
            sync()
        }
    }

    /** Merges the two smallest stake notes of [denom] (derth/<valoper>) into one. */
    fun mergeStake(denom: String): TxResult {
        val two = spendableStake(denom).sortedBy { it.amount }.take(2)
        require(two.size == 2) { "nothing to merge" }
        return restake(parseDerth(denom), two, listOf(two.sumOf { it.amount }))
    }

    /**
     * Turns [amount] derth/[validator] into an owner-locked unbonding claim,
     * minted to our stake self-mint pc at the live rate; claimable once its
     * epoch's undelegation matures.
     */
    fun undelegate(validator: String, amount: Long): TxResult {
        val denom = derthDenom(validator)
        val ins = StakeSelection.cover(spendableStake(denom), amount)
        val stake = stakePlan(denom, ins, listOf(ins.sumOf { it.amount } - amount), amount, mint = stakeMint())
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgUndelegate.newBuilder().setBundle(bs[0]).setValidator(validator).setAmount(amount).setStake(sp).build()
            }
        }
    }

    /**
     * Claims matured unbonding claims of [denom] (unbond/<valoper>/<epoch>,
     * up to two notes a claim): the chain mints their ERTH to a pc of ours in
     * the pool (v2 ciphertext), the fee out of it (fee_from_output, the one
     * msg that may), so the msg carries no bundle at all.
     */
    fun claimUnbonding(denom: String): TxResult {
        val (validator, epoch) = parseUnbond(denom)
        val ins = spendableStake(denom).sortedByDescending { it.amount }.take(2)
        require(ins.isNotEmpty()) { "no unbonding claim of $denom" }
        val amount = ins.sumOf { it.amount }
        val stake = stakePlan(denom, ins, emptyList(), amount)
        val erth = mint(FEE)
        return run { fee ->
            Assembled(emptyList(), stake) { _, sp, _ ->
                MsgClaimUnbonding.newBuilder().setValidator(validator).setEpoch(epoch).setAmount(amount)
                    .setPc(ByteString.copyFrom(erth.pc.toBytes())).setCiphertext(ByteString.copyFrom(erth.ciphertext))
                    .setFeeFromOutput(fee).setStake(sp).build()
            }
        }
    }

    /** Unbonding claim denoms this wallet holds. */
    fun unbondDenoms(): Set<String> = store.state.stakeNotes.filter { it.spendable && it.denom.startsWith(UNBOND_PREFIX) }.map { it.denom }.toSet()

    /**
     * Spend-to-vote: [notes] (1-2 derth notes of one validator, in the stake
     * tree at the proposal's snapshot) are spent against the snapshot root,
     * their sum is the vote's weight, and the chain re-mints it to our stake
     * self-mint pc. The fee bundle is against the pool's current roots.
     */
    fun stakeVote(proposalId: Long, notes: List<OwnedStakeNote>, options: List<WeightedVoteOption>): TxResult {
        require(notes.size in 1..2)
        val denom = notes.first().denom
        require(notes.all { it.denom == denom }) { "one validator per stake vote" }
        val validator = parseDerth(denom)
        val snap = reads.snapshot(proposalId)
        require(notes.all { it.position < snap.treeSize }) { "this stake arrived after the proposal's snapshot and cannot vote on it" }
        val tree = store.stakeTree
        check(tree.rootAt(snap.treeSize) == snap.root) { "the local stake tree disagrees with the proposal's snapshot root" }
        val weight = notes.sumOf { it.amount }
        val stake = stakePlan(denom, notes, emptyList(), weight, mint = stakeMint(), anchor = snap.root,
            paths = notes.map { tree.pathAt(it.position, snap.treeSize) })
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgStakeVote.newBuilder().setBundle(bs[0]).setProposalId(proposalId).setValidator(validator)
                    .addAllOptions(options).setWeight(weight).setStake(sp).build()
            }
        }
    }

    /** What a stake vote on a proposal weighs: notes, positions that can vote, uerth. */
    data class StakeWeight(val notes: Int, val positionIds: Set<Long>, val uerth: Long)

    /**
     * This wallet's weight on [proposalId]: every derth note that can
     * stake-vote and every position created before the snapshot's block (the
     * chain refuses later ones), each at its validator's rate at the snapshot.
     */
    fun stakeVoteWeight(proposalId: Long, positions: List<PrivacyChainReads.Position>): StakeWeight {
        val snap = reads.snapshot(proposalId)
        val notes = eligible(snap)
        val ps = votingPositions(positions, snap)
        val total = notes.sumOf { derthValue(it.amount, snap.rates[parseDerth(it.denom)] ?: BigDecimal.ONE) } +
            ps.sumOf { derthValue(it.derth, snap.rates[it.validator] ?: BigDecimal.ONE) }
        return StakeWeight(notes.size, ps.map { it.id }.toSet(), total)
    }

    private fun eligible(snap: PrivacyChainReads.Snapshot): List<OwnedStakeNote> =
        store.state.stakeNotes.filter { it.spendable && it.denom.startsWith(DERTH_PREFIX) && it.position < snap.treeSize }

    /** The derth notes that can stake-vote on [proposalId]: unspent, and in the stake tree at its snapshot. */
    fun stakeVoteNotes(proposalId: Long): List<OwnedStakeNote> = eligible(reads.snapshot(proposalId))

    /**
     * Votes every eligible derth note on [proposalId], two notes of one
     * validator a tx. Final: the spent nullifiers and the re-minted notes'
     * absence from the snapshot root stop a second vote.
     *
     * One vote at a time (L4): between votes a full sync (so the next fee
     * is laid out from the chain's view, never the previous vote's change
     * unseen) and a random pause of [VOTE_PAUSE_MIN_MS]..[VOTE_PAUSE_MAX_MS],
     * so the votes are not one burst that times them together.
     */
    fun stakeVoteAll(proposalId: Long, options: List<WeightedVoteOption>): List<TxResult> {
        val ns = stakeVoteNotes(proposalId)
        require(ns.isNotEmpty()) { "no stake from before this proposal's snapshot" }
        val groups = ns.groupBy { it.denom }.values.flatMap { it.chunked(2) }.shuffled(rng)
        val out = ArrayList<TxResult>()
        for ((i, g) in groups.withIndex()) {
            if (i > 0) {
                pause(VOTE_PAUSE_MIN_MS + (rng.nextDouble() * (VOTE_PAUSE_MAX_MS - VOTE_PAUSE_MIN_MS)).toLong())
                sync()
            }
            // The notes as the last sync left them (a pending one is skipped).
            val now = g.mapNotNull { n -> store.state.stakeNotes.firstOrNull { it.position == n.position && it.spendable } }
            if (now.isNotEmpty()) out.add(stakeVote(proposalId, now, options))
        }
        return out
    }

    /**
     * This wallet's Groundworks positions: the public positions whose owner
     * tag is one of ours (owner-tag counters 0 ... last+gap, so a wallet
     * restored from the mnemonic finds them too), each with its counter.
     */
    fun positions(): List<Pair<PrivacyChainReads.Position, Int>> {
        val s = store.state
        val all = reads.positions()
        // Counters 0 ... next + OTAG_GAP, extended past every match: closed
        // positions vanish from the chain, so the window must cross a run of
        // them (and of failed locks) to reach a live one.
        var limit = s.nextOtagCounter + OTAG_GAP
        val out = ArrayList<Pair<PrivacyChainReads.Position, Int>>()
        var from = 0
        while (from < limit) {
            val mine = (from until limit).associateBy { ownerTag(it) }
            val found = all.mapNotNull { p -> mine[p.ownerTag]?.let { p to it } }
            out += found
            from = limit
            found.maxOfOrNull { it.second }?.let { top -> if (top + 1 + OTAG_GAP > limit) limit = top + 1 + OTAG_GAP }
        }
        out.maxOfOrNull { it.second }?.let { top ->
            if (top + 1 > s.nextOtagCounter) { s.nextOtagCounter = top + 1; store.save() }
        }
        return out.sortedBy { it.first.id }
    }

    private val otags = HashMap<Int, Fr>()

    private fun ownerTag(c: Int): Fr = synchronized(otags) { otags.getOrPut(c) { keys.ownerTag(c) } }

    /** Locks [amount] derth/[validator] into a new position split by [splits], under a fresh owner tag. */
    fun lockPosition(validator: String, amount: Long, splits: Map<Long, Long>): TxResult {
        val denom = derthDenom(validator)
        val ins = StakeSelection.cover(spendableStake(denom), amount)
        positions() // a restored wallet's counter starts past every tag it already holds
        val counter = synchronized(this) { store.state.nextOtagCounter.also { store.state.nextOtagCounter = it + 1; store.save() } }
        val stake = stakePlan(denom, ins, listOf(ins.sumOf { it.amount } - amount), amount, salt = keys.otagSalt(counter))
        val w = weights(splits)
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgLockPosition.newBuilder().setBundle(bs[0]).setValidator(validator).setAmount(amount)
                    .addAllSplits(w).setStake(sp).build()
            }
        }
    }

    private fun ownerPlan(position: PrivacyChainReads.Position, counter: Int, mint: Pair<Pair<Fr, Fr>, ByteArray>? = null): StakePlan {
        check(keys.ownerTag(counter) == position.ownerTag) { "position ${position.id} is not owned by tag $counter" }
        return stakePlan(null, emptyList(), emptyList(), 0, mint = mint, salt = keys.otagSalt(counter))
    }

    fun updatePosition(position: PrivacyChainReads.Position, counter: Int, splits: Map<Long, Long>): TxResult {
        val stake = ownerPlan(position, counter)
        val w = weights(splits)
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgUpdatePosition.newBuilder().setBundle(bs[0]).setPositionId(position.id).addAllSplits(w).setStake(sp).build()
            }
        }
    }

    /** Closes [position]; its derth comes back as a stake note to our stake self-mint pc. */
    fun unlockPosition(position: PrivacyChainReads.Position, counter: Int): TxResult {
        val stake = ownerPlan(position, counter, mint = stakeMint())
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgUnlockPosition.newBuilder().setBundle(bs[0]).setPositionId(position.id).setStake(sp).build()
            }
        }
    }

    fun positionVote(position: PrivacyChainReads.Position, counter: Int, proposalId: Long, options: List<WeightedVoteOption>): TxResult {
        val stake = ownerPlan(position, counter)
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgPositionVote.newBuilder().setBundle(bs[0]).setPositionId(position.id).setProposalId(proposalId)
                    .addAllOptions(options).setStake(sp).build()
            }
        }
    }

    private fun weights(splits: Map<Long, Long>): List<AllocationWeight> =
        splits.entries.sortedBy { it.key }.map { AllocationWeight.newBuilder().setOptionId(it.key).setPercent(it.value).build() }

    // ---- dex ----------------------------------------------------------------

    /**
     * Swaps [amountIn] [denomIn] from notes for at least [minOut] [denomOut]
     * (any pools, through the ERTH hub), the output minted to us or to [to]
     * with a value-blind (v2) ciphertext opened against the amount the chain
     * publishes. The fee comes from the bundle's ERTH balance (the one fee
     * rule: a swap of ANML into ERTH needs an ERTH note for it too).
     */
    fun noteSwap(denomIn: String, amountIn: Long, denomOut: String, minOut: Long, to: ShieldedAddress? = null): TxResult {
        require(denomIn != denomOut && amountIn > 0 && minOut > 0)
        requireTransferable(denomIn)
        val out = payout(denomOut, to)
        return run { fee ->
            val b = bundle(release = plus(mapOf(denomIn to amountIn), FEE, fee))
            Assembled(listOf(b)) { bs, _, _ ->
                MsgNoteSwap.newBuilder().setBundle(bs[0]).setDenomIn(denomIn).setAmountIn(amountIn)
                    .setDenomOut(denomOut).setMinAmountOut(minOut)
                    .setPc(ByteString.copyFrom(out.pc.toBytes())).setCiphertext(ByteString.copyFrom(out.ciphertext)).build()
            }
        }
    }

    /**
     * Deposits [tokenAmount] [token] and [erthAmount] uerth from notes into
     * [poolId], in one bundle (its token balance the token leg, its uerth
     * balance less the fee the ERTH leg, named as erth_amount). The LP shares
     * are private: minted as a dexlp/<pool> note to a pc of ours. Whatever the
     * pool ratio does not take is minted back to one refund pc (a note per
     * asset, both opened by the one v2 refund ciphertext).
     */
    fun addLiquidityShielded(poolId: Long, token: String, tokenAmount: Long, erthAmount: Long, minShares: String): TxResult {
        require(tokenAmount > 0 && erthAmount > 0 && token != FEE)
        val refund = mint(token)
        val shares = mint(lpDenom(poolId))
        return run { fee ->
            val b = bundle(release = mapOf(token to tokenAmount, FEE to Math.addExact(erthAmount, fee)))
            Assembled(listOf(b)) { bs, _, _ ->
                MsgAddLiquidityShielded.newBuilder().setBundle(bs[0]).setPoolId(poolId).setMinShares(minShares)
                    .setRefundPc(ByteString.copyFrom(refund.pc.toBytes())).setRefundCiphertext(ByteString.copyFrom(refund.ciphertext))
                    .setSharePc(ByteString.copyFrom(shares.pc.toBytes())).setShareCiphertext(ByteString.copyFrom(shares.ciphertext))
                    .setErthAmount(erthAmount).build()
            }
        }
    }

    /**
     * Withdraws [shares] dexlp/[poolId] from share notes: escrowed for the
     * dex's LP unbonding period with no account named, then both legs
     * (ERTH and [token]) minted to pcs of ours as notes (v2 ciphertexts).
     */
    fun removeLiquidityShielded(poolId: Long, token: String, shares: Long): TxResult {
        require(shares > 0)
        val erth = mint(FEE)
        val tok = mint(token)
        return run { fee ->
            val b = bundle(release = mapOf(lpDenom(poolId) to shares, FEE to fee))
            Assembled(listOf(b)) { bs, _, _ ->
                MsgRemoveLiquidityShielded.newBuilder().setBundle(bs[0]).setPoolId(poolId)
                    .setErthPc(ByteString.copyFrom(erth.pc.toBytes())).setErthCiphertext(ByteString.copyFrom(erth.ciphertext))
                    .setTokenPc(ByteString.copyFrom(tok.pc.toBytes())).setTokenCiphertext(ByteString.copyFrom(tok.ciphertext))
                    .build()
            }
        }
    }

    /** Private LP shares per pool id (dexlp/<id> notes). */
    fun lpShares(): Map<Long, Long> = poolBalances().filterKeys { it.startsWith(LP_PREFIX) }
        .mapNotNull { (d, v) -> d.removePrefix(LP_PREFIX).toLongOrNull()?.let { it to v } }.toMap()

    /**
     * The note a pool-1 MsgRemoveLiquidity names for its ANML leg (signed by
     * the provider's transparent key): pc and v2 ciphertext to us, since the
     * payout is priced when the withdrawal matures.
     */
    fun withdrawalNote(): NoteOut = payout("uanml", null)

    /**
     * Where a chain-priced payment goes (a swap's or a MsgBuyAnml's output,
     * a withdrawal's token leg): a pc of fresh secrets, ours or [to]'s, with
     * a value-blind (v2) ciphertext the owner opens once the chain publishes
     * the amount.
     */
    fun payout(denom: String, to: ShieldedAddress?): NoteOut =
        if (to == null || to.ownerPk == keys.ownerPk) mint(denom) else NoteOut.blindTo(to, denom)

    companion object {
        const val FEE = "uerth"
        const val DERTH_PREFIX = "derth/"
        const val UNBOND_PREFIX = "unbond/"
        const val LP_PREFIX = "dexlp/"

        /** floor(derth x rate) in uerth, the chain's conversion of derth to ERTH. */
        fun derthValue(derth: Long, rate: BigDecimal): Long =
            BigDecimal.valueOf(derth).multiply(rate).setScale(0, java.math.RoundingMode.DOWN).toLong()

        /** Positions created before the block the proposal entered voting at (all, when unknown). */
        fun votingPositions(positions: List<PrivacyChainReads.Position>, snap: PrivacyChainReads.Snapshot) =
            positions.filter { snap.height == 0L || it.createdHeight < snap.height }

        /** Owner-tag counters scanned past the highest known (PRIVACY_FORMATS.md 1). */
        const val OTAG_GAP = 1024

        /** The random pause between stake votes (L4). */
        const val VOTE_PAUSE_MIN_MS = 20_000L
        const val VOTE_PAUSE_MAX_MS = 120_000L

        private val rng = java.security.SecureRandom()

        /**
         * The ISO alpha-2 of the DSC's issuer (C=): the wallet's guess at the
         * country the chain records for the registration (the verifying
         * CSCA's). "" when unparsable.
         */
        fun dscCountry(dscDer: ByteArray): String = runCatching {
            val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(java.io.ByteArrayInputStream(dscDer)) as java.security.cert.X509Certificate
            val dn = cert.issuerX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
            Regex("""(?:^|,)\s*C=([A-Za-z]{2})\s*(?:,|$)""").find(dn)?.groupValues?.get(1)?.uppercase()
        }.getOrNull() ?: ""

        const val SECONDS_PER_DAY = 86_400L
        const val ANML_PER_CLAIM = 1_000_000L
        /**
         * MsgRegister's gas, for the fee estimate before simulating: the
         * passport proof (3M) and DSC chain (300k), the fee bundle's two
         * action proofs and note writes, and the tx's bytes.
         */
        const val REGISTER_GAS_ESTIMATE = 7_000_000L
        /** Slack against the chain's clock for bounds the wallet must stay under. */
        const val CLOCK_MARGIN = 600L

        fun derthDenom(valoper: String) = "$DERTH_PREFIX$valoper"
        fun unbondDenom(valoper: String, epoch: Long) = "$UNBOND_PREFIX$valoper/$epoch"
        fun lpDenom(poolId: Long) = "$LP_PREFIX$poolId"

        fun parseDerth(denom: String): String {
            require(denom.startsWith(DERTH_PREFIX)) { "not a derth note" }
            return denom.removePrefix(DERTH_PREFIX)
        }

        fun parseUnbond(denom: String): Pair<String, Long> {
            val parts = denom.split("/")
            require(parts.size == 3 && parts[0] == "unbond") { "not an unbond note" }
            return parts[1] to parts[2].toLong()
        }
    }
}
