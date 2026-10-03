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
import network.erth.wallet.privacy.prove.VoteWitness
import network.erth.wallet.privacy.sync.StakeVoteRecord
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
import network.erth.wallet.privacy.tx.VoteWitnessSpec
import network.erth.wallet.privacy.zk.IndexedTree
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import java.math.BigDecimal

/** The chain reads the wallet's private msgs are built from. */
interface PrivacyChainReads {
    data class PersonhoodParams(val caretakerVoteSeconds: Long, val identityRootWindowSeconds: Long)
    data class BallotInputs(val scope: Fr, val excludedDsc: Fr, val excludedCountry: Fr, val maxActivation: Long, val round: Long, val ballotId: Long)
    /**
     * A proposal's stake-vote snapshot: the stake tree's root and size when it
     * entered voting, the block it did (0 when unknown), rate_v (ERTH per
     * derth) per validator then, what a stake vote's derth weighs, and the
     * stake nullifier tree's root and size (sentinel included) at the same
     * moment (null: a snapshot from before votes stopped spending; it takes
     * no stake vote).
     */
    data class Snapshot(
        val root: Fr,
        val treeSize: Long,
        val height: Long = 0,
        val rates: Map<String, BigDecimal> = emptyMap(),
        val nfRoot: Fr? = null,
        val nfSize: Long = 0,
    )
    /** Query/StakeNullifierTree: the values at leaf start+1.. in insertion order, and the tree's current size. */
    data class NfTreePage(val values: List<Fr>, val size: Long)
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
    /** x/shieldedstaking Query/StakeNullifierTree{start, limit} (at most 1000 a page). */
    fun stakeNullifierTree(start: Long, limit: Int): NfTreePage
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
) {
    private val engine = PrivateTxEngine(chainId, chain, prover)

    val address: ShieldedAddress get() = keys.address

    @Synchronized
    fun sync(): WalletSync.Result {
        fillPendingRegistration()
        return WalletSync(indexer, store, keys, chainId, roots, now).sync()
    }

    /**
     * K7: a registration recorded at acceptance whose block the wallet has
     * not seen (the wait timed out, the app was killed) is looked up by its
     * hash: committed, it gets its leaf index and activated_at; failed in its
     * block, the failure is kept for the UI (a new registration replaces it).
     */
    private fun fillPendingRegistration() {
        val p = store.state.pendingRegistration ?: return
        if (p.leafIndex != null || p.failure?.startsWith(TX_FAILED) == true) return
        val r = runCatching { chain.tx(p.txHash) }.getOrNull() ?: return
        store.state.pendingRegistration = if (r.code != 0) {
            p.copy(failure = "$TX_FAILED (code ${r.code}): ${r.log.take(200)}")
        } else {
            val index = r.attr("register", "leaf_index")?.toLongOrNull() ?: return
            p.copy(leafIndex = index, activatedAt = r.time, failure = null)
        }
        store.save()
    }

    fun identityStatus(): WalletSync.IdentityStatus = WalletSync(indexer, store, keys, chainId, roots, now).identityStatus()

    /** A committed registration whose leaf is not matched yet (null: none), and why, if it failed. */
    val pendingRegistration: PendingRegistration? get() = store.state.pendingRegistration

    val notes: List<OwnedNote> get() = store.state.notes.toList()

    val stakeNotes: List<OwnedStakeNote> get() = store.state.stakeNotes.toList()

    /** Spendable pool balance per denom (pending spends excluded). */
    fun poolBalances(): Map<String, Long> =
        store.state.notes.filter { it.unspent && it.pendingAt == null }
            .groupBy { it.note.denom }.mapValues { (_, ns) -> Amounts.satSum(ns) { it.note.value } }

    /** Stake (derth/<valoper>) and unbonding claims (unbond/<valoper>/<epoch>) per denom: owner-locked, never sendable. */
    fun stakeBalances(): Map<String, Long> =
        store.state.stakeNotes.filter { it.spendable }.groupBy { it.denom }.mapValues { (_, ns) -> Amounts.satSum(ns) { it.amount } }

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
    private fun run(memo: String = "", accepted: (hash: String, timeoutHeight: Long) -> Unit = { _, _ -> }, assemble: (fee: Long) -> Assembled): TxResult {
        requireVerified()
        // The spent notes are marked the moment the node accepts the tx
        // (K7), before the wait for its block: a wait that times out (the tx
        // may still land) or a killed app never leaves them spendable. They
        // stay pending until the chain is past the tx's timeout_height.
        val (result, _) = engine.run(assemble, memo, shownFee.get()) { hash, a, timeout ->
            markPending(a.spends, a.stakeSpends, timeout)
            accepted(hash, timeout)
        }
        return result
    }

    /**
     * Audit 3: only on roots verified by the last sync, in that sync's own
     * generation (a sync that failed part way leaves them unverified).
     */
    private fun requireVerified() {
        val s = store.state
        check(s.rootsVerified && s.verifiedGeneration == s.syncGeneration) {
            s.rootsError ?: "the wallet has not checked its notes against the chain yet; sync again"
        }
    }

    /** What [run] would charge, without proving (placeholder nullifiers): for a confirm sheet. */
    fun quote(assemble: (fee: Long) -> Assembled): PrivateTxEngine.Quote = engine.quote(assemble)

    private fun markPending(spent: List<OwnedNote>, stake: List<OwnedStakeNote>, timeoutHeight: Long) {
        val positions = spent.map { it.position }.toSet()
        val stakePositions = stake.map { it.position }.toSet()
        val s = store.state
        val t = now()
        for (i in s.notes.indices) if (s.notes[i].position in positions) s.notes[i] = s.notes[i].copy(pendingAt = t, pendingUntil = timeoutHeight)
        for (i in s.stakeNotes.indices) if (s.stakeNotes[i].position in stakePositions) {
            s.stakeNotes[i] = s.stakeNotes[i].copy(pendingAt = t, pendingUntil = timeoutHeight)
        }
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

    /** Signs a referrer consent with a transparent account key: (33-byte compressed key, 64-byte low-S r||s over SHA-256 of the message). */
    fun interface ReferrerSigner {
        fun sign(message: ByteArray): Pair<ByteArray, ByteArray>
    }

    /** The chain is ahead of the local trees for what is asked: sync, then try again. */
    class SyncFirst(message: String) : IllegalStateException(message)

    /** The identity is too recent for this action; it opens [waitSeconds] from now. */
    class NotYet(val waitSeconds: Long) : Exception("this registration is too recent for this action; try again in ${waitSeconds / 3600 + 1}h")

    // ---- pool ---------------------------------------------------------------

    /** A private send of [amount] [denom] to [to]; the fee comes out of ERTH notes. */
    fun send(to: ShieldedAddress, denom: String, amount: Long, memo: ByteArray = ByteArray(0)): TxResult {
        requireTransferable(denom)
        return run(assemble = sendAssembly(to, denom, amount, memo))
    }

    /** What [send] would charge (simulated with placeholder nullifiers, nothing proven): for a confirm sheet. */
    fun quoteSend(to: ShieldedAddress, denom: String, amount: Long, memo: ByteArray = ByteArray(0)): PrivateTxEngine.Quote {
        requireTransferable(denom)
        return quote(sendAssembly(to, denom, amount, memo))
    }

    private fun sendAssembly(to: ShieldedAddress, denom: String, amount: Long, memo: ByteArray): (Long) -> Assembled = { fee ->
        val b = bundle(listOf(NoteOut.to(to, denom, amount, memo)), mapOf(FEE to fee))
        Assembled(listOf(b)) { bs, _, _ -> MsgSend.newBuilder().setBundle(bs[0]).setFee(fee).build() }
    }

    /**
     * Unshields so that [receiver] gets exactly [amount] [denom], the fee from
     * ERTH notes. With [feeFromAmount] (ERTH only) the receiver gets [amount]
     * less the fee instead: the bundle releases exactly [amount], so the most
     * a wallet holds can leave in one go (Max).
     */
    fun unshield(receiver: String, denom: String, amount: Long, feeFromAmount: Boolean = false, memo: String = ""): TxResult {
        requireTransferable(denom)
        // Wave 3 (B/F2): the chain refuses an unshield to any module account.
        PrivateMsgs.moduleAccountOf(network.erth.wallet.crypto.Bech32.decode(receiver))?.let {
            throw IllegalArgumentException("$receiver is the $it module account; it cannot receive an unshield")
        }
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
        if (denom == FEE) require(Amounts.exactSum(ns) { it.note.value } > fee) { "these notes do not cover the ${fee}uerth fee" }
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
        require(PrivateMsgs.isCalendarDate(publicSignals[0])) { "the passport proof's current_date ${publicSignals[0]} is not a calendar date" }
        val base = registerMsg(prep, proof, publicSignals, signatureAlgorithm, dscDer)
        val dscKey = PrivateMsgs.decimalField(publicSignals[3])
        val hint = dscCountry(dscDer)
        val record = NoteOut.to(keys.address, FEE, 0, WalletSync.regMemo(keys.nk, dscKey, hint, now()))
        val pending = { hash: String, _: Long ->
            // K7: by hash, the moment the node accepts it; the leaf comes later.
            store.state.pendingRegistration = PendingRegistration(
                txHash = hash, leafIndex = null, dscKey = dscKey, passportNullifier = publicSignals.getOrElse(2) { "" },
                publicSignals = publicSignals, activatedAt = null, countryHint = hint,
            )
            store.save()
        }
        val result = run(accepted = pending) { fee ->
            Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee)))) { bs, _, _ -> base.toBuilder().setFee(bs[0]).build() }
        }
        recordRegistration(result)
        return result
    }

    /**
     * Fills the pending registration (C2, K7) from its committed tx — the
     * leaf index from its register event, activated_at its block time —
     * and syncs; every later sync retries until the leaf is in the local
     * identity tree and matches.
     */
    fun recordRegistration(result: TxResult) {
        val index = result.attr("register", "leaf_index")?.toLongOrNull()
            ?: throw IllegalStateException("registration tx ${result.hash} has no leaf_index")
        synchronized(this) {
            val p = store.state.pendingRegistration
            if (p != null && p.txHash == result.hash) {
                store.state.pendingRegistration = p.copy(leafIndex = index, activatedAt = result.time, failure = null)
                store.save()
            }
        }
        runCatching { sync() }
    }

    /** Today's ANML. Opens once the identity was activated before yesterday began. */
    fun claimAnml(day: Long = today()): TxResult {
        require(day >= 1) { "no claim day before day 1" }
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
     * now - R - 86400 (the chain's bound since wave 3, L4/L5: the largest root
     * window, not the live one), rounded down to the hour so it says nothing
     * about when the tx was made, less a margin for clock skew.
     */
    private fun leaseBound(): Long {
        val p = reads.personhoodParams()
        val bound = now() - p.caretakerVoteSeconds - ACTIVATION_MARGIN - CLOCK_MARGIN
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
    fun bindReferrer(address: String, consent: ReferrerSigner? = null): TxResult {
        val maxAct = leaseBound()
        val m = membership(Privacy.referrerScope(), Fr.ZERO, Fr.ZERO, maxAct)
        // Wave 3 (L6): binding an address needs its owner's consent, a
        // secp256k1 signature over (domain, chain id, the membership's
        // nullifier, the address) by the key whose address it is. The
        // nullifier is the scope's, known before proving; not in the sighash.
        val (pub, sig) = if (address.isEmpty()) ByteArray(0) to ByteArray(0) else {
            val signer = consent ?: throw IllegalStateException("binding a referrer address needs its owner's signature")
            val raw = network.erth.wallet.crypto.Bech32.decode(address)
            val (p, s) = signer.sign(PrivateMsgs.referrerConsentBytes(chainId, m.witness(Fr.ZERO).nullifier.toBytes(), raw))
            require(p.size == 33 && s.size == 64) { "a referrer consent is a 33-byte key and a 64-byte signature" }
            require(network.erth.wallet.crypto.WalletCrypto.addressOfPubKey(p) == address) { "$address is not an address this wallet controls" }
            p to s
        }
        val r = run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgBindReferrer.newBuilder().setFee(bs[0]).setMembership(mem).setAddress(address).setMaxActivation(maxAct)
                    .setReferrerPubKey(ByteString.copyFrom(pub)).setReferrerSignature(ByteString.copyFrom(sig)).build()
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
        // Wave 3 (L4/L5): the start of today (UTC) less a day, whatever the root window.
        val m = membership(Privacy.proposeRemovalScope(optionId, day), Fr.ZERO, Fr.ZERO, day * SECONDS_PER_DAY - ACTIVATION_MARGIN)
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
        require(Amounts.exactSum(notes) { it.amount } == Amounts.exactSum(amounts) { it }) { "a restake keeps the amount" }
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
            if (Amounts.satSum(ns.sortedByDescending { it.amount }.take(2)) { it.amount } >= amount || ns.size < 3) return out
            out.add(mergeStake(denom))
            sync()
        }
    }

    /** Merges the two smallest stake notes of [denom] (derth/<valoper>) into one. */
    fun mergeStake(denom: String): TxResult {
        val two = spendableStake(denom).sortedBy { it.amount }.take(2)
        require(two.size == 2) { "nothing to merge" }
        return restake(parseDerth(denom), two, listOf(Amounts.exactSum(two) { it.amount }))
    }

    /**
     * Turns [amount] derth/[validator] into an owner-locked unbonding claim,
     * minted to our stake self-mint pc at the live rate; claimable once its
     * epoch's undelegation matures.
     */
    fun undelegate(validator: String, amount: Long): TxResult {
        val denom = derthDenom(validator)
        val ins = StakeSelection.cover(spendableStake(denom), amount)
        val stake = stakePlan(denom, ins, listOf(Amounts.exactSum(ins) { it.amount } - amount), amount, mint = stakeMint())
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
        val amount = Amounts.exactSum(ins) { it.amount }
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
     * Votes one derth note on [proposalId] without spending it (ORCHARD_DESIGN
     * 15): a vote proof that the note is under the proposal's snapshot root,
     * that its spend nullifier is absent from the snapshot's stake nullifier
     * tree (rebuilt here and checked against nf_root), with weight
     * [voteWeight] of its amount, and its per-proposal vote nullifier. The
     * note is untouched: it votes on every other open proposal and is spent
     * as usual. The fee bundle is against the pool's current roots. The
     * (proposal, vote nullifier) is remembered from the moment the node
     * accepts the tx, so the note never votes twice on the proposal.
     */
    fun stakeVote(proposalId: Long, note: OwnedStakeNote, options: List<WeightedVoteOption>): TxResult {
        val validator = parseDerth(note.denom)
        require(note.amount > 0) { "an empty note has no vote" }
        val snap = snapshot(proposalId)
        val tree = store.stakeTree
        // Audit 3: a snapshot past the local tree (stake landed since the last sync) cannot be checked here.
        if (snap.treeSize < 0 || snap.treeSize > tree.size) throw SyncFirst("the proposal's stake snapshot is ahead of this wallet; sync first")
        require(note.position < snap.treeSize) { "this stake arrived after the proposal's snapshot and cannot vote on it" }
        check(tree.rootAt(snap.treeSize) == snap.root) { "the local stake tree disagrees with the proposal's snapshot root" }
        val vnf = Privacy.voteNf(keys.nk, note.rho, note.position, proposalId)
        resolveVotes()
        if (voted(proposalId, vnf)) throw AlreadyVoted()
        val nfRoot = snap.nfRoot!!
        val low = snapshotNullifiers(snap).nonMembership(Privacy.stakeNf(keys.nk, note.rho, note.position))
            ?: throw SpentBeforeSnapshot()
        val weight = voteWeight(note.amount)
        val path = tree.pathAt(note.position, snap.treeSize)
        val asset = Privacy.assetId(note.denom)
        val vote = VoteWitnessSpec(vnf) { sighash ->
            VoteWitness(keys.nk, note.amount, note.rho, note.rcm, note.position, path, low, snap.root, nfRoot, asset, weight, proposalId, sighash)
        }
        try {
            val r = run(accepted = { hash, timeout -> recordVote(StakeVoteRecord(proposalId, vnf, hash, timeout, confirmed = false)) }) { fee ->
                Assembled(listOf(feeBundle(fee)), vote = vote) { bs, _, _ ->
                    MsgStakeVote.newBuilder().setBundle(bs[0]).setProposalId(proposalId).setValidator(validator)
                        .addAllOptions(PrivateMsgs.canonicalOptions(options)).setWeight(weight).build()
                }
            }
            recordVote(StakeVoteRecord(proposalId, vnf, r.hash, null, confirmed = true))
            return r
        } catch (e: Exception) {
            // Already voted (a restored wallet, a vote whose block the wallet missed): final either way.
            if (alreadyVotedError(e)) { recordVote(StakeVoteRecord(proposalId, vnf, null, null, confirmed = true)); throw AlreadyVoted() }
            throw e
        }
    }

    private val snapshotRows = HashMap<Long, network.erth.wallet.privacy.sync.StakeSnapshotRow>()
    private var snapshotsNext = 0L

    /**
     * [proposalId]'s snapshot: from the indexer's full snapshot stream (no
     * request names the proposal), else the chain's Query/Snapshot (a
     * legacy snapshot without a nullifier root, or one the indexer has not
     * reached). Whatever the source, the note root is checked against the
     * wallet's verified stake tree and the nullifier root against the tree
     * the nullifiers rebuild; the chain proves against its own.
     */
    fun snapshot(proposalId: Long): PrivacyChainReads.Snapshot {
        val row = synchronized(snapshotRows) {
            if (proposalId !in snapshotRows) runCatching {
                repeat(MAX_SNAPSHOT_PAGES) {
                    val page = indexer.stakeSnapshots(snapshotsNext)
                    page.rows.forEach { snapshotRows[it.proposalId] = it }
                    snapshotsNext = maxOf(snapshotsNext, page.nextHeight)
                    if (!page.complete) return@runCatching
                }
            }
            snapshotRows[proposalId]
        }
        if (row?.root != null && row.nfRoot != null) {
            return PrivacyChainReads.Snapshot(row.root, row.treeSize, row.height, emptyMap(), row.nfRoot, row.nfSize)
        }
        return reads.snapshot(proposalId)
    }

    /** This note already voted on this proposal (the chain's code 1119): votes are final. */
    class AlreadyVoted : IllegalStateException("this stake note already voted on this proposal")

    /** The note's nullifier is in the proposal's snapshot nullifier tree: it was spent before voting opened. */
    class SpentBeforeSnapshot : IllegalStateException("this stake was spent before the proposal's snapshot and cannot vote on it")

    private fun voted(proposalId: Long, vnf: Fr): Boolean = synchronized(store) {
        store.state.stakeVotes.any { it.proposalId == proposalId && it.vnf == vnf }
    }

    private fun recordVote(v: StakeVoteRecord) = synchronized(store) {
        val s = store.state.stakeVotes
        s.removeAll { it.proposalId == v.proposalId && it.vnf == v.vnf }
        s.add(v)
        store.save()
    }

    /**
     * Settles the votes still pending: committed (or refused as already
     * voted) they are final; failed in their block, or unknown once the
     * chain is past their timeout_height, they are forgotten and the note
     * may vote again.
     */
    private fun resolveVotes() {
        val pending = synchronized(store) { store.state.stakeVotes.filter { !it.confirmed } }
        if (pending.isEmpty()) return
        val tip by lazy { runCatching { chain.tipHeight() }.getOrNull() }
        for (v in pending) {
            val r = v.txHash?.let { h -> runCatching { chain.tx(h) }.getOrNull() }
            val next = when {
                r != null && (r.code == 0 || r.code == VOTE_NULLIFIER_USED) -> v.copy(confirmed = true)
                r != null -> null
                v.txHash == null || (v.until != null && tip != null && tip!! > v.until) -> null
                else -> continue
            }
            synchronized(store) {
                store.state.stakeVotes.removeAll { it.proposalId == v.proposalId && it.vnf == v.vnf }
                if (next != null) store.state.stakeVotes.add(next)
                store.save()
            }
        }
    }

    /** The stake nullifier tree's values in insertion order (leaf 1 on), as far as fetched: a prefix of the chain's. */
    private val nfValues = ArrayList<Fr>()
    private val nfTrees = LinkedHashMap<Fr, IndexedTree>()

    /**
     * The stake nullifier tree at [snap] (ORCHARD_DESIGN 15, wallet format 3):
     * its first nf_size - 1 values in insertion order, from the indexer's
     * stream by leaf index (full ranges only: nothing names a note of ours),
     * the chain's Query/StakeNullifierTree for whatever the indexer lacks,
     * inserted in order; its root must be the snapshot's nf_root. A mismatch
     * drops what was fetched and rebuilds from the chain alone once.
     */
    private fun snapshotNullifiers(snap: PrivacyChainReads.Snapshot): IndexedTree = synchronized(nfValues) {
        val nfRoot = snap.nfRoot ?: throw IllegalStateException("this proposal's snapshot has no stake nullifier root; it takes no stake vote")
        nfTrees[nfRoot]?.let { return it }
        require(snap.nfSize in 0..network.erth.wallet.privacy.zk.Merkle.CAPACITY && snap.nfSize - 1 < Int.MAX_VALUE) { "nf_size ${snap.nfSize}" }
        val n = maxOf(0L, snap.nfSize - 1).toInt()
        for (chainOnly in listOf(false, true)) {
            val values = runCatching { fetchNullifiers(n, chainOnly) }.getOrElse { if (chainOnly) throw it else { nfValues.clear(); null } } ?: continue
            val t = runCatching { IndexedTree.build(values) }.getOrNull()
            if (t != null && t.root() == nfRoot) {
                nfTrees[nfRoot] = t
                while (nfTrees.size > 2) nfTrees.remove(nfTrees.keys.first())
                return t
            }
            nfValues.clear()
        }
        throw IllegalStateException("the stake nullifiers served do not rebuild the proposal's snapshot nullifier root")
    }

    /** The first [n] stake nullifiers in insertion order (holding nfValues' lock). */
    private fun fetchNullifiers(n: Int, chainOnly: Boolean): List<Fr> {
        if (!chainOnly) runCatching {
            while (nfValues.size < n) {
                val page = indexer.stakeNullifierLeaves(nfValues.size + 1L, minOf(n - nfValues.size, NF_PAGE))
                if (page.leaves.isEmpty()) break
                for ((index, v) in page.leaves) {
                    // Contiguous from where we are, or the page is not the tree's order.
                    if (index != nfValues.size + 1L) throw WalletSync.Inconsistent("stake nullifier leaf $index out of order")
                    nfValues.add(v)
                }
            }
        }
        while (nfValues.size < n) {
            val page = reads.stakeNullifierTree(nfValues.size.toLong(), minOf(n - nfValues.size, LCD_NF_PAGE))
            if (page.values.isEmpty()) break
            nfValues.addAll(page.values.take(LCD_NF_PAGE))
        }
        check(nfValues.size >= n) { "only ${nfValues.size} of the snapshot's $n stake nullifiers were served" }
        return nfValues.subList(0, n).toList()
    }

    /** What a stake vote on a proposal weighs: notes, positions that can vote, uerth. */
    data class StakeWeight(val notes: Int, val positionIds: Set<Long>, val uerth: Long)

    /**
     * This wallet's weight on [proposalId]: every derth note that can still
     * stake-vote on it (at its rounded [voteWeight]) and every position
     * created before the snapshot's block (the chain refuses later ones),
     * each at its validator's rate at the snapshot.
     */
    fun stakeVoteWeight(proposalId: Long, positions: List<PrivacyChainReads.Position>): StakeWeight {
        val snap = snapshot(proposalId)
        val notes = eligible(proposalId, snap)
        val ps = votingPositions(positions, snap)
        val total = Amounts.satAdd(
            Amounts.satSum(notes) { derthValue(voteWeight(it.amount), snap.rates[parseDerth(it.denom)] ?: BigDecimal.ONE) },
            Amounts.satSum(ps) { derthValue(it.derth, snap.rates[it.validator] ?: BigDecimal.ONE) },
        )
        return StakeWeight(notes.size, ps.map { it.id }.toSet(), total)
    }

    /**
     * Derth notes that may vote on [proposalId]: in the stake tree at the
     * snapshot, not spent before it as far as sync knows (the cast checks the
     * snapshot's nullifier tree itself), and not already voted on it. A note
     * spent after the snapshot still votes; its outputs cannot.
     */
    private fun eligible(proposalId: Long, snap: PrivacyChainReads.Snapshot): List<OwnedStakeNote> {
        if (snap.nfRoot == null) return emptyList()
        resolveVotes()
        return store.state.stakeNotes.filter {
            it.denom.startsWith(DERTH_PREFIX) && it.amount > 0 && it.position < snap.treeSize &&
                (it.spentHeight == null || snap.height == 0L || it.spentHeight >= snap.height) &&
                !voted(proposalId, Privacy.voteNf(keys.nk, it.rho, it.position, proposalId))
        }
    }

    /** One cast of a stake vote: one derth note, or a position. */
    sealed interface StakeVoteItem {
        data class Note(val position: Long) : StakeVoteItem
        data class Position(val id: Long, val counter: Int) : StakeVoteItem
    }

    /**
     * Every cast a stake vote on [proposalId] takes (K5): each eligible derth
     * note on its own, and every position of ours that may vote (created
     * before the snapshot's block). Cast them through [StakeVoteController],
     * the one path the app uses: one at a time, a sync and a random pause
     * between.
     */
    fun stakeVoteItems(proposalId: Long): List<StakeVoteItem> {
        val snap = snapshot(proposalId)
        val notes = eligible(proposalId, snap).map { StakeVoteItem.Note(it.position) }
        val mine = positions()
        val voting = votingPositions(mine.map { it.first }, snap).map { it.id }.toSet()
        return notes + mine.filter { it.first.id in voting }.map { (p, c) -> StakeVoteItem.Position(p.id, c) }
    }

    /**
     * Casts [item] as the last sync left things; null when there is nothing
     * left of it to cast (the note already voted on this proposal, or was
     * spent before its snapshot).
     */
    fun castStakeVote(proposalId: Long, item: StakeVoteItem, options: List<WeightedVoteOption>): String? = when (item) {
        is StakeVoteItem.Note -> store.state.stakeNotes.firstOrNull { it.position == item.position }?.let { n ->
            try { stakeVote(proposalId, n, options).hash } catch (e: AlreadyVoted) { null } catch (e: SpentBeforeSnapshot) { null }
        }
        is StakeVoteItem.Position -> positions().firstOrNull { it.first.id == item.id && it.second == item.counter }
            ?.let { (p, c) -> positionVote(p, c, proposalId, options).hash }
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
        // them (and of failed locks) to reach a live one. A restored wallet
        // knows the closed ones' counters from their unlock memos (K11).
        var limit = maxOf(s.nextOtagCounter, s.closedOtagMax + 1) + OTAG_GAP
        val out = ArrayList<Pair<PrivacyChainReads.Position, Int>>()
        var from = 0
        while (from < limit) {
            val mine = (from until limit).associateBy { ownerTag(it) }
            val found = all.mapNotNull { p -> mine[p.ownerTag]?.let { p to it } }
            out += found
            from = limit
            found.maxOfOrNull { it.second }?.let { top -> if (top + 1 + OTAG_GAP > limit) limit = top + 1 + OTAG_GAP }
        }
        val next = maxOf(s.nextOtagCounter, s.closedOtagMax + 1, (out.maxOfOrNull { it.second } ?: -1) + 1)
        if (next > s.nextOtagCounter) { s.nextOtagCounter = next; store.save() }
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
        val stake = stakePlan(denom, ins, listOf(Amounts.exactSum(ins) { it.amount } - amount), amount, salt = keys.otagSalt(counter))
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

    /**
     * Closes [position]; its derth comes back as a stake note to our stake
     * self-mint pc, whose memo names the closed counter (K11) so no restore
     * ever locks under its tag again.
     */
    fun unlockPosition(position: PrivacyChainReads.Position, counter: Int): TxResult {
        val stake = ownerPlan(position, counter, mint = StakePlan.selfMint(keys, WalletSync.unlockMemo(keys.nk, counter)))
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
                    .addAllOptions(PrivateMsgs.canonicalOptions(options)).setStake(sp).build()
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
        /**
         * The fee the confirm sheet showed, for the private run on this
         * thread (TxController sets it around the run): a fee above it throws
         * PrivateTxEngine.FeeAboveQuote and the sheet asks again (audit 3).
         * Unset (automation, later stake-vote casts): only the cap applies.
         */
        val shownFee = ThreadLocal<Long?>()

        /** Runs [block] with [fee] as the shown fee on this thread. */
        fun <T> withShownFee(fee: Long, block: () -> T): T {
            val before = shownFee.get()
            shownFee.set(fee)
            try { return block() } finally { shownFee.set(before) }
        }

        const val FEE = "uerth"
        const val DERTH_PREFIX = "derth/"
        const val UNBOND_PREFIX = "unbond/"
        const val LP_PREFIX = "dexlp/"

        /** floor(derth x rate) in uerth, the chain's conversion of derth to ERTH (saturating; a negative rate is 0). */
        fun derthValue(derth: Long, rate: BigDecimal): Long {
            val v = BigDecimal.valueOf(derth).multiply(rate).setScale(0, java.math.RoundingMode.DOWN).toBigInteger()
            return when {
                v.signum() <= 0 -> 0L
                v.bitLength() > 63 -> Long.MAX_VALUE
                else -> v.toLong()
            }
        }

        /**
         * A stake vote's public weight for a note of [amount] uderth
         * (PRIVACY_FORMATS 4e): the amount rounded down to three significant
         * decimal digits (whole below 1000), so the published weight names a
         * bucket rather than the note's exact amount (a delegation's minted
         * amount is public). Gives up less than 1% of the note's voice.
         */
        fun voteWeight(amount: Long): Long {
            require(amount > 0) { "an empty note has no vote" }
            var unit = 1L
            while (amount / unit >= 1000) unit *= 10
            return amount / unit * unit
        }

        /** x/shieldedstaking ErrVoteNullifierUsed. */
        const val VOTE_NULLIFIER_USED = 1119

        /** Whether [e] is the chain refusing a vote nullifier already used on the proposal. */
        fun alreadyVotedError(e: Throwable): Boolean {
            val m = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            return "already voted on this proposal" in m || Regex("""code\s*$VOTE_NULLIFIER_USED\b""").containsMatchIn(m)
        }

        /** Stake nullifier leaves asked of the indexer a page, and of the LCD (its maximum). */
        const val NF_PAGE = 5000
        const val LCD_NF_PAGE = 1000
        private const val MAX_SNAPSHOT_PAGES = 1000

        /** Positions created before the block the proposal entered voting at (all, when unknown). */
        fun votingPositions(positions: List<PrivacyChainReads.Position>, snap: PrivacyChainReads.Snapshot) =
            positions.filter { snap.height == 0L || it.createdHeight < snap.height }

        /** Owner-tag counters scanned past the highest known (PRIVACY_FORMATS.md 1). */
        const val OTAG_GAP = 1024

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
        /** A pending registration whose tx failed in its block (K7). */
        const val TX_FAILED = "the registration tx failed"

        /** Slack against the chain's clock for bounds the wallet must stay under. */
        const val CLOCK_MARGIN = 600L

        /** The day every activation bound keeps from now (wave 3: the largest identity root window). */
        const val ACTIVATION_MARGIN = 86_400L

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
            return parts[1] to (Amounts.parseU64(parts[2]) ?: throw IllegalArgumentException("not an unbond note"))
        }
    }
}
