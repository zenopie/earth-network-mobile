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
import network.erth.earth.proto.personhood.MsgBindHandle
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgMoveCaretaker
import network.erth.earth.proto.personhood.MsgMoveHandle
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
import network.erth.wallet.privacy.sync.PendingMove
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
import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles
import java.math.BigDecimal

/** The chain reads the wallet's private msgs are built from. */
interface PrivacyChainReads {
    data class PersonhoodParams(
        val caretakerVoteSeconds: Long,
        val identityRootWindowSeconds: Long,
        val handleLeaseSeconds: Long = network.erth.wallet.privacy.handles.Handles.DEFAULT_LEASE_SECONDS,
        val handleRenewalSeconds: Long = network.erth.wallet.privacy.handles.Handles.DEFAULT_RENEWAL_SECONDS,
    )
    data class BallotInputs(
        val scope: Fr,
        val excludedDsc: Fr,
        val excludedCountry: Fr,
        val maxActivation: Long,
        val round: Long,
        val ballotId: Long,
        val maxPredecessor: Long = Privacy.NO_BOUND,
    )
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

    /**
     * x/personhood Query/LeaseBounds (chain 203d3b2): the lease lengths the
     * chain's predecessor bounds use now (the longest handle lease ever in
     * force; the caretaker lease including a held longer one after a cut),
     * the activation margin, and both bounds at [blockTime]. Every
     * max_predecessor a handle claim or a new caretaker split names comes
     * from here, never from Params, which may be shorter.
     */
    data class LeaseBounds(
        val blockTime: Long,
        val activationMarginSeconds: Long,
        val handleLeaseSeconds: Long,
        val handleClaimBound: Long,
        val caretakerLeaseSeconds: Long,
        val caretakerCastBound: Long,
        val caretakerLeaseHoldUntil: Long = 0,
    )

    fun personhoodParams(): PersonhoodParams
    fun leaseBounds(): LeaseBounds
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
    private val engine = PrivateTxEngine(chainId, chain, prover, verifiedHeight = { store.state.verifiedHeight })

    val address: ShieldedAddress get() = keys.address

