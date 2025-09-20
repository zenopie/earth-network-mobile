package com.example.earthwallet.wallet.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePreferencesUtil
 *
 * Utility for creating encrypted shared preferences using the modern MasterKey API
 * instead of the deprecated MasterKeys class.
 */
object SecurePreferencesUtil {

    /**
     * Create encrypted shared preferences using modern MasterKey API
     */
    @Throws(Exception::class)
    fun createEncryptedPreferences(
        context: Context,
        fileName: String
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}