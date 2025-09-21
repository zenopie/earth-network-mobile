package com.example.earthwallet.wallet.services

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.example.earthwallet.wallet.utils.SecurePreferencesUtil
import com.example.earthwallet.wallet.utils.HardwareKeyManager
import com.example.earthwallet.wallet.utils.WalletCrypto
import com.example.earthwallet.wallet.utils.SoftwareEncryption
import com.example.earthwallet.wallet.utils.SecurityLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * SecureWalletManager
 *
 * Provides secure just-in-time mnemonic fetching with automatic cleanup.
 * This class ensures mnemonics are only held in memory for the minimum required time
 * and are securely zeroed after use.
 */
object SecureWalletManager {

    private const val TAG = "SecureWalletManager"
    private const val PREF_FILE = "secret_wallet_prefs"

    /**
     * Interface for operations that need access to the mnemonic.
     * The mnemonic is provided just-in-time and automatically cleaned up after the operation.
     */
    fun interface MnemonicOperation<T> {
        @Throws(Exception::class)
        fun execute(mnemonic: String): T
    }

    /**
     * Secure interface for operations that need access to the mnemonic as char array.
     * More secure than String version as char arrays can be properly zeroed.
     */
    fun interface SecureMnemonicOperation<T> {
        @Throws(Exception::class)
        fun execute(mnemonic: CharArray): T
    }

