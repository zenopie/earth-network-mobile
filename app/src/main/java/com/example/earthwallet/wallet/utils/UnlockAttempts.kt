package network.erth.wallet.wallet.utils

import android.content.Context
import android.content.SharedPreferences

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
 */
object UnlockAttempts {

    private const val PREF_FILE = "unlock_attempts"
    private const val KEY_FAILED = "failed"
    private const val KEY_LOCKOUTS = "lockouts"
    private const val KEY_UNTIL = "until"

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
        val until = prefs.getLong(KEY_UNTIL, 0L)
        val remaining = until - System.currentTimeMillis()

        if (remaining > 0) {
            return Status(
                lockedOut = true,
                attemptsLeft = 0,
                message = "Too many attempts. Try again in ${remaining.asDuration()}.",
            )
        }

        // The window elapsed. Clear it so the next wrong PIN starts a fresh
        // count rather than tripping the lockout again immediately.
        if (until != 0L) {
            prefs.edit().remove(KEY_UNTIL).putInt(KEY_FAILED, 0).apply()
        }

        return Status(
            lockedOut = false,
            attemptsLeft = MAX_ATTEMPTS - prefs.getInt(KEY_FAILED, 0),
            message = null,
        )
    }

    fun recordFailure(context: Context): Status {
        val prefs = prefs(context)
        val failed = prefs.getInt(KEY_FAILED, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED, failed)

        if (failed >= MAX_ATTEMPTS) {
            val lockouts = prefs.getInt(KEY_LOCKOUTS, 0)
            val duration = BACKOFF_MS.getOrElse(lockouts) { EXTENDED_MS }
            editor
                .putInt(KEY_FAILED, 0)
                .putInt(KEY_LOCKOUTS, lockouts + 1)
                .putLong(KEY_UNTIL, System.currentTimeMillis() + duration)
        }

        editor.apply()
        return status(context)
    }

    fun recordSuccess(context: Context) {
        prefs(context).edit().clear().apply()
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
