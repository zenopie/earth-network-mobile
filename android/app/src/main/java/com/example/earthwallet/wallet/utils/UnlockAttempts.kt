package network.erth.wallet.wallet.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/**
 * Brute-force backoff for the unlock screen.
 *
 * Separate from [PinSecurityManager] because that one cannot work here:
 * it keeps its counter in preferences encrypted by the session key, and the
 * session key is what the PIN produces. Reading it before unlocking throws,
 * which is why the old PIN fragment never called it and had no limit at all.
 *
 * Plain SharedPreferences is the right store for this. A failed-attempt count
 * is not a secret — it is a counter — and encrypting it with the key it is
 * meant to protect buys nothing. The threat this defends against is someone
 * who has picked up an unlocked-screen phone and is guessing four digits;
 * against an attacker with root neither store helps, because they can clear
 * either one.
 *
 * The schedule matches PinSecurityManager's so the two behave alike: three
 * attempts, then 30s, 5m, 15m, and an hour thereafter.
 *
 * Timed on [SystemClock.elapsedRealtime], not the wall clock. The deadline
 * used to be wall-clock, so setting the date forward in Settings ended the
 * lockout, and the backoff with it. elapsedRealtime cannot be set and counts
 * through sleep, but restarts at zero on boot; so the deadline is stored with
 * the boot it belongs to, and a reboot restarts the lockout in force rather
 * than ending it. Same design as the iOS app's UnlockAttempts.
 */
object UnlockAttempts {

    private const val PREF_FILE = "unlock_attempts"
    private const val KEY_FAILED = "failed"
    private const val KEY_LOCKOUTS = "lockouts"
    // "until" held a wall-clock deadline; these replace it.
    private const val KEY_LEGACY_UNTIL = "until"
    private const val KEY_DEADLINE = "deadline_elapsed"
    private const val KEY_WAIT = "wait"
    private const val KEY_BOOT = "boot"

    private const val MAX_ATTEMPTS = 3
    private val BACKOFF_MS = longArrayOf(30_000, 300_000, 900_000)
    private const val EXTENDED_MS = 3_600_000L

    data class Status(
        val lockedOut: Boolean,
        val attemptsLeft: Int,
        /** Non-null only while locked out; ready to show as-is. */
        val message: String?,
    )

    fun status(context: Context): Status {
        val prefs = prefs(context)
        migrate(prefs)
        var deadline = prefs.getLong(KEY_DEADLINE, 0L)

        if (deadline != 0L) {
            val now = SystemClock.elapsedRealtime()
            val wait = prefs.getLong(KEY_WAIT, 0L)
            // A different boot, or more time left than the lockout ever had:
            // either way the clock this deadline was set on is gone.
            if (prefs.getInt(KEY_BOOT, -1) != bootCount(context) || deadline - now > wait) {
                deadline = now + wait
                prefs.edit()
                    .putLong(KEY_DEADLINE, deadline)
                    .putInt(KEY_BOOT, bootCount(context))
                    .apply()
            }

            val remaining = deadline - now
            if (remaining > 0) {
                return Status(
                    lockedOut = true,
                    attemptsLeft = 0,
                    message = "Too many attempts. Try again in ${remaining.asDuration()}.",
                )
            }

            // The window elapsed. Clear it so the next wrong PIN starts a fresh
            // count rather than tripping the lockout again immediately.
            prefs.edit().remove(KEY_DEADLINE).remove(KEY_WAIT).putInt(KEY_FAILED, 0).apply()
        }

        return Status(
            lockedOut = false,
            attemptsLeft = MAX_ATTEMPTS - prefs.getInt(KEY_FAILED, 0),
            message = null,
        )
    }

    fun recordFailure(context: Context): Status {
        val prefs = prefs(context)
        migrate(prefs)
        val failed = prefs.getInt(KEY_FAILED, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED, failed)

        if (failed >= MAX_ATTEMPTS) {
            val lockouts = prefs.getInt(KEY_LOCKOUTS, 0)
            val duration = BACKOFF_MS.getOrElse(lockouts) { EXTENDED_MS }
            editor
                .putInt(KEY_FAILED, 0)
                .putInt(KEY_LOCKOUTS, lockouts + 1)
                .putLong(KEY_WAIT, duration)
                .putLong(KEY_DEADLINE, SystemClock.elapsedRealtime() + duration)
                .putInt(KEY_BOOT, bootCount(context))
        }

        // commit, not apply: the count has to be on disk before the process can
        // be killed, or force-stopping after each guess would reset it.
        editor.commit()
        return status(context)
    }

    fun recordSuccess(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Which boot this is. -1 if the platform will not say, in which case a
     * reboot is still caught by the deadline landing further off than the
     * lockout's own length.
     */
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Carries a wall-clock lockout from an older version over, once. */
    private fun migrate(prefs: SharedPreferences) {
        if (!prefs.contains(KEY_LEGACY_UNTIL)) return
        val left = prefs.getLong(KEY_LEGACY_UNTIL, 0L) - System.currentTimeMillis()
        val editor = prefs.edit().remove(KEY_LEGACY_UNTIL)
        if (left > 0) {
            val wait = minOf(left, EXTENDED_MS)
            editor
                .putLong(KEY_WAIT, wait)
                .putLong(KEY_DEADLINE, SystemClock.elapsedRealtime() + wait)
                .putInt(KEY_BOOT, -2) // restarted on first read, with the real boot count
        }
        editor.commit()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private fun Long.asDuration(): String {
        val seconds = this / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
