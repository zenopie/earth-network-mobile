package network.erth.wallet.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.BadPaddingException

/**
 * The unlocked session: the wallet storage is decrypted once at unlock, held
 * in memory while the session is open, and re-sealed whenever it changes.
 */
object SessionManager {

    private const val TAG = "SessionManager"
    // The file name is older than this app. Every install has it on disk, so
    // it stays.
    private const val PREF_FILE = "secret_wallet_prefs"

    // The IV for the device binding layer. Every blob this app writes has one,
    // and a blob without one is refused rather than read — see
    // parseSoftwareEncryptedData and DeviceBinding.
    private const val KEY_DEVICE_IV = "device_iv"

    private const val KEY_WALLETS_ENCRYPTED = "wallets_encrypted"
    private const val KEY_SELECTED_WALLET = "selected_wallet_index"

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
            val softwarePrefs = softwarePrefs(context)

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

    /** End the session and drop the decrypted storage from memory. */
    @Synchronized
    fun endSession() {
        clearSession()
    }

    /**
     * Whether a wallet has been sealed on this device, i.e. whether there is
     * anything for an unlock secret to open. Reads only the blob's presence.
     */
    fun hasSealedStorage(context: Context): Boolean =
        softwarePrefs(context)
            .contains(KEY_WALLETS_ENCRYPTED)

    /**
     * Delete the PIN hash earlier builds kept beside the blob.
     *
     * Called on every launch and every unlock, so an install that had one
     * stops carrying it the first time it runs this build, locked or not.
     */
    @Synchronized
    fun purgeLegacyPinHash(context: Context) {
        val prefs = softwarePrefs(context)
        if (prefs.contains(KEY_LEGACY_PIN_HASH)) {
            prefs.edit().remove(KEY_LEGACY_PIN_HASH).commit()
        }
        otherPrefsData.remove(KEY_LEGACY_PIN_HASH)
    }

    fun isSessionActive(): Boolean = isSessionActive

    /** The session's wallet list: a copy, written back with [saveWallets]. */
    fun wallets(): JSONArray {
        requireActive()
        val storage = versionedWalletStorage ?: throw IllegalStateException("No wallet storage available")
        return JSONArray(WalletStorageVersion.getWalletsArray(storage).toString())
    }

    /** Replace the wallet list and re-seal it under the session's secret. */
    fun saveWallets(context: Context, wallets: JSONArray) {
        requireActive()
        val pin = sessionPin ?: throw IllegalStateException("Session PIN not available")
        try {
            val storage = versionedWalletStorage ?: throw IllegalStateException("No wallet storage available")
            versionedWalletStorage = WalletStorageVersion.updateWallets(storage, wallets)
            saveVersionedStorageToEncryption(context, pin)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update wallet data", e)
            throw Exception("Failed to update wallet data: ${e.message}", e)
        }
    }

    /**
     * Which wallet is selected, or -1. Kept beside the sealed blob rather than
     * in it: an index is not a secret.
     */
    fun selectedWalletIndex(): Int {
        requireActive()
        return otherPrefsData[KEY_SELECTED_WALLET] as? Int ?: -1
    }

    fun setSelectedWalletIndex(context: Context, index: Int) {
        requireActive()
        otherPrefsData[KEY_SELECTED_WALLET] = index
        softwarePrefs(context).edit().putInt(KEY_SELECTED_WALLET, index).apply()
    }

    private fun requireActive() {
        if (!isSessionActive) {
            throw IllegalStateException("No active session - call startSession() first")
        }
    }

    private fun softwarePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)

    private fun clearSession() {
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
        requireActive()
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
        requireActive()
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

        val softwarePrefs = softwarePrefs(context)
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