    @Synchronized
    fun sync(): WalletSync.Result {
        fillPendingRegistration()
        runCatching { resolvePendingMoves() }
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

    /**
     * Audit 5 (L2): the chain's time, the LCD tip's block time, for what the
     * chain checks against its own clock (predecessor bounds, the removal
     * day); the device clock only when the node cannot say.
     */
    private fun chainNow(): Long = runCatching { roots.latestBlock()?.time }.getOrNull()?.takeIf { it > 0 } ?: now()

    // ---- running ------------------------------------------------------------

    /**
     * Proves and broadcasts. Only on trees the chain itself vouched for at
     * the last sync (C3): a proof over an indexer's forged tree is refused by
     * the chain anyway, and its notes may not exist.
     */
    @Synchronized
    private fun run(
        memo: String = "",
        accepted: (hash: String, timeoutHeight: Long) -> Unit = { _, _ -> },
        rejected: (hash: String) -> Unit = {},
        assemble: (fee: Long) -> Assembled,
    ): TxResult {
        requireVerified()
        requireFreshAnchor()
        // The spent notes are marked before the tx is sent (K7, audit 4),
        // under its hash: a wait that times out (the tx may still land), a
        // lost answer or a killed app never leaves them spendable. They stay
        // pending until the chain is past the tx's timeout_height and says the
        // tx is not in a block (WalletSync.releaseStalePending).
        val (result, _) = tipChecked {
            engine.run(assemble, memo, shownFee.get(), accepted = { hash, a, timeout ->
                markPending(a.spends, a.stakeSpends, timeout, hash)
                accepted(hash, timeout)
            }, rejected = { hash, a ->
                unmarkPending(a.spends, a.stakeSpends, hash)
                rejected(hash)
            })
        }
        return result
    }

    /**
     * Audit 6 (M4): a tip far past the last verified sync height is either a
     * stale sync (sync, and try once more) or a node lying about the tip
     * (refused again: nothing was laid out, proven or sent).
     */
    private fun <T> tipChecked(block: () -> T): T = try {
        block()
    } catch (e: PrivateTxEngine.TipOutOfRange) {
        sync()
        requireVerified()
        try {
            block()
        } catch (e: PrivateTxEngine.TipOutOfRange) {
            throw SyncFirst("the node says the chain is at height ${e.tip}, far past the ${e.verified} this wallet verified; try another node or sync again")
        }
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

    /**
     * Whether the local note tree's root, every bundle's anchor, stays an
     * anchor long enough (chain 203d3b2): CheckTx refuses one lapsing within
     * 120 s of the last block, and a proposer leaves out a tx whose anchor
     * lapsed by its block. [ANCHOR_MARGIN] covers proving and the tx's
     * timeout_height on top. Null: the node could not say (the chain's own
     * check stands).
     */
    private fun anchorFresh(): Boolean? {
        if (store.noteTree.size == 0L) return true
        val rec = runCatching { roots.noteRoot(store.noteTree.root()) }.getOrNull() ?: return null
        if (!rec.valid) return false
        val exp = rec.expiresAt ?: return null
        return exp == 0L || exp >= Handles.satAdd(chainNow(), ANCHOR_MARGIN)
    }

    /**
     * A root lapsing soon is replaced by a newer one before anything is laid
     * out: sync (newer notes, a newer root), and refuse if it is still too
     * old (an indexer behind the chain).
     */
    private fun requireFreshAnchor() {
        if (anchorFresh() != false) return
        sync()
        requireVerified()
        if (anchorFresh() == false) {
            throw SyncFirst("this wallet's notes are anchored to a note tree the chain stops accepting within ${ANCHOR_MARGIN / 60} minutes; sync again and retry")
        }
    }

    /** What [run] would charge, without proving (placeholder nullifiers): for a confirm sheet. */
    fun quote(assemble: (fee: Long) -> Assembled): PrivateTxEngine.Quote = tipChecked { engine.quote(assemble) }

    private fun markPending(spent: List<OwnedNote>, stake: List<OwnedStakeNote>, timeoutHeight: Long, hash: String) {
        val positions = spent.map { it.position }.toSet()
        val stakePositions = stake.map { it.position }.toSet()
        val s = store.state
        val t = now()
        for (i in s.notes.indices) if (s.notes[i].position in positions) s.notes[i] = s.notes[i].copy(pendingAt = t, pendingUntil = timeoutHeight, pendingTx = hash)
        for (i in s.stakeNotes.indices) if (s.stakeNotes[i].position in stakePositions) {
            s.stakeNotes[i] = s.stakeNotes[i].copy(pendingAt = t, pendingUntil = timeoutHeight, pendingTx = hash)
        }
        store.save()
    }

    /** A broadcast refused outright (in no mempool): the notes it marked are spendable again. */
    private fun unmarkPending(spent: List<OwnedNote>, stake: List<OwnedStakeNote>, hash: String) {
        val positions = spent.map { it.position }.toSet()
        val stakePositions = stake.map { it.position }.toSet()
        val s = store.state
        for (i in s.notes.indices) if (s.notes[i].position in positions && s.notes[i].pendingTx == hash) {
            s.notes[i] = s.notes[i].copy(pendingAt = null, pendingUntil = null, pendingTx = null)
        }
        for (i in s.stakeNotes.indices) if (s.stakeNotes[i].position in stakePositions && s.stakeNotes[i].pendingTx == hash) {
            s.stakeNotes[i] = s.stakeNotes[i].copy(pendingAt = null, pendingUntil = null, pendingTx = null)
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

    /**
     * A membership proof's witness for [scope] under the chain's statement:
     * activated_at <= [maxActivation], predecessor_at <= [maxPredecessor]
     * (Privacy.NO_BOUND: none).
     */
    private fun membership(scope: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: Long, maxPredecessor: Long): MembershipWitnessSpec {
        val id = identity()
        if (id.activatedAt > maxActivation) {
            throw NotYet(maxOf(0, id.activatedAt - maxActivation))
        }
        if (id.predecessorAt > maxPredecessor) {
            throw NotYet(maxOf(0, id.predecessorAt - maxPredecessor))
        }
        val tree = store.identityTree
        val path = tree.path(id.leafIndex)
        val root = tree.root()
        return MembershipWitnessSpec { signal ->
            MembershipWitness(
                idSecret = keys.idSecret, dscKey = id.dscKey, country = id.country, activatedAt = id.activatedAt,
                predecessorAt = id.predecessorAt,
                leafIndex = id.leafIndex, siblings = path, root = root, scope = scope, signal = signal,
                excludedDsc = excludedDsc, excludedCountry = excludedCountry, maxActivation = maxActivation,
                maxPredecessor = maxPredecessor,
            )
        }
    }

    /** The chain is ahead of the local trees for what is asked: sync, then try again. */
    class SyncFirst(message: String) : IllegalStateException(message)

    /** The identity is too recent for this action (or replaced another too recently); it opens [waitSeconds] from now. */
    open class NotYet(val waitSeconds: Long, message: String = notYetText(waitSeconds)) : Exception(message)

    /**
     * Audit 5 (M1): a renewal or refresh sent with no bound, by an identity
     * whose own bound has not passed, which the chain refused (in its ante,
     * before any fee): this identity holds nothing there.
     */
    class NotHeld(waitSeconds: Long) : NotYet(
        waitSeconds,
        "the chain says this identity holds nothing live here (nothing was charged): a lapsed one renews only as a new claim, " +
            "and this identity replaced another too recently to make one; that opens in ${waitSeconds / SECONDS_PER_DAY + 1} days",
    )

    /**
     * Chain 203d3b2: [handle] is in its renewal period, so renewing or
     * changing it is bounded like a claim, which this identity (it replaced
     * another too recently) cannot make yet. Nothing was sent.
     */
    class HandleNotLive(val handle: String, waitSeconds: Long) : NotYet(
        waitSeconds,
        "@$handle is past its expiry (in its renewal period): renewing or changing it now counts as a new claim, and this identity " +
            "replaced another too recently to make one; that opens in ${waitSeconds / SECONDS_PER_DAY + 1} days. Until its renewal period ends nobody else can take it",
    )

    /** Chain 203d3b2: the caretaker vote lapsed; casting again is a new vote, which this identity cannot make yet. Nothing was sent. */
    class CaretakerLapsed(waitSeconds: Long) : NotYet(
        waitSeconds,
        "your caretaker vote has lapsed: casting again counts as a new vote, and this identity replaced another too recently to make one; " +
            "that opens in ${waitSeconds / SECONDS_PER_DAY + 1} days",
    )

    /** Chain 203d3b2: MsgMoveHandle moves only a live handle. Nothing was sent. */
    class HandleNotMovable(val handle: String) : IllegalStateException(
        "@$handle is past its expiry (in its renewal period), and only a live handle can be moved: renew it first, then move it",
    )

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
     * /gas/register note (its ciphertext is not bound). [referrer], when the
     * registrant names one, is a handle, bound into the affiliate field as
     * H(TAG_AFFILIATE, Bytes(handle)); the chain mints the referrer's half
     * itself, to the address the handle resolves to (chain 203d3b2).
     */
    class RegistrationPrep(val anml: NoteOut, val erth: NoteOut, val gas: NoteOut, val referrer: String, val binding: Fr, val idc: Fr)

    /** A referrer named by handle, resolved from the directory: the handle and the address it names now. */
    data class Referrer(val handle: String, val address: ShieldedAddress)

    fun prepareRegistration(referrer: Referrer?): RegistrationPrep {
        val anml = mint("uanml")
        val erth = mint(FEE)
        val gas = mint(FEE)
        referrer?.let {
            require(network.erth.wallet.privacy.handles.Handles.valid(it.handle)) { "${it.handle} is not a handle" }
            require(it.address.ownerPk != keys.ownerPk) { "a registration cannot name its own wallet as its referrer" }
        }
        val aff = if (referrer == null) Fr.ZERO else Privacy.affiliateField(referrer.handle)
        val binding = Privacy.registrationBinding(keys.idc, anml.pc, anml.ciphertext, erth.pc, erth.ciphertext, aff)
        return RegistrationPrep(anml, erth, gas, referrer?.handle.orEmpty(), binding, keys.idc)
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
            .setAffiliateHandle(prep.referrer)
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
        val refused = { hash: String ->
            if (store.state.pendingRegistration?.txHash == hash) { store.state.pendingRegistration = null; store.save() }
        }
        val result = run(accepted = pending, rejected = refused) { fee ->
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
        // Claims bound the activation only (start of yesterday); the predecessor is no bound.
        val m = membership(Privacy.claimScope(day), Fr.ZERO, Fr.ZERO, (day - 1) * SECONDS_PER_DAY, Privacy.NO_BOUND)
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
        // Checked throughout (audit 4, H1): an activated_at no block can
        // have (sync bounds them; an old store may hold one) has no answer.
        if (id.activatedAt < 0) return null
        return try {
            val firstDay = Math.addExact(id.activatedAt / SECONDS_PER_DAY + 1, if (id.activatedAt % SECONDS_PER_DAY == 0L) 0L else 1L)
            val day = maxOf(today() + if (claimedToday()) 1 else 0, firstDay)
            if (day == today()) 0L else Math.multiplyExact(day, SECONDS_PER_DAY)
        } catch (e: ArithmeticException) {
            null
        }
    }

    /**
     * The chain's lease bounds now (Query/LeaseBounds), checked before use:
     * lease lengths in range, a sane margin, and bounds that are what the
     * lengths give at its block time. Every max_predecessor below comes from
     * these lease lengths, never from Params (audit 5 P3; mobile audit 5 L1).
     */
    private fun leaseBounds(): PrivacyChainReads.LeaseBounds {
        val lb = reads.leaseBounds()
        if (lb.blockTime <= 0) throw java.io.IOException("the node's lease bounds have no block time")
        leaseParam(lb.handleLeaseSeconds, "handle lease")
        leaseParam(lb.caretakerLeaseSeconds, "caretaker lease")
        if (lb.activationMarginSeconds !in 0..Handles.MAX_AHEAD_SECONDS) throw java.io.IOException("the node's activation margin ${lb.activationMarginSeconds} is out of range")
        val handle = lb.blockTime - lb.handleLeaseSeconds - lb.activationMarginSeconds
        val caretaker = lb.blockTime - lb.caretakerLeaseSeconds - lb.activationMarginSeconds
        if (lb.handleClaimBound != handle || lb.caretakerCastBound != caretaker) throw java.io.IOException("the node's lease bounds do not add up")
        return lb
    }

    /**
     * The max_predecessor a new caretaker split or handle claim names: the
     * chain needs it strictly below block time - [lease] - the activation
     * margin, so an identity that replaced another waits until anything its
     * predecessor could hold there has lapsed. [lease] is the length
     * LeaseBounds reports (the longest handle lease ever in force; a held
     * longer caretaker lease after a cut). Rounded down to the hour so it
     * says nothing about when the tx was made, less a margin for clock skew.
     * Every wallet names the same bound (a fresh registrant's predecessor_at
     * 0 meets it), so the proof does not tell a fresh identity from an old one.
     */
    private fun predecessorBound(lb: PrivacyChainReads.LeaseBounds, lease: Long): Long {
        val bound = Handles.satSub(Handles.satSub(lb.blockTime, lease), Handles.satAdd(lb.activationMarginSeconds, CLOCK_MARGIN))
        return maxOf(0L, bound / 3600 * 3600)
    }

    /**
     * The max_predecessor for a msg bounded unless the prover holds something
     * live in its scope, and the wait it implies: the lease bound when this
     * identity meets it (the chain takes it either way); else no bound when
     * the wallet believes it holds a live one ([held] true) or cannot tell
     * (null), and the chain checks that in its ante, before any fee: a
     * renewal or refresh of a live one goes through, anything else is
     * refused at no cost ([boundAttempt] says why, with the wait). Held but
     * known lapsed ([held] false; chain 203d3b2: a handle in its renewal
     * period, a split past its expiry) needs the bound like a claim, so
     * [lapsed] is thrown before anything is sent.
     */
    private fun leaseStatement(bound: Long, held: Boolean?, lapsed: (wait: Long) -> NotYet): Pair<Long, Long?> {
        val id = identity()
        return when {
            id.predecessorAt <= bound -> bound to null
            held == false -> throw lapsed(id.predecessorAt - bound)
            else -> Privacy.NO_BOUND to (id.predecessorAt - bound)
        }
    }

    /** Runs [block]; a chain refusal of its unmet predecessor bound becomes [NotHeld] when [wait] is set. */
    private fun boundAttempt(wait: Long?, block: () -> TxResult): TxResult {
        if (wait == null) return block()
        try {
            return block()
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("max_predecessor") }) throw NotHeld(wait)
            throw e
        }
    }

    /**
     * Whether the split this wallet holds is live at chain time [t]: true, or
     * false when the chain's own expiry has passed (a lapsed split the sweep
     * has not reached is not held: refreshing it is a new split, bounded;
     * chain 203d3b2), null when it holds none or knows only an estimate.
     */
    private fun caretakerHeldLive(t: Long): Boolean? {
        if (!holdsSplit()) return null
        val exp = caretakerExpiresAt()
        return when {
            exp > t -> true
            store.state.caretakerExpiresAt > 0 -> false
            else -> null
        }
    }

    /** Whether this wallet holds a caretaker split the chain still counts (as far as it knows). */
    fun caretakerLive(): Boolean = holdsSplit() && caretakerExpiresAt() > now()

    private fun holdsSplit(): Boolean = store.state.caretakerSplit.isNotEmpty() || store.state.caretakerSplitUnknown

    /** When the split lapses: the chain's expires_at, or its cast time + R. 0 for none. */
    fun caretakerExpiresAt(): Long {
        val s = store.state
        if (!holdsSplit()) return 0
        if (s.caretakerExpiresAt > 0) return s.caretakerExpiresAt
        return runCatching { Math.addExact(s.caretakerCastAt, reads.personhoodParams().caretakerVoteSeconds) }.getOrDefault(0)
    }

    /** A value-0 state record note (PRIVACY_FORMATS.md 3b) to [to]'s own address, tagged with its nk. */
    private fun stateRecord(to: PrivacyKeys, memo: (Fr) -> ByteArray): NoteOut = NoteOut.to(to.address, FEE, 0, memo(to.nk))

    private fun leaseParam(v: Long, name: String): Long {
        require(v in 1..Handles.MAX_AHEAD_SECONDS) { "the node's $name ($v s) is out of range" }
        return v
    }

    /**
     * Casts, refreshes or (empty) clears the caretaker split, option id ->
     * percent. Nothing refreshes it on its own: it lapses at expires_at
     * unless its owner casts again (the app reminds them).
     */
    fun setCaretaker(split: Map<Long, Long>): TxResult {
        check(!store.state.caretakerMovedOut || split.isEmpty()) { "this identity moved its caretaker vote to another; it cannot cast one again" }
        check(store.state.pendingMoves.none { !it.incoming && it.kind == PendingMove.CARETAKER && !it.confirmed }) {
            "this identity's caretaker vote is being moved; wait for the move to be confirmed"
        }
        // Validated before anything is sent (audit 5, L6): nothing after the broadcast can throw on it.
        // r0: the lease a cast gets now (Params), for the record's expiry estimate. The
        // bound: LeaseBounds' caretaker lease, which a held longer one keeps after a cut.
        val r0 = leaseParam(reads.personhoodParams().caretakerVoteSeconds, "caretaker lease")
        val (maxPred, wait) = if (split.isEmpty()) Privacy.NO_BOUND to null else {
            val lb = leaseBounds()
            leaseStatement(predecessorBound(lb, lb.caretakerLeaseSeconds), caretakerHeldLive(lb.blockTime)) { CaretakerLapsed(it) }
        }
        val m = membership(Privacy.caretakerScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, maxPred)
        val weights = weights(split)
        // The state record: what a wallet restored from the mnemonic finds (audit 5, M1). Its
        // expiry is the wallet's estimate; the chain's own (from the result) replaces it here.
        val record = stateRecord(keys) { nk ->
            if (split.isEmpty()) WalletSync.caretakerMemo(nk, WalletSync.RECORD_NONE)
            else WalletSync.caretakerMemo(nk, WalletSync.RECORD_HOLDS, Handles.satAdd(now(), r0), split)
        }
        val r = boundAttempt(wait) {
            run { fee ->
                Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee))), membership = m) { bs, _, mem ->
                    MsgSetCaretaker.newBuilder().setFee(bs[0]).setMembership(mem).addAllPercentages(weights).setMaxPredecessor(maxPred).build()
                }
            }
        }
        // Audit 5 (M4, L6): the node's expires_at only within the lease range; else the block time + R, saturating.
        val exp = r.attr("set_caretaker", "expires_at")?.toLongOrNull()?.takeIf { it > 0 && it <= Handles.satAdd(now(), Handles.MAX_AHEAD_SECONDS) }
        synchronized(this) {
            store.state.caretakerCastAt = now(); store.state.caretakerSplit = split; store.state.caretakerSplitUnknown = false
            store.state.caretakerExpiresAt = if (split.isEmpty()) 0 else exp ?: Handles.satAdd(r.time.takeIf { it > 0 } ?: now(), r0)
            store.save()
        }
        return r
    }

    /**
     * The nullifier [other]'s identity proves in [scope]: what a move names
     * as new_owner, H(TAG_SN, new_id_secret, scope). Computed from the other
     * wallet's keys on this phone; it says nothing about the passport.
     */
    fun newOwner(other: PrivacyKeys, scope: Fr): Fr = Privacy.scopeNullifier(other.idSecret, scope)

    /**
     * Hands the live caretaker split (and its expiry) to [newOwner], the
     * caretaker-scope nullifier of the identity that is to hold it: how a
     * switch of identity keeps its vote. This identity may never cast one
     * again (ErrCaretakerMovedOut, 1126).
     */
    fun moveCaretaker(newOwner: Fr, target: PrivacyKeys? = null, recorder: MoveRecorder? = null): TxResult {
        check(caretakerLive()) { "this identity holds no live caretaker vote to move" }
        require(newOwner != Privacy.scopeNullifier(keys.idSecret, Privacy.caretakerScope())) { "the new owner is this identity" }
        target?.let { require(newOwner == newOwner(it, Privacy.caretakerScope())) { "new_owner is not the target wallet's" } }
        checkNoMove(PendingMove.CARETAKER)
        val s = store.state
        val move = PendingMove(PendingMove.CARETAKER, "", 0, incoming = false, split = s.caretakerSplit,
            splitUnknown = s.caretakerSplitUnknown, expiresAt = caretakerExpiresAt(), target = recorder?.targetId.orEmpty())
        // State records: moved out for this identity, held (split, expiry) for the new one.
        val outs = listOf(stateRecord(keys) { WalletSync.caretakerMemo(it, WalletSync.RECORD_MOVED_OUT) }) +
            listOfNotNull(target?.let { t -> stateRecord(t) { WalletSync.caretakerMemo(it, WalletSync.RECORD_HOLDS, move.expiresAt, if (move.splitUnknown) emptyMap() else move.split) } })
        val m = membership(Privacy.caretakerScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, Privacy.NO_BOUND)
        val r = moveRun(move, recorder) { fee ->
            Assembled(listOf(bundle(outs, mapOf(FEE to fee))), membership = m) { bs, _, mem ->
                MsgMoveCaretaker.newBuilder().setFee(bs[0]).setMembership(mem).setNewOwner(ByteString.copyFrom(newOwner.toBytes())).build()
            }
        }
        synchronized(this) { confirmMove(r.hash) }
        return r
    }

    /**
     * Writes a move into the new identity's wallet (audit 5, M2): before the
     * broadcast, as pending, so neither a lost answer nor a killed app can
     * strand what moved; undone only on a definite refusal. [targetId] is
     * that wallet's store id.
     */
    interface MoveRecorder {
        val targetId: String
        fun record(move: PendingMove)
        fun rollback(move: PendingMove)
    }

    private fun checkNoMove(kind: String) =
        check(store.state.pendingMoves.none { !it.incoming && it.kind == kind && !it.confirmed }) { "a move of this identity's $kind is waiting for the chain" }

    /**
     * Runs a move: recorded here (outgoing) and in the target (incoming)
     * before the broadcast; a refusal undoes both; a confirmed tx is applied
     * by the caller ([confirmMove]); anything else (a wait that timed out, a
     * tx that may yet land or fail) stays pending for [resolvePendingMoves].
     */
    private fun moveRun(move: PendingMove, recorder: MoveRecorder?, assemble: (fee: Long) -> Assembled): TxResult {
        recorder?.let { rc ->
            val fixed = store.state.switchTarget
            check(fixed.isEmpty() || fixed == rc.targetId) { "this identity already moved to another wallet; switch to that one" }
        }
        return run(
            accepted = { hash, timeout ->
                val p = move.copy(txHash = hash, timeoutHeight = timeout)
                val ok = recorder?.let { rc -> runCatching { rc.record(p.copy(incoming = true, target = "", recorded = true)) }.isSuccess } ?: true
                val s = store.state
                s.pendingMoves.add(p.copy(recorded = ok))
                if (recorder != null && s.switchTarget.isEmpty()) s.switchTarget = recorder.targetId
                store.save()
            },
            rejected = { hash ->
                store.state.pendingMoves.removeAll { it.txHash == hash && !it.incoming }
                store.save()
                recorder?.let { rc -> runCatching { rc.rollback(move.copy(txHash = hash, incoming = true)) } }
            },
            assemble = assemble,
        )
    }

    /** The move [hash] is in a block and succeeded: this identity no longer holds what it moved. */
    private fun confirmMove(hash: String) {
        val s = store.state
        val i = s.pendingMoves.indexOfFirst { it.txHash == hash }
        if (i < 0) return
        val p = s.pendingMoves[i]
        if (!p.incoming) {
            if (p.kind == PendingMove.HANDLE) { s.handle = ""; s.handleMovedOut = true; s.handleSetAt = now() }
            else { s.caretakerSplit = emptyMap(); s.caretakerSplitUnknown = false; s.caretakerExpiresAt = 0; s.caretakerMovedOut = true }
        }
        if (p.incoming || p.recorded) s.pendingMoves.removeAt(i) else s.pendingMoves[i] = p.copy(confirmed = true)
        store.save()
    }

    /** The move [p] is definitely not in the chain (refused, failed in its block, or gone past its timeout_height). */
    private fun dropMove(p: PendingMove) {
        val s = store.state
        s.pendingMoves.removeAll { it.txHash == p.txHash && it.incoming == p.incoming }
        if (p.incoming) undoIncoming(s, p, now())
        store.save()
    }

    /**
     * Settles every move in flight by its tx (audit 5, M2): committed, it is
     * applied; failed in its block, or unknown to the chain past its
     * timeout_height, it is undone (and its state records void). A move the
     * chain cannot say anything about yet stays. Returns whether any is
     * still unconfirmed.
     */
    @Synchronized
    fun resolvePendingMoves(): Boolean {
        val s = store.state
        for (p in s.pendingMoves.toList()) {
            if (p.confirmed) continue
            val r = runCatching { chain.tx(p.txHash) }.getOrNull()
            when {
                r != null && r.code == 0 -> confirmMove(p.txHash)
                r != null -> { s.voidRecordHeights.add(r.height); dropMove(p) }
                // Audit 6 (M4): a timeout no sane tip gives is settled by the tx's status alone.
                !PrivateTxEngine.timeoutSane(p.timeoutHeight, s.verifiedHeight) -> {
                    val st = runCatching { roots.txStatus(p.txHash) }.getOrNull()
                    if (st == network.erth.wallet.privacy.sync.TxStatus.MISSING) dropMove(p)
                }
                else -> {
                    val tip = runCatching { chain.tipHeight() }.getOrNull() ?: continue
                    if (tip > p.timeoutHeight) dropMove(p)
                }
            }
        }
        return s.pendingMoves.any { !it.confirmed }
    }

    /** Moves away from this identity that the chain has not confirmed yet, and confirmed ones not yet recorded in their target. */
    fun outgoingMoves(): List<PendingMove> = store.state.pendingMoves.filter { !it.incoming }

    /** Marks a confirmed move recorded in its target (a retried [MoveRecorder.record] succeeded). */
    @Synchronized
    fun markRecorded(hash: String) {
        val s = store.state
        val i = s.pendingMoves.indexOfFirst { it.txHash == hash && !it.incoming }
        if (i < 0) return
        val p = s.pendingMoves[i]
        if (p.confirmed) s.pendingMoves.removeAt(i) else s.pendingMoves[i] = p.copy(recorded = true)
        store.save()
    }

    // ---- handles ------------------------------------------------------------

    /**
     * Claims [handle] for [address] (default: this wallet's shielded
     * address), renews the one held (the same handle: lease now +
     * handle_lease_seconds, address updated), or changes to another (the old
     * one is freed at once). A claim by an identity holding none bounds its
     * predecessor by the longest lease; a renewal or change does not.
     * Nothing renews on its own: the app reminds the owner before expiry.
     */
    fun bindHandle(handle: String, address: ShieldedAddress = keys.address): TxResult {
        require(Handles.valid(handle)) { "\"$handle\" is not a handle: 3-32 of a-z, 0-9 and -, no dash at either end" }
        val holds = store.state.handle.isNotEmpty()
        check(holds || !store.state.handleMovedOut) { "this identity moved its handle to another; it cannot claim one again" }
        checkNoMove(PendingMove.HANDLE)
        // Chain 203d3b2: only a live handle renews or changes unbounded; one in its
        // renewal period is bounded like a claim, by the longest lease ever in force.
        val lb = leaseBounds()
        // The lease this bind gets (Params), validated before anything is sent (audit 5, L6).
        val leaseNow = leaseParam(reads.personhoodParams().handleLeaseSeconds, "handle lease")
        val held = if (!holds) null else handleExpiresAt().takeIf { it > 0 }?.let { it > lb.blockTime }
        val (maxPred, wait) = leaseStatement(predecessorBound(lb, lb.handleLeaseSeconds), held) { HandleNotLive(store.state.handle, it) }
        val addr = address.encode()
        val m = membership(Privacy.handleScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, maxPred)
        val record = stateRecord(keys) { WalletSync.handleMemo(it, WalletSync.RECORD_HOLDS, handle) }
        val r = boundAttempt(wait) {
            run { fee ->
                Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee))), membership = m) { bs, _, mem ->
                    MsgBindHandle.newBuilder().setFee(bs[0]).setMembership(mem).setHandle(handle).setAddress(addr).setMaxPredecessor(maxPred).build()
                }
            }
        }
        // The chain's expires_at (handle_bound), within the lease range; else the block time + the lease.
        val exp = r.attr("handle_bound", "expires_at")?.toLongOrNull()?.takeIf { it > 0 && it <= Handles.satAdd(now(), Handles.MAX_AHEAD_SECONDS) }
        synchronized(this) {
            val s = store.state
            s.handle = handle; s.handleSetAt = now()
            s.handleExpiresFor = handle
            s.handleExpiresAt = exp ?: Handles.satAdd(r.time.takeIf { it > 0 } ?: now(), leaseNow)
            store.save()
        }
        return r
    }

    /**
     * When this identity's handle stops being live, as the chain last said
     * (its bind, or its directory); 0 when the wallet does not know.
     */
    fun handleExpiresAt(): Long {
        val s = store.state
        return if (s.handle.isNotEmpty() && s.handleExpiresFor == s.handle) s.handleExpiresAt else 0
    }

    /** Releases this identity's handle at once (anyone may claim it). */
    fun releaseHandle(): TxResult {
        // Audit 5 (L12): the chain refuses a release by a holder of none only after taking the fee.
        check(store.state.handle.isNotEmpty()) { "this identity holds no handle to release" }
        checkNoMove(PendingMove.HANDLE)
        val m = membership(Privacy.handleScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, Privacy.NO_BOUND)
        val record = stateRecord(keys) { WalletSync.handleMemo(it, WalletSync.RECORD_NONE) }
        val r = run { fee ->
            Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee))), membership = m) { bs, _, mem ->
                MsgBindHandle.newBuilder().setFee(bs[0]).setMembership(mem).setMaxPredecessor(Privacy.NO_BOUND).build()
            }
        }
        synchronized(this) { store.state.handle = ""; store.state.handleSetAt = now(); store.save() }
        return r
    }

    /**
     * Hands this identity's handle (lease unchanged) to [newOwner], the
     * handle-scope nullifier of the identity that is to hold it. This
     * identity may never claim one again (ErrHandleMovedOut, 1125).
     */
    fun moveHandle(newOwner: Fr, target: PrivacyKeys? = null, recorder: MoveRecorder? = null): TxResult {
        val handle = store.state.handle
        check(handle.isNotEmpty()) { "this identity holds no handle to move" }
        require(newOwner != Privacy.scopeNullifier(keys.idSecret, Privacy.handleScope())) { "the new owner is this identity" }
        target?.let { require(newOwner == newOwner(it, Privacy.handleScope())) { "new_owner is not the target wallet's" } }
        checkNoMove(PendingMove.HANDLE)
        // Chain 203d3b2: MsgMoveHandle refuses a handle that is not live (its renewal period).
        handleExpiresAt().takeIf { it > 0 }?.let { if (it <= chainNow()) throw HandleNotMovable(handle) }
        val move = PendingMove(PendingMove.HANDLE, "", 0, incoming = false, handle = handle, target = recorder?.targetId.orEmpty())
        // State records: moved out for this identity, held for the new one.
        val outs = listOf(stateRecord(keys) { WalletSync.handleMemo(it, WalletSync.RECORD_MOVED_OUT) }) +
            listOfNotNull(target?.let { t -> stateRecord(t) { WalletSync.handleMemo(it, WalletSync.RECORD_HOLDS, handle) } })
        val m = membership(Privacy.handleScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, Privacy.NO_BOUND)
        val r = moveRun(move, recorder) { fee ->
            Assembled(listOf(bundle(outs, mapOf(FEE to fee))), membership = m) { bs, _, mem ->
                MsgMoveHandle.newBuilder().setFee(bs[0]).setMembership(mem).setHandle(handle)
                    .setNewOwner(ByteString.copyFrom(newOwner.toBytes())).build()
            }
        }
        synchronized(this) { confirmMove(r.hash) }
        return r
    }

    /**
     * Records what a switch moved to this wallet's identity (the other
     * wallet's moves named its nullifiers): the handle, and the split with
     * its expiry. Its renewal or refresh then takes no predecessor bound.
     */
    @Synchronized
    fun adoptMoved(handle: String?, split: Map<Long, Long>?, splitExpiresAt: Long) {
        if (handle != null) recordIncoming(store, PendingMove(PendingMove.HANDLE, "", 0, incoming = true, handle = handle), now())
        if (split != null && split.isNotEmpty()) {
            recordIncoming(store, PendingMove(PendingMove.CARETAKER, "", 0, incoming = true, split = split, expiresAt = splitExpiresAt), now())
        }
    }

    /**
     * Audit 5 (M1, L11): squares the store's handle with the chain's
     * directory [dir], read at [readAt] (wallet clock): a handle the chain
     * swept (absent or free) is dropped; with none held, a single entry
     * naming this wallet's own address is taken as held (a restore lost
     * it; renewing it is refused at no cost if it is not). Nothing changes
     * while a move is in flight or when the directory predates the store's
     * last change. Returns every non-free entry naming this wallet's
     * address, for the reminders.
     */
    @Synchronized
    fun reconcileHandle(dir: Map<String, HandleEntry>, readAt: Long): List<HandleEntry> {
        val s = store.state
        val t = now()
        val own = keys.address.encode()
        val addressed = dir.values.filter { it.address == own && it.statusAt(t) != HandleEntry.FREE }
        val moving = s.pendingMoves.any { it.kind == PendingMove.HANDLE && !it.confirmed }
        if (!moving && readAt > s.handleSetAt) {
            if (s.handle.isNotEmpty()) {
                val e = dir[s.handle]
                if (e == null || e.statusAt(t) == HandleEntry.FREE) { s.handle = ""; s.handleSetAt = t; store.save() }
            } else if (!s.handleMovedOut && addressed.size == 1) {
                s.handle = addressed[0].handle; s.handleSetAt = t; store.save()
            }
            // The chain's expiry of the handle held: whether it is live (chain 203d3b2).
            dir[s.handle]?.takeIf { s.handle.isNotEmpty() && it.statusAt(t) != HandleEntry.FREE }?.let { e ->
                if (s.handleExpiresFor != s.handle || s.handleExpiresAt != e.expiresAt) { s.handleExpiresFor = s.handle; s.handleExpiresAt = e.expiresAt; store.save() }
            }
        }
        return addressed
    }

    // ---- assembly -----------------------------------------------------------

    /** A human vote on an x/gov proposal. The scope is recomputed here rather than taken from the node. */
    fun voteProposal(proposalId: Long, yes: Boolean): TxResult {
        val b = reads.ballotInputs(proposalId = proposalId)
        check(b.scope == Privacy.proposalScope(proposalId, b.round)) { "the node's ballot scope is not this proposal's" }
        // The chain's statement: max_activation no bound, max_predecessor the ballot's (opened - 86400).
        val m = membership(b.scope, b.excludedDsc, b.excludedCountry, b.maxActivation, b.maxPredecessor)
        val option = if (yes) VoteOption.VOTE_OPTION_YES else VoteOption.VOTE_OPTION_NO
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgVoteProposal.newBuilder().setFee(bs[0]).setMembership(mem).setProposalId(proposalId).setOption(option).build()
            }
        }
    }

    fun proposeRemoval(optionId: Long): TxResult {
        // The chain's day (audit 5, L2): its scope is the including block's UTC day.
        val day = chainNow() / SECONDS_PER_DAY
        // The predecessor bound: the start of today (UTC) less a day, whatever the root window; no activation bound.
        val m = membership(Privacy.proposeRemovalScope(optionId, day), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, day * SECONDS_PER_DAY - ACTIVATION_MARGIN)
        return run { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgProposeRemoval.newBuilder().setFee(bs[0]).setMembership(mem).setOptionId(optionId).build()
            }
        }
    }

    fun voteRemoval(optionId: Long, yes: Boolean): TxResult {
        val b = reads.ballotInputs(optionId = optionId)
        check(b.scope == Privacy.removalScope(b.ballotId)) { "the node's ballot scope is not this ballot's" }
        // The chain's statement: max_activation no bound, max_predecessor the ballot's (opened - 86400).
        val m = membership(b.scope, b.excludedDsc, b.excludedCountry, b.maxActivation, b.maxPredecessor)
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
     *
     * Audit 4: at most [maxMerges] merges per confirmation (each pays a fee
     * the user confirmed once), a random pause before each after the first
     * and before handing back to the action that follows, so the merges and
     * the action are not one burst that times them together. More than that
     * is refused: merge on the Notes screen first.
     */
    fun consolidateStake(denom: String, amount: Long, maxMerges: Int = MAX_MERGES_PER_CONFIRM, pause: (Long) -> Unit = { Thread.sleep(it) }): List<TxResult> {
        val out = ArrayList<TxResult>()
        fun space() = pause(MERGE_PAUSE_MIN_MS + (SPACING_RNG.nextDouble() * (MERGE_PAUSE_MAX_MS - MERGE_PAUSE_MIN_MS)).toLong())
        while (true) {
            val ns = spendableStake(denom)
            if (Amounts.satSum(ns.sortedByDescending { it.amount }.take(2)) { it.amount } >= amount || ns.size < 3) {
                if (out.isNotEmpty()) space()
                return out
            }
            if (out.size >= maxMerges) throw IllegalStateException("this stake is spread over too many notes for one confirmation; merge them on the Notes screen first")
            if (out.isNotEmpty()) space()
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
        val nfRoot = snap.nfRoot ?: throw IllegalStateException("this proposal's snapshot has no stake nullifier root; it takes no stake vote")
        val low = snapshotNullifiers(snap).nonMembership(Privacy.stakeNf(keys.nk, note.rho, note.position))
        if (low == null) {
            // Audit 4 (M3): only when sync, too, saw the spend at or before the
            // snapshot's block. Otherwise the two disagree (a stream or a
            // snapshot that is not the chain's): an error, never a vote
            // silently skipped.
            if (note.spentHeight != null && snap.height > 0 && note.spentHeight <= snap.height) throw SpentBeforeSnapshot()
            throw IllegalStateException("the proposal's snapshot nullifier tree holds this note's nullifier, but sync saw no spend before the snapshot; sync again")
        }
        val weight = voteWeight(note.amount)
        val path = tree.pathAt(note.position, snap.treeSize)
        val asset = Privacy.assetId(note.denom)
        val vote = VoteWitnessSpec(vnf) { sighash ->
            VoteWitness(keys.nk, note.amount, note.rho, note.rcm, note.position, path, low, snap.root, nfRoot, asset, weight, proposalId, sighash)
        }
        try {
            val r = run(
                accepted = { hash, timeout -> recordVote(StakeVoteRecord(proposalId, vnf, hash, timeout, confirmed = false)) },
                rejected = { hash -> forgetVote(proposalId, vnf, hash) },
            ) { fee ->
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

    private val snapshots = HashMap<Long, PrivacyChainReads.Snapshot>()

    /**
     * Per-wallet caches built from one chain (snapshots, the stake nullifier
     * tree): dropped when the store's genesis changes (audit 4, L2).
     */
    private var cacheGenesis: String? = null

    private fun checkCaches() = synchronized(snapshots) {
        val g = store.state.genesis
        if (g != cacheGenesis) {
            snapshots.clear()
            synchronized(nfValues) { nfValues.clear(); nfTrees.clear() }
            cacheGenesis = g
        }
    }

    /**
     * [proposalId]'s snapshot, from the chain's own Query/Snapshot (audit 4,
     * M3): its stake root and size, nullifier root and size, block and
     * validator rates are taken from the LCD, never from the indexer (a
     * forged nf_root would make a note look spent before the snapshot). The
     * proposal id is public, so asking names nothing of this wallet. The
     * note root is then checked against the wallet's verified stake tree and
     * the nullifier root against the tree the nullifiers rebuild.
     */
    fun snapshot(proposalId: Long): PrivacyChainReads.Snapshot {
        checkCaches()
        synchronized(snapshots) { snapshots[proposalId] }?.let { return it }
        val snap = reads.snapshot(proposalId)
        synchronized(snapshots) { snapshots[proposalId] = snap }
        return snap
    }

    /** This note already voted on this proposal (the chain's code 1119): votes are final. */
    class AlreadyVoted : IllegalStateException("this stake note already voted on this proposal")

    /** The note's nullifier is in the proposal's snapshot nullifier tree: it was spent before voting opened. */
    class SpentBeforeSnapshot : IllegalStateException("this stake was spent before the proposal's snapshot and cannot vote on it")

    /** A vote the node refused outright (in no mempool): the note may vote again. */
    private fun forgetVote(proposalId: Long, vnf: Fr, hash: String) = synchronized(store) {
        if (store.state.stakeVotes.removeAll { it.proposalId == proposalId && it.vnf == vnf && !it.confirmed && it.txHash == hash }) store.save()
    }

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
                r != null && (r.code == 0 || (r.code == VOTE_NULLIFIER_USED && r.codespace == VOTE_CODESPACE)) -> v.copy(confirmed = true)
                r != null -> null
                v.txHash == null || (v.until != null && tip != null && tip!! > v.until) -> null
                // Audit 6 (M4): a timeout no sane tip gives is settled by the tx's status alone.
                v.until != null && !PrivateTxEngine.timeoutSane(v.until, store.state.verifiedHeight) &&
                    runCatching { roots.txStatus(v.txHash) }.getOrNull() == network.erth.wallet.privacy.sync.TxStatus.MISSING -> null
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
        // Audit 4 (L4): nf_size is the LCD's (snapshot()), so the fetch
        // below is bounded by the chain's own count, never an indexer's.
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
                // The backend's paging rule: aligned pages of NF_PAGE leaf
                // indexes from 0 (leaf 0, the sentinel, is never a row); the
                // leaves already held are dropped, each checked against ours.
                val next = nfValues.size + 1L
                val page = indexer.stakeNullifierLeaves(WalletSync.aligned(next, NF_PAGE), NF_PAGE)
                if (page.leaves.size > NF_PAGE) throw WalletSync.Inconsistent("the indexer sent ${page.leaves.size} stake nullifiers in a page of $NF_PAGE")
                var added = 0
                for ((index, v) in page.leaves) {
                    if (index in 1 until next) {
                        if (nfValues[(index - 1).toInt()] != v) throw WalletSync.Inconsistent("stake nullifier leaf $index differs from the one held")
                        continue
                    }
                    // Contiguous from where we are, or the page is not the tree's order.
                    if (index != nfValues.size + 1L) throw WalletSync.Inconsistent("stake nullifier leaf $index out of order")
                    if (nfValues.size >= n) break
                    nfValues.add(v); added++
                }
                if (added == 0) break
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
                // Spent in the snapshot's own block is spent before it (the
                // snapshot is the trees at that block's end; audit 4).
                (it.spentHeight == null || snap.height == 0L || it.spentHeight > snap.height) &&
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
    fun castStakeVote(proposalId: Long, item: StakeVoteItem, options: List<WeightedVoteOption>, accepted: (hash: String) -> Unit = {}): String? = when (item) {
        is StakeVoteItem.Note -> store.state.stakeNotes.firstOrNull { it.position == item.position }?.let { n ->
            try { stakeVote(proposalId, n, options).hash } catch (e: AlreadyVoted) { null } catch (e: SpentBeforeSnapshot) { null }
        }
        is StakeVoteItem.Position -> positions().firstOrNull { it.first.id == item.id && it.second == item.counter }
            ?.let { (p, c) -> positionVote(p, c, proposalId, options, accepted).hash }
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

    fun positionVote(position: PrivacyChainReads.Position, counter: Int, proposalId: Long, options: List<WeightedVoteOption>, accepted: (hash: String) -> Unit = {}): TxResult {
        val stake = ownerPlan(position, counter)
        return run(accepted = { hash, _ -> accepted(hash) }) { fee ->
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

    /** A withdrawal whose note-paid leg is too large to start (nothing was sent). */
    class WithdrawalTooLarge(message: String) : IllegalArgumentException(message)

    companion object {
        /**
         * The most a withdrawal's note-paid leg may be worth when it starts:
         * a quarter of one note this wallet can hold (2^63 - 1), so the pool
         * can move 4x against the provider before maturity, as x/dex allows
         * for its own cap. Exactly x/dex's maxWithdrawalNoteLeg (chain
         * 8ed1278): MaxSplitNotes / 4 = 32 notes of MaxNoteValue (2^63 - 1),
         * paid as several notes, each one this wallet can hold (Amounts).
         */
        val MAX_WITHDRAWAL_NOTE_LEG: java.math.BigInteger =
            java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(32))

        /**
         * Refuses, before anything is proven or signed, a withdrawal of
         * [shares] of [totalShares] whose note legs ([erthNote], [tokenNote])
         * at the pool's reserves now are above [MAX_WITHDRAWAL_NOTE_LEG]
         * (x/dex checkWithdrawalNoteLegs: floor(shares x reserve / total)).
         */
        fun checkWithdrawalNoteLegs(
            shares: java.math.BigInteger, totalShares: java.math.BigInteger,
            reserveErth: java.math.BigInteger, reserveToken: java.math.BigInteger, tokenDenom: String,
            erthNote: Boolean, tokenNote: Boolean,
        ) {
            if (totalShares.signum() <= 0) return
            for ((on, reserve, denom) in listOf(Triple(erthNote, reserveErth, FEE), Triple(tokenNote, reserveToken, tokenDenom))) {
                if (!on) continue
                val v = shares.multiply(reserve).divide(totalShares)
                if (v > MAX_WITHDRAWAL_NOTE_LEG) {
                    throw WithdrawalTooLarge("this withdrawal's $denom leg ($v) is more than one withdrawal can pay as private notes; withdraw in smaller parts")
                }
            }
        }

        /**
         * Writes [p] (a move to this store's identity) into [store]: what it
         * now holds, and the move as pending until its own wallet settles it
         * by hash (audit 5, M2). Used by the mover for the other wallet's store.
         */
        fun recordIncoming(store: PrivacyStore, p: PendingMove, now: Long) = synchronized(store) {
            val s = store.state
            if (p.kind == PendingMove.HANDLE) { s.handle = p.handle; s.handleSetAt = now }
            else {
                s.caretakerSplit = p.split; s.caretakerSplitUnknown = p.splitUnknown || p.split.isEmpty()
                s.caretakerExpiresAt = p.expiresAt.coerceIn(0, Handles.satAdd(now, Handles.MAX_AHEAD_SECONDS)); s.caretakerCastAt = now
            }
            if (p.txHash.isNotEmpty() && s.pendingMoves.none { it.txHash == p.txHash && it.incoming }) {
                s.pendingMoves.add(p.copy(incoming = true, target = "", recorded = true, confirmed = false))
            }
            store.save()
        }

        /** Undoes [recordIncoming] for a move that definitely did not happen. */
        fun rollbackIncoming(store: PrivacyStore, p: PendingMove, now: Long) = synchronized(store) {
            val s = store.state
            s.pendingMoves.removeAll { it.txHash == p.txHash && it.incoming }
            undoIncoming(s, p, now)
            store.save()
        }

        internal fun undoIncoming(s: network.erth.wallet.privacy.sync.PrivacyState, p: PendingMove, now: Long) {
            if (p.kind == PendingMove.HANDLE) {
                if (s.handle == p.handle) { s.handle = ""; s.handleSetAt = now }
            } else if (s.caretakerSplit == p.split && s.caretakerSplitUnknown == (p.splitUnknown || p.split.isEmpty())) {
                s.caretakerSplit = emptyMap(); s.caretakerSplitUnknown = false; s.caretakerExpiresAt = 0
            }
        }

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

        /** Stake merges one confirmation may pay for, and the random pause between them (audit 4). */
        const val MAX_MERGES_PER_CONFIRM = 2
        const val MERGE_PAUSE_MIN_MS = 15_000L
        const val MERGE_PAUSE_MAX_MS = 45_000L
        private val SPACING_RNG = java.security.SecureRandom()

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

        /** The codespace [VOTE_NULLIFIER_USED] is registered in: the code alone could be any module's (audit 4). */
        const val VOTE_CODESPACE = "shieldedstaking"

        /** Whether [e] is the chain refusing a vote nullifier already used on the proposal. */
        fun alreadyVotedError(e: Throwable): Boolean {
            val chainOf = generateSequence(e) { it.cause }
            if (chainOf.any { it is network.erth.wallet.privacy.tx.UnsignedTx.TxRejected && it.code == VOTE_NULLIFIER_USED && it.codespace == VOTE_CODESPACE }) return true
            // Simulate answers with the error's registered text, not its code.
            return chainOf.mapNotNull { it.message }.any { "this stake note already voted on this proposal" in it }
        }

        /** Stake nullifier leaves asked of the indexer a page, and of the LCD (its maximum). */
        const val NF_PAGE = WalletSync.PAGE_SIZE
        const val LCD_NF_PAGE = 1000

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

        fun notYetText(waitSeconds: Long): String =
            if (waitSeconds > 2 * SECONDS_PER_DAY) "this identity replaced another too recently for this action; it opens in ${waitSeconds / SECONDS_PER_DAY + 1} days"
            else "this registration is too recent for this action; try again in ${waitSeconds / 3600 + 1}h"
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

        /**
         * How long an anchor must stay valid past the chain's last block for
         * a tx built on it: the chain's CheckTx margin (120 s), the tx's
         * timeout_height (50 blocks) and proving on a phone, with room.
         */
        const val ANCHOR_MARGIN = 1_800L

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
