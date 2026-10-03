package network.erth.wallet.privacy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.sync.WalletSync
import java.security.SecureRandom

/**
 * The one thing the wallet does on its own while it is unlocked (it needs the
 * keys, so never in the background): it completes an undelegation the user
 * started, claiming the unbonding claim (stake note) once its epoch's
 * undelegation has matured. Its fee comes out of what it claims.
 *
 * Nothing else spends a fee unasked: the day's ANML claim, the caretaker
 * vote's refresh and the handle's renewal are the user's to make, and
 * [Reminders] says when each is due.
 *
 * Maturity is worked out from chain-wide timing alone (the current epoch,
 * epoch length, x/staking's unbonding time), never by asking the node about
 * a (validator, epoch) record: a query for the records this wallet holds,
 * repeated every pass, would tell the node which unbond notes are whose
 * long before any claim. If the record is not matured after all (its epoch's
 * undelegation was deferred), the claim fails before anything is spent and
 * waits [RETRY_S].
 *
 * Claims are never made in one burst (audit 3): one at a time, chosen at
 * random among those due, with a random pause and a full sync between each
 * and a fresh decision after it. Logs name the kind of action only, never a
 * denom.
 *
 * [decide] is the pure part, unit-tested; [runPass] runs one pass; [loop] runs passes.
 */
object PrivacyAutomation {
    private const val TAG = "PrivacyAutomation"
    private const val INTERVAL_MS = 10 * 60 * 1000L
    /** The only automatic action: completing an undelegation the user started. */
    sealed interface Action {
        data class ClaimUnbonding(val denom: String) : Action
    }

    data class Inputs(
        val now: Long,
        /** unbond/<valoper>/<epoch> denoms whose claims have matured. */
        val maturedUnbonds: List<String>,
    )

    fun decide(i: Inputs): List<Action> =
        // Fee from output: needs no fee note.
        i.maturedUnbonds.map { Action.ClaimUnbonding(it) }

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
        if (e >= current || e < 0) return null
        // Chain-supplied numbers: any overflow means no answer, never a wrapped one (audit 3).
        return try {
            Math.addExact(Math.addExact(Math.subtractExact(currentStart, Math.multiplyExact(current - 1 - e, epochSeconds)), unbondingSeconds), MATURITY_MARGIN_S)
        } catch (x: ArithmeticException) {
            null
        }
    }

    /** The unbond denoms to claim now: matured by [maturesBy], and not waiting out a refused claim. */
    fun matured(
        notes: List<OwnedStakeNote>,
        now: Long,
        current: Long,
        currentStart: Long,
        epochSeconds: Long,
        unbondingSeconds: Long,
        retryAt: Map<String, Long>,
    ): List<String> = notes.filter { n ->
        if (!n.spendable || !n.denom.startsWith(PrivacyWallet.UNBOND_PREFIX)) return@filter false
        val e = runCatching { PrivacyWallet.parseUnbond(n.denom).second }.getOrNull() ?: return@filter false
        val by = maturesBy(e, current, currentStart, epochSeconds, unbondingSeconds) ?: return@filter false
        now >= by && now >= (retryAt[n.denom] ?: 0L)
    }.map { it.denom }.distinct()

    private val rng = SecureRandom()

    /** The random pause between two automated actions in one pass. */
    const val ACTION_PAUSE_MIN_MS = 30_000L
    const val ACTION_PAUSE_MAX_MS = 180_000L

    /** The kind of [a], for logs: never its denom. */
    fun kind(a: Action): String = a::class.java.simpleName

    /**
     * One pass: a [sync], then while anything is due, one action
     * chosen at random among those due ([inputs] is read afresh each time),
     * and before the next a random pause and a full sync. Each action is
     * tried at most once a pass. Returns the actions taken, in order.
     */
    suspend fun runPass(
        sync: () -> Unit,
        inputs: () -> Inputs,
        act: (Action) -> Unit,
        pause: suspend (Long) -> Unit,
        onFailure: (Action, Throwable) -> Unit = { _, _ -> },
        random: java.util.Random = rng,
    ): List<Action> {
        sync()
        val attempted = ArrayList<Action>()
        var acted = false
        // Whether more than the last action was due when it was chosen.
        var othersDue = false
        while (true) {
            val due = decide(inputs()).filterNot { it in attempted }
            if (acted) {
                // Never two actions without a pause and a sync between; a
                // pause too when another was due (it may be waiting for the
                // last one's change to land).
                if (due.isEmpty() && !othersDue) return attempted
                pause(ACTION_PAUSE_MIN_MS + (random.nextDouble() * (ACTION_PAUSE_MAX_MS - ACTION_PAUSE_MIN_MS)).toLong())
                sync()
                acted = false
                continue
            }
            if (due.isEmpty()) return attempted
            val a = due[random.nextInt(due.size)]
            attempted.add(a)
            acted = true
            othersDue = due.size > 1
            try {
                act(a)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Audit 4 (M7): an Error too (a hostile response's
                // StackOverflowError): the action fails, the app does not.
                onFailure(a, e)
            }
        }
    }

    /** One pass on the selected wallet. Suspends through its pauses; blocking calls, so an IO dispatcher. */
    suspend fun runOnce(context: Context) {
        val w = PrivacySession.wallet(context)
        // Global reads only: the same for every wallet.
        val epoch = PrivacyQueries.epoch()
        val timing = PrivacyQueries.stakingTiming()
        fun inputs(): Inputs {
            val now = System.currentTimeMillis() / 1000
            return Inputs(
                now = now,
                maturedUnbonds = matured(w.stakeNotes, now, epoch.number, epoch.startTime, timing.epochSeconds, timing.unbondingSeconds,
                    w.store.state.unbondRetryAt),
            )
        }
        runPass(
            { w.sync() }, ::inputs,
            act = { a ->
                w.claimUnbonding((a as Action.ClaimUnbonding).denom)
            },
            pause = { delay(it) },
            onFailure = { a, e ->
                Log.w(TAG, "automation ${kind(a)} failed: ${e.javaClass.simpleName}")
                val denom = (a as Action.ClaimUnbonding).denom
                synchronized(w) { w.store.state.unbondRetryAt[denom] = System.currentTimeMillis() / 1000 + RETRY_S; runCatching { w.store.save() } }
            },
        )
    }

    /** Runs [runOnce] every ten minutes until cancelled (scope it to the unlocked session). */
    suspend fun loop(context: Context) {
        while (coroutineContext.isActive) {
            try {
                runOnce(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Audit 4 (M7): an Error escaping here would end the coroutine and crash the app on every unlock.
                Log.w(TAG, "automation pass failed: ${e.javaClass.simpleName}")
            }
            delay(INTERVAL_MS)
        }
    }
}
