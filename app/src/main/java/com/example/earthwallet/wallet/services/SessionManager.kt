package com.example.earthwallet.wallet.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.earthwallet.wallet.utils.SoftwareEncryption
import com.example.earthwallet.wallet.utils.SecurePreferencesUtil
import com.example.earthwallet.wallet.utils.WalletStorageVersion
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

/**
 * SessionManager
 *
 * Manages session-based decryption where wallet data is decrypted once at startup
 * and kept in memory for the duration of the app session. Handles re-encryption
 * when data changes during the session.
 */
object SessionManager {

    private const val TAG = "SessionManager"
    private const val PREF_FILE = "secret_wallet_prefs"

    // Session state
    private var isSessionActive = false
    private var sessionPin: String? = null
    private var versionedWalletStorage: WalletStorageVersion.VersionedWalletStorage? = null
    private var otherPrefsData = mutableMapOf<String, Any?>()

    /**
     * Start a new session by decrypting wallet data with PIN
     */
    @Throws(Exception::class)
    fun startSession(context: Context, pin: String) {
        try {

            if (!SoftwareEncryption.isAvailable()) {
                throw Exception("Software encryption not available")
            }

            // Store PIN for session
            sessionPin = pin

            // Load and decrypt wallet data
            val softwarePrefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)

            // Load encrypted wallet data
            val encryptedWalletsJson = softwarePrefs.getString("wallets_encrypted", null)
            if (encryptedWalletsJson != null) {
                val encryptedData = parseSoftwareEncryptedData(encryptedWalletsJson)
                val decryptedStorageJson = SoftwareEncryption.decrypt(encryptedData, pin, context)

                // Parse with versioning support
                versionedWalletStorage = WalletStorageVersion.parseWalletStorage(decryptedStorageJson)
            } else {
                versionedWalletStorage = WalletStorageVersion.createVersionedStorage(JSONArray())
            }

            // Load other preferences data (non-encrypted)
            loadOtherPrefsData(softwarePrefs)

            isSessionActive = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            clearSession()
            throw Exception("Failed to start session: ${e.message}", e)
        }
    }

    /**
     * End the current session and clear sensitive data from memory
     */
    fun endSession() {
        clearSession()
    }

    /**
     * Check if session is active
     */
    fun isSessionActive(): Boolean {
        return isSessionActive
    }

    /**
     * Get decrypted wallet data (requires active session)
     */
    @Throws(Exception::class)
    fun getWalletData(): String {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }
        val storage = versionedWalletStorage ?: throw IllegalStateException("No wallet storage available")
        return WalletStorageVersion.getWalletsArray(storage).toString()
    }

    /**
     * Update wallet data and re-encrypt to storage
     */
    @Throws(Exception::class)
    fun updateWalletData(context: Context, newWalletData: String) {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }

        val pin = sessionPin ?: throw IllegalStateException("Session PIN not available")

        try {
            // Parse new wallet data as JSONArray and update versioned storage
            val newWalletsArray = JSONArray(newWalletData)
            val currentStorage = versionedWalletStorage ?: throw IllegalStateException("No wallet storage available")
            versionedWalletStorage = WalletStorageVersion.updateWallets(currentStorage, newWalletsArray)

            // Save to encrypted storage
            saveVersionedStorageToEncryption(context, pin)


        } catch (e: Exception) {
            Log.e(TAG, "Failed to update wallet data", e)
            throw Exception("Failed to update wallet data: ${e.message}", e)
        }
    }

    /**
     * Get other preferences data (non-wallet data)
     */
    fun getPrefsData(): Map<String, Any?> {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }
        return otherPrefsData.toMap()
    }

    /**
     * Update other preferences data
     */
    @Throws(Exception::class)
    fun updatePrefsData(context: Context, key: String, value: Any?) {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }

        try {
            // Update in-memory data
            otherPrefsData[key] = value

            // Save to storage
            val softwarePrefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)
            val editor = softwarePrefs.edit()

            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Long -> editor.putLong(key, value)
                null -> editor.remove(key)
                else -> throw IllegalArgumentException("Unsupported value type: ${value::class.java}")
            }

            editor.apply()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preferences data for key: $key", e)
            throw Exception("Failed to update preferences data: ${e.message}", e)
        }
    }

    /**
     * Create a session-aware SharedPreferences proxy
     */
    @Throws(Exception::class)
    fun createSessionPreferences(context: Context): SharedPreferences {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }
        return SessionSharedPreferences(this, context)
    }

    /**
     * Clear sensitive session data from memory
     */
    private fun clearSession() {
        sessionPin?.let { pin ->
            // Clear PIN from memory
            pin.toCharArray().fill('\u0000')
        }
        sessionPin = null

        versionedWalletStorage = null
        otherPrefsData.clear()
        isSessionActive = false

    }

    /**
     * Load non-encrypted preferences data
     */
    private fun loadOtherPrefsData(softwarePrefs: SharedPreferences) {
        otherPrefsData.clear()

        val allPrefs = softwarePrefs.all
        for ((key, value) in allPrefs) {
            // Skip encrypted wallet data
            if (key != "wallets_encrypted") {
                otherPrefsData[key] = value
            }
        }

    }

    /**
     * Parse software encrypted data from JSON string
     */
    @Throws(Exception::class)
    private fun parseSoftwareEncryptedData(jsonString: String): SoftwareEncryption.EncryptedData {
        return try {
            val json = JSONObject(jsonString)
            SoftwareEncryption.EncryptedData(
                ciphertext = Base64.decode(json.getString("ciphertext"), Base64.DEFAULT),
                iv = Base64.decode(json.getString("iv"), Base64.DEFAULT),
                salt = Base64.decode(json.getString("salt"), Base64.DEFAULT)
            )
        } catch (e: Exception) {
            throw Exception("Failed to parse software encrypted data", e)
        }
    }

    /**
     * Save versioned wallet storage to encrypted storage
     */
    @Throws(Exception::class)
    private fun saveVersionedStorageToEncryption(context: Context, pin: String) {
        val storage = versionedWalletStorage ?: throw IllegalStateException("No wallet storage available")

        // Serialize versioned storage to JSON
        val storageJson = WalletStorageVersion.serializeWalletStorage(storage)

        // Encrypt and save
        val encryptedData = SoftwareEncryption.encrypt(storageJson, pin, context)
        val json = JSONObject().apply {
            put("ciphertext", Base64.encodeToString(encryptedData.ciphertext, Base64.DEFAULT))
            put("iv", Base64.encodeToString(encryptedData.iv, Base64.DEFAULT))
            put("salt", Base64.encodeToString(encryptedData.salt, Base64.DEFAULT))
        }

        val softwarePrefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)
        softwarePrefs.edit().putString("wallets_encrypted", json.toString()).apply()

    }
}

