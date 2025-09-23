package com.example.earthwallet.bridge.utils

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import com.example.earthwallet.wallet.utils.SecurePreferencesUtil
import com.example.earthwallet.bridge.models.Permit
import com.example.earthwallet.bridge.models.PermitSignDoc
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.example.earthwallet.wallet.services.TransactionSigner
import com.google.gson.Gson
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.*

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
    private val gson: Gson

    init {
        try {
            securePrefs = createSecurePrefs(context.applicationContext)
            gson = Gson()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize secure preferences", e)
            throw RuntimeException("PermitManager initialization failed", e)
        }
    }

    /**
     * Get permit for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return The permit, or null if not found
     */
    fun getPermit(walletAddress: String, contractAddress: String): Permit? {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return null
        }

        val key = "permit_${walletAddress}_$contractAddress"
        val permitJson = securePrefs.getString(key, "") ?: ""

        if (TextUtils.isEmpty(permitJson)) {
            return null
        }

        return try {
            val permit = gson.fromJson(permitJson, Permit::class.java)

            // Perform integrity checks on the retrieved permit
            if (permit == null) {
                return null
            }

            // Check if permit structure is valid
            if (!permit.isValid()) {
                removePermit(walletAddress, contractAddress) // Remove corrupted permit
                return null
            }

            // Validate permit signature to ensure integrity
            if (!validatePermitSignature(permit, walletAddress)) {
                removePermit(walletAddress, contractAddress) // Remove corrupted/tampered permit
                return null
            }

            permit

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize or validate permit", e)
            // Remove potentially corrupted permit
            removePermit(walletAddress, contractAddress)
            null
        }
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
            val permitJson = gson.toJson(permit)
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
     * @param permissions List of permissions (balance, history, allowance)
     * @return The created permit
     */
    fun createPermit(
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
            // Use SecureWalletManager to sign the permit with secure mnemonic handling
            val signedPermit = SecureWalletManager.executeWithSecureMnemonic(context) { mnemonicChars ->
                // Get wallet key from secure mnemonic char array
                val walletKey = com.example.earthwallet.wallet.utils.WalletCrypto.deriveKeyFromSecureMnemonic(mnemonicChars)
                val derivedAddress = com.example.earthwallet.wallet.utils.WalletCrypto.getAddress(walletKey)

                if (walletAddress != derivedAddress) {
                    throw Exception("Wallet address mismatch")
                }

                // Create permit sign document
                val signDoc = PermitSignDoc("secret-4", permitName, contractAddresses, permissions)

                // Serialize and sign
                val signDocJson = gson.toJson(signDoc)
                val signDocBytes = signDocJson.toByteArray(Charsets.UTF_8)

                // Sign the document using TransactionSigner
                val signature = TransactionSigner.createSignature(signDocBytes, walletKey)

                // Create signed permit
                val permit = Permit(permitName, contractAddresses, permissions)
                permit.signature = Base64.getEncoder().encodeToString(signature.bytes)
                permit.publicKey = Base64.getEncoder().encodeToString(signature.getPublicKey())

                permit
            }

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
     * @return true if permit exists and is valid, false otherwise
     */
    fun hasPermit(walletAddress: String, contractAddress: String): Boolean {
        val permit = getPermit(walletAddress, contractAddress)
        return permit != null && permit.isValid()
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

    /**
     * Check if a permit allows a specific permission for a contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @param permission The permission to check (balance, history, allowance)
     * @return true if permit exists and allows the permission, false otherwise
     */
    fun hasPermission(walletAddress: String, contractAddress: String, permission: String): Boolean {
        val permit = getPermit(walletAddress, contractAddress)
        return permit != null && permit.hasPermission(permission)
    }

    /**
     * Get all permits for a specific wallet address
     * @param walletAddress The wallet address
     * @return A map of contract addresses to permits
     */
    fun getAllPermits(walletAddress: String): Map<String, Permit> {
        val permits = mutableMapOf<String, Permit>()

        if (TextUtils.isEmpty(walletAddress)) {
            return permits
        }

        try {
            val allPrefs = securePrefs.all
            val keyPrefix = "permit_${walletAddress}_"

            for ((prefKey, value) in allPrefs) {
                if (prefKey.startsWith(keyPrefix)) {
                    val contractAddress = prefKey.substring(keyPrefix.length)
                    val permitJson = value as? String

                    if (!TextUtils.isEmpty(permitJson)) {
                        try {
                            val permit = gson.fromJson(permitJson, Permit::class.java)
                            if (permit != null && permit.isValid()) {
                                permits[contractAddress] = permit
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all permits", e)
        }

        return permits
    }

    /**
     * Clear all permits for a specific wallet address
     * @param walletAddress The wallet address
     */
    fun clearAllPermits(walletAddress: String) {
        if (TextUtils.isEmpty(walletAddress)) {
            return
        }

        try {
            val allPrefs = securePrefs.all
            val keyPrefix = "permit_${walletAddress}_"

            val editor = securePrefs.edit()

            for (prefKey in allPrefs.keys) {
                if (prefKey.startsWith(keyPrefix)) {
                    editor.remove(prefKey)
                }
            }

            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear permits", e)
        }
    }

    /**
     * Validate a permit signature against the expected wallet address
     * @param permit The permit to validate
     * @param walletAddress The expected wallet address
     * @return true if permit signature is valid, false otherwise
     */
    fun validatePermitSignature(permit: Permit, walletAddress: String): Boolean {
        if (!permit.isValid() || TextUtils.isEmpty(walletAddress)) {
            return false
        }

        return try {
            // Implement proper signature validation
            // 1. Recreate the PermitSignDoc used for signing
            val signDoc = PermitSignDoc(
                "secret-4",
                permit.permitName ?: "",
                permit.allowedTokens ?: emptyList(),
                permit.permissions ?: emptyList()
            )

            // 2. Serialize to the same JSON format used for signing
            val signDocJson = gson.toJson(signDoc)
            val signDocBytes = signDocJson.toByteArray(Charsets.UTF_8)

            // 3. Decode the stored signature and public key
            val signatureBytes = Base64.getDecoder().decode(permit.signature ?: return false)
            val publicKeyBytes = Base64.getDecoder().decode(permit.publicKey ?: return false)

            // 4. Verify the signature using the public key
            val publicKey = ECKey.fromPublicOnly(publicKeyBytes)
            val derivedAddress = com.example.earthwallet.wallet.utils.WalletCrypto.getAddress(publicKey)

            // 5. Check if derived address matches expected wallet address
            if (walletAddress != derivedAddress) {
                return false
            }

            // 6. Verify the signature against the sign document
            // Hash the sign document bytes
            val hash = Sha256Hash.of(signDocBytes)

            // Parse signature bytes from raw 64-byte format (32-byte r + 32-byte s)
            val ecdsaSignature = parseRawSignature(signatureBytes)
            val isValid = publicKey.verify(hash, ecdsaSignature)

            isValid

        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate permit signature", e)
            false
        }
    }

    /**
     * Check if a permit is expired based on timestamp
     * @param permit The permit to check
     * @param maxAgeMs Maximum age in milliseconds (default 24 hours)
     * @return true if permit is still valid, false if expired
     */
    fun isPermitValid(permit: Permit?, maxAgeMs: Long): Boolean {
        if (permit == null || !permit.isValid()) {
            return false
        }

        val currentTime = System.currentTimeMillis()
        val permitAge = currentTime - permit.timestamp

        return permitAge <= maxAgeMs
    }

    /**
     * Check if a permit is valid with default 24-hour expiration
     */
    fun isPermitValid(permit: Permit?): Boolean {
        return isPermitValid(permit, 24 * 60 * 60 * 1000L) // 24 hours in milliseconds
    }

    /**
     * Get a valid permit for querying (checks existence, validation, and expiration)
     * @param walletAddress The wallet address
     * @param contractAddress The contract address
     * @param permission The required permission
     * @return A valid permit, or null if none exists or is valid
     */
    fun getValidPermitForQuery(walletAddress: String, contractAddress: String, permission: String): Permit? {
        val permit = getPermit(walletAddress, contractAddress)

        if (permit == null) {
            return null
        }

        if (!isPermitValid(permit)) {
            removePermit(walletAddress, contractAddress) // Clean up expired permit
            return null
        }

        if (!permit.hasPermission(permission)) {
            return null
        }

        if (!validatePermitSignature(permit, walletAddress)) {
            removePermit(walletAddress, contractAddress) // Remove invalid permit
            return null
        }

        return permit
    }

    /**
     * Format a permit for SNIP-24 query structure (corrected to match SNIP-24 spec)
     * @param permit The permit to format
     * @param chainId The chain ID (e.g., "secret-4")
     * @return JSON object with permit structure for queries
     */
    fun formatPermitForQuery(permit: Permit, chainId: String): JSONObject {
        if (!permit.isValid()) {
            throw Exception("Invalid permit for query formatting")
        }

        val permitObj = JSONObject()

        // SNIP-24 params structure
        val params = JSONObject().apply {
            put("chain_id", chainId)
            put("permit_name", permit.permitName)
            put("allowed_tokens", JSONArray(permit.allowedTokens))
            put("permissions", JSONArray(permit.permissions))
        }

        permitObj.put("params", params)

        // Signature section with proper structure
        val signature = JSONObject()

        // Public key with base64 encoding
        val pubKey = JSONObject().apply {
            put("type", "tendermint/PubKeySecp256k1")
            put("value", permit.publicKey ?: "") // Should be base64 encoded
        }
        signature.put("pub_key", pubKey)

        // Signature should be base64 encoded
        signature.put("signature", permit.signature ?: "")

        permitObj.put("signature", signature)

        return permitObj
    }

    /**
     * Create a with_permit query structure for SNIP-24
     * @param innerQuery The actual query (e.g., balance query)
     * @param permit The permit to use
     * @param chainId The chain ID
     * @return Complete with_permit query structure
     */
    fun createWithPermitQuery(innerQuery: JSONObject, permit: Permit, chainId: String): JSONObject {
        val withPermit = JSONObject()
        val permitQuery = JSONObject()

        permitQuery.put("permit", formatPermitForQuery(permit, chainId))
        permitQuery.put("query", innerQuery)

        withPermit.put("with_permit", permitQuery)
        return withPermit
    }

    /**
     * TEMPORARY LEGACY COMPATIBILITY - to be removed
     * This method returns empty string to avoid compilation errors in other fragments
     * that are still being updated
     */
    @Deprecated("Use hasPermit() instead")
    fun getViewingKey(walletAddress: String, contractAddress: String): String {
        return if (hasPermit(walletAddress, contractAddress)) "permit_exists" else ""
    }

    /**
     * Parse raw 64-byte signature format back to ECDSASignature
     * @param rawSignature The raw signature bytes (32-byte r + 32-byte s)
     * @return ECDSASignature object
     */
    private fun parseRawSignature(rawSignature: ByteArray): ECKey.ECDSASignature {
        if (rawSignature.size != 64) {
            throw Exception("Invalid raw signature length: ${rawSignature.size} (expected 64)")
        }

        // Extract r and s components (32 bytes each)
        val rBytes = rawSignature.sliceArray(0..31)
        val sBytes = rawSignature.sliceArray(32..63)

        // Convert to BigInteger
        val r = BigInteger(1, rBytes)
        val s = BigInteger(1, sBytes)

        return ECKey.ECDSASignature(r, s)
    }
}