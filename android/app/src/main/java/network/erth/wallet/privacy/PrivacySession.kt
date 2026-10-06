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
        val w = walletFor(app, keys)
        current = address to w
        return w
    }

    /**
     * The [PrivacyWallet] of [keys] (the selected wallet's, or another of
     * this phone's): one per store id per process.
     */
    @Synchronized
    private fun walletFor(app: Context, keys: PrivacyKeys): PrivacyWallet {
        val id = storeId(keys)
        wallets[id]?.let { return it }
        val w = PrivacyWallet(
            keys = keys,
            store = PrivacyStore.shared(app.filesDir, id, network.erth.wallet.wallet.SessionManager.dataKey()),
            indexer = HttpPrivacyIndexer(Constants.EARTH_API_URL),
            chain = RestPrivateChain,
            reads = RestChainReads,
            prover = AndroidProver(app),
            chainId = Constants.EARTH_CHAIN_ID,
            roots = network.erth.wallet.privacy.chain.LcdChainRoots,
        )
        wallets[id] = w
        return w
    }

    /** The private side of the wallet at [index] (another of this phone's wallets). Blocking. */
    fun walletAt(context: Context, index: Int): PrivacyWallet = walletFor(context.applicationContext, keysOf(context, index))

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
                PrivacyWallet.recordIncoming(PrivacyStore.shared(app.filesDir, targetId, dataKey()), move, System.currentTimeMillis() / 1000)
            override fun rollback(move: network.erth.wallet.privacy.sync.PendingMove) =
                PrivacyWallet.rollbackIncoming(PrivacyStore.shared(app.filesDir, targetId, dataKey()), move, System.currentTimeMillis() / 1000)
            override fun refusal(move: network.erth.wallet.privacy.sync.PendingMove): String? {
                val store = PrivacyStore.shared(app.filesDir, targetId, dataKey())
                return synchronized(store) { PrivacyWallet.targetRefusal(store.state, move.kind, System.currentTimeMillis() / 1000) }
            }
        }
    }

    /** Retries recording every move of [w] (default: the selected wallet) whose target write failed; returns how many remain unrecorded. */
    fun retryMoveRecords(context: Context, w: PrivacyWallet = wallet(context)): Int {
        for (p in w.outgoingMoves().filter { !it.recorded && it.target.isNotEmpty() }) {
            if (runCatching { recorderFor(context, p.target).record(p.copy(incoming = true, target = "", recorded = true)) }.isSuccess) w.markRecorded(p.txHash)
        }
        return w.outgoingMoves().count { !it.recorded }
    }

    /**
     * What the identity this wallet's registration succeeded (another wallet
     * on this phone, same passport) holds that a move can bring here: shown
     * after a switch lands. Null when this identity is not live, or no wallet
     * on the phone is its predecessor (a switch from a lost phrase: nothing
     * can move, as the move proof needs both identity secrets).
     */
    data class MoveOffer(
        /** The predecessor's wallet index and name. */
        val fromIndex: Int,
        val fromName: String,
        /** Its handle ("" none) and whether it is live (only a live one moves). */
        val handle: String,
        val handleLive: Boolean,
        /** Whether it holds a live caretaker vote, and until when. */
        val voteLive: Boolean,
        val voteExpiresAt: Long,
        /** Its moves already sent and not yet confirmed (or not recorded here). */
        val inFlight: List<network.erth.wallet.privacy.sync.PendingMove>,
        /** Its shielded ERTH: the moves' fees come out of it. */
        val feeErth: Long,
    ) {
        val anything: Boolean get() = handle.isNotEmpty() || voteLive || inFlight.isNotEmpty()
    }

    /**
     * Finds the predecessor among this phone's wallets by the succession leaf
     * the chain appended for this identity's registration (searched in this
     * wallet's own tree: nothing asked names it), syncs that wallet, and says
     * what it holds. Blocking (a sync).
     */
    fun moveOffer(context: Context): MoveOffer? {
        val app = context.applicationContext
        val w = wallet(app)
        val id = w.store.state.identity?.takeIf { it.verified } ?: return null
        if (w.identityStatus() != network.erth.wallet.privacy.sync.WalletSync.IdentityStatus.LIVE) return null
        val selected = SecureWalletManager.getSelectedWalletIndex()
        for (info in SecureWalletManager.listWallets()) {
            if (info.index == selected) continue
            val keys = runCatching { keysOf(app, info.index) }.getOrNull() ?: continue
            if (keys.idc == w.keys.idc) continue
            w.successionIndex(keys.idc, w.keys.idc, id.leafIndex + 1) ?: continue
            val p = walletFor(app, keys)
            runCatching { p.sync() }
            runCatching { p.resolvePendingMoves() }
            val st = p.store.state
            val now = System.currentTimeMillis() / 1000
            val handleExp = p.handleExpiresAt()
            return MoveOffer(
                fromIndex = info.index, fromName = info.name,
                handle = st.handle, handleLive = st.handle.isNotEmpty() && (handleExp == 0L || handleExp > now),
                voteLive = p.caretakerLive(), voteExpiresAt = p.caretakerExpiresAt(),
                inFlight = p.outgoingMoves().filter { !it.confirmed || !it.recorded },
                feeErth = p.poolBalances()[PrivacyWallet.FEE] ?: 0L,
            )
        }
        return null
    }

    /** This (selected) wallet's identity as the successor a move from another wallet names. */
    fun selfAsSuccessor(context: Context): PrivacyWallet.Successor {
        val w = wallet(context.applicationContext)
        val id = w.store.state.identity?.takeIf { it.verified } ?: throw IllegalStateException("this wallet's registration has not been verified yet; sync, then try again")
        return PrivacyWallet.Successor(w.keys, id)
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
        val st = runCatching { PrivacyStore.shared(app.filesDir, id, dataKey()).state }.getOrNull()
        val now = System.currentTimeMillis() / 1000
        return TargetInfo(
            // A registration it sent that can still land counts: one that failed may be replayed.
            id, st != null && (st.identity != null || st.pendingRegistration != null || st.registrationKeepUntil > now), st?.handle.orEmpty(),
            st?.let { PrivacyWallet.targetRefusal(it, network.erth.wallet.privacy.sync.PendingMove.HANDLE, now) },
            st?.let { PrivacyWallet.targetRefusal(it, network.erth.wallet.privacy.sync.PendingMove.CARETAKER, now) },
        )
    }

    /** The install's data key, which seals every wallet's private store (a session must be open). */
    private fun dataKey(): ByteArray = network.erth.wallet.wallet.SessionManager.dataKey()

    /** Forget the cached wallet (lock, wallet switch). */
    fun clear() {
        current = null
    }

    private class AndroidProver(private val context: Context) : Prover {
        override fun proveAction(w: ActionWitness): ByteArray = PrivacyProver.proveAction(context, w)
        override fun proveStake(w: StakeWitness): ByteArray = PrivacyProver.proveStake(context, w)
        override fun proveMembership(w: MembershipWitness): ByteArray = PrivacyProver.proveMembership(context, w)
        override fun proveMove(w: network.erth.wallet.privacy.prove.MoveWitness): ByteArray = PrivacyProver.proveMove(context, w)
        override fun proveVote(w: network.erth.wallet.privacy.prove.VoteWitness): ByteArray = PrivacyProver.proveVote(context, w)
    }

    private object RestChainReads : PrivacyChainReads {
        override fun personhoodParams() = PrivacyQueries.personhoodParams().let {
            PrivacyChainReads.PersonhoodParams(it.caretakerVoteSeconds, it.identityRootWindowSeconds, it.handleLeaseSeconds, it.handleRenewalSeconds,
                PrivacyWallet.keepSkew(it.currentDateMaxSkewSeconds.takeIf { s -> s > 0 }))
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
            PrivacyChainReads.Position(it.id, it.validator, it.derth, it.ownerTag, it.splits, it.createdHeight, it.splitExpiresAt)
        }

        override fun debtTree(start: Long, limit: Int) = PrivacyQueries.debtTree(start, limit)

        override fun validators() = PrivacyQueries.validators()

        override fun minDelegation(): Long = PrivacyQueries.minDelegation()
    }
}
