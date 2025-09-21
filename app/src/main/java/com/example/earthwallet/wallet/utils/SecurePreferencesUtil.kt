package com.example.earthwallet.wallet.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * SecurePreferencesUtil
 *
 * Utility for creating hardware-backed secure preferences using HardwareKeyManager
 * with TEE security and automatic key rotation. Replaces EncryptedSharedPreferences.
 */
object SecurePreferencesUtil {

    private const val TAG = "SecurePreferencesUtil"

    /**
     * Create hardware-backed secure preferences using HardwareKeyManager
     */
    @Throws(Exception::class)
    fun createEncryptedPreferences(
        context: Context,
        fileName: String
    ): SharedPreferences {
        return HardwareSecurePreferences(context, fileName)
    }

    /**
     * Hardware-backed secure preferences implementation
     */
    private class HardwareSecurePreferences(
        private val context: Context,
        fileName: String
    ) : SharedPreferences {

        private val plainPrefs: SharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

        companion object {
            private const val ENCRYPTED_SUFFIX = "_hw_encrypted"
        }

        init {
            // Initialize hardware encryption key
            try {
                HardwareKeyManager.initializeEncryptionKey()
                Log.d(TAG, "Hardware-backed preferences initialized for: $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize hardware encryption", e)
                throw SecurityException("Hardware encryption initialization failed", e)
            }
        }

        override fun getString(key: String, defValue: String?): String? {
            return try {
                val encryptedDataJson = plainPrefs.getString(key + ENCRYPTED_SUFFIX, null)
                    ?: return defValue

                // Parse and decrypt
                val jsonObj = JSONObject(encryptedDataJson)
                val encryptedData = HardwareKeyManager.EncryptedData(
                    ciphertext = Base64.decode(jsonObj.getString("ciphertext"), Base64.DEFAULT),
                    iv = Base64.decode(jsonObj.getString("iv"), Base64.DEFAULT),
                    keyAlias = jsonObj.getString("keyAlias"),
                    encryptionTimestamp = jsonObj.getLong("timestamp")
                )

                // Decrypt without key rotation
                HardwareKeyManager.decryptWithHardwareKey(encryptedData, context)
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed for key: $key", e)
                defValue
            }
        }

        override fun getInt(key: String, defValue: Int): Int {
            return getString(key, defValue.toString())?.toIntOrNull() ?: defValue
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            return getString(key, defValue.toString())?.toBooleanStrictOrNull() ?: defValue
        }

        override fun getLong(key: String, defValue: Long): Long {
            return getString(key, defValue.toString())?.toLongOrNull() ?: defValue
        }

        override fun getFloat(key: String, defValue: Float): Float {
            return getString(key, defValue.toString())?.toFloatOrNull() ?: defValue
        }

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            // Not implemented for security reasons - use JSON strings instead
            return defValues
        }

        override fun getAll(): MutableMap<String, *> {
            // Return empty map for security - enumerate keys individually
            return mutableMapOf<String, Any>()
        }

        override fun contains(key: String): Boolean {
            return plainPrefs.contains(key + ENCRYPTED_SUFFIX)
        }

        override fun edit(): SharedPreferences.Editor {
            return HardwareEditor(this)
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            plainPrefs.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            plainPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }

        private inner class HardwareEditor(private val prefs: HardwareSecurePreferences) : SharedPreferences.Editor {
            private val operations = mutableMapOf<String, Any?>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                operations[key] = value
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                operations[key] = value.toString()
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                operations[key] = value.toString()
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                operations[key] = value.toString()
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                operations[key] = value.toString()
                return this
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                // Not implemented for security
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                operations[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                operations.clear()
                plainPrefs.edit().clear().apply()
                return this
            }

            override fun commit(): Boolean {
                return try {
                    apply()
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override fun apply() {
                val editor = plainPrefs.edit()

                for ((key, value) in operations) {
                    if (value == null) {
                        editor.remove(key + ENCRYPTED_SUFFIX)
                    } else {
                        try {
                            val encryptedData = HardwareKeyManager.encryptWithHardwareKey(value.toString(), context)
                            val json = JSONObject().apply {
                                put("ciphertext", Base64.encodeToString(encryptedData.ciphertext, Base64.DEFAULT))
                                put("iv", Base64.encodeToString(encryptedData.iv, Base64.DEFAULT))
                                put("keyAlias", encryptedData.keyAlias)
                                put("timestamp", encryptedData.encryptionTimestamp)
                            }
                            editor.putString(key + ENCRYPTED_SUFFIX, json.toString())
                        } catch (e: Exception) {
                            Log.e(TAG, "Encryption failed for key: $key", e)
                        }
                    }
                }

                editor.apply()
                operations.clear()
            }
        }
    }
}