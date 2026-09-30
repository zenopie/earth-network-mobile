package network.erth.wallet.wallet

import android.content.Context
import android.util.Log
import network.erth.wallet.crypto.WalletCrypto
import org.bitcoinj.crypto.MnemonicCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wallets stored in the unlocked session.
 *
 * [SessionManager] owns the encryption and the session; this is the layer
 * above it that reads and writes the wallet list. Every call needs an active
 * session.
 *
 * Storage format, which existing installs depend on: `wallets` is a JSON
 * array of `{name, mnemonic, address}`, alongside `selected_wallet_index`.
 */
object SecureWalletManager {

    private const val TAG = "SecureWalletManager"

    /** One stored wallet, without its mnemonic. */
    data class WalletInfo(val index: Int, val name: String, val address: String)

    /**
     * Runs [operation] with the selected wallet's mnemonic.
     *
     * The mnemonic is read from the session for the length of the call and
     * not kept.
     */
    fun <T> executeWithMnemonic(context: Context, operation: (String) -> T): T {
        WalletCrypto.initialize(context)
        val mnemonic = selectedMnemonic()
        if (mnemonic.isEmpty()) {
            throw IllegalStateException("No wallet mnemonic found - wallet may not be initialized")
        }
        return operation(mnemonic)
    }

    /** The selected wallet's address. Throws when there is no session. */
    fun getWalletAddress(context: Context): String? =
        executeWithMnemonic(context) { WalletCrypto.getAddressFromMnemonic(it) }

    fun getCurrentWalletName(): String = try {
        if (!SessionManager.isSessionActive()) {
            "No session"
        } else {
            val wallets = SessionManager.wallets()
            if (wallets.length() > 0) {
                val index = SessionManager.selectedWalletIndex()
                    .takeIf { it in 0 until wallets.length() } ?: 0
                wallets.getJSONObject(index).optString("name", "Wallet ${index + 1}")
            } else {
                "No wallet"
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get current wallet name", e)
        "No wallet"
    }

    fun getWalletCount(): Int = try {
        if (!SessionManager.isSessionActive()) 0 else SessionManager.wallets().length()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get wallet count", e)
        0
    }

    fun getSelectedWalletIndex(): Int = try {
        if (!SessionManager.isSessionActive()) -1 else SessionManager.selectedWalletIndex()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get selected wallet index", e)
        -1
    }

    /**
     * Every wallet in the session, names and addresses only.
     *
     * The mnemonics stay where they are. A list is for choosing between
     * wallets and nothing on a list screen needs a seed phrase, so it is not
     * carried out of storage to sit in a UI model where it can be logged,
     * screenshotted or recomposed into a crash report.
     */
    fun listWallets(): List<WalletInfo> {
        if (!SessionManager.isSessionActive()) return emptyList()
        val wallets = SessionManager.wallets()
        return (0 until wallets.length()).map { i ->
            val w = wallets.getJSONObject(i)
            WalletInfo(
                index = i,
                name = w.optString("name", "Wallet ${i + 1}"),
                address = w.optString("address", ""),
            )
        }
    }

    fun generateMnemonic(context: Context): String {
        WalletCrypto.initialize(context)
        return WalletCrypto.generateMnemonic()
    }

    /**
     * Validate a BIP-39 mnemonic: every word in the list, and the checksum.
     *
     * Deriving a key is not a check. BIP-32 turns any string into a seed, so a
     * single mistyped word used to import cleanly as a different, empty wallet
     * — indistinguishable from funds having vanished. The checksum is what
     * catches that.
     */
    fun validateMnemonic(context: Context, mnemonic: String): Boolean = try {
        WalletCrypto.initialize(context)
        MnemonicCode.INSTANCE.check(mnemonic.trim().split(Regex("\\s+")))
        WalletCrypto.deriveKeyFromMnemonic(mnemonic)
        true
    } catch (e: Exception) {
        false
    }

    /** Adds a wallet and selects it. */
    fun createWallet(context: Context, walletName: String, mnemonic: String) {
        try {
            requireSession()
            WalletCrypto.initialize(context)
            val wallet = JSONObject()
                .put("name", walletName)
                .put("mnemonic", mnemonic)
                .put("address", WalletCrypto.getAddressFromMnemonic(mnemonic))

            val wallets = SessionManager.wallets().put(wallet)
            SessionManager.saveWallets(context, wallets)
            SessionManager.setSelectedWalletIndex(context, wallets.length() - 1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create wallet: $walletName", e)
            throw Exception("Failed to create wallet: ${e.message}")
        }
    }

    fun selectWallet(context: Context, walletIndex: Int) {
        requireSession()
        val count = SessionManager.wallets().length()
        require(walletIndex in 0 until count) {
            "Invalid wallet index: $walletIndex (total wallets: $count)"
        }
        SessionManager.setSelectedWalletIndex(context, walletIndex)
    }

    /**
     * The selected wallet's mnemonic, or "" when there is none. An
     * out-of-range selection falls back to the only wallet when there is
     * exactly one.
     */
    private fun selectedMnemonic(): String = try {
        requireSession()
        val wallets = SessionManager.wallets()
        val selected = SessionManager.selectedWalletIndex()
        when {
            selected in 0 until wallets.length() ->
                wallets.getJSONObject(selected).optString("mnemonic", "")
            wallets.length() == 1 -> wallets.getJSONObject(0).optString("mnemonic", "")
            else -> ""
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch mnemonic from session", e)
        throw Exception("Failed to retrieve wallet mnemonic from session", e)
    }

    private fun requireSession() {
        if (!SessionManager.isSessionActive()) {
            throw IllegalStateException("No active session - call startSession() first")
        }
    }
}