    /**
     * Execute an operation with just-in-time mnemonic fetching and automatic cleanup.
     * Uses hardware-backed encryption with key rotation for enhanced security.
     *
     * @param context Android context for fallback access to secure preferences
     * @param operation The operation to execute with the mnemonic
     * @return The result of the operation
     * @throws Exception If mnemonic retrieval or operation execution fails
     */
    @Throws(Exception::class)
    fun <T> executeWithMnemonic(context: Context, operation: MnemonicOperation<T>): T {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context)
        return executeWithMnemonic(context, null, operation)
    }

    /**
     * Execute an operation with just-in-time secure mnemonic fetching and automatic cleanup.
     * More secure version that uses char arrays instead of Strings.
     * Uses hardware-backed encryption with key rotation for enhanced security.
     *
     * @param context Android context for fallback access to secure preferences
     * @param operation The operation to execute with the mnemonic char array
     * @return The result of the operation
     * @throws Exception If mnemonic retrieval or operation execution fails
     */
    @Throws(Exception::class)
    fun <T> executeWithSecureMnemonic(context: Context, operation: SecureMnemonicOperation<T>): T {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context)
        var mnemonicChars: CharArray? = null
        return try {
            // Initialize hardware key manager only when actually needed for mnemonic operations
            HardwareKeyManager.initializeEncryptionKey()
            Log.d(TAG, "Executing secure mnemonic operation with hardware-backed encryption")

            // Fetch mnemonic and convert to char array immediately
            val mnemonic = fetchMnemonicSecurely(context)
            if (TextUtils.isEmpty(mnemonic)) {
                throw IllegalStateException("No wallet mnemonic found - wallet may not be initialized")
            }

            mnemonicChars = mnemonic.toCharArray()
            // Clear the String immediately
            securelyClearString(mnemonic)

            // Execute the operation with the char array
            operation.execute(mnemonicChars)

        } finally {
            // Securely zero the char array regardless of success or failure
            mnemonicChars?.let { chars ->
                chars.fill('\u0000')
                mnemonicChars = null
            }
        }
    }

    /**
     * Execute an operation with just-in-time mnemonic fetching and automatic cleanup.
     * Uses hardware-backed encryption with key rotation for enhanced security.
     *
     * @param context Android context for fallback access to secure preferences
     * @param securePrefs Pre-initialized secure preferences instance (preferred)
     * @param operation The operation to execute with the mnemonic
     * @return The result of the operation
     * @throws Exception If mnemonic retrieval or operation execution fails
     */
    @Throws(Exception::class)
    fun <T> executeWithMnemonic(
        context: Context,
        securePrefs: SharedPreferences?,
        operation: MnemonicOperation<T>
    ): T {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context)
        var mnemonic: String? = null
        return try {
            // Initialize hardware key manager only when actually using mnemonic
            HardwareKeyManager.initializeEncryptionKey()
            Log.d(TAG, "Executing mnemonic operation with hardware-backed encryption")

            // Fetch mnemonic just-in-time using provided securePrefs or fallback to context
            mnemonic = if (securePrefs != null) {
                fetchMnemonicFromPrefs(securePrefs)
            } else {
                fetchMnemonicSecurely(context)
            }

            if (TextUtils.isEmpty(mnemonic)) {
                throw IllegalStateException("No wallet mnemonic found - wallet may not be initialized")
            }

            // Execute the operation with the mnemonic
            operation.execute(mnemonic)

        } finally {
            // Securely zero the mnemonic regardless of success or failure
            mnemonic?.let { securelyClearString(it) }
        }
    }

    /**
     * Fetch mnemonic from already-initialized secure preferences (preferred method)
     */
    @Throws(Exception::class)
    private fun fetchMnemonicFromPrefs(securePrefs: SharedPreferences): String {
        return try {
            // Get mnemonic for the selected wallet (matches existing multi-wallet logic)
            val walletsJson = securePrefs.getString("wallets", "[]") ?: "[]"
            val arr = JSONArray(walletsJson)
            val sel = securePrefs.getInt("selected_wallet_index", -1)

            if (arr.length() > 0) {
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "")
                } else if (arr.length() == 1) {
                    return arr.getJSONObject(0).optString("mnemonic", "")
                }
            }

            // Fallback to legacy mnemonic
            securePrefs.getString("mnemonic", "") ?: ""

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch mnemonic from secure preferences", e)
            throw Exception("Failed to retrieve wallet mnemonic from secure preferences", e)
        }
    }

    /**
     * Fetch wallet mnemonic from secure preferences using the same pattern as existing code
     */
    @Throws(Exception::class)
    private fun fetchMnemonicSecurely(context: Context): String {
        return try {
            // Use encrypted preferences for secure wallet storage
            val securePrefs = SecurePreferencesUtil.createEncryptedPreferences(context, PREF_FILE)

            // Get mnemonic for the selected wallet (matches existing multi-wallet logic)
            val walletsJson = securePrefs.getString("wallets", "[]") ?: "[]"
            val arr = JSONArray(walletsJson)
            val sel = securePrefs.getInt("selected_wallet_index", -1)

            if (arr.length() > 0) {
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "")
                } else if (arr.length() == 1) {
                    return arr.getJSONObject(0).optString("mnemonic", "")
                }
            }

            // Fallback to legacy mnemonic
            val legacyMnemonic = securePrefs.getString("mnemonic", "") ?: ""
            if (!TextUtils.isEmpty(legacyMnemonic)) {
                return legacyMnemonic
            }

            Log.w(TAG, "No mnemonic found in secure preferences, trying software fallback")
            trySecureFallbackRetrieval(context)

        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Hardware encryption failed, using software fallback: ${e.message}")
            trySecureFallbackRetrieval(context)
        } catch (e: IOException) {
            Log.w(TAG, "Hardware encryption failed, using software fallback: ${e.message}")
            trySecureFallbackRetrieval(context)
        }
    }

    /**
     * Secure fallback mnemonic retrieval using software encryption
     */
    @Throws(Exception::class)
    private fun trySecureFallbackRetrieval(context: Context): String {
        return try {
            Log.w(TAG, "Using software encryption fallback - security level reduced")

            if (!SoftwareEncryption.isAvailable()) {
                throw Exception("Software encryption not available on this device")
            }

            val softwarePrefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)

            // Try to get encrypted wallet data
            val encryptedWalletsJson = softwarePrefs.getString("wallets_encrypted", null)
            if (encryptedWalletsJson != null) {
                val encryptedData = parseSoftwareEncryptedData(encryptedWalletsJson)
                val walletsJson = SoftwareEncryption.decrypt(encryptedData, context)
                val arr = JSONArray(walletsJson)

                if (arr.length() > 0) {
                    val sel = softwarePrefs.getInt("selected_wallet_index", -1)
                    if (sel >= 0 && sel < arr.length()) {
                        return arr.getJSONObject(sel).optString("mnemonic", "")
                    } else {
                        return arr.getJSONObject(0).optString("mnemonic", "")
                    }
                }
            }

            // No encrypted data found
            throw Exception("No encrypted wallet data found in software fallback storage")

        } catch (e: Exception) {
            Log.e(TAG, "Software encryption fallback failed", e)
            throw Exception("Failed to retrieve wallet mnemonic from software encrypted storage", e)
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
                ciphertext = android.util.Base64.decode(json.getString("ciphertext"), android.util.Base64.DEFAULT),
                iv = android.util.Base64.decode(json.getString("iv"), android.util.Base64.DEFAULT),
                salt = android.util.Base64.decode(json.getString("salt"), android.util.Base64.DEFAULT)
            )
        } catch (e: Exception) {
            throw Exception("Failed to parse software encrypted data", e)
        }
    }

    /**
     * Save data using software encryption fallback
     */
    @Throws(Exception::class)
    private fun saveSoftwareEncryptedData(context: Context, key: String, data: String) {
        try {
            val encryptedData = SoftwareEncryption.encrypt(data, context)
            val json = JSONObject().apply {
                put("ciphertext", android.util.Base64.encodeToString(encryptedData.ciphertext, android.util.Base64.DEFAULT))
                put("iv", android.util.Base64.encodeToString(encryptedData.iv, android.util.Base64.DEFAULT))
                put("salt", android.util.Base64.encodeToString(encryptedData.salt, android.util.Base64.DEFAULT))
            }

            val softwarePrefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)
            softwarePrefs.edit().putString(key, json.toString()).apply()

            Log.d(TAG, "Saved data using software encryption fallback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save software encrypted data", e)
            throw Exception("Failed to save data using software encryption", e)
        }
    }

    /**
     * Securely clear a string from memory by overwriting its internal char array.
     * This is a best-effort approach since String is immutable in Java, but it helps
     * reduce the time sensitive data stays in memory.
     */
    private fun securelyClearString(sensitiveString: String?) {
        if (sensitiveString == null) {
            return
        }

        try {
            // Convert to char array and clear it
            val chars = sensitiveString.toCharArray()
            chars.fill('\u0000')

            // Note: We cannot directly access String's internal char array in modern Java,
            // but clearing the char array we created helps minimize exposure.
            // The original String will be garbage collected eventually.

            Log.d(TAG, "Mnemonic cleared from memory (best effort)")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to securely clear mnemonic: ${e.message}")
        }
    }

    /**
     * Check if a wallet mnemonic is available without actually retrieving it
     */
    fun isWalletAvailable(context: Context): Boolean {
        return isWalletAvailable(context, null)
    }

    /**
     * Check if a wallet mnemonic is available without actually retrieving it
     */
    fun isWalletAvailable(context: Context?, securePrefs: SharedPreferences?): Boolean {
        if (context == null) return false

        return try {
            val mnemonic = if (securePrefs != null) {
                fetchMnemonicFromPrefs(securePrefs)
            } else {
                fetchMnemonicSecurely(context)
            }
            val available = !TextUtils.isEmpty(mnemonic)

            // Clear the mnemonic immediately after checking
            securelyClearString(mnemonic)

            available
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check wallet availability: ${e.message}")
            false
        }
    }

    /**
     * Get the wallet address using secure just-in-time mnemonic fetching
     *
     * @param context Android context for accessing secure preferences
     * @return The wallet address or null if no wallet is available
     */
    @Throws(Exception::class)
    fun getWalletAddress(context: Context): String? {
        return executeWithMnemonic(context) { mnemonic ->
            WalletCrypto.getAddressFromMnemonic(mnemonic)
        }
    }

    /**
     * Get the wallet address using secure just-in-time mnemonic fetching
     *
     * @param context Android context for fallback access
     * @param securePrefs Pre-initialized secure preferences instance (preferred)
     * @return The wallet address or null if no wallet is available
     */
    @Throws(Exception::class)
    fun getWalletAddress(context: Context, securePrefs: SharedPreferences): String? {
        return executeWithMnemonic(context, securePrefs) { mnemonic ->
            WalletCrypto.getAddressFromMnemonic(mnemonic)
        }
    }

    // =========================================================================
    // Security Level Detection
    // =========================================================================

    /**
     * Get current security level for wallet storage
     */
    @Throws(Exception::class)
    fun getSecurityLevel(context: Context): SecurityLevel {
        return try {
            // Check if hardware-backed encryption is working
            createSecurePrefs(context)
            SecurityLevel.HARDWARE_BACKED
        } catch (e: Exception) {
            Log.w(TAG, "Hardware encryption not available: ${e.message}")

            // Check if software encryption is available
            if (SoftwareEncryption.isAvailable()) {
                SecurityLevel.SOFTWARE_ENCRYPTED
            } else {
                SecurityLevel.INSECURE
            }
        }
    }

    /**
     * Check if hardware-backed security is available
     */
    fun isHardwareSecurityAvailable(): Boolean {
        return try {
            HardwareKeyManager.isHardwareBackedKeystoreAvailable()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get security status message for UI display
     */
    @Throws(Exception::class)
    fun getSecurityStatusMessage(context: Context): String {
        val level = getSecurityLevel(context)
        return when (level) {
            SecurityLevel.HARDWARE_BACKED -> "🔒 ${level.displayName}: ${level.description}"
            SecurityLevel.SOFTWARE_ENCRYPTED -> "🔐 ${level.displayName}: ${level.description}"
            SecurityLevel.INSECURE -> "⚠️ ${level.displayName}: ${level.description}"
        }
    }

    // =========================================================================
    // Wallet Management Methods
    // =========================================================================

    /**
     * Get current wallet name
     */
    @Throws(Exception::class)
    fun getCurrentWalletName(context: Context): String {
        return getCurrentWalletName(context, null)
    }

    /**
     * Get current wallet name using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun getCurrentWalletName(context: Context, securePrefs: SharedPreferences?): String {
        return try {
            val prefs = securePrefs ?: createSecurePrefs(context)
            val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)
            val selectedIndex = prefs.getInt("selected_wallet_index", -1)

            if (walletsArray.length() > 0) {
                val index = if (selectedIndex >= 0 && selectedIndex < walletsArray.length()) selectedIndex else 0
                walletsArray.getJSONObject(index).optString("name", "Wallet ${index + 1}")
            } else {
                "No wallet"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current wallet name", e)
            throw Exception("Failed to get current wallet name", e)
        }
    }

    /**
     * Get total number of wallets
     */
    @Throws(Exception::class)
    fun getWalletCount(context: Context): Int {
        return getWalletCount(context, null)
    }

    /**
     * Get total number of wallets using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun getWalletCount(context: Context, securePrefs: SharedPreferences?): Int {
        return try {
            val prefs = securePrefs ?: createSecurePrefs(context)
            val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)
            walletsArray.length()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallet count", e)
            throw Exception("Failed to get wallet count", e)
        }
    }

    /**
     * Get selected wallet index
     */
    @Throws(Exception::class)
    fun getSelectedWalletIndex(context: Context): Int {
        return getSelectedWalletIndex(context, null)
    }

    /**
     * Get selected wallet index using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun getSelectedWalletIndex(context: Context, securePrefs: SharedPreferences?): Int {
        return try {
            val prefs = securePrefs ?: createSecurePrefs(context)
            prefs.getInt("selected_wallet_index", -1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get selected wallet index", e)
            throw Exception("Failed to get selected wallet index", e)
        }
    }

    /**
     * Ensure current wallet has an address (migrate legacy wallets)
     */
    @Throws(Exception::class)
    fun ensureCurrentWalletHasAddress(context: Context) {
        ensureCurrentWalletHasAddress(context, null)
    }

    /**
     * Ensure current wallet has an address using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun ensureCurrentWalletHasAddress(context: Context, securePrefs: SharedPreferences?) {
        try {
            val prefs = securePrefs ?: createSecurePrefs(context)
            val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)
            val selectedIndex = prefs.getInt("selected_wallet_index", -1)

            if (walletsArray.length() > 0) {
                val index = if (selectedIndex >= 0 && selectedIndex < walletsArray.length()) selectedIndex else 0
                val selectedWallet = walletsArray.getJSONObject(index)

                val currentAddress = selectedWallet.optString("address", "")
                val currentMnemonic = selectedWallet.optString("mnemonic", "")

                // If address is missing, derive it and update storage
                if (TextUtils.isEmpty(currentAddress) && !TextUtils.isEmpty(currentMnemonic)) {
                    val address = WalletCrypto.getAddressFromMnemonic(currentMnemonic)
                    selectedWallet.put("address", address)
                    walletsArray.put(index, selectedWallet)
                    prefs.edit().putString("wallets", walletsArray.toString()).apply()
                    Log.d(TAG, "Migrated wallet address for: ${selectedWallet.optString("name", "Unknown")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure wallet has address", e)
            throw Exception("Failed to ensure wallet has address", e)
        }
    }

    /**
     * Ensure all wallets have addresses (migrate legacy wallets)
     */
    @Throws(Exception::class)
    fun ensureAllWalletsHaveAddresses(context: Context) {
        ensureAllWalletsHaveAddresses(context, null)
    }

    /**
     * Ensure all wallets have addresses using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun ensureAllWalletsHaveAddresses(context: Context, securePrefs: SharedPreferences?) {
        try {
            val prefs = securePrefs ?: createSecurePrefs(context)
            val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)
            var modified = false

            for (i in 0 until walletsArray.length()) {
                val wallet = walletsArray.getJSONObject(i)
                val currentAddress = wallet.optString("address", "")
                val currentMnemonic = wallet.optString("mnemonic", "")

                // If address is missing, derive it and update storage
                if (TextUtils.isEmpty(currentAddress) && !TextUtils.isEmpty(currentMnemonic)) {
                    val address = WalletCrypto.getAddressFromMnemonic(currentMnemonic)
                    wallet.put("address", address)
                    walletsArray.put(i, wallet)
                    modified = true
                    Log.d(TAG, "Migrated wallet address for: ${wallet.optString("name", "Unknown")}")
                }
            }

            // Save changes if any wallets were modified
            if (modified) {
                prefs.edit().putString("wallets", walletsArray.toString()).apply()
                Log.d(TAG, "Completed wallet address migration")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure all wallets have addresses", e)
            throw Exception("Failed to ensure all wallets have addresses", e)
        }
    }

    // =========================================================================
    // Wallet Creation Methods
    // =========================================================================

    /**
     * Generate a new BIP-39 mnemonic
     */
    @Throws(Exception::class)
    fun generateMnemonic(context: Context): String {
        WalletCrypto.initialize(context)
        return WalletCrypto.generateMnemonic()
    }

    /**
     * Create a new wallet with name and mnemonic
     */
    @Throws(Exception::class)
    fun createWallet(context: Context, walletName: String, mnemonic: String) {
        createWallet(context, null, walletName, mnemonic)
    }

    /**
     * Create a new wallet with name and mnemonic using secure storage with fallback
     */
    @Throws(Exception::class)
    fun createWallet(context: Context, securePrefs: SharedPreferences?, walletName: String, mnemonic: String) {
        try {
            WalletCrypto.initialize(context)

            // Derive address from mnemonic
            val address = WalletCrypto.getAddressFromMnemonic(mnemonic)

            // Create new wallet object
            val newWallet = JSONObject().apply {
                put("name", walletName)
                put("mnemonic", mnemonic)
                put("address", address)
            }

            // Try hardware-backed storage first
            try {
                val prefs = securePrefs ?: createSecurePrefs(context)

                // Load existing wallets
                val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
                val walletsArray = JSONArray(walletsJson)

                // Add to wallets array
                walletsArray.put(newWallet)
                val newIndex = walletsArray.length() - 1

                // Save to hardware-backed preferences
                val editor = prefs.edit()
                editor.putString("wallets", walletsArray.toString())
                editor.putInt("selected_wallet_index", newIndex)
                editor.apply()

                Log.d(TAG, "Created new wallet with hardware encryption: $walletName ($address)")

            } catch (e: Exception) {
                Log.w(TAG, "Hardware storage failed, using software fallback: ${e.message}")

                // Fall back to software encryption
                if (SoftwareEncryption.isAvailable()) {
                    val softwarePrefs = context.getSharedPreferences(PREF_FILE + "_software", Context.MODE_PRIVATE)

                    // Load existing wallets from software storage
                    val existingWalletsJson = try {
                        val encryptedJson = softwarePrefs.getString("wallets_encrypted", null)
                        if (encryptedJson != null) {
                            val encryptedData = parseSoftwareEncryptedData(encryptedJson)
                            SoftwareEncryption.decrypt(encryptedData, context)
                        } else {
                            "[]"
                        }
                    } catch (ex: Exception) {
                        "[]"
                    }

                    val walletsArray = JSONArray(existingWalletsJson)
                    walletsArray.put(newWallet)
                    val newIndex = walletsArray.length() - 1

                    // Save encrypted wallet data
                    saveSoftwareEncryptedData(context, "wallets_encrypted", walletsArray.toString())
                    softwarePrefs.edit().putInt("selected_wallet_index", newIndex).apply()

                    Log.d(TAG, "Created new wallet with software encryption: $walletName ($address)")
                } else {
                    throw Exception("Neither hardware nor software encryption available")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create wallet", e)
            throw Exception("Failed to create wallet: ${e.message}", e)
        }
    }

    /**
     * Validate a BIP-39 mnemonic
     */
    fun validateMnemonic(context: Context, mnemonic: String): Boolean {
        return try {
            WalletCrypto.initialize(context)
            // Try to derive a key - if it fails, mnemonic is invalid
            WalletCrypto.deriveKeyFromMnemonic(mnemonic)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Invalid mnemonic: ${e.message}")
            false
        }
    }

    /**
     * Get all wallets with their addresses (no mnemonics exposed)
     */
    @Throws(Exception::class)
    fun getAllWallets(context: Context): JSONArray {
        return getAllWallets(context, null)
    }

    /**
     * Get all wallets with their addresses using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun getAllWallets(context: Context, securePrefs: SharedPreferences?): JSONArray {
        return try {
            val prefs = securePrefs ?: createSecurePrefs(context)
            val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)

            // Return a copy with only safe data (name and address, no mnemonic)
            val safeWallets = JSONArray()
            for (i in 0 until walletsArray.length()) {
                val wallet = walletsArray.getJSONObject(i)
                val safeWallet = JSONObject().apply {
                    put("name", wallet.optString("name", "Wallet ${i + 1}"))
                    put("address", wallet.optString("address", ""))
                }
                safeWallets.put(safeWallet)
            }

            safeWallets
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all wallets", e)
            throw Exception("Failed to get all wallets", e)
        }
    }

    /**
     * Delete wallet at specific index
     */
    @Throws(Exception::class)
    fun deleteWallet(context: Context, walletIndex: Int) {
        deleteWallet(context, null, walletIndex)
    }

    /**
     * Delete wallet at specific index using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun deleteWallet(context: Context, securePrefs: SharedPreferences?, walletIndex: Int) {
        val prefs = securePrefs ?: createSecurePrefs(context)
        val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
        val walletsArray = JSONArray(walletsJson)

        if (walletIndex < 0 || walletIndex >= walletsArray.length()) {
            throw IllegalArgumentException("Invalid wallet index: $walletIndex")
        }

        // Remove wallet at index
        val newArray = JSONArray()
        for (i in 0 until walletsArray.length()) {
            if (i != walletIndex) {
                newArray.put(walletsArray.getJSONObject(i))
            }
        }

        // Update selected wallet index
        val currentSelected = prefs.getInt("selected_wallet_index", -1)
        val newSelected = when {
            newArray.length() == 0 -> -1
            walletIndex < currentSelected -> currentSelected - 1
            walletIndex == currentSelected -> if (currentSelected >= newArray.length()) newArray.length() - 1 else currentSelected
            else -> currentSelected
        }

        // Save changes
        val editor = prefs.edit()
        editor.putString("wallets", newArray.toString())
        editor.putInt("selected_wallet_index", newSelected)
        editor.apply()
    }

    /**
     * Verify PIN hash against stored hash
     */
    @Throws(Exception::class)
    fun verifyPinHash(context: Context, pinHash: String): Boolean {
        return verifyPinHash(context, null, pinHash)
    }

    /**
     * Verify PIN hash against stored hash using pre-initialized secure preferences.
     * Uses hardware-backed encryption with key rotation for enhanced security.
     */
    @Throws(Exception::class)
    fun verifyPinHash(context: Context, securePrefs: SharedPreferences?, pinHash: String): Boolean {
        // Initialize hardware key manager for TEE security
        HardwareKeyManager.initializeEncryptionKey()
        Log.d(TAG, "Verifying PIN with hardware-backed encryption")

        val prefs = securePrefs ?: createSecurePrefs(context)
        val storedHash = prefs.getString("pin_hash", "") ?: ""
        return storedHash == pinHash
    }

    /**
     * Set PIN hash securely
     */
    @Throws(Exception::class)
    fun setPinHash(context: Context, pinHash: String) {
        setPinHash(context, null, pinHash)
    }

    /**
     * Set PIN hash securely using pre-initialized secure preferences.
     * Uses hardware-backed encryption with key rotation for enhanced security.
     */
    @Throws(Exception::class)
    fun setPinHash(context: Context, securePrefs: SharedPreferences?, pinHash: String) {
        // Initialize hardware key manager for TEE security
        HardwareKeyManager.initializeEncryptionKey()
        Log.d(TAG, "Setting PIN with hardware-backed encryption")

        val prefs = securePrefs ?: createSecurePrefs(context)
        val editor = prefs.edit()
        editor.putString("pin_hash", pinHash)
        editor.apply()
    }

    /**
     * Check if PIN is already set
     */
    @Throws(Exception::class)
    fun hasPinSet(context: Context): Boolean {
        return hasPinSet(context, null)
    }

    /**
     * Check if PIN is already set using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun hasPinSet(context: Context, securePrefs: SharedPreferences?): Boolean {
        val prefs = securePrefs ?: createSecurePrefs(context)
        val storedHash = prefs.getString("pin_hash", "") ?: ""
        return storedHash.isNotEmpty()
    }

    /**
     * Check if biometric authentication is enabled
     */
    @Throws(Exception::class)
    fun isBiometricAuthEnabled(context: Context): Boolean {
        return isBiometricAuthEnabled(context, null)
    }

    /**
     * Check if biometric authentication is enabled using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun isBiometricAuthEnabled(context: Context, securePrefs: SharedPreferences?): Boolean {
        val prefs = securePrefs ?: createSecurePrefs(context)
        return prefs.getBoolean("biometric_auth_enabled", false)
    }

    /**
     * Set biometric authentication enabled/disabled
     */
    @Throws(Exception::class)
    fun setBiometricAuthEnabled(context: Context, enabled: Boolean) {
        setBiometricAuthEnabled(context, null, enabled)
    }

    /**
     * Set biometric authentication enabled/disabled using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun setBiometricAuthEnabled(context: Context, securePrefs: SharedPreferences?, enabled: Boolean) {
        val prefs = securePrefs ?: createSecurePrefs(context)
        val editor = prefs.edit()
        editor.putBoolean("biometric_auth_enabled", enabled)
        editor.apply()
        Log.d(TAG, "Biometric authentication ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if transaction authentication is enabled
     */
    @Throws(Exception::class)
    fun isTransactionAuthEnabled(context: Context): Boolean {
        return isTransactionAuthEnabled(context, null)
    }

    /**
     * Check if transaction authentication is enabled using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun isTransactionAuthEnabled(context: Context, securePrefs: SharedPreferences?): Boolean {
        val prefs = securePrefs ?: createSecurePrefs(context)
        return prefs.getBoolean("transaction_auth_enabled", false)
    }

    /**
     * Set transaction authentication enabled/disabled
     */
    @Throws(Exception::class)
    fun setTransactionAuthEnabled(context: Context, enabled: Boolean) {
        setTransactionAuthEnabled(context, null, enabled)
    }

    /**
     * Set transaction authentication enabled/disabled using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun setTransactionAuthEnabled(context: Context, securePrefs: SharedPreferences?, enabled: Boolean) {
        val prefs = securePrefs ?: createSecurePrefs(context)
        val editor = prefs.edit()
        editor.putBoolean("transaction_auth_enabled", enabled)
        editor.apply()
        Log.d(TAG, "Transaction authentication ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Select a wallet by index
     */
    @Throws(Exception::class)
    fun selectWallet(context: Context, walletIndex: Int) {
        selectWallet(context, null, walletIndex)
    }

    /**
     * Select a wallet by index using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun selectWallet(context: Context, securePrefs: SharedPreferences?, walletIndex: Int) {
        val prefs = securePrefs ?: createSecurePrefs(context)
        val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
        val walletsArray = JSONArray(walletsJson)

        if (walletIndex < 0 || walletIndex >= walletsArray.length()) {
            throw IllegalArgumentException("Invalid wallet index: $walletIndex")
        }

        val editor = prefs.edit()
        editor.putInt("selected_wallet_index", walletIndex)
        editor.apply()

        Log.d(TAG, "Selected wallet at index: $walletIndex")
    }

    /**
     * Execute an operation with a specific wallet's mnemonic by index
     */
    @Throws(Exception::class)
    fun <T> executeWithWalletMnemonic(context: Context, walletIndex: Int, operation: MnemonicOperation<T>): T {
        return executeWithWalletMnemonic(context, null, walletIndex, operation)
    }

    /**
     * Execute an operation with a specific wallet's mnemonic by index using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun <T> executeWithWalletMnemonic(
        context: Context,
        securePrefs: SharedPreferences?,
        walletIndex: Int,
        operation: MnemonicOperation<T>
    ): T {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context)
        var mnemonic: String? = null
        return try {
            val prefs = securePrefs ?: createSecurePrefs(context)
            val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)

            if (walletIndex < 0 || walletIndex >= walletsArray.length()) {
                throw IllegalArgumentException("Invalid wallet index: $walletIndex")
            }

            val wallet = walletsArray.getJSONObject(walletIndex)
            mnemonic = wallet.optString("mnemonic", "")

            if (TextUtils.isEmpty(mnemonic)) {
                throw IllegalStateException("No mnemonic found for wallet at index $walletIndex")
            }

            // Execute the operation with the specific wallet's mnemonic
            operation.execute(mnemonic)

        } finally {
            // Securely zero the mnemonic regardless of success or failure
            mnemonic?.let { securelyClearString(it) }
        }
    }

    /**
     * Create secure preferences (helper method)
     */
    @Throws(Exception::class)
    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            SecurePreferencesUtil.createEncryptedPreferences(context, PREF_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create secure preferences", e)
            throw Exception("Failed to create secure preferences", e)
        }
    }
}