package network.erth.wallet.privacy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.note.OwnedNote
import network.erth.wallet.privacy.sync.WalletSync
import java.security.SecureRandom

/**
 * What the wallet does on its own while it is unlocked (it needs the keys, so
 * never in the background):
 *
 *  - claims the day's ANML, at a random time of day chosen afresh each day,
 *    so a claim's timing says nothing about who made it;
 *  - refreshes the caretaker split before it lapses (it counts for R after
 *    each cast);
 *  - refreshes the referrer binding the same way;
 *  - claims unbonding notes once their epoch's undelegation has matured.
 *
 * Maturity is worked out from chain-wide timing alone (the current epoch,
 * epoch length, x/staking's unbonding time), never by asking the node about
 * a (validator, epoch) record: a query for the records this wallet holds,
 * repeated every pass, would tell the node which unbond notes are whose
 * long before any claim. If the record is not matured after all (its epoch's
 * undelegation was deferred), the claim fails before anything is spent and
 * waits [RETRY_S].
 *
 * [decide] is the pure part, unit-tested; [loop] runs it.
 */
object PrivacyAutomation {
    private const val TAG = "PrivacyAutomation"
    private const val INTERVAL_MS = 10 * 60 * 1000L
    /** Claims land within this many seconds after UTC midnight plus the day's draw. */
    const val CLAIM_WINDOW_S = 12 * 3600L

    sealed interface Action {
        data class ClaimAnml(val day: Long) : Action
        data object RefreshCaretaker : Action
        data object RefreshReferrer : Action
        data class ClaimUnbonding(val note: OwnedNote) : Action
    }

    data class Inputs(
        val now: Long,
        val identityLive: Boolean,
        val claimOpensAt: Long?,
        val claimedToday: Boolean,
        /** Seconds after UTC midnight today's claim waits for. */
        val claimOffset: Long,
        val caretakerDue: Boolean,
        val referrerDue: Boolean = false,
        val hasFeeErth: Boolean,
        val maturedUnbonds: List<OwnedNote>,
    )

    fun decide(i: Inputs): List<Action> {
        val out = ArrayList<Action>()
        val day = i.now / PrivacyWallet.SECONDS_PER_DAY
        if (i.identityLive && i.hasFeeErth && !i.claimedToday && i.claimOpensAt == 0L &&
            i.now - day * PrivacyWallet.SECONDS_PER_DAY >= i.claimOffset
        ) out.add(Action.ClaimAnml(day))
        if (i.identityLive && i.hasFeeErth && i.caretakerDue) out.add(Action.RefreshCaretaker)
        if (i.identityLive && i.hasFeeErth && i.referrerDue) out.add(Action.RefreshReferrer)
        // Fee from output: needs no fee note.
        i.maturedUnbonds.forEach { out.add(Action.ClaimUnbonding(it)) }
        return out
    }

    /** Slack past the computed completion for the block that completes it. */
    const val MATURITY_MARGIN_S = 15 * 60L
    /** How long a claim the chain refused waits before trying again. */
    const val RETRY_S = 6 * 3600L

    /**
     * The latest moment epoch [e]'s undelegation can complete. Epoch e ends
     * in the block that starts e+1, and each epoch lasts at least
     * [epochSeconds], so end(e) <= start(current) - (current - 1 - e) x
     * epochSeconds; the SDK entry completes unbondingSeconds after that.
     * Null while e has not ended.
     */
    fun maturesBy(e: Long, current: Long, currentStart: Long, epochSeconds: Long, unbondingSeconds: Long): Long? {
        if (e >= current) return null
        return currentStart - (current - 1 - e) * epochSeconds + unbondingSeconds + MATURITY_MARGIN_S
    }

    /** The unbond notes to claim now: matured by [maturesBy], and not waiting out a refused claim. */
    fun matured(
        notes: List<OwnedNote>,
        now: Long,
        current: Long,
        currentStart: Long,
        epochSeconds: Long,
        unbondingSeconds: Long,
        retryAt: Map<String, Long>,
    ): List<OwnedNote> = notes.filter { n ->
        if (!n.unspent || n.pendingAt != null || !n.note.denom.startsWith("unbond/")) return@filter false
        val (_, e) = PrivacyWallet.parseUnbond(n.note.denom)
        val by = maturesBy(e, current, currentStart, epochSeconds, unbondingSeconds) ?: return@filter false
        now >= by && now >= (retryAt[n.note.denom] ?: 0L)
    }

    private val rng = SecureRandom()
    private var offsetDay = -1L
    private var offset = 0L

    /** Today's random claim offset, drawn once per UTC day. */
    @Synchronized
    fun claimOffset(now: Long): Long {
        val day = now / PrivacyWallet.SECONDS_PER_DAY
        if (day != offsetDay) { offsetDay = day; offset = (rng.nextDouble() * CLAIM_WINDOW_S).toLong() }
        return offset
    }

    /** One pass. Blocking; IO thread. */
    fun runOnce(context: Context) {
        val w = PrivacySession.wallet(context)
        w.sync()
        val now = System.currentTimeMillis() / 1000
        // Global reads only: the same for every wallet.
        val epoch = PrivacyQueries.epoch()
        val timing = PrivacyQueries.stakingTiming()
        val matured = matured(w.notes, now, epoch.number, epoch.startTime, timing.epochSeconds, timing.unbondingSeconds,
            w.store.state.unbondRetryAt)
        val inputs = Inputs(
            now = now,
            identityLive = w.identityStatus() == WalletSync.IdentityStatus.LIVE,
            claimOpensAt = runCatching { w.claimOpensAt() }.getOrNull(),
            claimedToday = w.claimedToday(),
            claimOffset = claimOffset(now),
            caretakerDue = runCatching { w.caretakerDue() }.getOrDefault(false),
            referrerDue = runCatching { w.referrerDue() }.getOrDefault(false),
            hasFeeErth = (w.balances()["uerth"] ?: 0L) > 0,
            maturedUnbonds = matured,
        )
        for (a in decide(inputs)) {
            runCatching {
                when (a) {
                    is Action.ClaimAnml -> w.claimAnml(a.day)
                    Action.RefreshCaretaker -> w.setCaretaker(w.store.state.caretakerSplit)
                    Action.RefreshReferrer -> w.bindReferrer(w.store.state.referrerAddress)
                    is Action.ClaimUnbonding -> w.claimUnbonding(a.note)
                }
            }.onFailure {
                Log.w(TAG, "automation $a failed", it)
                if (a is Action.ClaimUnbonding) {
                    w.store.state.unbondRetryAt[a.note.note.denom] = now + RETRY_S
                    w.store.save()
                }
            }
        }
    }

    /** Runs [runOnce] every ten minutes until cancelled (scope it to the unlocked session). */
    suspend fun loop(context: Context) {
        while (coroutineContext.isActive) {
            runCatching { runOnce(context) }.onFailure { Log.w(TAG, "automation pass failed", it) }
            delay(INTERVAL_MS)
        }
    }
}
