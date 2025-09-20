package com.example.earthwallet.wallet.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences

/**
 * PinSecurityManager
 *
 * Handles PIN attempt tracking, rate limiting, and lockout mechanisms
 * to prevent brute force attacks on wallet PINs.
 */
object PinSecurityManager {

    private const val TAG = "PinSecurityManager"
    private const val PREF_FILE = "pin_security_prefs"

    // Lockout configuration
    private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 3
    private const val FIRST_LOCKOUT_DURATION_MS = 30_000L        // 30 seconds
    private const val SECOND_LOCKOUT_DURATION_MS = 300_000L      // 5 minutes
    private const val THIRD_LOCKOUT_DURATION_MS = 900_000L       // 15 minutes
    private const val EXTENDED_LOCKOUT_DURATION_MS = 3_600_000L  // 1 hour

    // Storage keys
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKOUT_COUNT = "lockout_count"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    private const val KEY_LAST_ATTEMPT_TIME = "last_attempt_time"

    data class PinSecurityStatus(
        val isLockedOut: Boolean,
        val remainingLockoutTimeMs: Long,
        val failedAttempts: Int,
        val canAttemptPin: Boolean,
        val lockoutMessage: String?
    )

    /**
     * Check if PIN entry is currently allowed
     */
    @Throws(Exception::class)
    fun checkPinSecurity(context: Context): PinSecurityStatus {
        val prefs = getSecurePrefs(context)
        val currentTime = System.currentTimeMillis()

        val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val lockoutCount = prefs.getInt(KEY_LOCKOUT_COUNT, 0)

        val isLockedOut = currentTime < lockoutUntil
        val remainingLockoutTime = if (isLockedOut) lockoutUntil - currentTime else 0L

        val lockoutMessage = if (isLockedOut) {
            formatLockoutMessage(remainingLockoutTime)
        } else null

        Log.d(TAG, "PIN security check - Failed attempts: $failedAttempts, Locked out: $isLockedOut")

        return PinSecurityStatus(
            isLockedOut = isLockedOut,
            remainingLockoutTimeMs = remainingLockoutTime,
            failedAttempts = failedAttempts,
            canAttemptPin = !isLockedOut,
            lockoutMessage = lockoutMessage
        )
    }

    /**
     * Record a failed PIN attempt and apply lockout if necessary
     */
    @Throws(Exception::class)
    fun recordFailedAttempt(context: Context): PinSecurityStatus {
        val prefs = getSecurePrefs(context)
        val currentTime = System.currentTimeMillis()

        val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val lockoutCount = prefs.getInt(KEY_LOCKOUT_COUNT, 0)

        Log.w(TAG, "Recording failed PIN attempt #$failedAttempts")

        val editor = prefs.edit()
        editor.putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
        editor.putLong(KEY_LAST_ATTEMPT_TIME, currentTime)

        // Apply lockout if threshold reached
        if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            val newLockoutCount = lockoutCount + 1
            val lockoutDuration = getLockoutDuration(newLockoutCount)
            val lockoutUntil = currentTime + lockoutDuration

            editor.putInt(KEY_LOCKOUT_COUNT, newLockoutCount)
            editor.putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            editor.putInt(KEY_FAILED_ATTEMPTS, 0) // Reset for next cycle

            Log.w(TAG, "PIN lockout triggered - Duration: ${lockoutDuration}ms, Count: $newLockoutCount")
        }

        editor.apply()

        return checkPinSecurity(context)
    }

    /**
     * Record a successful PIN entry - clears all lockout state
     */
    @Throws(Exception::class)
    fun recordSuccessfulAttempt(context: Context) {
        val prefs = getSecurePrefs(context)

        Log.i(TAG, "Recording successful PIN attempt - clearing all lockout state")

        val editor = prefs.edit()
        editor.putInt(KEY_FAILED_ATTEMPTS, 0)
        editor.putInt(KEY_LOCKOUT_COUNT, 0)
        editor.putLong(KEY_LOCKOUT_UNTIL, 0L)
        editor.putLong(KEY_LAST_ATTEMPT_TIME, System.currentTimeMillis())
        editor.apply()
    }

    /**
     * Reset all PIN security state (admin function)
     * Should only be called after biometric authentication or app reinstall
     */
    @Throws(Exception::class)
    fun resetPinSecurity(context: Context) {
        val prefs = getSecurePrefs(context)

        Log.i(TAG, "Resetting PIN security state (admin function)")

        val editor = prefs.edit()
        editor.clear()
        editor.apply()
    }

    /**
     * Get lockout duration based on lockout count (progressive delays)
     */
    private fun getLockoutDuration(lockoutCount: Int): Long {
        return when (lockoutCount) {
            1 -> FIRST_LOCKOUT_DURATION_MS
            2 -> SECOND_LOCKOUT_DURATION_MS
            3 -> THIRD_LOCKOUT_DURATION_MS
            else -> EXTENDED_LOCKOUT_DURATION_MS // 4+ attempts = 1 hour
        }
    }

    /**
     * Format lockout time remaining into user-friendly message
     */
    private fun formatLockoutMessage(remainingTimeMs: Long): String {
        val seconds = remainingTimeMs / 1000
        return when {
            seconds < 60 -> "Locked for ${seconds}s"
            seconds < 3600 -> {
                val minutes = seconds / 60
                "Locked for ${minutes}m"
            }
            else -> {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                if (minutes > 0) "Locked for ${hours}h ${minutes}m" else "Locked for ${hours}h"
            }
        }
    }

    /**
     * Get secure preferences for PIN security data
     */
    @Throws(Exception::class)
    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            SecurePreferencesUtil.createEncryptedPreferences(context, PREF_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create secure preferences for PIN security", e)
            throw Exception("Failed to initialize PIN security storage", e)
        }
    }

    /**
     * Get current lockout status for UI display
     */
    @Throws(Exception::class)
    fun getLockoutStatusMessage(context: Context): String? {
        val status = checkPinSecurity(context)
        return if (status.isLockedOut) {
            "Too many failed attempts. ${status.lockoutMessage}"
        } else if (status.failedAttempts > 0) {
            val remaining = MAX_ATTEMPTS_BEFORE_LOCKOUT - status.failedAttempts
            "Warning: $remaining attempts remaining before lockout"
        } else {
            null
        }
    }
}