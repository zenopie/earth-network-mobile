package network.erth.wallet.wallet.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import network.erth.wallet.wallet.utils.DeviceBinding
import network.erth.wallet.wallet.utils.SoftwareEncryption
import network.erth.wallet.wallet.utils.SecurePreferencesUtil
import network.erth.wallet.wallet.utils.WalletStorageVersion
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import javax.crypto.BadPaddingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // The IV for the device binding layer. Every blob this app writes has one,
    // and a blob without one is refused rather than read — see
    // parseSoftwareEncryptedData and DeviceBinding.
    private const val KEY_DEVICE_IV = "device_iv"

    private const val KEY_WALLETS_ENCRYPTED = "wallets_encrypted"

    // An unsalted SHA-256 of the unlock secret, written by earlier builds in
    // plain preferences. For a four-digit PIN that is the PIN, so it is deleted
    // wherever it is found and never read. See purgeLegacyPinHash.
    private const val KEY_LEGACY_PIN_HASH = "pin_hash"

    /** The secret did not open the stored wallet: a wrong guess, and counted as one. */
    class WrongSecretException : Exception("The secret does not open this wallet")

    // Session state
    private var isSessionActive = false
    private val _active = MutableStateFlow(false)

    /** Whether a session is open, for screens that must leave when it closes. */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var sessionPin: String? = null
    private var versionedWalletStorage: WalletStorageVersion.VersionedWalletStorage? = null
    private var otherPrefsData = mutableMapOf<String, Any?>()

    /**
     * Start a new session by decrypting wallet data with PIN
     */
    @Synchronized
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
            val encryptedWalletsJson = softwarePrefs.getString(KEY_WALLETS_ENCRYPTED, null)
            if (encryptedWalletsJson != null) {
                val encryptedData = parseSoftwareEncryptedData(encryptedWalletsJson)
                // The decrypt is the PIN check. Nothing else on disk can confirm
                // a guess, so an offline attacker pays the full PBKDF2 cost per
                // try. A failed GCM tag here is the wrong secret; anything that
                // fails before it (the device key, a corrupt blob) is not a
                // guess and must not be counted as one.
                val decryptedStorageJson = try {
                    SoftwareEncryption.decrypt(encryptedData, pin, context)
                } catch (e: Exception) {
                    if (e.causes().any { it is BadPaddingException }) throw WrongSecretException()
                    throw e
                }

                // Parse with versioning support
                versionedWalletStorage = WalletStorageVersion.parseWalletStorage(decryptedStorageJson)
            } else {
                versionedWalletStorage = WalletStorageVersion.createVersionedStorage(JSONArray())
            }

            purgeLegacyPinHash(context)

            // Load other preferences data (non-encrypted)
            loadOtherPrefsData(softwarePrefs)

            isSessionActive = true
            _active.value = true

        } catch (e: WrongSecretException) {
            clearSession()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            clearSession()
            throw Exception("Failed to start session: ${e.message}", e)
        }
    }

    /**
     * End the current session and clear sensitive data from memory
     */
    @Synchronized
    fun endSession() {
        clearSession()
    }

    /**
     * Whether a wallet has been sealed on this device, i.e. whether there is
     * anything for an unlock secret to open. Reads only the blob's presence.
     */
    fun hasSealedStorage(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)
            .contains(KEY_WALLETS_ENCRYPTED)

    /**
     * Delete the PIN hash earlier builds kept beside the blob.
     *
     * Called on every launch and every unlock, so an install that had one
     * stops carrying it the first time it runs this build, locked or not.
     */
    @Synchronized
    fun purgeLegacyPinHash(context: Context) {
        val prefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LEGACY_PIN_HASH)) {
            prefs.edit().remove(KEY_LEGACY_PIN_HASH).commit()
        }
        otherPrefsData.remove(KEY_LEGACY_PIN_HASH)
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
        _active.value = false

    }

    /**
     * Load non-encrypted preferences data
     */
    private fun loadOtherPrefsData(softwarePrefs: SharedPreferences) {
        otherPrefsData.clear()

        val allPrefs = softwarePrefs.all
        for ((key, value) in allPrefs) {
            // Skip encrypted wallet data
            if (key != KEY_WALLETS_ENCRYPTED) {
                otherPrefsData[key] = value
            }
        }

    }

    /**
     * Parse the stored blob and unwrap its device binding layer.
     *
     * Device binding is required, not optional. Every blob this app writes
     * carries `device_iv`, so one without it is not an older format to fall
     * back on — it is a blob this app did not write, and treating it as
     * loadable would mean accepting storage sealed by nothing but a four-digit
     * PIN. Refusing is the safe reading.
     */
    @Throws(Exception::class)
    private fun parseSoftwareEncryptedData(jsonString: String): SoftwareEncryption.EncryptedData {
        val json = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            throw Exception("Failed to parse software encrypted data", e)
        }
        if (!json.has(KEY_DEVICE_IV)) {
            throw Exception("Stored wallet is not bound to this device and cannot be opened")
        }

        val wrapped = DeviceBinding.Wrapped(
            ciphertext = Base64.decode(json.getString("ciphertext"), Base64.DEFAULT),
            iv = Base64.decode(json.getString(KEY_DEVICE_IV), Base64.DEFAULT),
        )
        // A null unwrap is the device key having gone — a factory reset, cleared
        // app data, or this file moved to other hardware. Reported separately
        // from a bad PIN, because it is unrecoverable and inviting the user to
        // try again would waste their time.
        val ciphertext = DeviceBinding.unwrap(wrapped) ?: throw Exception(
            "This wallet is sealed to a device key that is no longer present. " +
                "It cannot be opened here; restore from your recovery phrase."
        )

        return try {
            SoftwareEncryption.EncryptedData(
                ciphertext = ciphertext,
                iv = Base64.decode(json.getString("iv"), Base64.DEFAULT),
                salt = Base64.decode(json.getString("salt"), Base64.DEFAULT)
            )
        } catch (e: Exception) {
            throw Exception("Failed to parse software encrypted data", e)
        }
    }

    /**
     * Re-encrypt the open wallet under a different secret.
     *
     * Changing how the wallet is unlocked is not a preference — the secret is
     * the encryption key, so the stored blob has to be rewritten under the new
     * one. Only possible with a session open, which is the point: the old
     * secret had to work before the new one is allowed to replace it.
     *
     * The in-memory storage is written straight back out, so nothing is
     * decrypted again in between and a wrong new secret cannot silently
     * produce an unopenable wallet.
     */
    @Throws(Exception::class)
    fun changeSecret(context: Context, newSecret: String) {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }
        saveVersionedStorageToEncryption(context, newSecret)
        sessionPin = newSecret
    }

    /**
     * Write the open storage out under the session's secret.
     *
     * First run needs this: a session opened with nothing stored has nothing
     * on disk, and the blob's existence is what says a wallet is set up.
     */
    @Throws(Exception::class)
    fun seal(context: Context) {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }
        val pin = sessionPin ?: throw IllegalStateException("Session PIN not available")
        saveVersionedStorageToEncryption(context, pin)
    }

    /**
     * Save versioned wallet storage to encrypted storage
     */
    @Throws(Exception::class)
    private fun saveVersionedStorageToEncryption(context: Context, pin: String) {
        val storage = versionedWalletStorage ?: throw IllegalStateException("No wallet storage available")

        // Serialize versioned storage to JSON
        val storageJson = WalletStorageVersion.serializeWalletStorage(storage)

        // Encrypt, then bind the result to this device.
        //
        // The PBKDF2 layer alone is only as strong as the unlock secret, and for
        // a PIN that is four digits — fine while the bytes stay here, worth
        // nothing against anyone who copies them. Wrapping the ciphertext under
        // a non-exportable Keystore key means a copy cannot be opened off this
        // device at any iteration count. See DeviceBinding.
        //
        // A failure here propagates and the save fails. There is deliberately no
        // fallback to storing unbound: minSdk is 24 and AES in the Keystore has
        // worked since 23, so a device that cannot do this is a broken device
        // rather than an old one — and the fallback's whole effect would be to
        // silently write the weak storage this exists to prevent.
        val encryptedData = SoftwareEncryption.encrypt(storageJson, pin, context)
        val wrapped = DeviceBinding.wrap(encryptedData.ciphertext)
        val json = JSONObject().apply {
            put("ciphertext", Base64.encodeToString(wrapped.ciphertext, Base64.DEFAULT))
            put(KEY_DEVICE_IV, Base64.encodeToString(wrapped.iv, Base64.DEFAULT))
            put("iv", Base64.encodeToString(encryptedData.iv, Base64.DEFAULT))
            put("salt", Base64.encodeToString(encryptedData.salt, Base64.DEFAULT))
        }

        val softwarePrefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)
        // commit, not apply. A secret change has BiometricVault drop the old
        // slot as soon as this returns, so a write still in flight when the
        // process dies would leave the blob sealed by a key that is gone.
        if (!softwarePrefs.edit().putString(KEY_WALLETS_ENCRYPTED, json.toString()).commit()) {
            throw Exception("Failed to write encrypted wallet storage")
        }

    }
}

private fun Throwable.causes(): Sequence<Throwable> =
    generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }

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