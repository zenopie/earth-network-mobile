package network.erth.wallet.wallet

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import network.erth.wallet.crypto.WalletCrypto
import org.bitcoinj.crypto.MnemonicCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * SecureWalletManager
 *
 * Provides secure session-based wallet management with PIN-based encryption.
 * Uses SessionManager for session-based decryption where wallet data is decrypted
 * once at startup and kept in memory for the duration of the app session.
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
     * Suspend-compatible interface for operations that need access to the mnemonic.
     * Allows calling suspend functions within the operation.
     */
    fun interface SuspendMnemonicOperation<T> {
        @Throws(Exception::class)
        suspend fun execute(mnemonic: String): T
    }

    /**
     * Start a new session with PIN-based decryption
     */
    @Throws(Exception::class)
    fun startSession(context: Context, pin: String) {
        SessionManager.startSession(context, pin)
    }

    /**
     * End the current session
     */
    fun endSession() {
        SessionManager.endSession()
    }

    /**
     * Check if session is active
     */
    fun isSessionActive(): Boolean {
        return SessionManager.isSessionActive()
    }

    /**
     * Execute an operation with mnemonic from active session.
     *
     * @param context Android context
     * @param operation The operation to execute with the mnemonic
     * @return The result of the operation
     * @throws Exception If no active session or mnemonic retrieval fails
     */
    @Throws(Exception::class)
    fun <T> executeWithMnemonic(context: Context, operation: MnemonicOperation<T>): T {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context)
        return executeWithMnemonic(context, null, operation)
    }

    /**
     * Execute an operation with mnemonic from active session.
     *
     * @param context Android context
     * @param securePrefs Legacy parameter - ignored in session-based approach
     * @param operation The operation to execute with the mnemonic
     * @return The result of the operation
     * @throws Exception If no active session or mnemonic retrieval fails
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

            // Fetch mnemonic from active session
            mnemonic = fetchMnemonicFromSession(context)

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
     * Fetch mnemonic from active session
     */
    @Throws(Exception::class)
    private fun fetchMnemonicFromSession(context: Context): String {
        return try {
            if (!SessionManager.isSessionActive()) {
                throw IllegalStateException("No active session - call startSession() first")
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)

            // Get mnemonic for the selected wallet (matches existing multi-wallet logic)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
            val arr = JSONArray(walletsJson)
            val sel = sessionPrefs.getInt("selected_wallet_index", -1)

            if (arr.length() > 0) {
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "")
                } else if (arr.length() == 1) {
                    return arr.getJSONObject(0).optString("mnemonic", "")
                }
            }

            // No wallet found
            ""

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch mnemonic from session", e)
            throw Exception("Failed to retrieve wallet mnemonic from session", e)
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


        } catch (e: Exception) {
        }
    }

    /**
     * Check if a wallet mnemonic is available in the current session
     */
    fun isWalletAvailable(context: Context): Boolean {
        return isWalletAvailable(context, null)
    }

    /**
     * Check if a wallet mnemonic is available in the current session
     */
    fun isWalletAvailable(context: Context?, securePrefs: SharedPreferences?): Boolean {
        if (context == null) return false

        return try {
            if (!SessionManager.isSessionActive()) {
                return false
            }

            val mnemonic = fetchMnemonicFromSession(context)
            val available = !TextUtils.isEmpty(mnemonic)

            // Clear the mnemonic immediately after checking
            securelyClearString(mnemonic)

            available
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the wallet address from the current session
     *
     * @param context Android context
     * @return The wallet address or null if no wallet is available
     */
    @Throws(Exception::class)
    fun getWalletAddress(context: Context): String? {
        return executeWithMnemonic(context) { mnemonic ->
            WalletCrypto.getAddressFromMnemonic(mnemonic)
        }
    }

    /**
     * Get the wallet address from the current session
     *
     * @param context Android context
     * @param securePrefs Legacy parameter - ignored in session-based approach
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
     * Get current wallet name from session
     */
    @Throws(Exception::class)
    fun getCurrentWalletName(context: Context): String {
        return getCurrentWalletName(context, null)
    }

    /**
     * Get current wallet name from session
     */
    @Throws(Exception::class)
    fun getCurrentWalletName(context: Context, securePrefs: SharedPreferences?): String {
        return try {
            if (!SessionManager.isSessionActive()) {
                return "No session"
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)
            val selectedIndex = sessionPrefs.getInt("selected_wallet_index", -1)

            if (walletsArray.length() > 0) {
                val index = if (selectedIndex >= 0 && selectedIndex < walletsArray.length()) selectedIndex else 0
                walletsArray.getJSONObject(index).optString("name", "Wallet ${index + 1}")
            } else {
                "No wallet"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current wallet name", e)
            "No wallet"
        }
    }

    /**
     * Get total number of wallets from session
     */
    @Throws(Exception::class)
    fun getWalletCount(context: Context): Int {
        return getWalletCount(context, null)
    }

    /**
     * Get total number of wallets from session
     */
    @Throws(Exception::class)
    fun getWalletCount(context: Context, securePrefs: SharedPreferences?): Int {
        return try {
            if (!SessionManager.isSessionActive()) {
                return 0
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)
            walletsArray.length()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallet count", e)
            0
        }
    }

    /**
     * Get selected wallet index from session
     */
    @Throws(Exception::class)
    fun getSelectedWalletIndex(context: Context): Int {
        return getSelectedWalletIndex(context, null)
    }

    /**
     * Get selected wallet index from session
     */
    @Throws(Exception::class)
    fun getSelectedWalletIndex(context: Context, securePrefs: SharedPreferences?): Int {
        return try {
            if (!SessionManager.isSessionActive()) {
                return -1
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            sessionPrefs.getInt("selected_wallet_index", -1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get selected wallet index", e)
            -1
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
     * Ensure current wallet has an address using session
     */
    @Throws(Exception::class)
    fun ensureCurrentWalletHasAddress(context: Context, securePrefs: SharedPreferences?) {
        try {
            if (!SessionManager.isSessionActive()) {
                throw IllegalStateException("No active session - call startSession() first")
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)
            val selectedIndex = sessionPrefs.getInt("selected_wallet_index", -1)

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
                    sessionPrefs.edit().putString("wallets", walletsArray.toString()).apply()
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
     * Ensure all wallets have addresses using session
     */
    @Throws(Exception::class)
    fun ensureAllWalletsHaveAddresses(context: Context, securePrefs: SharedPreferences?) {
        try {
            if (!SessionManager.isSessionActive()) {
                throw IllegalStateException("No active session - call startSession() first")
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
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
                }
            }

            // Save changes if any wallets were modified
            if (modified) {
                sessionPrefs.edit().putString("wallets", walletsArray.toString()).apply()
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
     * Create a new wallet with name and mnemonic in current session
     */
    @Throws(Exception::class)
    fun createWallet(context: Context, walletName: String, mnemonic: String) {
        createWallet(context, null, walletName, mnemonic)
    }

    /**
     * Create a new wallet with name and mnemonic using session
     */
    @Throws(Exception::class)
    fun createWallet(context: Context, securePrefs: SharedPreferences?, walletName: String, mnemonic: String) {
        try {
            if (!SessionManager.isSessionActive()) {
                throw IllegalStateException("No active session - call startSession() first")
            }

            WalletCrypto.initialize(context)

            // Derive address from mnemonic
            val address = WalletCrypto.getAddressFromMnemonic(mnemonic)

            // Create new wallet object
            val newWallet = JSONObject().apply {
                put("name", walletName)
                put("mnemonic", mnemonic)
                put("address", address)
            }

            // Use session preferences
            val sessionPrefs = SessionManager.createSessionPreferences(context)

            // Load existing wallets
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)

            // Add new wallet
            walletsArray.put(newWallet)
            val newIndex = walletsArray.length() - 1

            // Save updated wallets
            val editor = sessionPrefs.edit()
            editor.putString("wallets", walletsArray.toString())
            editor.putInt("selected_wallet_index", newIndex)
            editor.apply()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create wallet: $walletName", e)
            throw Exception("Failed to create wallet: ${e.message}")
        }
    }



    /** One stored wallet, without its mnemonic. */
    data class WalletInfo(val index: Int, val name: String, val address: String)

    /**
     * Every wallet in the session, names and addresses only.
     *
     * The mnemonics stay where they are. A list is for choosing between
     * wallets and nothing on a list screen needs a seed phrase, so it is not
     * carried out of storage to sit in a UI model where it can be logged,
     * screenshotted or recomposed into a crash report.
     */
    @Throws(Exception::class)
    fun listWallets(context: Context): List<WalletInfo> {
        if (!SessionManager.isSessionActive()) return emptyList()
        val sessionPrefs = SessionManager.createSessionPreferences(context)
        val walletsArray = JSONArray(sessionPrefs.getString("wallets", "[]") ?: "[]")
        return (0 until walletsArray.length()).map { i ->
            val w = walletsArray.getJSONObject(i)
            WalletInfo(
                index = i,
                name = w.optString("name", "Wallet ${i + 1}"),
                address = w.optString("address", ""),
            )
        }
    }

    /**
     * Validate a BIP-39 mnemonic: every word in the list, and the checksum.
     *
     * Deriving a key is not a check. BIP-32 turns any string into a seed, so a
     * single mistyped word used to import cleanly as a different, empty wallet
     * — indistinguishable from funds having vanished. The checksum is what
     * catches that.
     */
    fun validateMnemonic(context: Context, mnemonic: String): Boolean {
        return try {
            WalletCrypto.initialize(context)
            MnemonicCode.INSTANCE.check(mnemonic.trim().split(Regex("\\s+")))
            WalletCrypto.deriveKeyFromMnemonic(mnemonic)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get all wallets with their addresses (no mnemonics exposed) from session
     */
    @Throws(Exception::class)
    fun getAllWallets(context: Context): JSONArray {
        return getAllWallets(context, null)
    }

    /**
     * Get all wallets with their addresses from session
     */
    @Throws(Exception::class)
    fun getAllWallets(context: Context, securePrefs: SharedPreferences?): JSONArray {
        return try {
            if (!SessionManager.isSessionActive()) {
                return JSONArray()
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
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
            JSONArray()
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
     * Delete wallet at specific index using session
     */
    @Throws(Exception::class)
    fun deleteWallet(context: Context, securePrefs: SharedPreferences?, walletIndex: Int) {
        if (!SessionManager.isSessionActive()) {
            throw IllegalStateException("No active session - call startSession() first")
        }

        val sessionPrefs = SessionManager.createSessionPreferences(context)
        val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
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
        val currentSelected = sessionPrefs.getInt("selected_wallet_index", -1)
        val newSelected = when {
            newArray.length() == 0 -> -1
            walletIndex < currentSelected -> currentSelected - 1
            walletIndex == currentSelected -> if (currentSelected >= newArray.length()) newArray.length() - 1 else currentSelected
            else -> currentSelected
        }

        // Save changes
        val editor = sessionPrefs.edit()
        editor.putString("wallets", newArray.toString())
        editor.putInt("selected_wallet_index", newSelected)
        editor.apply()
    }

    /**
     * Select a wallet by index in current session
     */
    @Throws(Exception::class)
    fun selectWallet(context: Context, walletIndex: Int) {
        selectWallet(context, null, walletIndex)
    }

    /**
     * Select a wallet by index using session
     */
    @Throws(Exception::class)
    fun selectWallet(context: Context, securePrefs: SharedPreferences?, walletIndex: Int) {
        try {
            if (!SessionManager.isSessionActive()) {
                throw IllegalStateException("No active session - call startSession() first")
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
            val walletsArray = JSONArray(walletsJson)


            if (walletIndex < 0 || walletIndex >= walletsArray.length()) {
                throw IllegalArgumentException("Invalid wallet index: $walletIndex (total wallets: ${walletsArray.length()})")
            }

            val editor = sessionPrefs.edit()
            editor.putInt("selected_wallet_index", walletIndex)
            editor.apply()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to select wallet at index $walletIndex", e)
            throw e
        }
    }

    /**
     * Execute an operation with a specific wallet's mnemonic by index
     */
    @Throws(Exception::class)
    fun <T> executeWithWalletMnemonic(context: Context, walletIndex: Int, operation: MnemonicOperation<T>): T {
        return executeWithWalletMnemonic(context, null, walletIndex, operation)
    }

    /**
     * Execute an operation with a specific wallet's mnemonic by index using session
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
            if (!SessionManager.isSessionActive()) {
                throw IllegalStateException("No active session - call startSession() first")
            }

            val sessionPrefs = SessionManager.createSessionPreferences(context)
            val walletsJson = sessionPrefs.getString("wallets", "[]") ?: "[]"
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


}
