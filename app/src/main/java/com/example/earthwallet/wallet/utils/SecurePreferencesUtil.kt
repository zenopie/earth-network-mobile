package com.example.earthwallet.wallet.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.earthwallet.wallet.services.SessionManager

/**
 * SecurePreferencesUtil
 *
 * Utility for creating session-based secure preferences.
 * All encryption now uses PIN-based SessionManager.
 */
object SecurePreferencesUtil {

    private const val TAG = "SecurePreferencesUtil"

    /**
     * Create session-based secure preferences
     * Requires an active session with PIN
     */
    @Throws(Exception::class)
    fun createEncryptedPreferences(
        context: Context,
        fileName: String
    ): SharedPreferences {
        if (!SessionManager.isSessionActive()) {
            throw IllegalStateException("No active session - call SessionManager.startSession() first")
        }
        return SessionManager.createSessionPreferences(context)
    }
}