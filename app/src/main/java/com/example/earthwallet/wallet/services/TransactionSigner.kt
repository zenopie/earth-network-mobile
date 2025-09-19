package com.example.earthwallet.wallet.services

import android.util.Log
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import com.google.protobuf.ByteString
import cosmos.tx.v1beta1.Tx
import com.example.earthwallet.wallet.utils.WalletCrypto
import kotlin.math.max
import kotlin.math.min

/**
 * General purpose transaction signer for Secret Network
 * Handles all signature operations with proper validation and error handling
 */
object TransactionSigner {

    private const val TAG = "TransactionSigner"

    /**
     * Signs a transaction with the provided wallet key
     * @param signDoc The SignDoc protobuf to sign
     * @param walletKey The wallet key to sign with
     * @return Signed transaction bytes ready for broadcast
     */
    @JvmStatic
    @Throws(Exception::class)
    fun signTransaction(signDoc: Tx.SignDoc, walletKey: ECKey): ByteArray {
        Log.d(TAG, "Signing transaction with wallet key")

        try {
            // 1. Serialize SignDoc for signing
            val signDocBytes = signDoc.toByteArray()
            Log.d(TAG, "SignDoc serialized: ${signDocBytes.size} bytes")

            // 2. Create signature
            val signature = createSignature(signDocBytes, walletKey)
            Log.d(TAG, "Signature created: ${signature.length} bytes")

            // 3. Build final transaction
            val txRaw = buildSignedTransaction(signDoc, signature)

            val txBytes = txRaw.toByteArray()
            Log.i(TAG, "Signed transaction ready: ${txBytes.size} bytes")

            return txBytes

        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign transaction", e)
            throw Exception("Transaction signing failed: ${e.message}")
        }
    }

    /**
     * Creates a signature for the given data
     */
    @JvmStatic
    @Throws(Exception::class)
    fun createSignature(data: ByteArray, walletKey: ECKey): TransactionSignature {
        Log.d(TAG, "Creating signature for ${data.size} bytes of data")

        // Hash the data
        val hash = Sha256Hash.of(data)

        // Sign the hash
        val ecdsaSignature = walletKey.sign(hash)

        // Get public key for verification
        val publicKey = walletKey.pubKey

        // Create our signature wrapper
        return TransactionSignature(ecdsaSignature, publicKey)
    }

    /**
     * Builds the final signed transaction
     */
    @Throws(Exception::class)
    private fun buildSignedTransaction(
        signDoc: Tx.SignDoc,
        signature: TransactionSignature
    ): Tx.TxRaw {

        return Tx.TxRaw.newBuilder()
            .setBodyBytes(signDoc.bodyBytes)
            .setAuthInfoBytes(signDoc.authInfoBytes)
            .addSignatures(ByteString.copyFrom(signature.bytes))
            .build()
    }

    /**
     * Validates that a wallet key matches a sender address
     */
    @JvmStatic
    @Throws(Exception::class)
    fun validateWalletMatchesSender(senderAddress: String, walletKey: ECKey) {
        val walletAddress = WalletCrypto.getAddress(walletKey)

        if (walletAddress != senderAddress) {
            throw Exception("Wallet/sender mismatch: " +
                "Expected: $senderAddress, " +
                "Wallet derives to: $walletAddress")
        }

        Log.d(TAG, "✅ Wallet validation passed")
    }

    /**
     * Wrapper class for transaction signatures
     */
    class TransactionSignature @Throws(Exception::class) constructor(
        private val ecdsaSignature: ECKey.ECDSASignature,
        private val publicKey: ByteArray
    ) {
        private val signatureBytes: ByteArray

        init {
            // Use raw 64-byte format for Cosmos compatibility
            signatureBytes = createRawSignature(ecdsaSignature)

            Log.d(TAG, "Signature created: ${signatureBytes.size} bytes (Raw format)")
        }

        /**
         * Converts ECDSA signature to raw 64-byte format (32-byte r + 32-byte s)
         */
        @Throws(Exception::class)
        private fun createRawSignature(signature: ECKey.ECDSASignature): ByteArray {
            val rBytes = signature.r.toByteArray()
            val sBytes = signature.s.toByteArray()

            // Create 64-byte array
            val result = ByteArray(64)

            // Copy r to first 32 bytes (right-aligned)
            val rStart = max(0, rBytes.size - 32)
            val rDest = max(0, 32 - rBytes.size)
            val rLen = min(32, rBytes.size)
            System.arraycopy(rBytes, rStart, result, rDest, rLen)

            // Copy s to last 32 bytes (right-aligned)
            val sStart = max(0, sBytes.size - 32)
            val sDest = max(0, 64 - sBytes.size)
            val sLen = min(32, sBytes.size)
            System.arraycopy(sBytes, sStart, result, sDest, sLen)

            return result
        }

        val bytes: ByteArray
            get() = signatureBytes

        val length: Int
            get() = signatureBytes.size

        fun getPublicKey(): ByteArray {
            return publicKey
        }

        val hex: String
            get() {
                val hex = StringBuilder()
                for (b in signatureBytes) {
                    hex.append(String.format("%02x", b))
                }
                return hex.toString()
            }

        // Future enhancement: Add method to convert to raw format
        @Throws(Exception::class)
        fun getRawBytes(): ByteArray {
            // TODO: Convert DER to raw 64-byte format for Cosmos compatibility
            throw UnsupportedOperationException("Raw format not yet implemented")
        }
    }
}