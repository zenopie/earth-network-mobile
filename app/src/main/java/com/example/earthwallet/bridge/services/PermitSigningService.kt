package com.example.earthwallet.bridge.services

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.earthwallet.bridge.models.Permit
import com.example.earthwallet.bridge.models.PermitSignDoc
import com.example.earthwallet.bridge.utils.PermitManager
import com.example.earthwallet.wallet.services.TransactionSigner
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.google.gson.Gson
import org.bitcoinj.core.ECKey
import java.util.Base64

/**
 * Service for signing SNIP-24 permits
 * Integrates with TransactionActivity for confirmation and signing flow
 */
object PermitSigningService {

    private const val TAG = "PermitSigningService"
    private const val CHAIN_ID = "secret-4" // Secret Network mainnet

    /**
     * Execute permit signing through the standard transaction flow
     * Expected intent extras:
     * - permit_name: The permit name (e.g., app name)
     * - allowed_tokens: Comma-separated list of contract addresses
     * - permissions: Comma-separated list of permissions (balance,history,allowance)
     */
    @JvmStatic
    @Throws(Exception::class)
    fun execute(context: Context, intent: Intent): Array<String> {

        // Extract parameters
        val permitName = intent.getStringExtra("permit_name")
        val allowedTokensStr = intent.getStringExtra("allowed_tokens")
        val permissionsStr = intent.getStringExtra("permissions")

        if (permitName == null || allowedTokensStr == null || permissionsStr == null) {
            throw Exception("Missing required parameters for permit signing")
        }

        val allowedTokens = allowedTokensStr.split(",")
        val permissions = permissionsStr.split(",")


        // Use SecureWalletManager for just-in-time mnemonic access with automatic cleanup
        return SecureWalletManager.executeWithMnemonic(context) { mnemonic ->

            // Get wallet key and address from mnemonic
            val walletKey = com.example.earthwallet.wallet.utils.WalletCrypto.deriveKeyFromMnemonic(mnemonic)
            val walletAddress = com.example.earthwallet.wallet.utils.WalletCrypto.getAddress(walletKey)

            if (walletKey == null || walletAddress == null) {
                throw Exception("No wallet available for signing")
            }

            try {
                // Create permit sign document
                val signDoc = PermitSignDoc(CHAIN_ID, permitName, allowedTokens, permissions)

                // Serialize for signing (following Cosmos SDK amino JSON format)
                val gson = Gson()
                val signDocJson = gson.toJson(signDoc)
                val signDocBytes = signDocJson.toByteArray(Charsets.UTF_8)


                // Sign the document
                val signature = TransactionSigner.createSignature(signDocBytes, walletKey)

                // Create permit with base64-encoded signature and public key
                val permit = Permit(permitName, allowedTokens, permissions).apply {
                    this.signature = Base64.getEncoder().encodeToString(signature.bytes)
                    this.publicKey = Base64.getEncoder().encodeToString(signature.getPublicKey())
                }


                // Store permit for each token contract
                val permitManager = PermitManager.getInstance(context)
                for (contractAddress in allowedTokens) {
                    permitManager.setPermit(walletAddress, contractAddress, permit)
                }

                // Return success result in expected format [result, senderAddress]
                val resultJson = gson.toJson(permit)
                arrayOf(resultJson, walletAddress)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to sign permit", e)
                throw Exception("Permit signing failed: ${e.message}")
            }
        }
    }

    /**
     * Create an intent for permit signing via TransactionActivity
     */
    @JvmStatic
    fun createPermitIntent(
        context: Context,
        permitName: String,
        allowedTokens: List<String>,
        permissions: List<String>
    ): Intent {
        return Intent(context, com.example.earthwallet.bridge.activities.TransactionActivity::class.java).apply {
            putExtra(com.example.earthwallet.bridge.activities.TransactionActivity.EXTRA_TRANSACTION_TYPE, "permit_signing")
            putExtra("permit_name", permitName)
            putExtra("allowed_tokens", allowedTokens.joinToString(","))
            putExtra("permissions", permissions.joinToString(","))
        }
    }

    /**
     * Helper method to convert bytes to hex string
     */
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}