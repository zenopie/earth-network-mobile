package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption
import cosmos.gov.v1.WeightedVoteOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.erth.wallet.privacy.sync.StakeVoteRun

/**
 * Casts a stake vote on a proposal the one way the app does (K5): every
 * eligible derth note (one vote proof each, nothing spent: ORCHARD_DESIGN
 * 15) and every position that may vote, in a shuffled
 * order, one at a time, with a full sync and a random 20-120 s pause between
 * casts, so a vote's fee never spends the previous vote's change unseen and
 * the casts are not one burst that times them together.
 *
 * Runs in [scope] (the app-level govern view model's), off the screen's
 * lifecycle and never holding the wallet lock while it waits; [progress]
 * says how far it got and when the next cast is due, [cancel] stops it at
 * once. The plan is persisted (PrivacyState.stakeVoteRun) so a run the
 * process lost is picked up by [resume] on the next unlock; positions
 * already voted are not voted twice, notes already voted are known by their
 * recorded vote nullifiers (PrivacyState.stakeVotes) and skipped. A note
 * votes once per proposal and on every open proposal.
 *
 * The run lives no longer than the unlocked session (audit 3): [suspend]
 * (on lock, session end and wallet switch: PrivacySession.clear) stops it
 * and drops the wallet it held, keeping the persisted run, which [resume]
 * picks up only from that wallet's own store on the next unlock.
 */
