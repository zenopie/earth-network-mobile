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
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgRedelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.wallet.chain.math.StakingApr
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.note.StakeLabel
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.VoteLayout
import network.erth.wallet.privacy.prove.VoteSlot
import network.erth.wallet.privacy.prove.VoteWitness
import network.erth.wallet.privacy.sync.PendingUnbond
import network.erth.wallet.privacy.sync.StakeVoteRecord
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.IdentityRecord
import network.erth.wallet.privacy.sync.IdentitySlot
import network.erth.wallet.privacy.sync.PendingMove
import network.erth.wallet.privacy.tx.MoveWitnessSpec
import network.erth.wallet.privacy.prove.MoveWitness
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
import network.erth.wallet.privacy.zk.DebtTree
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.chain.PrivacyQueries
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
        /** current_date_max_skew_seconds: how long a failed registration may still land after its current_date. */
        val currentDateMaxSkewSeconds: Long = PrivacyWallet.REGISTRATION_SKEW_SECONDS,
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
        /** When the split stops counting (x/allocation groundworks_lease_seconds after it was cast or renewed; 0 without a split). */
        val splitExpiresAt: Long = 0,
    )

    /**
     * x/personhood Query/LeaseBounds: the lease lengths the
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

    /**
     * x/shieldedstaking Query/DebtTree{start, limit}: the
     * slash debt tree's rows from leaf start+1 in insertion order (each with
     * its latest retained), its size (sentinel included, 0 before the first
     * row) and current root, the label window and the clear_before a proof
     * may name now. The whole tree, paged: nothing names a move of ours.
     */
    data class DebtTreePage(val rows: List<Pair<Fr, Long>>, val size: Long, val root: Fr, val windowSeconds: Long, val clearBefore: Long)

    /**
     * One entry of x/shieldedstaking Query/Validators (ORCHARD_DESIGN 12.2):
     * what a delegation, undelegation, redelegation or stake vote at
     * [validator] is quoted from. B = [backing], S = [supply] (rate = B / S,
     * exact integers; [rate] the chain's LegacyDec of it, for display), the
     * queue P = [pendingDelegation], U = [pendingUndelegation], the module's
     * delegation D = [delegation] and unwithdrawn rewards W = [rewards]
     * (B = D + W + P - U), x/staking's [status] ("" for a book whose
     * validator x/staking removed), [jailed], [tombstoned], whether a
     * delegation or a redelegation into it is taken now ([delegatable], else
     * [refusal], the chain's reason), and the module's redelegation record
     * out of it per destination ([redelegations], what MsgRedelegate's gas
     * grows with).
     */
    data class ValidatorQuote(
        val validator: String,
        val backing: java.math.BigInteger,
        val supply: java.math.BigInteger,
        val pendingDelegation: java.math.BigInteger = java.math.BigInteger.ZERO,
        val pendingUndelegation: java.math.BigInteger = java.math.BigInteger.ZERO,
        val delegation: java.math.BigInteger = backing - pendingDelegation + pendingUndelegation,
        val rewards: java.math.BigInteger = java.math.BigInteger.ZERO,
        val rate: BigDecimal = if (supply.signum() == 0) BigDecimal.ONE else BigDecimal(backing).divide(BigDecimal(supply), 18, java.math.RoundingMode.DOWN),
        val status: String = PrivacyChainReads.BOND_STATUS_BONDED,
        val jailed: Boolean = false,
        val tombstoned: Boolean = false,
        val delegatable: Boolean = true,
        val refusal: String = "",
        val moniker: String = "",
        /** x/staking's commission rate, a fraction (0.10 for 10%). */
        val commission: Double = 0.0,
        /** x/staking's bonded tokens (the validator's whole stake, not the module's). */
        val tokens: java.math.BigInteger = java.math.BigInteger.ZERO,
        val redelegations: Map<String, RedelegationLoad> = emptyMap(),
    ) {
        val removed: Boolean get() = status.isEmpty()
        val bonded: Boolean get() = status == PrivacyChainReads.BOND_STATUS_BONDED

        /**
         * Whether a move out of here leaves the queue first (8.7 step 3): no
         * slash can reach the stake (x/staking unbonded or removed it, or the
         * bonded part D - U is nothing). Otherwise the value leaves the queue
         * and the bonded stake pro rata.
         */
        val queueFirst: Boolean get() = removed || status == PrivacyChainReads.BOND_STATUS_UNBONDED || delegation <= pendingUndelegation

        /** The queue a move draws on: P, and W, which a move withdraws into it first. */
        val queue: java.math.BigInteger get() = pendingDelegation + rewards
    }

    companion object {
        const val BOND_STATUS_BONDED = "BOND_STATUS_BONDED"
        const val BOND_STATUS_UNBONDED = "BOND_STATUS_UNBONDED"
    }

    /** RedelegationLoad: the pair's x/staking record, its [entries] and [countedEntries] (those of positive height). */
    data class RedelegationLoad(val entries: Long, val countedEntries: Long)

    /** Query/Validators, every page, read at one [height]. */
    data class ValidatorList(val height: Long, val validators: List<ValidatorQuote>) {
        private val byOperator = validators.associateBy { it.validator }

        operator fun get(valoper: String): ValidatorQuote? = byOperator[valoper]

        /** [valoper]'s entry; a validator the list does not carry has no book and no x/staking record. */
        fun of(valoper: String): ValidatorQuote = byOperator[valoper] ?: throw IllegalStateException("the chain lists no validator $valoper")

        /** x/staking's bonded tokens, every bonded validator's: what the staking emission is shared over. */
        val bondedTokens: java.math.BigInteger get() = validators.filter { it.bonded }.fold(java.math.BigInteger.ZERO) { acc, v -> acc + v.tokens }
    }

    fun personhoodParams(): PersonhoodParams
    fun leaseBounds(): LeaseBounds
    fun ballotInputs(proposalId: Long = 0, optionId: Long = 0): BallotInputs
    fun epochNumber(): Long
    fun snapshot(proposalId: Long): Snapshot
    fun positions(): List<Position>
    /** x/shieldedstaking Query/StakeNullifierTree{start, limit} (at most 1000 a page). */
    fun stakeNullifierTree(start: Long, limit: Int): NfTreePage
    /** x/shieldedstaking Query/DebtTree{start, limit} (at most 1000 a page). */
    fun debtTree(start: Long, limit: Int): DebtTreePage
    /**
     * x/shieldedstaking Query/Validators, every page at one height: what
     * every staking quote reads. Never a query about one validator (asked
     * shortly before a public msg, it would tie the asking IP to that intent).
     */
    fun validators(): ValidatorList
    /** x/shieldedstaking params.min_delegation (uerth, and the least derth one may credit). */
    fun minDelegation(): Long

    /**
     * About when an undelegation booked in [epoch] is paid, from chain-wide
     * timing alone (the current epoch, epoch length, x/staking's unbonding
     * time): never a query about this wallet's undelegation. Null: unknown.
     */
    fun unbondDueBy(epoch: Long): Long? = null
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
    /** The chain's own trees, every synced root is checked against. */
    private val roots: ChainRoots,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Whether a tx result's code and codespace can be taken as the chain's,
     * given the LCD that answered it: the node is reached over https. An own
     * node over plain http answers for anyone on its network, so its codes
     * move nothing persistent.
     */
    private val chainCodesTrusted: (lcd: String) -> Boolean = { true },
) {
    private val engine = PrivateTxEngine(chainId, chain, prover, verifiedHeight = { store.state.verifiedHeight })

    val address: ShieldedAddress get() = keys.address

    /**
     * The identity generation this wallet acts as (PrivacyKeys): its
     * registration, handle, split and votes are that identity's. A
     * registration of a later one moves it up once it lands.
     */
    val generation: Int get() = store.state.generation

    /** The idc of the identity this wallet acts as. */
    val idc: Fr get() = keys.idc(generation)

    @Synchronized
    fun sync(): WalletSync.Result {
        fillPendingRegistration()
        runCatching { resolvePendingMoves() }
        // The validator list (Query/Validators, whole) is read again with
        // every sync, as with every quote: what pickers and stake values
        // show, and the derth denoms sync names stake notes by. A failed read
        // keeps the last one.
        runCatching { reads.validators() }
        return WalletSync(indexer, store, keys, chainId, roots, now).sync().also { runCatching { resolveUnbonds() } }
    }

    /**
     * A registration recorded at acceptance whose block the wallet has
     * not seen (the wait timed out, the app was killed) is looked up by its
     * hash: committed, it gets its leaf index and activated_at; failed in its
     * block, the failure is kept for the UI (a new registration replaces it).
     */
    private fun fillPendingRegistration() {
        val p = store.state.pendingRegistration ?: return
        if (p.leafIndex != null || p.failure?.startsWith(TX_FAILED) == true) return
        val r = runCatching { chain.tx(p.txHash) }.getOrNull() ?: return
        store.state.pendingRegistration = if (r.code != 0) {
            store.state.activity.fail(p.txHash, "failed in its block (code ${r.code})", r.height.takeIf { it > 0 })
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

    /** Stake (derth/<valoper>) per denom: owner-locked, never sendable. */
    fun stakeBalances(): Map<String, Long> =
        store.state.stakeNotes.filter { it.spendable }.groupBy { it.denom }.mapValues { (_, ns) -> Amounts.satSum(ns) { it.amount } }

    /** Everything held privately: the pool's denoms and the stake denoms. */
    fun balances(): Map<String, Long> = poolBalances() + stakeBalances()

    /** x/shielded max_actions_per_bundle: the most notes (and outputs) one bundle carries. */
    fun maxActions(): Int = chain.maxActionsPerBundle()

    /** A note the chain will mint to us: fresh secrets, their v2 ciphertext to our own address. */
    private fun mint(denom: String): NoteOut = NoteOut.mintToSelf(keys, denom)

    private fun today(): Long = now() / SECONDS_PER_DAY

    /**
     * The chain's time, the LCD tip's block time, for what the
     * chain checks against its own clock (predecessor bounds, the removal
     * day); the device clock only when the node cannot say.
     */
    private fun chainNow(): Long = runCatching { roots.latestBlock()?.time }.getOrNull()?.takeIf { it > 0 } ?: now()

    // ---- running ------------------------------------------------------------

    /**
     * Proves and broadcasts. Only on trees the chain itself vouched for at
     * the last sync: a proof over an indexer's forged tree is refused by
     * the chain anyway, and its notes may not exist.
     */
    @Synchronized
    private fun run(
        act: Act,
        memo: String = "",
        accepted: (hash: String, timeoutHeight: Long) -> Unit = { _, _ -> },
        rejected: (hash: String) -> Unit = {},
        assemble: (fee: Long) -> Assembled,
    ): TxResult {
        requireVerified()
        requireFreshAnchor()
        // The spent notes are marked before the tx is sent,
        // under its hash: a wait that times out (the tx may still land), a
        // lost answer or a killed app never leaves them spendable. They stay
        // pending until the chain is past the tx's timeout_height and says the
        // tx is not in a block (WalletSync.releaseStalePending).
        var sent: String? = null
        val (result, _) = try {
            tipChecked {
                engine.run(assemble, memo, shownFee.get(), accepted = { hash, a, timeout ->
                    markPending(a.spends, a.stakeSpends, timeout, hash)
                    recordSent(act, hash, a)
                    sent = hash
                    accepted(hash, timeout)
                }, rejected = { hash, a ->
                    unmarkPending(a.spends, a.stakeSpends, hash)
                    synchronized(store) { store.state.activity.drop(hash); store.save() }
                    sent = null
                    rejected(hash)
                })
            }
        } catch (e: Exception) {
            // The broadcast's own wait saw it fail in its block: the activity row says why.
            sent?.let { h -> if (e is java.io.IOException && e.message?.startsWith(TX_FAILED_PREFIX) == true) noteOutcome(h) { it.fail(h, e.message!!.take(200)) } }
            throw e
        }
        noteOutcome(result.hash) { it.confirm(result.hash, result.height, result.time.takeIf { t -> t > 0 }) }
        return result
    }

    /**
     * What a private tx is, for the activity list ([PrivateActivity]):
     * its kind, who it is with where the wallet knows (a handle, its own new
     * identity), and the notes the chain mints to this wallet in the same tx
     * (shown as what came in). Its coins and fee are read from the tx itself.
     */
    class Act(val kind: PrivateActivityKind, val counterparty: String = "", val receives: List<NoteOut> = emptyList())

    /** Records a tx the node took in the sealed store's activity: never looked up by hash for it. */
    private fun recordSent(act: Act, hash: String, a: Assembled) {
        val own = keys.ownerPk
        val outs = a.bundles.flatMap { b -> b.actions.map { it.out } }
        val back = outs.filter { o -> o.note != null && o.note.pc(own) == o.pc }
        val stakeBack = listOfNotNull(
            a.stake?.let { p -> p.out?.let { o -> p.denom?.let { ActivityCoin(it, o.amount) } } },
            a.stake?.credit?.let { c -> ActivityCoin(c.denom, c.out.amount) },
        )
        val (o, i) = PrivateActivity.coins(
            PrivateActivity.ofNotes(a.spends), back.map { ActivityCoin(it.denom, it.value) },
            PrivateActivity.ofStake(a.stakeSpends), stakeBack, a.fee,
        )
        val change = back.map { it.note!!.rho.toHex() } +
            listOfNotNull(a.stake?.out?.rho?.toHex(), a.stake?.credit?.out?.rho?.toHex())
        val tx = SentPrivateTx(
            hash = hash, kind = act.kind, generation = generation, counterparty = act.counterparty, fee = a.fee, submittedAt = now(),
            outs = o, ins = i, change = change, receives = act.receives.mapNotNull { it.note?.rho?.toHex() },
            spent = (a.spends.map { it.nf } + a.stakeSpends.map { it.nf }).map { it.toHex() },
        )
        synchronized(store) { store.state.activity.record(tx); store.save() }
    }

    /** A tx an existing check already looked up (a move, an undelegation, a vote): its activity row takes the outcome. */
    private fun outcome(r: TxResult) = noteOutcome(r.hash) {
        if (r.code == 0) it.confirm(r.hash, r.height, r.time.takeIf { t -> t > 0 })
        else it.fail(r.hash, "${PrivateActivity.FAILED_IN_BLOCK} (code ${r.code})", r.height.takeIf { h -> h > 0 })
    }

    private fun noteOutcome(hash: String, f: (ActivityLog) -> Unit) = runCatching {
        synchronized(store) { f(store.state.activity); store.save() }
    }

    /**
     * Notes the chain will mint to this wallet later, by what they are
     * (a gas grant, an unbonding payout, a withdrawal's legs, a shield's
     * note): their received row says so. Local, like the rest of the activity.
     */
    fun expect(kind: PrivateActivityKind, vararg notes: NoteOut) {
        if (notes.isEmpty()) return
        runCatching { synchronized(store) { notes.forEach { n -> n.note?.let { store.state.activity.expect(it.rho, kind) } }; store.save() } }
    }

    /** Private activity rows, newest first (local: [PrivateActivity.rows]). */
    fun activity(): List<PrivateActivityRow> = synchronized(store) { PrivateActivity.rows(store.state) }

    /**
     * A tip far past the last verified sync height is either a
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
     * Only on roots verified by the last sync, in that sync's own
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
     * anchor long enough: CheckTx refuses one lapsing within
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
                idSecret = keys.idSecret(generation), dscKey = id.dscKey, country = id.country, activatedAt = id.activatedAt,
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
     * A renewal or refresh sent with no bound, by an identity
     * whose own bound has not passed, which the chain refused (in its ante,
     * before any fee): this identity holds nothing there.
     */
    class NotHeld(waitSeconds: Long) : NotYet(
        waitSeconds,
        "the chain says this identity holds nothing live here (nothing was charged): a lapsed one renews only as a new claim, " +
            "and this identity replaced another too recently to make one; that opens in ${waitSeconds / SECONDS_PER_DAY + 1} days",
    )

    /**
     * [handle] is in its renewal period, so renewing or
     * changing it is bounded like a claim, which this identity (it replaced
     * another too recently) cannot make yet. Nothing was sent.
     */
    class HandleNotLive(val handle: String, waitSeconds: Long) : NotYet(
        waitSeconds,
        "@$handle is past its expiry (in its renewal period): renewing or changing it now counts as a new claim, and this identity " +
            "replaced another too recently to make one; that opens in ${waitSeconds / SECONDS_PER_DAY + 1} days. Until its renewal period ends nobody else can take it",
    )

    /** The caretaker vote lapsed; casting again is a new vote, which this identity cannot make yet. Nothing was sent. */
    class CaretakerLapsed(waitSeconds: Long) : NotYet(
        waitSeconds,
        "your caretaker vote has lapsed: casting again counts as a new vote, and this identity replaced another too recently to make one; " +
            "that opens in ${waitSeconds / SECONDS_PER_DAY + 1} days",
    )

    /** MsgMoveHandle moves only a live handle. Nothing was sent. */
    class HandleNotMovable(val handle: String) : IllegalStateException(
        "@$handle is past its expiry (in its renewal period), and only a live handle can be moved: renew it first, then move it",
    )

    // ---- pool ---------------------------------------------------------------

    /** A private send of [amount] [denom] to [to]; the fee comes out of ERTH notes. */
    fun send(to: ShieldedAddress, denom: String, amount: Long, memo: ByteArray = ByteArray(0), counterparty: String = ""): TxResult {
        requireTransferable(denom)
        return run(Act(PrivateActivityKind.SEND, counterparty), assemble = sendAssembly(to, denom, amount, memo))
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
        // The chain refuses an unshield to any module account.
        PrivateMsgs.moduleAccountOf(network.erth.wallet.crypto.Bech32.decode(receiver))?.let {
            throw IllegalArgumentException("$receiver is the $it module account; it cannot receive an unshield")
        }
        require(!denom.startsWith(LP_PREFIX)) { "LP shares leave the pool only by a withdrawal" }
        require(!feeFromAmount || denom == FEE) { "only an ERTH unshield pays its fee from the amount" }
        // The memo (an exchange's deposit tag) is bound by the sighash.
        return run(Act(PrivateActivityKind.UNSHIELD, receiver), memo) { fee ->
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
    fun merge(denom: String): TxResult = run(Act(PrivateActivityKind.MERGE)) { fee ->
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
    fun shieldOutput(denom: String, amount: Long): NoteOut = mint(denom).also { expect(PrivateActivityKind.SHIELD, it) }

    private fun requireTransferable(denom: String) {
        require(!denom.startsWith(DERTH_PREFIX)) { "stake is owner-locked: it cannot be sent or unshielded" }
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
     * itself, to the address the handle resolves to.
     */
    class RegistrationPrep(
        val anml: NoteOut, val erth: NoteOut, val gas: NoteOut, val referrer: String, val binding: Fr, val idc: Fr,
        /** [idc]'s secret: the proof's id_secret witness, which outputs [idc]. In memory only, like the keys it comes from. */
        val idSecret: Fr,
        /** The identity generation registered ([idc] is its): this wallet's next unused one. */
        val generation: Int = 0,
    )

    /**
     * The identity this registration was prepared for has been registered
     * since (a registration of it landed meanwhile, or the chain refused
     * it as used, personhood 1130). The wallet moved on to its next
     * identity: prepare again. Nothing was sent.
     */
    class IdentityUsed : IllegalStateException(IDENTITY_USED)

    /**
     * Whether an identity of this wallet has been registered before (a
     * registration landed: its leaf, live or zeroed since, or a record a
     * restore found). The chain refuses an idc again (1130), so the next
     * registration uses the next generation ([nextGeneration]): a re-entry
     * needs no new recovery phrase.
     */
    fun registeredBefore(): Boolean = store.state.usedThrough() >= 0

    /** The identity generation the next registration (a first one, a re-entry, a fresh identity) proves with. */
    fun nextGeneration(): Int = synchronized(this) { store.state.nextGeneration() }

    /**
     * The chain refused [prep]'s identity as registered before (1130; the
     * wallet's records missed it): the next registration uses a later one.
     * Only on a structured refusal (CheckTx's code and codespace from an
     * https node, or the gas service's refusal kind), never on an error's
     * text. The floor stays below the highest recorded generation plus
     * [WalletSync.GENERATION_LOOKAHEAD], so a restore's record scan (which
     * looks that far past what it has found) always reaches the next
     * registration. Returns whether the wallet moved on.
     */
    @Synchronized
    fun identityRefused(prep: RegistrationPrep): Boolean {
        val s = store.state
        val cap = minOf(PrivacyKeys.MAX_GENERATION, s.usedThroughRecorded() + WalletSync.GENERATION_LOOKAHEAD)
        val floor = minOf(prep.generation + 1, cap)
        if (s.generationFloor < floor) {
            s.generationFloor = floor
            store.save()
        }
        return s.nextGeneration() > prep.generation
    }

    /**
     * A refusal that says, in text alone, that [prep]'s identity was
     * registered before: a simulate's message, an http node's, the gas
     * service's without its kind. Text proves nothing, so the floor stays;
     * a sync finds the record if one of this wallet's registrations used it.
     * Returns whether the wallet moved on.
     */
    fun identityRefusedUnconfirmed(prep: RegistrationPrep): Boolean {
        runCatching { sync() }
        return nextGeneration() > prep.generation
    }

    /** The refusal said the identity was used, but neither the chain's code nor this wallet's records confirm it. */
    class IdentityRefusalUnconfirmed : IllegalStateException(IDENTITY_REFUSAL_UNCONFIRMED)

    /** A referrer named by handle, resolved from the directory: the handle and the address it names now. */
    data class Referrer(val handle: String, val address: ShieldedAddress)

    fun prepareRegistration(referrer: Referrer?): RegistrationPrep {
        // A fresh identity of this wallet's: the chain registers each idc once.
        val gen = nextGeneration()
        val anml = mint("uanml")
        val erth = mint(FEE)
        val gas = mint(FEE).also { expect(PrivateActivityKind.GAS_GRANT, it) }
        referrer?.let {
            require(network.erth.wallet.privacy.handles.Handles.valid(it.handle)) { "${it.handle} is not a handle" }
            require(it.address.ownerPk != keys.ownerPk) { "a registration cannot name its own wallet as its referrer" }
        }
        val aff = if (referrer == null) Fr.ZERO else Privacy.affiliateField(referrer.handle)
        val idc = keys.idc(gen)
        val binding = Privacy.registrationBinding(chainId, idc, anml.pc, anml.ciphertext, erth.pc, erth.ciphertext, aff)
        return RegistrationPrep(anml, erth, gas, referrer?.handle.orEmpty(), binding, idc, keys.idSecret(gen), gen)
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
     * registration record note (PRIVACY_FORMATS.md §6: a value-0 note to
     * ourselves whose memo lets a wallet restored from the mnemonic find the
     * leaf), and records the registration as pending before anything else,
     * then tries to resolve it. [publicSignals] are the passport
     * proof's: [current_date, address, nullifier, dsc_key, idc].
     */
    fun register(prep: RegistrationPrep, proof: ByteArray, publicSignals: List<String>, signatureAlgorithm: String, dscDer: ByteArray): TxResult {
        require(publicSignals.size == REGISTER_SIGNALS) { "a passport proof has $REGISTER_SIGNALS public signals, not ${publicSignals.size}" }
        require(PrivateMsgs.decimalField(publicSignals[1]) == prep.binding) { "the passport proof is bound to other notes" }
        // The proof's idc output is the chain's check that the registrant holds
        // the identity's secret; one proven with another's would be refused.
        require(PrivateMsgs.decimalField(publicSignals[IDC_SIGNAL]) == prep.idc) { "the passport proof registers another identity" }
        require(prep.idc == keys.idc(prep.generation)) { "the registration names another wallet's identity" }
        // One of this wallet's that landed since it was prepared: the chain would refuse it (1130).
        if (prep.generation < nextGeneration()) throw IdentityUsed()
        require(PrivateMsgs.isCalendarDate(publicSignals[0])) { "the passport proof's current_date ${publicSignals[0]} is not a calendar date" }
        val base = registerMsg(prep, proof, publicSignals, signatureAlgorithm, dscDer)
        // Before any byte leaves: a registration that fails or is refused is
        // public and may still land while its current_date is in the chain's
        // skew, so this identity counts as possibly registered until then.
        val proofDate = PrivateMsgs.calendarDateUnix(publicSignals[0])!!
        val skew = keepSkew(runCatching { reads.personhoodParams().currentDateMaxSkewSeconds }.getOrNull())
        synchronized(this) {
            store.state.registrationKeepUntil = maxOf(store.state.registrationKeepUntil, Handles.satAdd(proofDate, skew))
            store.save()
        }
        val dscKey = PrivateMsgs.decimalField(publicSignals[3])
        val hint = dscCountry(dscDer)
        // The record's tag names the generation: a restore matches the leaf with its idc.
        val record = NoteOut.to(keys.address, FEE, 0, WalletSync.regMemo(keys.nk, dscKey, hint, now(), prep.generation))
        val pending = { hash: String, _: Long ->
            // By hash, the moment the node accepts it; the leaf comes later.
            store.state.pendingRegistration = PendingRegistration(
                txHash = hash, leafIndex = null, dscKey = dscKey, passportNullifier = publicSignals.getOrElse(2) { "" },
                publicSignals = publicSignals, activatedAt = null, countryHint = hint, generation = prep.generation,
            )
            store.save()
        }
        val refused = { hash: String ->
            if (store.state.pendingRegistration?.txHash == hash) { store.state.pendingRegistration = null; store.save() }
        }
        val result = try {
            run(Act(PrivateActivityKind.REGISTER, receives = listOf(prep.anml, prep.erth)), accepted = pending, rejected = refused) { fee ->
                Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee)))) { bs, _, _ -> base.toBuilder().setFee(bs[0]).build() }
            }
        } catch (e: Exception) {
            // Used before, by the chain's own set (1130, refused in the ante at
            // no cost): the next attempt proves with the next generation. The
            // chain's code, from an https node, moves the floor; text (a
            // simulate's message) only prompts a sync for the record.
            // Judged by the node that answered, not the one in use now.
            val refusal = identityRejection(e)
            if (refusal != null && chainCodesTrusted(refusal.lcd)) {
                throw if (identityRefused(prep)) IdentityUsed() else IllegalStateException(IDENTITY_SKIPS_EXHAUSTED, e)
            } else if (identityRefusalText(e)) {
                throw if (identityRefusedUnconfirmed(prep)) IdentityUsed() else IdentityRefusalUnconfirmed()
            }
            throw e
        }
        recordRegistration(result)
        // A switch: the move suggestion is drawn now, so its reminder comes
        // even if Identity is never opened.
        if (result.attr("register", "switched") == "true") {
            noteOutcome(result.hash) { it.update(result.hash) { t -> t.copy(kind = PrivateActivityKind.SWITCH) } }
            result.attr("register", "leaf_index")?.toLongOrNull()?.let { leaf -> synchronized(this) { drawMoveSuggestion(leaf, result.time, prep.generation) } }
        }
        return result
    }

    /**
     * Fills the pending registration from its committed tx — the
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
        val r = run(Act(PrivateActivityKind.CLAIM_ANML, receives = listOf(anml))) { fee ->
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
        // Checked throughout: an activated_at no block can
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
     * these lease lengths, never from Params.
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
     * known lapsed ([held] false; a handle in its renewal
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
     * has not reached is not held: refreshing it is a new split, bounded),
     * null when it holds none or knows only an estimate.
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

    /** Whether this wallet's identity (generation [g]) holds a caretaker split the chain still counts (as far as it knows). */
    fun caretakerLive(g: Int = generation): Boolean = caretakerLive(store.state.peekSlot(g))

    private fun caretakerLive(t: IdentitySlot): Boolean = holdsSplit(t) && caretakerExpiresAt(t) > now()

    private fun holdsSplit(t: IdentitySlot = store.state.current): Boolean = t.caretakerSplit.isNotEmpty() || t.caretakerSplitUnknown

    /** When the split of identity generation [g] lapses: the chain's expires_at, or its cast time + R. 0 for none. */
    fun caretakerExpiresAt(g: Int = generation): Long = caretakerExpiresAt(store.state.peekSlot(g))

    private fun caretakerExpiresAt(t: IdentitySlot): Long {
        if (!holdsSplit(t)) return 0
        if (t.caretakerExpiresAt > 0) return t.caretakerExpiresAt
        return runCatching { Math.addExact(t.caretakerCastAt, reads.personhoodParams().caretakerVoteSeconds) }.getOrDefault(0)
    }

    /**
     * A value-0 state record note (PRIVACY_FORMATS.md §6) to [to]'s own
     * address, tagged with its nk for identity [generation].
     */
    private fun stateRecord(to: PrivacyKeys, generation: Int, memo: (nk: Fr, generation: Int) -> ByteArray): NoteOut =
        NoteOut.to(to.address, FEE, 0, memo(to.nk, generation))

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
        // Validated before anything is sent: nothing after the broadcast can throw on it.
        // r0: the lease a cast gets now (Params), for the record's expiry estimate. The
        // bound: LeaseBounds' caretaker lease, which a held longer one keeps after a cut.
        val r0 = leaseParam(reads.personhoodParams().caretakerVoteSeconds, "caretaker lease")
        val (maxPred, wait) = if (split.isEmpty()) Privacy.NO_BOUND to null else {
            val lb = leaseBounds()
            leaseStatement(predecessorBound(lb, lb.caretakerLeaseSeconds), caretakerHeldLive(lb.blockTime)) { CaretakerLapsed(it) }
        }
        val m = membership(Privacy.caretakerScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, maxPred)
        val weights = weights(split)
        // The state record: what a wallet restored from the mnemonic finds. Its
        // expiry is the wallet's estimate; the chain's own (from the result) replaces it here.
        val record = stateRecord(keys, generation) { nk, g ->
            if (split.isEmpty()) WalletSync.caretakerMemo(nk, WalletSync.RECORD_NONE, generation = g)
            else WalletSync.caretakerMemo(nk, WalletSync.RECORD_HOLDS, Handles.satAdd(now(), r0), split, g)
        }
        val r = boundAttempt(wait) {
            run(Act(PrivateActivityKind.CARETAKER)) { fee ->
                Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee))), membership = m) { bs, _, mem ->
                    MsgSetCaretaker.newBuilder().setFee(bs[0]).setMembership(mem).addAllPercentages(weights).setMaxPredecessor(maxPred).build()
                }
            }
        }
        // The node's expires_at only within the lease range; else the block time + R, saturating.
        val exp = r.attr("set_caretaker", "expires_at")?.toLongOrNull()?.takeIf { it > 0 && it <= Handles.satAdd(now(), Handles.MAX_AHEAD_SECONDS) }
        synchronized(this) {
            store.state.caretakerCastAt = now(); store.state.caretakerSplit = split; store.state.caretakerSplitUnknown = false
            store.state.caretakerExpiresAt = if (split.isEmpty()) 0 else exp ?: Handles.satAdd(r.time.takeIf { it > 0 } ?: now(), r0)
            store.save()
        }
        return r
    }

    /**
     * The identity that succeeded this one under the same passport, on this
     * phone: another wallet's keys (derived from its recovery phrase) and its
     * registration as its own store records it. A move proves knowledge of
     * both identity secrets, so a wallet whose phrase is lost can move nothing.
     */
    data class Successor(val keys: PrivacyKeys, val identity: IdentityRecord, val generation: Int = 0) {
        val idc: Fr get() = keys.idc(generation)
    }

    /** A move cannot be made to that identity (nothing was sent). */
    class MoveNotPossible(message: String) : IllegalStateException(message)

    /**
     * Where the succession leaf H(TAG_SUCC, [idcOld], [idcNew]) sits in the
     * local identity tree, or null. The chain appends it right after the new
     * identity's leaf ([near]), in the same tx; the rest of the tree is
     * searched only if it is not there (and not at all with [nearOnly]). Local: nothing asked names it.
     */
    // Under the wallet's lock, which a sync holds while it appends to the tree.
    fun successionIndex(idcOld: Fr, idcNew: Fr, near: Long, nearOnly: Boolean = false): Long? = synchronized(this) {
        val want = Privacy.successionLeaf(idcOld, idcNew)
        val tree = store.identityTree
        if (near in 0 until tree.size && tree.leaf(near) == want) return near
        if (nearOnly) return null
        var i = tree.size - 1
        while (i >= 0) { if (tree.leaf(i) == want) return i; i-- }
        null
    }

    /**
     * The move proof's statement in [scope] from this identity to [to]: the
     * succession leaf (this identity, [to]) and [to]'s live leaf, both under
     * the local tree's root (verified at the last sync, so one the chain
     * recorded; a move goes out within the root window of it).
     */
    private fun moveStatement(scope: Fr, from: Int, to: Successor): MoveWitnessSpec {
        val id = to.identity
        val newIdc = to.idc
        val oldIdc = keys.idc(from)
        require(newIdc != oldIdc) { "a move goes to another identity" }
        val leaf = Privacy.identityLeaf(newIdc, id.dscKey, id.country, id.activatedAt, id.predecessorAt)
        val tree = store.identityTree
        if (id.leafIndex >= tree.size) throw SyncFirst("this wallet has not synced the new identity's registration yet: sync, then try again")
        when (tree.leaf(id.leafIndex)) {
            leaf -> {}
            Fr.ZERO -> throw MoveNotPossible("the new identity is no longer the passport's live one (it switched again or lapsed): a move goes only to the live successor")
            else -> throw MoveNotPossible("the new wallet's registration record does not match the identity tree; sync it, then try again")
        }
        val si = successionIndex(oldIdc, newIdc, id.leafIndex + 1)
            ?: throw MoveNotPossible("that identity did not directly succeed this one under this passport: a move goes only to the identity the passport registered next")
        val root = tree.root()
        val sp = tree.path(si)
        val lp = tree.path(id.leafIndex)
        val oldSecret = keys.idSecret(from)
        val newSecret = to.keys.idSecret(to.generation)
        return MoveWitnessSpec { signal ->
            MoveWitness(
                oldSecret = oldSecret, newSecret = newSecret, successionIndex = si, successionSiblings = sp,
                dscKey = id.dscKey, country = id.country, activatedAt = id.activatedAt, predecessorAt = id.predecessorAt,
                leafIndex = id.leafIndex, siblings = lp, root = root, scope = scope, signal = signal,
            )
        }
    }

    /**
     * Hands the live caretaker split (and its expiry) to [to], the identity
     * that succeeded this one under the same passport, once its registration
     * has landed and while it is still the passport's live one: how a switch
     * keeps its vote. This wallet pays the fee. This identity may never cast
     * one again (ErrCaretakerMovedOut, 1126). The whole move holds the
     * wallet's lock, as iOS's does: no sync appends to the identity tree
     * while its statement is read from it.
     */
    @Synchronized
    fun moveCaretaker(to: Successor, recorder: MoveRecorder? = null): TxResult = moveCaretakerFrom(generation, to, recorder)

    /**
     * Moves the caretaker split of this wallet's earlier identity [from]
     * (a lapse, then a re-entry; or a fresh identity) to the one it acts as
     * now: one phrase holds both secrets. Paid from this wallet's ERTH.
     */
    @Synchronized
    fun moveCaretakerWithin(from: Int): TxResult = moveCaretakerFrom(from, selfSuccessor(from), withinRecorder())

    private fun moveCaretakerFrom(from: Int, to: Successor, recorder: MoveRecorder?): TxResult {
        val t = store.state.slot(from)
        check(caretakerLive(t)) { "this identity holds no live caretaker vote to move" }
        checkNoMove(PendingMove.CARETAKER, t)
        val move = PendingMove(PendingMove.CARETAKER, "", 0, incoming = false, split = t.caretakerSplit,
            splitUnknown = t.caretakerSplitUnknown, expiresAt = caretakerExpiresAt(t), target = recorder?.targetId.orEmpty())
        // State records: moved out for this identity, held (split, expiry) for the new one.
        val outs = listOf(
            stateRecord(keys, from) { nk, g -> WalletSync.caretakerMemo(nk, WalletSync.RECORD_MOVED_OUT, generation = g) },
            stateRecord(to.keys, to.generation) { nk, g -> WalletSync.caretakerMemo(nk, WalletSync.RECORD_HOLDS, move.expiresAt, if (move.splitUnknown) emptyMap() else move.split, g) },
        )
        val mv = moveStatement(Privacy.caretakerScope(), from, to)
        val r = moveRun(move, recorder, t) { fee ->
            Assembled(listOf(bundle(outs, mapOf(FEE to fee))), move = mv) { bs, _, _ ->
                MsgMoveCaretaker.newBuilder().setFee(bs[0]).build()
            }
        }
        synchronized(this) { confirmMove(r.hash) }
        return r
    }

    /**
     * Writes a move into the new identity's wallet: before the
     * broadcast, as pending, so neither a lost answer nor a killed app can
     * strand what moved; undone only on a definite refusal. [targetId] is
     * that wallet's store id.
     */
    interface MoveRecorder {
        val targetId: String
        fun record(move: PendingMove)
        fun rollback(move: PendingMove)
        /** Why the target cannot take [move] ([targetRefusal]), or null. */
        fun refusal(move: PendingMove): String? = null
    }

    private fun checkNoMove(kind: String, t: IdentitySlot = store.state.current) =
        check(t.pendingMoves.none { !it.incoming && it.kind == kind && !it.confirmed }) { "a move of this identity's $kind is waiting for the chain" }

    /** This wallet's identity as the successor of its earlier identity [from]: verified, live, and a later generation. */
    private fun selfSuccessor(from: Int): Successor {
        val g = generation
        require(from in 0 until g) { "a move within this wallet goes from an earlier identity to the one it acts as" }
        val id = store.state.identity?.takeIf { it.verified } ?: throw IllegalStateException("this wallet's registration has not been verified yet; sync, then try again")
        return Successor(keys, id, g)
    }

    /**
     * Writes a move within this wallet into the identity it acts as (its
     * slot in the same store), like another wallet's [MoveRecorder].
     */
    private fun withinRecorder(): MoveRecorder {
        val g = generation
        return object : MoveRecorder {
            override val targetId = "$WITHIN_TARGET$g"
            override fun record(move: PendingMove) = recordIncoming(store, move, now(), g)
            override fun rollback(move: PendingMove) = rollbackIncoming(store, move, now(), g)
            override fun refusal(move: PendingMove): String? = synchronized(store) { targetRefusal(store.state.peekSlot(g), move.kind, now()) }
        }
    }

    /**
     * Runs a move: recorded here (outgoing) and in the target (incoming)
     * before the broadcast; a refusal undoes both; a confirmed tx is applied
     * by the caller ([confirmMove]); anything else (a wait that timed out, a
     * tx that may yet land or fail) stays pending for [resolvePendingMoves].
     */
    private fun moveRun(move: PendingMove, recorder: MoveRecorder?, t: IdentitySlot, assemble: (fee: Long) -> Assembled): TxResult {
        recorder?.let { rc ->
            // The target a confirmed move went to, or one a move
            // still in flight names; a refused, failed or expired move frees it.
            val fixed = switchTargetNow(t)
            check(fixed.isEmpty() || fixed == rc.targetId) { "this identity's moves already went to another wallet's identity" }
            rc.refusal(move)?.let { throw IllegalStateException(it) }
        }
        return run(
            Act(PrivateActivityKind.MOVE, if (move.kind == PendingMove.HANDLE) "@${move.handle}" else "caretaker vote"),
            accepted = { hash, timeout ->
                val p = move.copy(txHash = hash, timeoutHeight = timeout)
                val ok = recorder?.let { rc -> runCatching { rc.record(p.copy(incoming = true, target = "", recorded = true)) }.isSuccess } ?: true
                t.pendingMoves.add(p.copy(recorded = ok))
                store.save()
            },
            rejected = { hash ->
                t.pendingMoves.removeAll { it.txHash == hash && !it.incoming }
                clearSwitchTargetIfUnmoved(t)
                store.save()
                recorder?.let { rc -> runCatching { rc.rollback(move.copy(txHash = hash, incoming = true)) } }
            },
            assemble = assemble,
        )
    }

    /**
     * The wallet this identity's moves must go to: the one a
     * confirmed move went to, else the one a move still in flight names
     * (empty: any).
     */
    private fun switchTargetNow(s: IdentitySlot): String =
        s.switchTarget.ifEmpty { s.pendingMoves.firstOrNull { !it.incoming && !it.confirmed && it.target.isNotEmpty() }?.target.orEmpty() }

    /**
     * Frees the switch target when nothing moved: no handle or
     * split moved out and no move of this identity confirmed or in flight.
     * Returns whether it changed.
     */
    private fun clearSwitchTargetIfUnmoved(s: IdentitySlot): Boolean {
        if (s.switchTarget.isEmpty() || s.handleMovedOut || s.caretakerMovedOut || s.pendingMoves.any { !it.incoming }) return false
        s.switchTarget = ""
        return true
    }

    /**
     * The move [hash] is in a block and succeeded: the identity that moved
     * no longer holds what it moved (every slot: a move within this wallet
     * is in two of them, outgoing and incoming).
     */
    private fun confirmMove(hash: String) {
        for (s in store.state.slots.values) {
            val i = s.pendingMoves.indexOfFirst { it.txHash == hash && !it.confirmed }
            if (i < 0) continue
            val p = s.pendingMoves[i]
            if (!p.incoming) {
                if (p.kind == PendingMove.HANDLE) { s.handle = ""; s.handleMovedOut = true; s.handleSetAt = now() }
                else { s.caretakerSplit = emptyMap(); s.caretakerSplitUnknown = false; s.caretakerExpiresAt = 0; s.caretakerMovedOut = true }
                // The target is fixed only by a confirmed move.
                if (s.switchTarget.isEmpty() && p.target.isNotEmpty()) s.switchTarget = p.target
            }
            if (p.incoming || p.recorded) s.pendingMoves.removeAt(i) else s.pendingMoves[i] = p.copy(confirmed = true)
        }
        store.save()
    }

    /** The move [p] of slot [s] is definitely not in the chain (refused, failed in its block, or gone past its timeout_height). */
    private fun dropMove(p: PendingMove, s: IdentitySlot) {
        s.pendingMoves.removeAll { it.txHash == p.txHash && it.incoming == p.incoming }
        if (p.incoming) undoIncoming(s, p, now()) else clearSwitchTargetIfUnmoved(s)
        store.save()
    }

    /**
     * Settles every move in flight by its tx: committed, it is
     * applied; failed in its block, or unknown to the chain past its
     * timeout_height, it is undone (and its state records void). A move the
     * chain cannot say anything about yet stays. Returns whether any is
     * still unconfirmed.
     */
    @Synchronized
    fun resolvePendingMoves(): Boolean {
        val st = store.state
        for (s in st.slots.values.toList()) {
            // A switch target fixed by a move that never landed is freed.
            if (clearSwitchTargetIfUnmoved(s)) store.save()
            for (p in s.pendingMoves.toList()) {
                if (p.confirmed || s.pendingMoves.none { it == p }) continue
                val r = runCatching { chain.tx(p.txHash) }.getOrNull()
                r?.let { outcome(it) }
                when {
                    r != null && r.code == 0 -> confirmMove(p.txHash)
                    r != null -> { st.voidRecordHeights.add(r.height); dropMove(p, s) }
                    // A timeout no sane tip gives is settled by the tx's status alone.
                    !PrivateTxEngine.timeoutSane(p.timeoutHeight, st.verifiedHeight) -> {
                        val ts = runCatching { roots.txStatus(p.txHash) }.getOrNull()
                        if (ts == network.erth.wallet.privacy.sync.TxStatus.MISSING) dropMove(p, s)
                    }
                    else -> {
                        val tip = runCatching { chain.tipHeight() }.getOrNull() ?: continue
                        if (tip > p.timeoutHeight) dropMove(p, s)
                    }
                }
            }
        }
        return st.slots.values.any { s -> s.pendingMoves.any { !it.confirmed } }
    }

    /**
     * Moves away from identity generation [g] (default: the one this wallet
     * acts as) that the chain has not confirmed yet, and confirmed ones not
     * yet recorded in their target.
     */
    fun outgoingMoves(g: Int = generation): List<PendingMove> = store.state.peekSlot(g).pendingMoves.filter { !it.incoming }

    /**
     * When the wallet suggests bringing the predecessor's handle and
     * caretaker split to this identity: a delay drawn once per registration,
     * uniformly from [MOVE_DELAY_MIN_SECONDS] to [MOVE_DELAY_MAX_SECONDS],
     * after the registration's block time. A move that lands right after a
     * switch links the handle, its owner_pk and the split to the passport's
     * public registration by timing (ORCHARD_DESIGN 6.6). But the move has
     * a deadline: [deadline], the earliest lease end of what the
     * predecessor holds (0: none known). Past it the old identity can
     * neither move nor renew it, and this one cannot claim a handle or cast
     * a vote for up to a year, so the suggestion is never later than
     * [MOVE_DEADLINE_MARGIN_SECONDS] before it (now, when there is not that
     * long left). The deadline is kept for the reminder. Only a suggestion:
     * the user chooses, and nothing is sent unasked. 0 without a verified
     * registration.
     */
    @Synchronized
    fun suggestedMoveAt(deadline: Long = store.state.moveDeadline): Long {
        val id = store.state.identity?.takeIf { it.verified } ?: return 0
        val s = store.state
        val drawn = if (s.moveSuggestedLeaf == id.leafIndex && s.moveSuggestedAt > 0) s.moveSuggestedAt else drawMoveSuggestion(id.leafIndex, id.activatedAt)
        if (s.moveDeadline != deadline) { s.moveDeadline = maxOf(0L, deadline); store.save() }
        return cappedMoveAt(drawn, deadline)
    }

    private fun drawMoveSuggestion(leaf: Long, activatedAt: Long, g: Int = generation): Long {
        val span = (MOVE_DELAY_MAX_SECONDS - MOVE_DELAY_MIN_SECONDS).toInt()
        val at = Handles.satAdd(activatedAt, MOVE_DELAY_MIN_SECONDS + java.security.SecureRandom().nextInt(span + 1))
        // The registered generation's: the wallet may not act as it yet (its leaf not matched).
        val t = store.state.slot(g)
        t.moveSuggestedAt = at
        t.moveSuggestedLeaf = leaf
        store.save()
        return at
    }

    /**
     * Nothing is left for a move to bring here (the predecessor holds
     * nothing, or everything moved): the suggestion and its reminder end.
     */
    @Synchronized
    fun clearMoveSuggestion() {
        val s = store.state
        if (s.moveSuggestedAt <= 0 && s.moveDeadline == 0L) return
        if (s.moveSuggestedAt > 0) s.moveSuggestedAt = -1
        s.moveDeadline = 0
        store.save()
    }

    /** The suggested move time (capped at the deadline) once it has come, for the reminder (0: none due). */
    fun moveSuggestionDue(): Long {
        val s = store.state
        val at = s.moveSuggestedAt.takeIf { it > 0 } ?: return 0
        return cappedMoveAt(at, s.moveDeadline).coerceAtLeast(1).takeIf { it <= now() } ?: 0
    }

    /** The move's deadline as last read (Identity, the switch screen, or the reminder's refresh; 0: none known). */
    fun moveDeadline(): Long = store.state.moveDeadline

    /** Marks a confirmed move recorded in its target (a retried [MoveRecorder.record] succeeded). */
    @Synchronized
    fun markRecorded(hash: String) {
        val s = store.state.slots.values.firstOrNull { t -> t.pendingMoves.any { it.txHash == hash && !it.incoming } } ?: return
        val i = s.pendingMoves.indexOfFirst { it.txHash == hash && !it.incoming }
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
    fun bindHandle(handle: String, address: ShieldedAddress = keys.address, renewOnly: Boolean = false): TxResult {
        require(Handles.valid(handle)) { "\"$handle\" is not a handle: 3-32 of a-z, 0-9 and -, no dash at either end" }
        // A renewal binds only the handle held (or, holding none, one this
        // identity may hold): a bind of another would be a change, freeing the held one.
        if (renewOnly) check(store.state.handle.isEmpty() || store.state.handle == handle) {
            "this identity holds @${store.state.handle}; renewing @$handle would change to it and free @${store.state.handle}"
        }
        val holds = store.state.handle.isNotEmpty()
        check(holds || !store.state.handleMovedOut) { "this identity moved its handle to another; it cannot claim one again" }
        checkNoMove(PendingMove.HANDLE)
        // Only a live handle renews or changes unbounded; one in its
        // renewal period is bounded like a claim, by the longest lease ever in force.
        val lb = leaseBounds()
        // The lease this bind gets (Params), validated before anything is sent.
        val leaseNow = leaseParam(reads.personhoodParams().handleLeaseSeconds, "handle lease")
        val held = if (!holds) null else handleExpiresAt().takeIf { it > 0 }?.let { it > lb.blockTime }
        val (maxPred, wait) = leaseStatement(predecessorBound(lb, lb.handleLeaseSeconds), held) { HandleNotLive(store.state.handle, it) }
        val addr = address.encode()
        val m = membership(Privacy.handleScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, maxPred)
        val record = stateRecord(keys, generation) { nk, g -> WalletSync.handleMemo(nk, WalletSync.RECORD_HOLDS, handle, g) }
        val r = boundAttempt(wait) {
            run(Act(PrivateActivityKind.HANDLE, "@$handle")) { fee ->
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
    fun handleExpiresAt(g: Int = generation): Long = handleExpiresAt(store.state.peekSlot(g))

    private fun handleExpiresAt(s: IdentitySlot): Long = if (s.handle.isNotEmpty() && s.handleExpiresFor == s.handle) s.handleExpiresAt else 0

    /** Releases this identity's handle at once (anyone may claim it). */
    fun releaseHandle(): TxResult {
        // The chain refuses a release by a holder of none only after taking the fee.
        check(store.state.handle.isNotEmpty()) { "this identity holds no handle to release" }
        checkNoMove(PendingMove.HANDLE)
        val m = membership(Privacy.handleScope(), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, Privacy.NO_BOUND)
        val record = stateRecord(keys, generation) { nk, g -> WalletSync.handleMemo(nk, WalletSync.RECORD_NONE, generation = g) }
        val r = run(Act(PrivateActivityKind.HANDLE, "released @${store.state.handle}")) { fee ->
            Assembled(listOf(bundle(listOf(record), mapOf(FEE to fee))), membership = m) { bs, _, mem ->
                MsgBindHandle.newBuilder().setFee(bs[0]).setMembership(mem).setMaxPredecessor(Privacy.NO_BOUND).build()
            }
        }
        synchronized(this) { store.state.handle = ""; store.state.handleSetAt = now(); store.save() }
        return r
    }

    /**
     * Hands this identity's handle (lease unchanged) to [to], the identity
     * that succeeded it under the same passport (see [moveCaretaker]). This
     * identity may never claim one again (ErrHandleMovedOut, 1125). Holds
     * the wallet's lock throughout, like [moveCaretaker].
     */
    @Synchronized
    fun moveHandle(to: Successor, recorder: MoveRecorder? = null, expected: String? = null): TxResult =
        moveHandleFrom(generation, to, recorder, expected)

    /** Moves the handle of this wallet's earlier identity [from] to the one it acts as now (see [moveCaretakerWithin]). */
    @Synchronized
    fun moveHandleWithin(from: Int, expected: String? = null): TxResult = moveHandleFrom(from, selfSuccessor(from), withinRecorder(), expected)

    private fun moveHandleFrom(from: Int, to: Successor, recorder: MoveRecorder?, expected: String?): TxResult {
        val t = store.state.slot(from)
        val handle = t.handle
        check(handle.isNotEmpty()) { "this identity holds no handle to move" }
        // The handle the confirm sheet named: refused if this identity's is no longer that one.
        if (expected != null) check(handle == expected) { "this identity's handle is no longer @$expected; nothing was sent" }
        checkNoMove(PendingMove.HANDLE, t)
        // MsgMoveHandle refuses a handle that is not live (its renewal period).
        handleExpiresAt(t).takeIf { it > 0 }?.let { if (it <= chainNow()) throw HandleNotMovable(handle) }
        val move = PendingMove(PendingMove.HANDLE, "", 0, incoming = false, handle = handle, target = recorder?.targetId.orEmpty())
        // State records: moved out for this identity, held for the new one.
        val outs = listOf(
            stateRecord(keys, from) { nk, g -> WalletSync.handleMemo(nk, WalletSync.RECORD_MOVED_OUT, generation = g) },
            stateRecord(to.keys, to.generation) { nk, g -> WalletSync.handleMemo(nk, WalletSync.RECORD_HOLDS, handle, g) },
        )
        val mv = moveStatement(Privacy.handleScope(), from, to)
        val r = moveRun(move, recorder, t) { fee ->
            Assembled(listOf(bundle(outs, mapOf(FEE to fee))), move = mv) { bs, _, _ ->
                MsgMoveHandle.newBuilder().setFee(bs[0]).setHandle(handle).build()
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
     * Squares the store's handle with the
     * chain's directory [dir], read at [readAt] (wallet clock): a handle the
     * chain swept (absent or free), or one whose entry names another owner,
     * is dropped; with none held, the one entry whose owner is this
     * identity's handle-scope nullifier is taken as held (a restore lost
     * it). An entry merely naming this wallet's address is never adopted:
     * anyone may bind a handle to any address. Nothing changes while a move
     * is in flight or when the directory predates the store's last change.
     * Returns, while no handle is held, the non-free entries naming this
     * wallet's address whose owner is not someone else (unverified: a
     * directory without owners), for the cards and reminders.
     */
    @Synchronized
    fun reconcileHandle(dir: Map<String, HandleEntry>, readAt: Long): List<HandleEntry> {
        val s = store.state
        val t = now()
        val own = keys.address.encode()
        val mine = handleOwner()
        val addressed = dir.values.filter { it.address == own && it.statusAt(t) != HandleEntry.FREE && (it.owner.isEmpty() || it.owner == mine) }
        val owned = dir.values.filter { it.owner == mine && it.statusAt(t) != HandleEntry.FREE }
        val moving = s.pendingMoves.any { it.kind == PendingMove.HANDLE && !it.confirmed }
        if (!moving && readAt > s.handleSetAt) {
            if (s.handle.isNotEmpty()) {
                val e = dir[s.handle]
                if (e == null || e.statusAt(t) == HandleEntry.FREE || (e.owner.isNotEmpty() && e.owner != mine)) { s.handle = ""; s.handleSetAt = t; store.save() }
            }
            if (s.handle.isEmpty() && !s.handleMovedOut && owned.size == 1) {
                s.handle = owned[0].handle; s.handleSetAt = t; store.save()
            }
            // The chain's expiry of the handle held: whether it is live.
            dir[s.handle]?.takeIf { s.handle.isNotEmpty() && it.statusAt(t) != HandleEntry.FREE }?.let { e ->
                if (s.handleExpiresFor != s.handle || s.handleExpiresAt != e.expiresAt) { s.handleExpiresFor = s.handle; s.handleExpiresAt = e.expiresAt; store.save() }
            }
        }
        // While a handle is held, no other entry is offered (a bind of it would change, freeing the held one).
        return if (s.handle.isNotEmpty()) emptyList() else addressed
    }

    /** This identity's handle-scope nullifier as a directory entry's owner (64 lowercase hex). */
    fun handleOwner(): String = Privacy.scopeNullifier(keys.idSecret(generation), Privacy.handleScope()).toHex().lowercase(java.util.Locale.ROOT)

    // ---- assembly -----------------------------------------------------------

    /** A human vote on an x/gov proposal. The scope is recomputed here rather than taken from the node. */
    fun voteProposal(proposalId: Long, yes: Boolean): TxResult {
        val b = reads.ballotInputs(proposalId = proposalId)
        check(b.scope == Privacy.proposalScope(proposalId, b.round)) { "the node's ballot scope is not this proposal's" }
        // The chain's statement: max_activation no bound, max_predecessor the ballot's (opened - 86400).
        val m = membership(b.scope, b.excludedDsc, b.excludedCountry, b.maxActivation, b.maxPredecessor)
        val option = if (yes) VoteOption.VOTE_OPTION_YES else VoteOption.VOTE_OPTION_NO
        return run(Act(PrivateActivityKind.VOTE, "proposal $proposalId")) { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgVoteProposal.newBuilder().setFee(bs[0]).setMembership(mem).setProposalId(proposalId).setOption(option).build()
            }
        }
    }

    fun proposeRemoval(optionId: Long): TxResult {
        // The chain's day: its scope is the including block's UTC day.
        val day = chainNow() / SECONDS_PER_DAY
        // The predecessor bound: the start of today (UTC) less a day, whatever the root window; no activation bound.
        val m = membership(Privacy.proposeRemovalScope(optionId, day), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, day * SECONDS_PER_DAY - ACTIVATION_MARGIN)
        return run(Act(PrivateActivityKind.VOTE, "removal of option $optionId")) { fee ->
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
        return run(Act(PrivateActivityKind.VOTE, "removal of option $optionId")) { fee ->
            Assembled(listOf(feeBundle(fee)), membership = m) { bs, _, mem ->
                MsgVoteRemoval.newBuilder().setFee(bs[0]).setMembership(mem).setOptionId(optionId).setOption(option).build()
            }
        }
    }

    // ---- private staking (owner-locked stake notes, one per validator) ------

    /**
     * The stake tree's latest root: every stake proof's anchor, the empty
     * tree's root before the first note (the chain records it at its first
     * block, and a first delegation pads its input against it).
     */
    private fun stakeAnchor(): Fr = store.stakeTree.root()

    /** Stake notes of [denom] this wallet can spend now. */
    fun spendableStake(denom: String): List<OwnedStakeNote> = store.state.stakeNotes.filter { it.spendable && it.denom == denom }

    /**
     * The chain's slash debt now (Query/DebtTree, ORCHARD_DESIGN 8.7): its
     * root and size, the label window and the clear_before a proof may name.
     * The rows ([tree]) are read only when a label is cleared or voted, and
     * then whole (the indexer's stream, else the chain's pages), checked
     * against [root]: nothing asked names a move of this wallet.
     */
    inner class DebtView internal constructor(val root: Fr, val size: Long, val windowSeconds: Long, val clearBefore: Long) {
        val tree: DebtTree by lazy { debtTreeAt(root, size) }

        /** Whether [l]'s window has closed: a proof naming [clearBefore] clears it. */
        fun clearable(l: StakeLabel): Boolean = java.lang.Long.compareUnsigned(l.moveTime, clearBefore) < 0

        /** What [l]'s exposure is worth now: its row's retained, or all of it when the move was never slashed. */
        fun retained(l: StakeLabel): Long = tree.retainedOf(l.moveKey)?.coerceIn(0, l.exposed) ?: l.exposed
    }

    /** Reads the chain's debt view (one page of Query/DebtTree: its root, window and clear_before). */
    fun debtView(): DebtView {
        val p = reads.debtTree(0, 1)
        require(p.size in 0 until Merkle.CAPACITY) { "debt tree size ${p.size}" }
        require(p.windowSeconds in 0..PrivacyQueries.MAX_DURATION_S) { "label window ${p.windowSeconds}" }
        if (p.windowSeconds > 0 && store.state.labelWindowSeconds != p.windowSeconds) synchronized(store) {
            store.state.labelWindowSeconds = p.windowSeconds
            store.save()
        }
        return DebtView(p.root, p.size, p.windowSeconds, p.clearBefore)
    }

    private val debtTrees = LinkedHashMap<Fr, DebtTree>()

    /**
     * The debt tree whose root is [root] ([size] leaves, sentinel included):
     * the indexer's whole stream first, the chain's own pages when it does
     * not rebuild [root] (an indexer behind, or lying).
     */
    private fun debtTreeAt(root: Fr, size: Long): DebtTree {
        checkCaches()
        return synchronized(debtTrees) { debtTreeLocked(root, size) }
    }

    private fun debtTreeLocked(root: Fr, size: Long): DebtTree {
        debtTrees[root]?.let { return it }
        val n = maxOf(0L, size - 1)
        require(n < Int.MAX_VALUE) { "debt tree size $size" }
        val sources = listOf<() -> List<Pair<Fr, Long>>>(
            { indexerDebtRows(n.toInt()) },
            { lcdDebtRows(n.toInt()) },
        )
        for (read in sources) {
            val rows = runCatching(read).getOrNull() ?: continue
            val t = runCatching { DebtTree.build(rows) }.getOrNull() ?: continue
            if (t.root() == root) {
                debtTrees[root] = t
                while (debtTrees.size > 2) debtTrees.remove(debtTrees.keys.first())
                return t
            }
        }
        throw IllegalStateException("the slash debt rows served do not rebuild the chain's debt root; sync and try again")
    }

    /** The first [n] debt rows from the indexer's stream (aligned pages from leaf 0; the sentinel is never a row). */
    private fun indexerDebtRows(n: Int): List<Pair<Fr, Long>> {
        val out = ArrayList<Pair<Fr, Long>>(n)
        var from = 0L
        while (out.size < n) {
            val page = indexer.debtRows(from, DEBT_PAGE)
            if (page.rows.size > DEBT_PAGE) throw WalletSync.Inconsistent("the indexer sent ${page.rows.size} debt rows in a page of $DEBT_PAGE")
            for ((index, key, retained) in page.rows) {
                if (index != out.size + 1L) throw WalletSync.Inconsistent("debt row $index out of order")
                if (out.size >= n) break
                out.add(key to retained)
            }
            if (page.rows.isEmpty()) break
            from += DEBT_PAGE
        }
        check(out.size == n) { "only ${out.size} of the chain's $n debt rows were served" }
        return out
    }

    /** The first [n] debt rows from the chain's Query/DebtTree pages. */
    private fun lcdDebtRows(n: Int): List<Pair<Fr, Long>> {
        val out = ArrayList<Pair<Fr, Long>>(n)
        while (out.size < n) {
            val page = reads.debtTree(out.size.toLong(), minOf(n - out.size, LCD_DEBT_PAGE))
            if (page.rows.isEmpty()) break
            out.addAll(page.rows.take(minOf(LCD_DEBT_PAGE, n - out.size)))
        }
        check(out.size == n) { "only ${out.size} of the chain's $n debt rows were served" }
        return out
    }

    /** The label window as last read (0: never): for showing when moved stake may move again. */
    val labelWindowSeconds: Long get() = store.state.labelWindowSeconds

    /** When [l]'s exposure may leave its note (unix seconds): after move_time + the label window. */
    fun movableAfter(l: StakeLabel, windowSeconds: Long = labelWindowSeconds): Long = Handles.satAdd(l.moveTime, windowSeconds)

    /**
     * What [n] may give up now (ORCHARD_DESIGN 8.7): its amount; for a
     * labelled note with its window open, the amount less the exposure (the
     * exposure stays in place); with the window closed, less the exposure
     * plus what it retains (the proof clears it).
     */
    private fun freeOf(n: OwnedStakeNote, d: DebtView): Long {
        val l = n.label ?: return n.amount
        return if (d.clearable(l)) n.amount - l.exposed + d.retained(l) else n.amount - l.exposed
    }

    /** Why a move of exposed stake waits: the earliest date one of [notes]' open labels frees its exposure. */
    private fun lockedText(notes: List<OwnedStakeNote>, d: DebtView): String? {
        val until = notes.mapNotNull { it.label }.filter { !d.clearable(it) }.minOfOrNull { movableAfter(it, d.windowSeconds) } ?: return null
        return "moved stake can move again after ${dateText(until)}: until then a slash of the validator it left can still reach it, so it stays where it is (the rest of this stake moves freely)"
    }

    /** One validator's stake as this wallet holds it. */
    data class StakeHolding(
        val validator: String,
        /** derth held (every spendable note). */
        val derth: Long,
        /** derth that may leave now (what undelegating, locking or moving can take). */
        val free: Long,
        /** Moved-in derth whose window is open, and when the first of it may move again (null: none). */
        val locked: Long,
        val lockedUntil: Long?,
        /** Notes held here: more than one can be merged ([restake]). */
        val notes: Int,
        /** Whether two of them can merge now (at most one labelled). */
        val mergeable: Boolean,
    )

    /**
     * This wallet's stake per validator. [d] (null) reads the chain's debt
     * view only when a label is held; without it a closed window counts as
     * still open (nothing is shown as movable that is not).
     */
    fun stakeHoldings(d: DebtView? = null): List<StakeHolding> {
        val notes = store.state.stakeNotes.filter { it.spendable && it.denom.startsWith(DERTH_PREFIX) }
        val view = d ?: if (notes.any { it.label != null }) runCatching { debtView() }.getOrNull() else null
        return notes.groupBy { it.denom }.toSortedMap().map { (denom, ns) ->
            val open = ns.mapNotNull { it.label }.filter { view == null || !view.clearable(it) }
            StakeHolding(
                validator = parseDerth(denom),
                derth = Amounts.satSum(ns) { it.amount },
                free = Amounts.satSum(ns) { n -> view?.let { freeOf(n, it) } ?: (n.amount - (n.label?.exposed ?: 0L)) },
                locked = Amounts.satSum(open) { it.exposed },
                lockedUntil = open.minOfOrNull { movableAfter(it, view?.windowSeconds ?: labelWindowSeconds) },
                notes = ns.size,
                mergeable = ns.size >= 2 && ns.count { it.label == null } >= 1,
            )
        }
    }

    /**
     * The clear_before and debt root every stake proof names (also when it
     * clears nothing, so a clearing proof looks like any other), and the
     * clear of [l] when its window has closed.
     */
    private fun clearOf(l: StakeLabel?, d: DebtView): StakePlan.Clear {
        if (d.clearBefore == 0L) return StakePlan.Clear.NONE
        if (l == null || !d.clearable(l)) return StakePlan.Clear(d.clearBefore, d.root)
        val w = d.tree.witness(l.moveKey) ?: throw IllegalStateException("no debt witness for a move key")
        val r = w.retained(l.moveKey, l.exposed, d.root) ?: throw IllegalStateException("the debt tree does not read this stake's move")
        return StakePlan.Clear(d.clearBefore, d.root, w, r)
    }

    /**
     * Lane A of [denom]: [spends] (at most one labelled) merged with [vIn]
     * less [vOut] into one note back to us (the change, or a zero note), the
     * label kept or cleared ([clearOf]).
     */
    private fun laneA(
        denom: String,
        spends: List<OwnedStakeNote>,
        vIn: Long,
        vOut: Long,
        d: DebtView,
        salt: Fr = StakePlan.freshSalt(),
        credit: StakePlan.Credit? = null,
    ): StakePlan {
        val l = spends.firstNotNullOfOrNull { it.label }
        val clear = clearOf(l, d)
        var amount = Amounts.exactSum(spends) { it.amount }
        if (clear.clears) amount = amount - l!!.exposed + clear.retained
        amount = Math.subtractExact(Math.addExact(amount, vIn), vOut)
        require(amount >= 0) { "insufficient stake" }
        val out = StakePlan.out(keys, denom, amount, if (clear.clears) null else l)
        return StakePlan(keys.nk, denom, spends, spends.map { store.stakeTree.path(it.position) }, out, vIn, vOut, clear, credit, salt, stakeAnchor())
    }

    /** What a lane A clear gives up: the slash's cut of the cleared exposure (0 when nothing is cleared, or nothing was cut). */
    private fun haircutOf(p: StakePlan): Long {
        if (!p.clear.clears) return 0
        val l = p.spends.firstNotNullOf { it.label }
        return l.exposed - p.clear.retained
    }

    /** The chain refused nothing yet, but what the sheet showed no longer holds: show it again. */
    class QuoteChanged(message: String) : IllegalStateException(message)

    /**
     * derth bought for [value] uerth at the rate [book] will have after
     * [CREDIT_HORIZON_SECONDS] of rewards, less a margin (ORCHARD_DESIGN
     * 12.2): the chain refuses a credit the value does not buy, in its ante,
     * at no cost. The rewards are the module's part of the validator's
     * pro-rata share of the emission over [bonded] (x/staking's bonded
     * tokens), before commission: at most what accrues.
     */
    internal fun creditFor(value: java.math.BigInteger, book: PrivacyChainReads.ValidatorQuote, bonded: java.math.BigInteger): Long {
        if (book.supply.signum() == 0) {
            check(book.backing.signum() == 0) { "this validator's book is settling (no derth, some backing); try again after the epoch ends" }
            return value.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong()
        }
        check(book.backing.signum() > 0) { "this validator's stake is backed by nothing (slashed to zero)" }
        val accrues = if (bonded.signum() > 0) {
            java.math.BigInteger.valueOf(StakingApr.EMISSION_UERTH_PER_SEC * CREDIT_HORIZON_SECONDS).multiply(book.delegation).divide(bonded)
        } else java.math.BigInteger.ZERO
        val buys = value.multiply(book.supply).divide(book.backing + accrues)
        val margin = buys.multiply(java.math.BigInteger.valueOf(CREDIT_MARGIN_PPM)).add(java.math.BigInteger.valueOf(999_999)).divide(java.math.BigInteger.valueOf(1_000_000))
        val q = buys.subtract(margin)
        return if (q.signum() <= 0) 0L else q.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }

    /**
     * A delegation's quote (ORCHARD_DESIGN 12.2), shown on its confirm sheet:
     * [derth] credited to our note at [validator] for [amount] uerth, and the
     * [haircut] a merge clearing a moved-in label takes (0: none).
     */
    data class DelegateQuote(val validator: String, val amount: Long, val derth: Long, val haircut: Long)

    fun quoteDelegate(validator: String, amount: Long): DelegateQuote {
        require(amount > 0)
        val min = reads.minDelegation()
        if (amount < min) throw IllegalArgumentException("a private delegation is at least ${min}uerth")
        val list = reads.validators()
        val book = takesStake(list.of(validator))
        val derth = creditFor(java.math.BigInteger.valueOf(amount), book, list.bondedTokens)
        if (derth < min || derth <= 0) throw IllegalArgumentException("${amount}uerth buys less than the least derth a delegation may credit; stake more")
        val d = debtView()
        val plan = laneA(derthDenom(validator), StakeSelection.merge(spendableStake(derthDenom(validator))) { freeOf(it, d) }, derth, 0, d)
        return DelegateQuote(validator, amount, derth, haircutOf(plan))
    }

    /**
     * Stakes [q].amount uerth with its validator (ORCHARD_DESIGN 8.1): the
     * bundle releases it (and the fee) into the module; the stake proof
     * merges the quoted derth into our note there (up to two of them, a
     * labelled one cleared once its window closed), or pads its input when we
     * hold none, so a first delegation looks like a top-up. A rate that
     * outran the quote is refused in the ante: nothing spent, nothing paid.
     */
    fun delegate(q: DelegateQuote): TxResult {
        val denom = derthDenom(q.validator)
        val d = debtView()
        val stake = laneA(denom, StakeSelection.merge(spendableStake(denom)) { freeOf(it, d) }, q.derth, 0, d)
        if (haircutOf(stake) > q.haircut) throw QuoteChanged("a slash reached stake you moved to this validator since the quote; review it again")
        return run(Act(PrivateActivityKind.STAKE, q.validator)) { fee ->
            // The fee is the bundle's uerth balance less amount.
            val b = bundle(release = mapOf(FEE to Math.addExact(q.amount, fee)))
            Assembled(listOf(b), stake) { bs, sp, _ ->
                MsgDelegate.newBuilder().setBundle(bs[0]).setValidator(q.validator).setAmount(q.amount).setDerth(q.derth).setStake(sp!!).build()
            }
        }
    }

    /** Stakes [amount] uerth with [validator] at a quote taken now. */
    fun delegate(validator: String, amount: Long): TxResult = delegate(quoteDelegate(validator, amount))

    /**
     * Merges two of our notes at [validator] (MsgRestake, ORCHARD_DESIGN
     * 20.3): needed only for a note made beside a labelled one (a move into a
     * validator where ours was labelled) or by another device. At most one
     * labelled; a closed window clears. One user tap; nothing merges by itself.
     */
    fun restake(validator: String): TxResult {
        val denom = derthDenom(validator)
        val d = debtView()
        val two = StakeSelection.merge(spendableStake(denom)) { freeOf(it, d) }
        require(two.size == 2) {
            if (spendableStake(denom).size >= 2) "these notes each hold stake moved here recently; they merge once one of their windows closes"
            else "nothing to merge"
        }
        val stake = laneA(denom, two, 0, 0, d)
        return run(Act(PrivateActivityKind.RESTAKE, validator)) { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgRestake.newBuilder().setBundle(bs[0]).setValidator(validator).setStake(sp!!).build()
            }
        }
    }

    /** derth denoms held in more than one stake note that can merge now. */
    fun stakeMergeable(): Map<String, Int> =
        store.state.stakeNotes.filter { it.spendable && it.denom.startsWith(DERTH_PREFIX) }
            .groupBy { it.denom }.filter { (_, ns) -> ns.size >= 2 && ns.any { it.label == null } }.mapValues { it.value.size }

    /** Merges two stake notes of [denom] (derth/<valoper>) into one. */
    fun mergeStake(denom: String): TxResult = restake(parseDerth(denom))

    /**
     * What leaving [amount] derth/[validator] (an undelegation or a lock)
     * spends: the notes whose free value covers it, refused up front when
     * the exposure a window keeps in place is what it would take.
     */
    private fun leave(validator: String, amount: Long, d: DebtView, salt: Fr = StakePlan.freshSalt(), credit: StakePlan.Credit? = null): StakePlan {
        val denom = derthDenom(validator)
        val notes = spendableStake(denom)
        val ins = StakeSelection.cover(notes, amount, { freeOf(it, d) }) { lockedText(notes, d) }
        return laneA(denom, ins, 0, amount, d, salt, credit)
    }

    /** What leaving [amount] derth/[validator] costs beyond its fee: a cleared label's slash cut (0: none). Refuses as the tx would. */
    fun leaveHaircut(validator: String, amount: Long): Long = haircutOf(leave(validator, amount, debtView()))

    /**
     * An undelegation's quote, for its confirm sheet: [amount] derth worth
     * [value] uerth at the validator's live rate now (what the chain books
     * it at, floor(amount x B / S); a slash before the payout lowers what
     * arrives), and the [haircut] a cleared label takes (0: none).
     */
    data class UndelegateQuote(val validator: String, val amount: Long, val value: Long, val haircut: Long)

    fun quoteUndelegate(validator: String, amount: Long): UndelegateQuote {
        require(amount > 0)
        val haircut = leaveHaircut(validator, amount)
        val book = reads.validators().of(validator)
        check(book.supply.signum() > 0 && java.math.BigInteger.valueOf(amount) <= book.supply) { "more derth than this validator has" }
        val value = java.math.BigInteger.valueOf(amount).multiply(book.backing).divide(book.supply)
        return UndelegateQuote(validator, amount, value.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong(), haircut)
    }

    /** [book], refused with the chain's own reason when it takes no delegation or redelegation now (1102). */
    private fun takesStake(book: PrivacyChainReads.ValidatorQuote): PrivacyChainReads.ValidatorQuote {
        if (!book.delegatable) {
            throw IllegalStateException("this validator is not taking stake now" + if (book.refusal.isNotEmpty()) ": ${book.refusal}" else "")
        }
        return book
    }

    /**
     * Undelegates [amount] derth/[validator] (ORCHARD_DESIGN 8.4): the
     * stake proof spends it (the change, or a zero note when nothing is left,
     * back to us) and the msg names where the chain pays it out, a fresh pool
     * note opening of our own (pc and its v2 amount-blind ciphertext). At
     * maturity the chain mints the ERTH there by itself, as one note or, past
     * 2^63-1, several sharing that ciphertext; sync finds them by trial
     * decryption like any minted note. Nothing to claim, nothing sent later.
     * Moved-in derth whose window is open cannot leave: refused up front.
     *
     * The undelegation is remembered locally ([PendingUnbond]) from the
     * moment the node takes the tx, with its epoch, value and payout id from
     * the committed event, until a note to its pc arrives: what the wallet
     * shows while it waits. The chain's per-id payout query is never asked
     * (it would tie this wallet's IP to the undelegation).
     */
    fun undelegate(validator: String, amount: Long, maxHaircut: Long = Long.MAX_VALUE): TxResult {
        val stake = leave(validator, amount, debtView())
        if (haircutOf(stake) > maxHaircut) throw QuoteChanged("a slash reached stake you moved to this validator since the sheet was shown; review it again")
        val payout = mint(FEE)
        val pc = payout.pc
        expect(PrivateActivityKind.UNBONDING_PAYOUT, payout)
        val r = run(
            Act(PrivateActivityKind.UNSTAKE, validator),
            accepted = { hash, timeout -> recordUnbond(PendingUnbond(hash, validator, amount, pc, now(), timeout)) },
            rejected = { hash -> dropUnbond(hash) },
        ) { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgUndelegate.newBuilder().setBundle(bs[0]).setValidator(validator).setAmount(amount).setStake(sp!!)
                    .setPc(ByteString.copyFrom(pc.toBytes())).setCiphertext(ByteString.copyFrom(payout.ciphertext)).build()
            }
        }
        confirmUnbond(r)
        return r
    }

    /**
     * A move's quote (MsgRedelegate, ORCHARD_DESIGN 8.7), shown on its
     * confirm sheet: [amount] derth/[src] worth [value] uerth at src's live
     * rate arrives as [dstDerth] derth/[dst] (dst's live rate, less the
     * margin and what may stay behind in src's book), merged into our
     * unlabelled note there ([merges]) or a new note; [haircut] is a cleared
     * label's slash cut on the src side (0: none). The credited derth is
     * labelled: it cannot move again until the label window
     * ([windowSeconds]) has passed. [pairEntries] and [pairCounted] are the
     * (src, dst) x/staking record's entries when quoted: what the move's gas
     * headroom is sized by ([redelegateHeadroom]).
     */
    data class MoveQuote(
        val src: String,
        val dst: String,
        val amount: Long,
        val value: Long,
        val dstDerth: Long,
        val haircut: Long,
        val merges: Boolean,
        val windowSeconds: Long,
        val pairEntries: Long = 0,
        val pairCounted: Long = 0,
    )

    /** The note at [dst] a move's credit merges into: our largest unlabelled one there (none: the lane pads, making a second note beside a labelled one). */
    private fun creditTarget(dst: String): OwnedStakeNote? =
        spendableStake(derthDenom(dst)).filter { it.label == null }.maxWithOrNull(compareBy<OwnedStakeNote> { it.amount }.thenBy { -it.position })

    fun quoteMove(src: String, dst: String, amount: Long): MoveQuote {
        require(amount > 0)
        require(src != dst) { "move stake to another validator" }
        val d = debtView()
        val plan = leave(src, amount, d)
        val min = reads.minDelegation()
        val list = reads.validators()
        val a = list.of(src)
        val b = takesStake(list.of(dst))
        check(a.supply.signum() > 0 && java.math.BigInteger.valueOf(amount) <= a.supply) { "more derth than this validator has" }
        val u = java.math.BigInteger.valueOf(amount).multiply(a.backing).divide(a.supply)
        if (u < java.math.BigInteger.valueOf(min)) throw IllegalArgumentException("this stake is worth ${u}uerth, less than the ${min}uerth a move must carry")
        val credit = creditFor(arrives(a, u), b, list.bondedTokens)
        if (credit < min || credit <= 0) throw IllegalArgumentException("this move would credit less than the least derth a move may credit; move more")
        val load = a.redelegations[dst]
        return MoveQuote(
            src, dst, amount, u.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong(), credit, haircutOf(plan), creditTarget(dst) != null,
            d.windowSeconds, load?.entries ?: 0, load?.countedEntries ?: 0,
        )
    }

    /**
     * Moves [q].amount derth from [q].src to [q].dst with no unbonding gap
     * (MsgRedelegate). Lane A
     * spends our src notes (free value only: moved-in stake whose window is
     * open stays put, refused up front) with the change back; the credit lane
     * merges the quoted derth/dst into our unlabelled note there (or pads),
     * labelled with this move: move key = the lane's nullifier, move_time =
     * the chain's latest block time (the chain takes it within 600 s before
     * its block), exposed = the credit. A refusal (the rates outran the
     * quote, move_time too old) costs nothing: the ante runs before any spend.
     */
    fun redelegate(q: MoveQuote): TxResult {
        val d = debtView()
        val moveTime = roots.latestBlock()?.time?.takeIf { it > 0 } ?: throw IllegalStateException("the node did not say its latest block time; try again")
        val target = creditTarget(q.dst)
        val credit = StakePlan.credit(keys, derthDenom(q.dst), target, target?.let { store.stakeTree.path(it.position) }, q.dstDerth, moveTime)
        val stake = leave(q.src, q.amount, d, credit = credit)
        if (haircutOf(stake) > q.haircut) throw QuoteChanged("a slash reached stake you moved to this validator since the quote; review it again")
        return run(Act(PrivateActivityKind.REDELEGATE, "${q.src} → ${q.dst}")) { fee ->
            Assembled(listOf(feeBundle(fee)), stake, extraGas = redelegateHeadroom(q.pairEntries, q.pairCounted)) { bs, sp, _ ->
                MsgRedelegate.newBuilder().setBundle(bs[0]).setSrcValidator(q.src).setDstValidator(q.dst).setAmount(q.amount)
                    .setStake(sp!!).setDstDerth(q.dstDerth).setMoveTime(moveTime).build()
            }
        }
    }

    /** Undelegations of this wallet whose payout has not arrived yet, oldest first. */
    val pendingUnbonds: List<PendingUnbond> get() = synchronized(store) { store.state.pendingUnbonds.toList() }

    private fun recordUnbond(u: PendingUnbond) = synchronized(store) {
        store.state.pendingUnbonds.removeAll { it.txHash == u.txHash }
        store.state.pendingUnbonds.add(u)
        store.save()
    }

    private fun dropUnbond(hash: String) = synchronized(store) {
        if (store.state.pendingUnbonds.removeAll { it.txHash == hash }) store.save()
    }

    /** The committed undelegation's epoch, value and payout id, from its event. */
    private fun confirmUnbond(r: TxResult) = synchronized(store) {
        val s = store.state
        val i = s.pendingUnbonds.indexOfFirst { it.txHash.equals(r.hash, ignoreCase = true) }
        if (i < 0) return@synchronized
        if (r.code != 0) { s.pendingUnbonds.removeAt(i); store.save(); return@synchronized }
        fun attr(k: String) = r.attr(UNDELEGATE_EVENT, k)?.let { Amounts.parseU64(it) }?.takeIf { it >= 0 }
        val epoch = attr("epoch")
        s.pendingUnbonds[i] = s.pendingUnbonds[i].copy(
            confirmed = true, epoch = epoch, value = attr("value"), payoutId = attr("payout_id"),
            dueBy = epoch?.let { e -> runCatching { reads.unbondDueBy(e) }.getOrNull() },
        )
        store.save()
    }

    /**
     * Settles the undelegations remembered locally (after a sync): one whose
     * pc a note of ours now carries has been paid; one still unconfirmed is
     * looked up by its own hash, dropped when it failed in its block or is
     * missing past its timeout_height, confirmed when committed.
     */
    private fun resolveUnbonds() {
        val list = synchronized(store) { store.state.pendingUnbonds.toList() }
        if (list.isEmpty()) return
        val paid = synchronized(store) { store.state.notes.mapTo(HashSet()) { it.note.pc(keys.ownerPk) } }
        val tip by lazy { runCatching { chain.tipHeight() }.getOrNull() }
        for (u in list) {
            if (u.pc in paid) { dropUnbond(u.txHash); continue }
            if (u.confirmed) continue
            val r = runCatching { chain.tx(u.txHash) }.getOrNull()
            r?.let { outcome(it) }
            when {
                r != null -> confirmUnbond(r)
                u.until != null && tip != null && tip!! > u.until -> dropUnbond(u.txHash)
            }
        }
    }

    /**
     * What a note votes (ORCHARD_DESIGN 8.5): its amount, or for a labelled
     * note its amount less the slash cut of its exposure under the CURRENT
     * debt tree (a slash after the snapshot counts).
     */
    private fun voteValue(n: OwnedStakeNote, d: DebtView?): Long {
        val l = n.label ?: return n.amount
        val view = d ?: return n.amount - l.exposed
        return n.amount - l.exposed + view.retained(l)
    }

    /**
     * Votes this wallet's stake at [validator] on [proposalId] (ORCHARD_DESIGN
     * 18.2, 20.4): one msg, one vote proof for up to [PrivateMsgs.MAX_VOTE_NOTES]
     * of its derth notes (the largest eligible ones), one weight, their value
     * rounded down to three significant digits ([voteWeight]). The proof
     * shows every note under the proposal's snapshot root, its spend
     * nullifier absent from the snapshot's stake nullifier tree (rebuilt here
     * and checked against nf_root), a labelled note's value under the current
     * debt root, and each note's vote nullifier; an unused slot carries a
     * padding nullifier (fresh r, random slot), so every vote looks alike.
     * Nothing is spent: a note spent since the snapshot (a top-up, a move)
     * still votes the value it held then, its opening kept for that; the
     * merged note it became cannot vote on this proposal. The fee bundle is
     * against the pool's current roots. Each note's (proposal, vote
     * nullifier) is remembered from the moment the node accepts the tx, so no
     * note votes twice. A validator with more notes than one vote holds votes
     * the rest in another msg ([stakeVoteItems] says how many).
     *
     * A note the wallet does not know already voted (a restored wallet) is
     * refused by the chain before anything is sent (1119 at simulate, naming
     * its vote nullifier): it is recorded and the vote laid out again without
     * it, within this one confirmed action, until it carries only fresh notes.
     */
    fun stakeVote(proposalId: Long, validator: String, options: List<WeightedVoteOption>): TxResult {
        repeat(PrivateMsgs.MAX_VOTE_NOTES) {
            try {
                return stakeVoteOnce(proposalId, validator, options)
            } catch (e: NoteVoted) {
                // Recorded; lay the vote out again with the notes left.
            }
        }
        return stakeVoteOnce(proposalId, validator, options)
    }

    /** A 1119 naming one of the vote's notes, refused before it reached a block: that note is recorded, the rest may vote. */
    private class NoteVoted : Exception()

    private fun stakeVoteOnce(proposalId: Long, validator: String, options: List<WeightedVoteOption>): TxResult {
        val denom = derthDenom(validator)
        val snap = snapshot(proposalId)
        val tree = store.stakeTree
        // A snapshot past the local tree (stake landed since the last sync) cannot be checked here.
        if (snap.treeSize < 0 || snap.treeSize > tree.size) throw SyncFirst("the proposal's stake snapshot is ahead of this wallet; sync first")
        check(tree.rootAt(snap.treeSize) == snap.root) { "the local stake tree disagrees with the proposal's snapshot root" }
        val nfRoot = snap.nfRoot ?: throw IllegalStateException("this proposal's snapshot has no stake nullifier root; it takes no stake vote")
        val candidates = eligible(proposalId, snap).filter { it.denom == denom }.sortedWith(VOTE_ORDER)
        if (candidates.isEmpty()) throw AlreadyVoted()
        // Every vote names the current debt root (the chain checks it is current), labelled notes or not.
        val d = debtView()
        val nfs = snapshotNullifiers(snap)
        val chosen = ArrayList<OwnedStakeNote>()
        val slots = ArrayList<VoteSlot>()
        for (note in candidates) {
            if (chosen.size == PrivateMsgs.MAX_VOTE_NOTES) break
            val low = nfs.nonMembership(Privacy.stakeNf(keys.nk, note.rho, note.position))
            if (low == null) {
                // Skipped only when sync, too, saw the spend at
                // or before the snapshot's block. Otherwise the two disagree (a
                // stream or a snapshot that is not the chain's): an error, never
                // a vote silently skipped.
                if (note.spentHeight != null && snap.height > 0 && note.spentHeight <= snap.height) continue
                throw IllegalStateException("the proposal's snapshot nullifier tree holds a note's nullifier, but sync saw no spend before the snapshot; sync again")
            }
            // A labelled note's value is read from the debt tree under the current root.
            val debt = note.label?.let { l -> d.tree.witness(l.moveKey) ?: throw IllegalStateException("no debt witness for a move key") } ?: DebtTree.Witness.NONE
            val slot = VoteSlot(note.amount, note.rho, note.rcm, note.position, tree.pathAt(note.position, snap.treeSize), low, note.label, debt)
            // A note slashed to nothing votes nothing.
            if ((slot.value(d.root) ?: 0L) <= 0L) continue
            chosen.add(note)
            slots.add(slot)
        }
        if (chosen.isEmpty()) throw SpentBeforeSnapshot()
        val weight = voteWeight(Amounts.satSum(slots) { it.value(d.root)!! })
        val used = chosen.map { Privacy.voteNf(keys.nk, it.rho, it.position, proposalId) }
        val layout = VoteLayout.random(slots.size)
        val vnfs = layout.vnfs(keys.nk, slots, proposalId)
        val asset = Privacy.assetId(denom)
        val vote = VoteWitnessSpec(vnfs) { sighash ->
            VoteWitness(keys.nk, slots, layout, snap.root, nfRoot, d.root, asset, weight, proposalId, sighash)
        }
        // Whether the tx reached a mempool (and so may have landed, fee paid).
        var sent = false
        try {
            val r = run(
                Act(PrivateActivityKind.VOTE, "proposal $proposalId"),
                accepted = { hash, timeout -> sent = true; used.forEach { recordVote(StakeVoteRecord(proposalId, it, hash, timeout, confirmed = false)) } },
                rejected = { hash -> sent = false; used.forEach { forgetVote(proposalId, it, hash) } },
            ) { fee ->
                Assembled(listOf(feeBundle(fee)), vote = vote) { bs, _, _ ->
                    MsgStakeVote.newBuilder().setBundle(bs[0]).setProposalId(proposalId).setValidator(validator)
                        .addAllOptions(PrivateMsgs.canonicalOptions(options)).setWeight(weight)
                        .setDebtRoot(ByteString.copyFrom(d.root.toBytes())).build()
                }
            }
            used.forEach { recordVote(StakeVoteRecord(proposalId, it, r.hash, null, confirmed = true)) }
            return r
        } catch (e: Exception) {
            // One note already voted (a restored wallet, a vote whose block
            // the wallet missed): the chain names it. It is recorded, so the
            // next vote leaves it out; the others never voted.
            if (alreadyVotedError(e)) {
                val named = usedVoteNullifier(e, used) ?: throw AlreadyVoted()
                recordVote(StakeVoteRecord(proposalId, named, null, null, confirmed = true))
                // Refused before any mempool (simulate, CheckTx): nothing was
                // paid and the other notes never voted, so lay it out again.
                // A refusal in a block is settled by resolveVotes.
                if (!sent) throw NoteVoted()
                throw AlreadyVoted()
            }
            throw e
        }
    }

    private val snapshots = HashMap<Long, PrivacyChainReads.Snapshot>()

    /**
     * Per-wallet caches built from one chain (snapshots, the stake nullifier
     * tree): dropped when the store's genesis changes.
     */
    private var cacheGenesis: String? = null

    private fun checkCaches() = synchronized(snapshots) {
        val g = store.state.genesis
        if (g != cacheGenesis) {
            snapshots.clear()
            synchronized(nfValues) { nfValues.clear(); nfTrees.clear() }
            synchronized(debtTrees) { debtTrees.clear() }
            cacheGenesis = g
        }
    }

    /**
     * [proposalId]'s snapshot, from the chain's own Query/Snapshot: its stake root and size, nullifier root and size, block and
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

    /** This stake already voted on this proposal (the chain's code 1119): votes are final. */
    class AlreadyVoted : IllegalStateException("this stake already voted on this proposal; vote again to cast any of it that has not")

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
            r?.let { outcome(it) }
            val next = when {
                r != null && r.code == 0 -> v.copy(confirmed = true)
                // The whole msg was refused for the one note the chain names: only that one is final.
                r != null && r.code == VOTE_NULLIFIER_USED && r.codespace == VOTE_CODESPACE ->
                    if (namesVoteNullifier(r.log, v.vnf)) v.copy(confirmed = true) else null
                r != null -> null
                v.txHash == null || (v.until != null && tip != null && tip!! > v.until) -> null
                // A timeout no sane tip gives is settled by the tx's status alone.
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
     * The stake nullifier tree at [snap] (ORCHARD_DESIGN 3.4):
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
        // nf_size is the LCD's (snapshot()), so the fetch
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
                // snapshot is the trees at that block's end).
                (it.spentHeight == null || snap.height == 0L || it.spentHeight > snap.height) &&
                !voted(proposalId, Privacy.voteNf(keys.nk, it.rho, it.position, proposalId))
        }
    }

    /**
     * One stake vote msg: a validator's eligible notes (one msg votes up to
     * [PrivateMsgs.MAX_VOTE_NOTES]; [notes] more take [parts] msgs, each its
     * own weight and fee), or a position.
     */
    sealed interface StakeVoteItem {
        data class Validator(val validator: String, val notes: Int) : StakeVoteItem {
            val parts: Int get() = (notes + PrivateMsgs.MAX_VOTE_NOTES - 1) / PrivateMsgs.MAX_VOTE_NOTES
        }
        data class Position(val id: Long, val counter: Int) : StakeVoteItem
    }

    /**
     * Every stake vote [proposalId] takes from this wallet: one per validator
     * with eligible derth notes, and one per position of ours that may vote
     * (created before the snapshot's block). Each is its own tx, confirmed and
     * sent by the user one at a time ([castStakeVote]); nothing is cast for
     * them in the background.
     */
    fun stakeVoteItems(proposalId: Long): List<StakeVoteItem> {
        val snap = snapshot(proposalId)
        val notes = eligible(proposalId, snap).groupBy { parseDerth(it.denom) }.toSortedMap()
            .map { (v, ns) -> StakeVoteItem.Validator(v, ns.size) }
        val mine = positions()
        val voting = votingPositions(mine.map { it.first }, snap).map { it.id }.toSet()
        return notes + mine.filter { it.first.id in voting }.map { (p, c) -> StakeVoteItem.Position(p.id, c) }
    }

    /** What one stake vote weighs: the notes it carries (0 for a position) and the ERTH its weight is worth at the snapshot. */
    data class VotePreview(val notes: Int, val uerth: Long)

    /** What [item]'s next vote on [proposalId] carries, for its confirm sheet (null: nothing left). */
    fun stakeVotePreview(proposalId: Long, item: StakeVoteItem): VotePreview? {
        val snap = snapshot(proposalId)
        return when (item) {
            is StakeVoteItem.Validator -> {
                val part = eligible(proposalId, snap).filter { it.denom == derthDenom(item.validator) }
                    .sortedWith(VOTE_ORDER).take(PrivateMsgs.MAX_VOTE_NOTES)
                val d = if (part.any { it.label != null }) runCatching { debtView() }.getOrNull() else null
                val v = Amounts.satSum(part) { voteValue(it, d) }
                if (part.isEmpty() || v <= 0) null
                else rateFor(snap, item.validator)?.let { VotePreview(part.size, derthValue(voteWeight(v), it)) }
            }
            is StakeVoteItem.Position -> positions().firstOrNull { it.first.id == item.id }?.first
                ?.let { p -> rateFor(snap, p.validator)?.let { VotePreview(0, derthValue(p.derth, it)) } }
        }
    }

    /**
     * ERTH per derth of [validator] for a vote preview: the snapshot's, which
     * a snapshot with a seq does not carry, else the live book's (backing /
     * supply, Query/Validators read whole). Null when neither is known: the
     * sheet then shows no ERTH figure rather than the derth count as one.
     */
    private fun rateFor(snap: PrivacyChainReads.Snapshot, validator: String): BigDecimal? = snap.rates[validator]
        ?: runCatching { reads.validators().of(validator) }.getOrNull()?.takeIf { it.supply.signum() > 0 }
            ?.let { BigDecimal(it.backing).divide(BigDecimal(it.supply), 18, java.math.RoundingMode.DOWN) }

    /**
     * Casts [item] as the last sync left things: a validator's next vote (up
     * to two notes) or a position's. Null when there is nothing left of it
     * to cast (its notes already voted on this proposal, or were spent before
     * its snapshot; the position is gone).
     */
    fun castStakeVote(proposalId: Long, item: StakeVoteItem, options: List<WeightedVoteOption>): String? = when (item) {
        is StakeVoteItem.Validator -> try {
            stakeVote(proposalId, item.validator, options).hash
        } catch (e: AlreadyVoted) {
            if (eligible(proposalId, snapshot(proposalId)).none { it.denom == derthDenom(item.validator) }) null else throw e
        } catch (e: SpentBeforeSnapshot) { null }
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
        // knows the closed ones' counters from their unlock memos.
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
        var dirty = next > s.nextOtagCounter
        if (dirty) s.nextOtagCounter = next
        if (rememberLeases(out.map { it.first })) dirty = true
        if (dirty) store.save()
        return out.sortedBy { it.first.id }
    }

    /**
     * Keeps each of our positions' split and lease end as last seen, so a
     * lapse (which the chain records by clearing both) is still known: when,
     * and which split to cast again. Closed positions are forgotten. True
     * when anything changed.
     */
    private fun rememberLeases(mine: List<PrivacyChainReads.Position>): Boolean = synchronized(this) {
        val leases = store.state.positionLeases
        val before = HashMap(leases)
        leases.keys.retainAll(mine.map { it.id }.toSet())
        for (p in mine) if (p.splits.isNotEmpty() && p.splitExpiresAt > 0) {
            leases[p.id] = network.erth.wallet.privacy.sync.PositionLease(minOf(p.splitExpiresAt, Handles.satAdd(now(), Handles.MAX_AHEAD_SECONDS)), p.splits)
        }
        leases != before
    }

    /**
     * Each of [mine]'s (from [positions]) Groundworks lease: the chain's
     * split and lease end while it holds them, else the ones last seen here.
     * From this wallet's own reads only; nothing is asked about a position.
     */
    fun groundworksLeases(mine: List<PrivacyChainReads.Position>): List<Reminders.GroundworksLease> {
        val seen = store.state.positionLeases
        val cap = Handles.satAdd(now(), Handles.MAX_AHEAD_SECONDS)
        return mine.map { p ->
            if (p.splits.isNotEmpty()) Reminders.GroundworksLease(p.id, minOf(p.splitExpiresAt, cap), held = true, split = p.splits)
            else Reminders.GroundworksLease(p.id, seen[p.id]?.expiresAt ?: 0, held = false, split = seen[p.id]?.split.orEmpty())
        }
    }

    private val otags = HashMap<Int, Fr>()

    private fun ownerTag(c: Int): Fr = synchronized(otags) { otags.getOrPut(c) { keys.ownerTag(c) } }

    /** Locks [amount] derth/[validator] into a new position split by [splits], under a fresh owner tag. */
    fun lockPosition(validator: String, amount: Long, splits: Map<Long, Long>): TxResult {
        val d = debtView()
        // Refused up front, before a counter is taken, when moved-in stake whose window is open would have to leave.
        leave(validator, amount, d)
        positions() // a restored wallet's counter starts past every tag it already holds
        val counter = synchronized(this) { store.state.nextOtagCounter.also { store.state.nextOtagCounter = it + 1; store.save() } }
        val stake = leave(validator, amount, d, salt = keys.otagSalt(counter))
        val w = weights(splits)
        return run(Act(PrivateActivityKind.POSITION, "locked with $validator")) { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgLockPosition.newBuilder().setBundle(bs[0]).setValidator(validator).setAmount(amount)
                    .addAllSplits(w).setStake(sp!!).build()
            }
        }
    }

    /** A position's own proof (its update, its vote): no notes, the position's owner tag, the chain's current clear_before and debt root. */
    private fun ownerPlan(position: PrivacyChainReads.Position, counter: Int): StakePlan {
        check(keys.ownerTag(counter) == position.ownerTag) { "position ${position.id} is not owned by tag $counter" }
        return StakePlan(keys.nk, null, emptyList(), emptyList(), null, 0, 0, clearOf(null, debtView()), null, keys.otagSalt(counter), stakeAnchor())
    }

    fun updatePosition(position: PrivacyChainReads.Position, counter: Int, splits: Map<Long, Long>): TxResult {
        val stake = ownerPlan(position, counter)
        val w = weights(splits)
        return run(Act(PrivateActivityKind.POSITION, "updated position ${position.id}")) { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgUpdatePosition.newBuilder().setBundle(bs[0]).setPositionId(position.id).addAllSplits(w).setStake(sp!!).build()
            }
        }
    }

    /**
     * Closes [position]: the stake proof (its owner tag) merges the
     * position's derth into our note at its validator, or pads when we hold
     * none there (ORCHARD_DESIGN 8.3). The fee bundle carries a value-0
     * record note to ourselves naming the closed counter, so no
     * restore ever locks under its tag again.
     */
    fun unlockPosition(position: PrivacyChainReads.Position, counter: Int): TxResult {
        check(keys.ownerTag(counter) == position.ownerTag) { "position ${position.id} is not owned by tag $counter" }
        val denom = derthDenom(position.validator)
        val d = debtView()
        val stake = laneA(denom, StakeSelection.merge(spendableStake(denom)) { freeOf(it, d) }, position.derth, 0, d, salt = keys.otagSalt(counter))
        val record = NoteOut.to(keys.address, FEE, 0, WalletSync.unlockMemo(keys.nk, counter))
        return run(Act(PrivateActivityKind.POSITION, "unlocked position ${position.id}")) { fee ->
            Assembled(listOf(bundle(outputs = listOf(record), release = mapOf(FEE to fee))), stake) { bs, sp, _ ->
                MsgUnlockPosition.newBuilder().setBundle(bs[0]).setPositionId(position.id).setStake(sp!!).build()
            }
        }
    }

    fun positionVote(position: PrivacyChainReads.Position, counter: Int, proposalId: Long, options: List<WeightedVoteOption>, accepted: (hash: String) -> Unit = {}): TxResult {
        val stake = ownerPlan(position, counter)
        return run(Act(PrivateActivityKind.VOTE, "proposal $proposalId, position ${position.id}"), accepted = { hash, _ -> accepted(hash) }) { fee ->
            Assembled(listOf(feeBundle(fee)), stake) { bs, sp, _ ->
                MsgPositionVote.newBuilder().setBundle(bs[0]).setPositionId(position.id).setProposalId(proposalId)
                    .addAllOptions(PrivateMsgs.canonicalOptions(options)).setStake(sp!!).build()
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
    fun noteSwap(denomIn: String, amountIn: Long, denomOut: String, minOut: Long, to: ShieldedAddress? = null, counterparty: String = ""): TxResult {
        require(denomIn != denomOut && amountIn > 0 && minOut > 0)
        requireTransferable(denomIn)
        val out = payout(denomOut, to)
        val mine = to == null || to.ownerPk == keys.ownerPk
        return run(Act(if (mine) PrivateActivityKind.SWAP else PrivateActivityKind.SEND, counterparty, if (mine) listOf(out) else emptyList())) { fee ->
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
        return run(Act(PrivateActivityKind.ADD_LIQUIDITY, "pool $poolId", listOf(refund, shares))) { fee ->
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
        // Both legs are minted once the LP unbonding ends: received rows of their own.
        expect(PrivateActivityKind.LP_PAYOUT, erth, tok)
        return run(Act(PrivateActivityKind.REMOVE_LIQUIDITY, "pool $poolId")) { fee ->
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
    fun withdrawalNote(): NoteOut = payout("uanml", null).also { expect(PrivateActivityKind.LP_PAYOUT, it) }

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
         * a quarter of the most a split payout carries, so the pool can move
         * 4x against the provider before maturity, as x/dex allows for its
         * own cap. Exactly x/dex's maxWithdrawalNoteLeg:
         * MaxSplitNotes / 4 = 32 notes of MaxNoteValue (2^63 - 1),
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
         * by hash. Used by the mover for the other wallet's store.
         */
        fun recordIncoming(store: PrivacyStore, p: PendingMove, now: Long, generation: Int? = null) = synchronized(store) {
            val s = generation?.let { store.state.slot(it) } ?: store.state.current
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

        /**
         * Why a target wallet cannot take a move of [kind]:
         * its identity moved one away already (the chain refuses that owner),
         * or it holds one of its own (a handle; a live caretaker split) that
         * the move would overwrite here. Null: it can.
         */
        fun targetRefusal(s: network.erth.wallet.privacy.sync.PrivacyState, kind: String, now: Long): String? = targetRefusal(s.current, kind, now)

        fun targetRefusal(s: IdentitySlot, kind: String, now: Long): String? = when (kind) {
            PendingMove.HANDLE -> when {
                s.handleMovedOut -> "that wallet's identity already moved a handle away; it can never hold one again"
                s.handle.isNotEmpty() -> "that wallet already holds @${s.handle}"
                else -> null
            }
            else -> when {
                s.caretakerMovedOut -> "that wallet's identity already moved a caretaker vote away; it can never hold one again"
                s.caretakerExpiresAt > now && (s.caretakerSplit.isNotEmpty() || s.caretakerSplitUnknown) -> "that wallet already holds a caretaker vote"
                else -> null
            }
        }

        /** Undoes [recordIncoming] for a move that definitely did not happen. */
        fun rollbackIncoming(store: PrivacyStore, p: PendingMove, now: Long, generation: Int? = null) = synchronized(store) {
            val s = generation?.let { store.state.slot(it) } ?: store.state.current
            s.pendingMoves.removeAll { it.txHash == p.txHash && it.incoming }
            undoIncoming(s, p, now)
            store.save()
        }

        internal fun undoIncoming(s: IdentitySlot, p: PendingMove, now: Long) {
            if (p.kind == PendingMove.HANDLE) {
                if (s.handle == p.handle) { s.handle = ""; s.handleSetAt = now }
            } else if (s.caretakerSplit == p.split && s.caretakerSplitUnknown == (p.splitUnknown || p.split.isEmpty())) {
                s.caretakerSplit = emptyMap(); s.caretakerSplitUnknown = false; s.caretakerExpiresAt = 0
            }
        }

        /**
         * The fee the confirm sheet showed, for the private run on this
         * thread (TxController sets it around the run): a fee above it throws
         * PrivateTxEngine.FeeAboveQuote and the sheet asks again.
         * Every tx the app sends comes from a confirm sheet, so it is always
         * set there; unset (tests) only the cap applies.
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

        /** Slack past the computed payout time for the epoch transition and the block that pays it. */
        const val PAYOUT_MARGIN_S = 15 * 60L

        /**
         * The latest moment an undelegation booked in epoch [e] is paid, given
         * the current epoch [current] (started [currentStart], due to end
         * [currentEnd]). Epoch e ends in the block that starts e+1 and every
         * epoch lasts at least [epochSeconds]: an epoch not ended yet ends
         * (e - current) epochs after the current one does; an ended one by
         * start(current) - (current - 1 - e) x epochSeconds. The SDK entry
         * then matures [unbondingSeconds] later and the chain pays it in a
         * following block. Null on nonsense or overflow (chain-supplied
         * numbers: never a wrapped answer).
         */
        fun unbondDueBy(e: Long, current: Long, currentStart: Long, currentEnd: Long, epochSeconds: Long, unbondingSeconds: Long): Long? {
            if (e < 0 || current < 0 || epochSeconds <= 0 || unbondingSeconds < 0) return null
            return try {
                val end = if (e >= current) {
                    Math.addExact(maxOf(currentEnd, Math.addExact(currentStart, epochSeconds)), Math.multiplyExact(e - current, epochSeconds))
                } else {
                    Math.subtractExact(currentStart, Math.multiplyExact(current - 1 - e, epochSeconds))
                }
                Math.addExact(Math.addExact(end, unbondingSeconds), PAYOUT_MARGIN_S)
            } catch (x: ArithmeticException) {
                null
            }
        }

        /** The event an undelegation emits: its epoch, value and payout_id. */
        const val UNDELEGATE_EVENT = "shieldedstaking_undelegate"

        /** The notes one stake vote takes first: the largest (the most weight in one msg), then by position. */
        val VOTE_ORDER: Comparator<OwnedStakeNote> = compareByDescending<OwnedStakeNote> { it.amount }.thenBy { it.position }
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
         * (PRIVACY_FORMATS §15): the amount rounded down to three significant
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

        /** The codespace [VOTE_NULLIFIER_USED] is registered in: the code alone could be any module's. */
        const val VOTE_CODESPACE = "shieldedstaking"

        /** Whether [e] is the chain refusing a vote nullifier already used on the proposal. */
        fun alreadyVotedError(e: Throwable): Boolean {
            val chainOf = generateSequence(e) { it.cause }
            if (chainOf.any { it is network.erth.wallet.privacy.tx.UnsignedTx.TxRejected && it.code == VOTE_NULLIFIER_USED && it.codespace == VOTE_CODESPACE }) return true
            // Simulate answers with the error's registered text, not its code.
            return chainOf.mapNotNull { it.message }.any { "this stake note already voted on this proposal" in it }
        }

        /** Whether a 1119 refusal's [log] names [vnf] (the chain: "vote nullifier <HEX>"). */
        fun namesVoteNullifier(log: String, vnf: Fr): Boolean = log.contains(vnf.toHex(), ignoreCase = true)

        /** Which of [vnfs] a 1119 refusal ([e] or a cause) names, if any. */
        fun usedVoteNullifier(e: Throwable, vnfs: List<Fr>): Fr? {
            val texts = generateSequence(e) { it.cause }.mapNotNull { it.message }.toList()
            return vnfs.firstOrNull { v -> texts.any { namesVoteNullifier(it, v) } }
        }

        /** Stake nullifier leaves asked of the indexer a page, and of the LCD (its maximum). */
        const val NF_PAGE = WalletSync.PAGE_SIZE
        const val LCD_NF_PAGE = 1000

        /** Debt rows asked of the indexer a page (a size it serves), and of the LCD (its maximum). */
        const val DEBT_PAGE = 1000
        const val LCD_DEBT_PAGE = 1000

        /**
         * The margin a credit is quoted with beyond the rewards
         * [CREDIT_HORIZON_SECONDS] adds (rounding, a reward the bound misses),
         * in ppm of the derth the value buys.
         */
        const val CREDIT_MARGIN_PPM = 10L

        /**
         * How long a credit's quote must hold: its confirm sheet, the proof
         * and the tx's timeout. Rewards raise the rate every block, and while
         * little is bonded they raise it fast (3.6 ppm a second on launch
         * week), so the quote prices the backing the validator will have by
         * then. A quote the rate outran anyway is refused in the ante at no
         * cost, and retried.
         */
        const val CREDIT_HORIZON_SECONDS = 600L

        /** What of a redelegation's value beyond the source's queue may stay in its book (chain bondedDust, 0.001 ERTH). */
        const val BONDED_DUST = 1_000L

        /**
         * What of a move's value [u] arrives at dst (ORCHARD_DESIGN 8.7,
         * 12.2), from src's [a] list entry: all of u when the value leaves
         * the queue first ([PrivacyChainReads.ValidatorQuote.queueFirst]) and
         * the queue P + W covers it; otherwise u splits between the queue and
         * the bonded stake pro rata, a bonded part of at most 0.001 ERTH stays
         * in src's book and x/staking may truncate a uerth: u - 1001.
         */
        fun arrives(a: PrivacyChainReads.ValidatorQuote, u: java.math.BigInteger): java.math.BigInteger =
            if (a.queueFirst && u <= a.queue) u
            else (u - java.math.BigInteger.valueOf(BONDED_DUST + 1)).max(java.math.BigInteger.ZERO)

        /** x/shieldedstaking MaxEntryHeightsPerPair: at this many counted entries a move first merges two. */
        const val MAX_ENTRY_HEIGHTS_PER_PAIR = 1_024L

        /**
         * Gas a move declares beyond its simulation's headroom: the pair's
         * record gains at most an entry a block until the tx lands (within
         * PrivateTxEngine.TIMEOUT_BLOCKS of the tip), and the 10% headroom
         * covers those entries' 2,500 each, but not the merge (2,500 an entry
         * more and 128 x 20,000, the chain's redelegateGas) the move pays once
         * the pair reaches [MAX_ENTRY_HEIGHTS_PER_PAIR] counted entries. So
         * when it could reach the cap before the tx lands without being there
         * when simulated, that much more; otherwise nothing (at the cap, the
         * simulation priced it).
         */
        fun redelegateHeadroom(entries: Long, counted: Long): Long {
            val ahead = network.erth.wallet.privacy.tx.PrivateTxEngine.TIMEOUT_BLOCKS + 1
            if (counted >= MAX_ENTRY_HEIGHTS_PER_PAIR || counted + ahead < MAX_ENTRY_HEIGHTS_PER_PAIR) return 0
            return (entries.coerceIn(0, MAX_ENTRY_HEIGHTS_PER_PAIR) + ahead) * 2_500L + 128L * 20_000L
        }

        /** A unix time as the wallet shows it in a sentence: "2026-10-25 14:03 UTC". */
        fun dateText(unix: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(unix.coerceIn(0, 253_402_300_799) * 1000))

        /** Positions created before the block the proposal entered voting at (all, when unknown). */
        fun votingPositions(positions: List<PrivacyChainReads.Position>, snap: PrivacyChainReads.Snapshot) =
            positions.filter { snap.height == 0L || it.createdHeight < snap.height }

        /** Owner-tag counters scanned past the highest known (PRIVACY_FORMATS.md §7). */
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

        /** A move within the wallet names its target as this, followed by the generation moved to. */
        const val WITHIN_TARGET = "generation:"

        /** The suggested wait before a move after a switch: drawn uniformly between these (hours to days). */
        const val MOVE_DELAY_MIN_SECONDS = 6 * 3600L
        const val MOVE_DELAY_MAX_SECONDS = 3 * 86_400L

        /**
         * The suggested move comes at least this long before the earliest
         * lease end of what is to move: room for the reminder to be seen and
         * for a move that needs a retry.
         */
        const val MOVE_DEADLINE_MARGIN_SECONDS = 3 * 86_400L

        /**
         * Before a switch, and before the registration's year ends: a handle
         * or caretaker split whose lease ends within this long is worth
         * renewing first (the live identity can), so a move after the switch
         * or renewal has a full lease to land in rather than days.
         */
        const val RENEW_FIRST_WINDOW_SECONDS = 30 * 86_400L

        /** [drawn], no later than [MOVE_DEADLINE_MARGIN_SECONDS] before [deadline] (0: none). */
        fun cappedMoveAt(drawn: Long, deadline: Long): Long =
            if (deadline <= 0) drawn else minOf(drawn, Handles.satSub(deadline, MOVE_DEADLINE_MARGIN_SECONDS))

        /** A register proof's public signals: [current_date, address, nullifier, dsc_key, idc]. */
        const val REGISTER_SIGNALS = 5
        /** The idc's index among them (the chain's params.idc_index). */
        const val IDC_SIGNAL = 4
        /** What a wallet says when its identity was registered before (personhood 1130). */
        const val IDENTITY_USED = "This identity has been registered before, and the chain accepts each identity once. " +
            "The wallet has moved on to your next identity, from the same recovery phrase: start the registration again."

        /** What a wallet says when a refusal named its identity used but nothing confirms it. */
        const val IDENTITY_REFUSAL_UNCONFIRMED = "This identity was refused as already registered, but the refusal " +
            "did not carry the chain's code and this wallet has no record of registering it, so the wallet keeps it. " +
            "If you use your own node over plain http, check it or switch to Earth's node, then try again."

        /** The chain refused another identity, and the wallet has skipped as many unrecorded ones as a restore can find. */
        const val IDENTITY_SKIPS_EXHAUSTED = "The chain refused this identity as already registered, and this wallet has " +
            "already skipped as many identities with no record as it can. Create a new wallet and register there."

        /** The chain's text for personhood 1130 (ErrIdcUsed). */
        const val IDENTITY_USED_TEXT = "identity commitment has been registered before"

        /** ErrIdcUsed's code and codespace: a code means nothing without its codespace. */
        const val IDENTITY_USED_CODE = 1130
        const val IDENTITY_USED_CODESPACE = "personhood"

        /** Whether [e] (or a cause) is CheckTx refusing an identity as used: the chain's code and codespace (1130, personhood). */
        fun identityRefusal(e: Throwable): Boolean = identityRejection(e) != null

        /** The CheckTx refusal in [e] (or a cause) that names an identity as used, or null. */
        fun identityRejection(e: Throwable): network.erth.wallet.privacy.tx.UnsignedTx.TxRejected? =
            generateSequence(e) { it.cause }.take(8).filterIsInstance<network.erth.wallet.privacy.tx.UnsignedTx.TxRejected>()
                .firstOrNull { it.code == IDENTITY_USED_CODE && it.codespace == IDENTITY_USED_CODESPACE }

        /** Whether [e] (or a cause) says in its text alone that an identity is used: a hint for a sync, never a floor. */
        fun identityRefusalText(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(8).any { it.message?.contains(IDENTITY_USED_TEXT) == true }

        /**
         * The chain's current_date_max_skew_seconds (48 h): a registration
         * lands only while its proof's current_date is within it of the block
         * time. A switch must also be proven on a later date than the live
         * registration (error 1128), so the wallet always proves on today's
         * UTC date.
         */
        const val REGISTRATION_SKEW_SECONDS = 172_800L

        /**
         * The largest skew governance may set (x/personhood: a year) plus a
         * day: past it the chain refuses any current_date anyway.
         */
        const val MAX_REGISTRATION_SKEW_SECONDS = 366L * SECONDS_PER_DAY

        /**
         * How long after its current_date a sent registration is kept as
         * possibly landing: the chain's current_date_max_skew_seconds as the
         * node reports it, never below the default 48 h (a shorter window
         * only means keeping longer than needed; a node that says nothing, or
         * 0, gets the default) and never above [MAX_REGISTRATION_SKEW_SECONDS].
         */
        fun keepSkew(param: Long?): Long = (param ?: 0L).coerceIn(REGISTRATION_SKEW_SECONDS, MAX_REGISTRATION_SKEW_SECONDS)

        fun notYetText(waitSeconds: Long): String =
            if (waitSeconds > 2 * SECONDS_PER_DAY) "this identity replaced another too recently for this action; it opens in ${waitSeconds / SECONDS_PER_DAY + 1} days"
            else "this registration is too recent for this action; try again in ${waitSeconds / 3600 + 1}h"
        /**
         * MsgRegister's gas for the confirm sheet, and so the most it may pay
         * without asking again: the passport proof (3M), the DSC chain (300k)
         * and six note writes (PrivateTxEngine's register gas, 4.2M), the fee
         * bundle (100k) and its two actions (4.6M), the base (100k), ~50 KB
         * of tx bytes (500k), and the 10% headroom the quote adds: ~10.5M.
         * It was 7M, under the proofs alone, so every registration's quote
         * came in above the sheet and the sheet came back over "Sending"
         * (iOS build 21). At 0.005 uerth/gas the fee (65,000) stays inside
         * the 100,000 gas grant. As iOS.
         */
        const val REGISTER_GAS_ESTIMATE = 13_000_000L
        /** A pending registration whose tx failed in its block. */
        const val TX_FAILED = "the registration tx failed"
        /** How the broadcast's wait words a tx that failed in its block (RestPrivateChain, FakeChain). */
        const val TX_FAILED_PREFIX = "tx failed"

        /** Slack against the chain's clock for bounds the wallet must stay under. */
        const val CLOCK_MARGIN = 600L

        /**
         * How long an anchor must stay valid past the chain's last block for
         * a tx built on it: the chain's CheckTx margin (120 s), the tx's
         * timeout_height (50 blocks) and proving on a phone, with room.
         */
        const val ANCHOR_MARGIN = 1_800L

        /** The day every activation bound keeps from now (the largest identity root window). */
        const val ACTIVATION_MARGIN = 86_400L

        fun derthDenom(valoper: String) = "$DERTH_PREFIX$valoper"
        fun lpDenom(poolId: Long) = "$LP_PREFIX$poolId"

        fun parseDerth(denom: String): String {
            require(denom.startsWith(DERTH_PREFIX)) { "not a derth note" }
            return denom.removePrefix(DERTH_PREFIX)
        }
    }
}
