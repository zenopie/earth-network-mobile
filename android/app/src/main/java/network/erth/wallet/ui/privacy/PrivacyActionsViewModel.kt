package network.erth.wallet.ui.privacy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.chain.Allocation
import network.erth.wallet.privacy.PrivacyChainReads
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.Reminders
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles
import network.erth.wallet.privacy.sync.PendingMove
import network.erth.wallet.privacy.sync.WalletSync

/** A position of ours, its owner-tag counter (PrivacyKeys.otagSalt) and its split's lease. */
data class PositionRow(val position: PrivacyChainReads.Position, val keyIndex: Int, val lease: Reminders.GroundworksLease? = null)

data class PrivacyActionsState(
    val positions: List<PositionRow>,
    val groundworksOptions: List<Allocation.OptionInfo>,
    val ballots: List<PrivacyQueries.RemovalBallot>,
    /** Spendable note counts per denom where a merge would help. */
    val mergeable: Map<String, Int>,
    /** This wallet's private stake per validator: what may move now, what waits for its window. */
    val stake: List<network.erth.wallet.privacy.PrivacyWallet.StakeHolding> = emptyList(),
    /** The chain's label window as last read (0: never): how long moved stake stays put. */
    val labelWindowSeconds: Long = 0,
    /** Every stake note, spent ones included: what tells stake still joining its validator (StakeRound.joining). */
    val stakeNotes: List<network.erth.wallet.privacy.note.OwnedStakeNote> = emptyList(),
)

/**
 * This identity's own standing: its handle and caretaker vote, what it may
 * still do with them, and what is due (reminders, never actions).
 */
data class PersonalState(
    val identityLive: Boolean,
    /** This wallet's shielded address (what a handle names by default). */
    val shieldedAddress: String,
    /** The handle this identity holds ("" for none), and its directory entry (null: not found or not loaded). */
    val handle: String,
    val handleEntry: HandleEntry?,
    val handleMovedOut: Boolean,
    val handleLeaseSeconds: Long,
    val handleRenewalSeconds: Long,
    /** The caretaker split's expiry (0: none) and whether it moved away. */
    val caretakerExpiresAt: Long,
    val caretakerMovedOut: Boolean,
    val caretakerSplit: Map<Long, Long>,
    /** This identity replaced another at this time (0: a fresh passport): a new claim waits a lease. */
    val predecessorAt: Long,
    val reminders: List<Reminders.Reminder>,
    /** The directory could not be read, or null. */
    val directoryError: String? = null,
    /** The split is held but was restored without its options. */
    val caretakerSplitUnknown: Boolean = false,
    /** Non-free directory entries naming this wallet's address. */
    val addressed: List<HandleEntry> = emptyList(),
    /** Moves away from this identity not yet confirmed, or not yet recorded in the new wallet. */
    val outgoingMoves: List<PendingMove> = emptyList(),
    /** Moves to this identity the chain has not confirmed yet. */
    val incomingMoves: List<PendingMove> = emptyList(),
)

/**
 * What the private Groundworks, removal-ballot, handle and note screens
 * show. Every read is public and whole: all positions (ours found by key),
 * all open ballots, all options; nothing asked names this wallet.
 */
class PrivacyActionsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<PrivacyActionsState?>(null)
    val state: StateFlow<PrivacyActionsState?> = _state.asStateFlow()

    fun refresh(): Job = viewModelScope.launch {
        val ctx = getApplication<Application>()
        var mine: List<PrivacyChainReads.Position>? = null
        _state.value = withContext(Dispatchers.IO) {
            val w = runCatching { PrivacySession.wallet(ctx) }.getOrNull()
            val owned = w?.let { runCatching { it.positions() }.getOrNull() }
            mine = owned?.map { it.first }
            val leases = w?.let { wl -> mine?.let { wl.groundworksLeases(it) } }.orEmpty().associateBy { it.positionId }
            PrivacyActionsState(
                positions = owned.orEmpty().map { (p, k) -> PositionRow(p, k, leases[p.id]) },
                groundworksOptions = runCatching { Allocation.stream(StreamId.STREAM_ID_GROUNDWORKS).options }.getOrDefault(emptyList()),
                ballots = runCatching { PrivacyQueries.removalBallots() }.getOrDefault(emptyList()),
                mergeable = w?.let { it.mergeable() + it.stakeMergeable() }.orEmpty(),
                stake = w?.let { runCatching { it.stakeHoldings() }.getOrNull() }.orEmpty(),
                labelWindowSeconds = w?.labelWindowSeconds ?: 0L,
                stakeNotes = w?.stakeNotes.orEmpty(),
            )
        }
        // The positions just read give the Groundworks reminders: no second download.
        val read = mine
        _personal.value = withContext(Dispatchers.IO) { personalOf(ctx, read) }
    }

    private val _personal = MutableStateFlow<PersonalState?>(null)
    val personal: StateFlow<PersonalState?> = _personal.asStateFlow()

    /**
     * Re-reads this identity's handle and caretaker standing and the
     * reminders due. The handle's entry comes from the whole directory (one
     * download for every lookup), never from a query for it alone.
     */
    fun refreshPersonal(): Job = viewModelScope.launch {
        val ctx = getApplication<Application>()
        _personal.value = withContext(Dispatchers.IO) { personalOf(ctx) }
    }

    fun clear() {
        _state.value = null
        _personal.value = null
    }

    companion object {
        /** [mine]: this wallet's positions as just read (null: read them here, the whole public list as ever). */
        fun personalOf(ctx: android.content.Context, mine: List<PrivacyChainReads.Position>? = null): PersonalState? {
            val w = runCatching { PrivacySession.wallet(ctx) }.getOrNull() ?: return null
            val now = System.currentTimeMillis() / 1000
            val st = w.store.state
            val live = runCatching { w.identityStatus() == WalletSync.IdentityStatus.LIVE }.getOrDefault(false)
            val params = runCatching { PrivacyQueries.personhoodParams() }.getOrNull()
            // The claim wait uses the lease the chain's bound uses (LeaseBounds: the longest ever in force), never Params.
            val bounds = runCatching { PrivacyQueries.leaseBounds() }.getOrNull()
            var dirError: String? = null
            // Every wallet reads the chain's own directory, whole, holder or not,
            // and squares its handle with it (a handle a restore lost, one the chain swept).
            val dir = runCatching { PrivacyQueries.handles.chainDirectoryRead() }.onFailure { dirError = it.message ?: "network error" }.getOrNull()
            val addressed = dir?.let { (d, at) -> runCatching { w.reconcileHandle(d, at) }.getOrNull() }.orEmpty()
            val entry = if (st.handle.isEmpty()) null else dir?.first?.get(st.handle)
            val caretakerExp = runCatching { w.caretakerExpiresAt() }.getOrDefault(0L)
            val positions = mine ?: runCatching { w.positions().map { it.first } }.getOrNull()
            val groundworks = positions?.let { runCatching { w.groundworksLeases(it) }.getOrNull() }.orEmpty()
            // The move reminder's deadline, read here too, not only when Identity opens.
            runCatching { PrivacySession.refreshMoveDeadline(ctx) }
            val reminders = Reminders.due(
                Reminders.Inputs(
                    now = now,
                    identityLive = live,
                    claimOpensAt = runCatching { w.claimOpensAt() }.getOrNull(),
                    claimedToday = w.claimedToday(),
                    caretakerExpiresAt = caretakerExp,
                    handle = st.handle,
                    handleEntry = entry,
                    addressed = addressed,
                    ownAddress = w.address.encode(),
                    groundworks = groundworks,
                    moveSuggestedAt = w.moveSuggestionDue(),
                    moveDeadline = w.moveDeadline(),
                    // The chain's registered_at is the registration's block time (activated_at).
                    registrationEndsAt = st.identity?.takeIf { live }?.let { id ->
                        val validity = params?.registrationValiditySeconds?.takeIf { it in 1..network.erth.wallet.privacy.handles.Handles.MAX_AHEAD_SECONDS } ?: (365L * 86_400)
                        network.erth.wallet.privacy.handles.Handles.satAdd(id.activatedAt, validity)
                    } ?: 0L,
                    handleExpiresAt = entry?.expiresAt ?: runCatching { w.handleExpiresAt() }.getOrDefault(0L),
                ),
            )
            return PersonalState(
                identityLive = live,
                shieldedAddress = w.address.encode(),
                handle = st.handle,
                handleEntry = entry,
                handleMovedOut = st.handleMovedOut,
                handleLeaseSeconds = bounds?.handleLeaseSeconds?.takeIf { it in 1..Handles.MAX_AHEAD_SECONDS } ?: Handles.DEFAULT_LEASE_SECONDS,
                handleRenewalSeconds = params?.handleRenewalSeconds ?: Handles.DEFAULT_RENEWAL_SECONDS,
                caretakerExpiresAt = caretakerExp,
                caretakerMovedOut = st.caretakerMovedOut,
                caretakerSplit = st.caretakerSplit,
                predecessorAt = st.identity?.predecessorAt ?: 0L,
                reminders = reminders,
                directoryError = dirError,
                caretakerSplitUnknown = st.caretakerSplitUnknown,
                addressed = addressed,
                outgoingMoves = st.pendingMoves.filter { !it.incoming },
                incomingMoves = st.pendingMoves.filter { it.incoming },
            )
        }
    }
}