class StakeVoteController(
    private val scope: CoroutineScope,
    private val wallet: () -> PrivacyWallet,
    /** Waits between casts (milliseconds); tests pass a recorder. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Progress(
        val proposalId: Long,
        val done: Int,
        val total: Int,
        /** When the next cast is due (epoch ms), while waiting. */
        val nextAt: Long? = null,
        val finished: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null,
    ) {
        val running: Boolean get() = !finished && !cancelled && error == null
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    @Volatile private var job: Job? = null

    /**
     * The last suspended job: its blocking cast may still be running. A
     * resumed or new run waits for it to end before casting anything (audit
     * 4, L5), so the same item is never cast twice at once.
     */
    @Volatile private var previous: Job? = null

    /** The running job's flag: set by [suspend], so the job keeps the persisted run for [resume]. */
    @Volatile private var suspended = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Plans and starts a run on [proposalId], blocking the calling thread
     * (an IO thread: the confirm sheet's) until the first cast is broadcast;
     * returns its hash. The rest goes on in [scope].
     */
    fun startAndAwaitFirst(proposalId: Long, options: List<WeightedVoteOption>): String {
        val first = CompletableDeferred<String>()
        synchronized(this) {
            check(job?.isActive != true) { "a stake vote is already running" }
            val w = wallet()
            val run = StakeVoteRun(proposalId, options.map { it.optionValue to it.weight }, emptySet(), 0)
            val total = w.stakeVoteItems(proposalId).size
            require(total > 0) { "no stake from before this proposal's voting opened" }
            synchronized(w) {
                check(w.store.state.stakeVoteRun == null) { "a stake vote is already running" }
                w.store.state.stakeVoteRun = run.copy(total = total); w.store.save()
            }
            // The first cast is what the confirm sheet showed a fee for.
            launch(w, first, shownFee = PrivacyWallet.shownFee.get())
        }
        return runBlocking { first.await() }
    }

    /** Picks up a persisted run the process lost (call on unlock): the selected wallet's own, from its store. */
    @Synchronized
    fun resume() {
        if (job?.isActive == true) return
        val w = runCatching { wallet() }.getOrNull() ?: return
        val run = synchronized(w) { w.store.state.stakeVoteRun } ?: run { _progress.value = null; return }
        _progress.value = Progress(run.proposalId, run.done, run.total)
        launch(w, null, resumed = true)
    }

    /** Stops the run now (between casts, or before the next one starts); nothing is left to resume. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Stops the run without forgetting it (lock, session end, wallet
     * switch): the job and the wallet it held go, the persisted run stays
     * for [resume] on the next unlock of the same wallet.
     */
    @Synchronized
    fun suspend() {
        val j = job ?: return
        suspended.set(true)
        j.cancel()
        previous = j
        job = null
        _progress.value = null
    }

    /** Runs the persisted run of [w] (the wallet the caller resolved: never re-resolved inside the job). */
    private fun launch(w: PrivacyWallet, first: CompletableDeferred<String>?, resumed: Boolean = false, shownFee: Long? = null) {
        val suspendedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        suspended = suspendedFlag
        // A suspended job says nothing more on screen (a blocking cast may still finish).
        fun show(p: Progress) { if (!suspendedFlag.get()) _progress.value = p }
        val before = previous
        job = scope.launch(Dispatchers.IO) {
            before?.join()
            var run = synchronized(w) { w.store.state.stakeVoteRun } ?: return@launch
            // The run is still this one (a chain switch drops it: audit 4, L2).
            fun stillOurs() = synchronized(w) { w.store.state.stakeVoteRun?.proposalId == run.proposalId }
            try {
                val options = run.options.map { (o, wt) -> WeightedVoteOption.newBuilder().setOption(VoteOption.forNumber(o)).setWeight(wt).build() }
                val items = w.stakeVoteItems(run.proposalId).filter { it !is PrivacyWallet.StakeVoteItem.Position || it.id !in run.votedPositions }.shuffled(RNG)
                show(Progress(run.proposalId, run.done, run.total))
                for ((i, item) in items.withIndex()) {
                    if (i > 0 || resumed) {
                        val ms = VOTE_PAUSE_MIN_MS + (RNG.nextDouble() * (VOTE_PAUSE_MAX_MS - VOTE_PAUSE_MIN_MS)).toLong()
                        show(Progress(run.proposalId, run.done, run.total, nextAt = clock() + ms))
                        pause(ms)
                        ensureActive()
                        w.sync()
                    }
                    ensureActive()
                    if (!stillOurs()) throw CancellationException("the stake vote run was dropped")
                    // Audit 6 (M8): the positions voted as the store says now,
                    // not as this job read them: another job (a suspended
                    // cast that finished) may have voted one since.
                    if (item is PrivacyWallet.StakeVoteItem.Position &&
                        synchronized(w) { w.store.state.stakeVoteRun?.votedPositions?.contains(item.id) == true }
                    ) continue
                    // A position's vote is persisted the moment the node takes it
                    // (audit 4, L5), before the wait for its block: a run resumed
                    // after a suspend or a lost process never votes it again.
                    val accepted = { _: String ->
                        if (item is PrivacyWallet.StakeVoteItem.Position) synchronized(w) {
                            w.store.state.stakeVoteRun?.takeIf { it.proposalId == run.proposalId }?.let { cur ->
                                w.store.state.stakeVoteRun = cur.copy(votedPositions = cur.votedPositions + item.id); runCatching { w.store.save() }
                            }
                        }
                    }
                    // As the last sync left it: a note voted (or pending) or a position gone is skipped.
                    val hash = (if (first?.isCompleted == false && shownFee != null) PrivacyWallet.withShownFee(shownFee) { w.castStakeVote(run.proposalId, item, options, accepted) }
                        else w.castStakeVote(run.proposalId, item, options, accepted)) ?: continue
                    // Merged into the run as stored (audit 6, M8), never a stale copy written over it.
                    run = synchronized(w) {
                        val cur = w.store.state.stakeVoteRun?.takeIf { it.proposalId == run.proposalId } ?: run
                        val next = cur.copy(
                            done = cur.done + 1,
                            votedPositions = if (item is PrivacyWallet.StakeVoteItem.Position) cur.votedPositions + item.id else cur.votedPositions,
                        )
                        w.store.state.stakeVoteRun = next; w.store.save()
                        next
                    }
                    show(Progress(run.proposalId, run.done, run.total))
                    first?.complete(hash)
                }
                first?.completeExceptionally(IllegalStateException("nothing left to vote with"))
                show(Progress(run.proposalId, run.done, run.total, finished = true))
            } catch (e: CancellationException) {
                show(Progress(run.proposalId, run.done, run.total, cancelled = true))
                first?.completeExceptionally(e)
            } catch (e: Throwable) {
                // An Error too (audit 4, M7): the run fails, the app does not.
                show(Progress(run.proposalId, run.done, run.total, error = e.message ?: e.toString()))
                first?.completeExceptionally(e)
            } finally {
                // Finished, cancelled or failed: nothing to resume. Suspended: kept for resume.
                if (!suspendedFlag.get()) synchronized(w) {
                    if (w.store.state.stakeVoteRun?.proposalId == run.proposalId) { w.store.state.stakeVoteRun = null; runCatching { w.store.save() } }
                }
            }
        }
    }

    companion object {
        /** The random pause between casts. */
        const val VOTE_PAUSE_MIN_MS = 20_000L
        const val VOTE_PAUSE_MAX_MS = 120_000L

        private val RNG = java.security.SecureRandom()
    }
}
