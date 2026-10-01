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
 *  - claims unbonding notes once their epoch's undelegation has matured.
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
        // Fee from output: needs no fee note.
        i.maturedUnbonds.forEach { out.add(Action.ClaimUnbonding(it)) }
        return out
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
        val matured = w.notes.filter { it.unspent && it.pendingAt == null && it.note.denom.startsWith("unbond/") }.filter { n ->
            val (v, e) = PrivacyWallet.parseUnbond(n.note.denom)
            // Asks about a (validator, epoch) record that every unstaker of
            // that epoch shares; it names no note.
            runCatching { PrivacyQueries.unbondRecord(v, e).status == "UNBOND_STATUS_MATURED" }.getOrDefault(false)
        }
        val inputs = Inputs(
            now = now,
            identityLive = w.identityStatus() == WalletSync.IdentityStatus.LIVE,
            claimOpensAt = runCatching { w.claimOpensAt() }.getOrNull(),
            claimedToday = w.claimedToday(),
            claimOffset = claimOffset(now),
            caretakerDue = runCatching { w.caretakerDue() }.getOrDefault(false),
            hasFeeErth = (w.balances()["uerth"] ?: 0L) > 0,
            maturedUnbonds = matured,
        )
        for (a in decide(inputs)) {
            runCatching {
                when (a) {
                    is Action.ClaimAnml -> w.claimAnml(a.day)
                    Action.RefreshCaretaker -> w.setCaretaker(w.store.state.caretakerSplit)
                    is Action.ClaimUnbonding -> w.claimUnbonding(a.note)
                }
            }.onFailure { Log.w(TAG, "automation $a failed", it) }
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
