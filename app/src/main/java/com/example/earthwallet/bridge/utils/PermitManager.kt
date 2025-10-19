package network.erth.wallet.bridge.utils

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import network.erth.wallet.wallet.utils.SecurePreferencesUtil
import network.erth.wallet.wallet.services.SecureWalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import io.eqoty.secretk.extensions.accesscontrol.PermitFactory
import io.eqoty.secretk.wallet.DirectSigningWallet
import io.eqoty.secret.std.types.Permit
import io.eqoty.secret.std.types.Permission
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * PermitManager
 *
 * General-purpose utility class for managing SNIP-24 permits across the application.
 * Provides a consistent interface for storing, retrieving, and checking permits.
 * Replaces the old viewing key system with the more user-friendly permit system.
 *
 * Usage:
 *   val permitManager = PermitManager.getInstance(context)
 *   val permit = permitManager.getPermit(walletAddress, contractAddress)
 *   permitManager.setPermit(walletAddress, permit)
 */
class PermitManager private constructor(context: Context) {

    companion object {
        private const val TAG = "PermitManager"
        private const val PREF_FILE = "permits_prefs"

        @Volatile
        private var instance: PermitManager? = null

        /**
         * Get singleton instance of PermitManager
         */
        @JvmStatic
        fun getInstance(context: Context): PermitManager {
            return instance ?: synchronized(this) {
                instance ?: PermitManager(context).also { instance = it }
            }
        }

        /**
         * Create encrypted shared preferences for secure storage
         */
        private fun createSecurePrefs(context: Context): SharedPreferences {
            return try {
                SecurePreferencesUtil.createEncryptedPreferences(context, PREF_FILE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create secure preferences", e)
                throw RuntimeException("Secure preferences initialization failed", e)
            }
        }
    }

    private val securePrefs: SharedPreferences

    init {
        try {
            securePrefs = createSecurePrefs(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize secure preferences", e)
            throw RuntimeException("PermitManager initialization failed", e)
        }
    }

    /**
     * Get permit for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return The permit as JSON string (from Json.encodeToString), or null if not found
     */
    fun getPermit(walletAddress: String, contractAddress: String): String? {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return null
        }

        val key = "permit_${walletAddress}_$contractAddress"
        val permitJson = securePrefs.getString(key, "") ?: ""

        if (TextUtils.isEmpty(permitJson)) {
            return null
        }

        return permitJson
    }

    /**
     * Set permit for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @param permit The permit to store
     */
    fun setPermit(walletAddress: String, contractAddress: String, permit: Permit) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            Log.e(TAG, "Cannot set permit: invalid parameters")
            return
        }

        try {
            val key = "permit_${walletAddress}_$contractAddress"
            // Use SecretK's kotlinx.serialization exactly like the example
            val permitJson = Json.encodeToString(permit)
            securePrefs.edit().putString(key, permitJson).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize permit", e)
        }
    }

    /**
     * Create and set a permit for multiple contracts with default permissions
     * @param context The application context
     * @param walletAddress The wallet address
     * @param contractAddresses List of contract addresses
     * @param permitName The permit name (e.g., app name)
     * @param permissions List of permissions (balance, history, allowance, owner)
     * @return The created permit
     */
    suspend fun createPermit(
        context: Context,
        walletAddress: String,
        contractAddresses: List<String>,
        permitName: String,
        permissions: List<String>
    ): Permit? {
        if (TextUtils.isEmpty(walletAddress) || contractAddresses.isEmpty()) {
            Log.e(TAG, "Cannot create permit: invalid parameters")
            return null
        }

        return try {
            // Get SecretK wallet from mnemonic
            val wallet = SecureWalletManager.executeWithSecureMnemonic(context) { mnemonicChars ->
                val mnemonicString = String(mnemonicChars)
                DirectSigningWallet(mnemonicString)
            }

            val derivedAddress = wallet.accounts.first().address
            if (walletAddress != derivedAddress) {
                throw Exception("Wallet address mismatch")
            }

            // Convert string permissions to Permission enum
            val secretKPermissions = permissions.mapNotNull { permission ->
                when (permission.lowercase()) {
                    "balance" -> Permission.Balance
                    "history" -> Permission.History
                    "allowance" -> Permission.Allowance
                    "owner" -> Permission.Owner
                    else -> {
                        Log.w(TAG, "Unknown permission: $permission")
                        null
                    }
                }
            }

            // Create permit using SecretK PermitFactory - returns complete signed permit
            val signedPermit = PermitFactory.newPermit(
                wallet = wallet,
                owner = walletAddress,
                chainId = "secret-4",
                permitName = permitName,
                allowedTokens = contractAddresses,
                permissions = secretKPermissions
            )

            // Store signed permit for each contract
            for (contractAddress in contractAddresses) {
                setPermit(walletAddress, contractAddress, signedPermit)
            }
            signedPermit

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create signed permit", e)
            null
        }
    }

    /**
     * Check if a permit exists for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return true if permit exists, false otherwise
     */
    fun hasPermit(walletAddress: String, contractAddress: String): Boolean {
        return getPermit(walletAddress, contractAddress) != null
    }

    /**
     * Remove permit for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     */
    fun removePermit(walletAddress: String, contractAddress: String) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return
        }

        val key = "permit_${walletAddress}_$contractAddress"
        securePrefs.edit().remove(key).apply()
    }

}