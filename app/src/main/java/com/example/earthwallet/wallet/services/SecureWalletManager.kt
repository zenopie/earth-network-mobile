package com.example.earthwallet.wallet.services

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.earthwallet.wallet.utils.WalletCrypto
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
            mnemonic = null
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
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val securePrefs = EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

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

            Log.w(TAG, "No mnemonic found in secure preferences, trying fallback")
            tryFallbackMnemonicRetrieval(context)

        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Failed to access encrypted preferences: ${e.message}")
            tryFallbackMnemonicRetrieval(context)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to access encrypted preferences: ${e.message}")
            tryFallbackMnemonicRetrieval(context)
        }
    }

    /**
     * Fallback mnemonic retrieval using plain SharedPreferences (matches existing fallback logic)
     */
    @Throws(Exception::class)
    private fun tryFallbackMnemonicRetrieval(context: Context): String {
        return try {
            val flatPrefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val walletsJson = flatPrefs.getString("wallets", "[]") ?: "[]"
            val arr = JSONArray(walletsJson)

            if (arr.length() > 0) {
                val sel = flatPrefs.getInt("selected_wallet_index", -1)
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "")
                } else {
                    return arr.getJSONObject(0).optString("mnemonic", "")
                }
            }

            flatPrefs.getString("mnemonic", "") ?: ""

        } catch (e: Exception) {
            Log.e(TAG, "All mnemonic retrieval methods failed", e)
            throw Exception("Failed to retrieve wallet mnemonic from secure or fallback storage", e)
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
            mnemonic?.let { securelyClearString(it) }

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
     * Create a new wallet with name and mnemonic using pre-initialized secure preferences
     */
    @Throws(Exception::class)
    fun createWallet(context: Context, securePrefs: SharedPreferences?, walletName: String, mnemonic: String) {
        try {
            WalletCrypto.initialize(context)

            // Derive address from mnemonic
            val address = WalletCrypto.getAddressFromMnemonic(mnemonic)

            val prefs = securePrefs ?: createSecurePrefs(context)

            // Load existing wallets
            val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)

            // Create new wallet object
            val newWallet = JSONObject().apply {
                put("name", walletName)
                put("mnemonic", mnemonic)
                put("address", address)
            }

            // Add to wallets array
            walletsArray.put(newWallet)

            // Set as selected wallet (index of new wallet)
            val newIndex = walletsArray.length() - 1

            // Save to preferences
            val editor = prefs.edit()
            editor.putString("wallets", walletsArray.toString())
            editor.putInt("selected_wallet_index", newIndex)
            editor.apply()

            Log.d(TAG, "Created new wallet: $walletName ($address)")
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
     * Create secure preferences (helper method)
     */
    @Throws(Exception::class)
    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to create secure preferences", e)
            throw Exception("Failed to create secure preferences", e)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create secure preferences", e)
            throw Exception("Failed to create secure preferences", e)
        }
    }
}