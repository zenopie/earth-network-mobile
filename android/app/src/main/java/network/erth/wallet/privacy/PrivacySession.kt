package network.erth.wallet.privacy

import android.content.Context
import network.erth.wallet.Constants
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.chain.RestPrivateChain
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.PrivacyProver
import network.erth.wallet.privacy.prove.ActionWitness
import network.erth.wallet.privacy.prove.StakeWitness
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.tx.Prover
import network.erth.wallet.privacy.zk.Privacy
import network.erth.wallet.wallet.SecureWalletManager

/**
 * The selected wallet's [PrivacyWallet], built on first use from its mnemonic
 * (the privacy keys are derived, never stored apart from it) and kept while
 * the session lasts. Switching wallets builds another; each wallet's notes
 * live in their own directory.
 */
object PrivacySession {
    @Volatile private var current: Pair<String, PrivacyWallet>? = null

    /**
     * One [PrivacyWallet] per wallet per process, by store id, kept across
     * lock and unlock: a tx still finishing when the app locks writes to the
     * same wallet and store the next unlock reads, and the wallet's lock
     * orders them. Dropped only when its private data is forgotten.
     */
    private val wallets = HashMap<String, PrivacyWallet>()

    /** The selected wallet's private side. Blocking (derives keys, opens the store). */
    @Synchronized
    fun wallet(context: Context): PrivacyWallet {
        val app = context.applicationContext
        val address = SecureWalletManager.getWalletAddress(app) ?: throw IllegalStateException("no wallet")
        current?.let { (a, w) -> if (a == address) return w }
        val keys = SecureWalletManager.executeWithMnemonic(app) { PrivacyKeys.fromMnemonic(it) }
        val id = storeId(keys)
        wallets[id]?.let { w -> current = address to w; return w }
        val w = PrivacyWallet(
            keys = keys,
            store = PrivacyStore.shared(app.filesDir, id),
            indexer = HttpPrivacyIndexer(Constants.EARTH_API_URL),
            chain = RestPrivateChain,
            reads = RestChainReads,
            prover = AndroidProver(app),
            chainId = Constants.EARTH_CHAIN_ID,
            roots = network.erth.wallet.privacy.chain.LcdChainRoots,
        )
        wallets[id] = w
        current = address to w
        return w
    }

    /**
     * A wallet's store directory: named by a hash of the owner key, not the
     * address, so nothing on disk pairs the transparent address with the
     * shielded one.
     */
    private fun storeId(keys: PrivacyKeys): String = Privacy.h(Privacy.TAG_OWNER, keys.ownerPk).toHex().take(16)

    /**
     * Deletes the selected wallet's private data from the phone
     * (PrivacyStore.delete, zeroed then unlinked). The session lets go of the
     * wallet first; nothing on chain
     * changes, and the next sync rebuilds everything from the mnemonic.
     */
    fun forgetPrivateData(context: Context) {
        val app = context.applicationContext
        val keys = SecureWalletManager.executeWithMnemonic(app) { PrivacyKeys.fromMnemonic(it) }
        synchronized(this) {
            clear()
            wallets.remove(storeId(keys))
            PrivacyStore.delete(app.filesDir, storeId(keys))
        }
    }

    /**
     * The privacy keys of the wallet at [index] (another of this phone's
     * wallets): what an identity switch names its moves to. The mnemonic is
     * read for the derivation only.
     */
    fun keysOf(context: Context, index: Int): PrivacyKeys =
        SecureWalletManager.executeWithMnemonicAt(context.applicationContext, index) { PrivacyKeys.fromMnemonic(it) }

    /** The store id of the wallet at [index] (its owner key's hash). */
    fun storeIdOf(context: Context, index: Int): String = storeId(keysOf(context, index))

    fun storeIdOf(keys: PrivacyKeys): String = storeId(keys)

    /**
     * Writes a switch's moves into another wallet's private store: before
     * the broadcast, as pending; undone only on a definite
     * refusal. Addressed by store id, so a retry needs no recovery phrase.
     */
    fun recorderFor(context: Context, targetId: String): PrivacyWallet.MoveRecorder {
        val app = context.applicationContext
        return object : PrivacyWallet.MoveRecorder {
            override val targetId = targetId
            override fun record(move: network.erth.wallet.privacy.sync.PendingMove) =
                PrivacyWallet.recordIncoming(PrivacyStore.shared(app.filesDir, targetId), move, System.currentTimeMillis() / 1000)
            override fun rollback(move: network.erth.wallet.privacy.sync.PendingMove) =
                PrivacyWallet.rollbackIncoming(PrivacyStore.shared(app.filesDir, targetId), move, System.currentTimeMillis() / 1000)
            override fun refusal(move: network.erth.wallet.privacy.sync.PendingMove): String? {
                val store = PrivacyStore.shared(app.filesDir, targetId)
                return synchronized(store) { PrivacyWallet.targetRefusal(store.state, move.kind, System.currentTimeMillis() / 1000) }
            }
        }
    }