/**
 * Session-aware SharedPreferences implementation
 */
private class SessionSharedPreferences(
    private val sessionManager: SessionManager,
    private val context: Context
) : SharedPreferences {

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue

        return try {
            if (key == "wallets") {
                sessionManager.getWalletData()
            } else {
                val prefsData = sessionManager.getPrefsData()
                prefsData[key] as? String ?: defValue
            }
        } catch (e: Exception) {
            Log.e("SessionSharedPreferences", "Failed to get string for key: $key", e)
            defValue
        }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key == null) return defValue

        return try {
            val prefsData = sessionManager.getPrefsData()
            prefsData[key] as? Int ?: defValue
        } catch (e: Exception) {
            Log.e("SessionSharedPreferences", "Failed to get int for key: $key", e)
            defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null) return defValue

        return try {
            val prefsData = sessionManager.getPrefsData()
            prefsData[key] as? Boolean ?: defValue
        } catch (e: Exception) {
            Log.e("SessionSharedPreferences", "Failed to get boolean for key: $key", e)
            defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key == null) return defValue

        return try {
            val prefsData = sessionManager.getPrefsData()
            prefsData[key] as? Float ?: defValue
        } catch (e: Exception) {
            Log.e("SessionSharedPreferences", "Failed to get float for key: $key", e)
            defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key == null) return defValue

        return try {
            val prefsData = sessionManager.getPrefsData()
            prefsData[key] as? Long ?: defValue
        } catch (e: Exception) {
            Log.e("SessionSharedPreferences", "Failed to get long for key: $key", e)
            defValue
        }
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        // String sets not commonly used in wallet storage, but can be implemented if needed
        return defValues
    }

    override fun contains(key: String?): Boolean {
        if (key == null) return false

        return try {
            if (key == "wallets") {
                sessionManager.getWalletData().isNotEmpty()
            } else {
                val prefsData = sessionManager.getPrefsData()
                prefsData.containsKey(key)
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun edit(): SharedPreferences.Editor {
        return SessionEditor(sessionManager, context)
    }

    override fun getAll(): MutableMap<String, *> {
        return try {
            val result = mutableMapOf<String, Any?>()
            result["wallets"] = sessionManager.getWalletData()
            result.putAll(sessionManager.getPrefsData())
            result
        } catch (e: Exception) {
            mutableMapOf<String, Any?>()
        }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        // Session-based preferences don't support change listeners
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        // Session-based preferences don't support change listeners
    }
}

/**
 * Session-aware SharedPreferences.Editor implementation
 */
private class SessionEditor(
    private val sessionManager: SessionManager,
    private val context: Context
) : SharedPreferences.Editor {

    private val pendingChanges = mutableMapOf<String, Any?>()

    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
        if (key != null) {
            pendingChanges[key] = value
        }
        return this
    }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
        if (key != null) {
            pendingChanges[key] = value
        }
        return this
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
        if (key != null) {
            pendingChanges[key] = value
        }
        return this
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
        if (key != null) {
            pendingChanges[key] = value
        }
        return this
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
        if (key != null) {
            pendingChanges[key] = value
        }
        return this
    }

    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
        if (key != null) {
            pendingChanges[key] = values
        }
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        if (key != null) {
            pendingChanges[key] = null
        }
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        // Mark all keys for removal
        try {
            val allData = sessionManager.getPrefsData()
            for (key in allData.keys) {
                pendingChanges[key] = null
            }
            pendingChanges["wallets"] = null
        } catch (e: Exception) {
            Log.e("SessionEditor", "Failed to clear preferences", e)
        }
        return this
    }

    override fun commit(): Boolean {
        return try {
            applyChanges()
            true
        } catch (e: Exception) {
            Log.e("SessionEditor", "Failed to commit changes", e)
            false
        }
    }

    override fun apply() {
        try {
            applyChanges()
        } catch (e: Exception) {
            Log.e("SessionEditor", "Failed to apply changes", e)
        }
    }

    private fun applyChanges() {
        for ((key, value) in pendingChanges) {
            if (key == "wallets") {
                if (value != null) {
                    sessionManager.updateWalletData(context, value as String)
                }
            } else {
                sessionManager.updatePrefsData(context, key, value)
            }
        }
        pendingChanges.clear()
    }
}