    /** Retries recording every move of the selected wallet whose target write failed; returns how many remain unrecorded. */
    fun retryMoveRecords(context: Context): Int {
        val w = wallet(context)
        for (p in w.outgoingMoves().filter { !it.recorded && it.target.isNotEmpty() }) {
            if (runCatching { recorderFor(context, p.target).record(p.copy(incoming = true, target = "", recorded = true)) }.isSuccess) w.markRecorded(p.txHash)
        }
        return w.outgoingMoves().count { !it.recorded }
    }

    /** What a switch target already holds: a registration, a handle. */
    data class TargetInfo(
        val storeId: String,
        val registered: Boolean,
        val handle: String,
        /** Why it cannot take this identity's handle / caretaker vote, or null. */
        val handleRefusal: String? = null,
        val voteRefusal: String? = null,
    )

    fun targetInfo(context: Context, index: Int): TargetInfo {
        val app = context.applicationContext
        val id = storeIdOf(app, index)
        val st = runCatching { PrivacyStore.shared(app.filesDir, id).state }.getOrNull()
        val now = System.currentTimeMillis() / 1000
        return TargetInfo(
            id, st?.identity != null || st?.pendingRegistration != null, st?.handle.orEmpty(),
            st?.let { PrivacyWallet.targetRefusal(it, network.erth.wallet.privacy.sync.PendingMove.HANDLE, now) },
            st?.let { PrivacyWallet.targetRefusal(it, network.erth.wallet.privacy.sync.PendingMove.CARETAKER, now) },
        )
    }

    /** Forget the cached wallet (lock, wallet switch). */
    fun clear() {
        current = null
    }

    private class AndroidProver(private val context: Context) : Prover {
        override fun proveAction(w: ActionWitness): ByteArray = PrivacyProver.proveAction(context, w)
        override fun proveStake(w: StakeWitness): ByteArray = PrivacyProver.proveStake(context, w)
        override fun proveMembership(w: MembershipWitness): ByteArray = PrivacyProver.proveMembership(context, w)
        override fun proveVote(w: network.erth.wallet.privacy.prove.VoteWitness): ByteArray = PrivacyProver.proveVote(context, w)
    }

    private object RestChainReads : PrivacyChainReads {
        override fun personhoodParams() = PrivacyQueries.personhoodParams().let {
            PrivacyChainReads.PersonhoodParams(it.caretakerVoteSeconds, it.identityRootWindowSeconds, it.handleLeaseSeconds, it.handleRenewalSeconds)
        }

        override fun leaseBounds() = PrivacyQueries.leaseBounds()

        override fun ballotInputs(proposalId: Long, optionId: Long) = PrivacyQueries.ballotInputs(proposalId, optionId).let {
            PrivacyChainReads.BallotInputs(it.scope, it.excludedDsc, it.excludedCountry, it.maxActivation, it.round, it.ballotId, it.maxPredecessor)
        }

        override fun epochNumber(): Long = PrivacyQueries.epoch().number

        override fun unbondDueBy(epoch: Long): Long? {
            val e = PrivacyQueries.epoch()
            val t = PrivacyQueries.stakingTiming()
            return PrivacyWallet.unbondDueBy(epoch, e.number, e.startTime, e.endTime, t.epochSeconds, t.unbondingSeconds)
        }

        override fun snapshot(proposalId: Long) = PrivacyQueries.snapshot(proposalId).let {
            PrivacyChainReads.Snapshot(it.root, it.treeSize, it.height, it.rates, it.nfRoot, it.nfSize)
        }

        override fun stakeNullifierTree(start: Long, limit: Int) = PrivacyQueries.stakeNullifierTree(start, limit).let {
            PrivacyChainReads.NfTreePage(it.values, it.size)
        }

        override fun positions() = PrivacyQueries.positions().map {
            PrivacyChainReads.Position(it.id, it.validator, it.derth, it.ownerTag, it.splits, it.createdHeight)
        }

        override fun debtTree(start: Long, limit: Int) = PrivacyQueries.debtTree(start, limit)

        override fun validatorBook(valoper: String) = PrivacyQueries.validatorBook(valoper)

        override fun minDelegation(): Long = PrivacyQueries.minDelegation()
    }
}
