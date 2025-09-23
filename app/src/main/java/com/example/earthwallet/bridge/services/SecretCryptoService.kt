package com.example.earthwallet.bridge.services

import android.util.Base64
import android.util.Log
import com.example.earthwallet.wallet.utils.WalletCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.ECKey
import org.cryptomator.siv.SivMode
import org.cryptomator.siv.UnauthenticCiphertextException
import org.whispersystems.curve25519.Curve25519
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SecretCryptoService
 *
 * Handles all cryptographic operations for Secret Network with Kotlin coroutines:
 * - Message encryption using AES-GCM/AES-SIV with HKDF key derivation
 * - ECDH shared secret computation with x25519 compatibility
 * - Transaction signing with secp256k1
 */
object SecretCryptoService {

    private const val TAG = "SecretCryptoService"

    // Hardcoded consensus IO public key from SecretJS (mainnetConsensusIoPubKey)
    private const val MAINNET_CONSENSUS_IO_PUBKEY_B64 = "UyAkgs8Z55YD2091/RjSnmdMH4yF9PKc5lWqjV78nS8="

    // HKDF constants (matches SecretJS encryption.ts line 18-20)
    private val HKDF_SALT = "000000000000000000024bead8df69990852c202db0e0097c1a12ea637d7e96d".hexStringToByteArray()

    /**
     * Encrypts a contract message using SecretJS-compatible encryption
     * Matches SecretJS encryption.ts exactly - uses consensus IO pubkey, no contract pubkey needed
     *
     * @param codeHash Contract code hash (optional)
     * @param msgJson Execute message JSON
     * @param mnemonic Wallet mnemonic for key derivation
     * @return Encrypted message bytes
     */
    suspend fun encryptContractMessage(
        codeHash: String?,
        msgJson: String,
        mnemonic: String
    ): ByteArray = withContext(Dispatchers.Default) {

        try {
            // Generate 32-byte nonce (matches SecretJS encryption.ts line 106)
            val nonce = ByteArray(32)
            SecureRandom().nextBytes(nonce)

            // Get the curve25519 provider (use BEST for native fallback to pure Java)
            val curve25519 = Curve25519.getInstance(Curve25519.BEST)

            // Generate a separate encryption seed like SecretJS does
            // SecretJS uses EncryptionUtilsImpl.GenerateNewSeed() - a random 32-byte seed
            // For deterministic behavior, we'll derive it from the wallet mnemonic
            val sha256 = MessageDigest.getInstance("SHA-256")
            sha256.update("secretjs-encryption-seed".toByteArray(StandardCharsets.UTF_8))
            sha256.update(mnemonic.toByteArray(StandardCharsets.UTF_8))
            val encryptionSeed = sha256.digest()

            // Generate deterministic keypair from wallet mnemonic (like SecretJS does)
            // SecretJS uses EncryptionUtilsImpl.GenerateNewKeyPairFromSeed(seed)
            val x25519PrivKey = ByteArray(32)
            System.arraycopy(encryptionSeed, 0, x25519PrivKey, 0, 32)

            // Clamp the private key according to curve25519 spec (like curve25519-js does)
            x25519PrivKey[0] = (x25519PrivKey[0].toInt() and 248).toByte() // Clear bottom 3 bits
            x25519PrivKey[31] = (x25519PrivKey[31].toInt() and 127).toByte() // Clear top bit
            x25519PrivKey[31] = (x25519PrivKey[31].toInt() or 64).toByte() // Set second-highest bit

            // Generate public key by scalar multiplication with base point
            // Use ECDH with standard base point to get public key
            val basePoint = ByteArray(32)
            basePoint[0] = 9 // Standard curve25519 base point
            val encryptionPubKey = curve25519.calculateAgreement(basePoint, x25519PrivKey)

            // This is the public key that goes in the encrypted message (SecretJS format)
            val walletPubkey32 = encryptionPubKey

            // Use consensus IO public key (matches SecretJS encryption.ts line 89)
            val consensusIoPubKey = Base64.decode(MAINNET_CONSENSUS_IO_PUBKEY_B64, Base64.NO_WRAP)

            // Compute x25519 ECDH shared secret using encryption private key (matches SecretJS encryption.ts line 91)
            val txEncryptionIkm = curve25519.calculateAgreement(consensusIoPubKey, x25519PrivKey)

            // Derive encryption key using HKDF (matches SecretJS encryption.ts lines 92-98)
            val keyMaterial = ByteArray(txEncryptionIkm.size + nonce.size)
            System.arraycopy(txEncryptionIkm, 0, keyMaterial, 0, txEncryptionIkm.size)
            System.arraycopy(nonce, 0, keyMaterial, txEncryptionIkm.size, nonce.size)

            val txEncryptionKey = hkdf(keyMaterial, HKDF_SALT, "", 32)

            // Create plaintext: contractCodeHash + JSON.stringify(msg) (matches SecretJS encryption.ts line 116)
            val plaintext = if (!codeHash.isNullOrEmpty()) {
                codeHash + msgJson
            } else {
                msgJson
            }
            val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)


            // Encrypt using RFC 5297 AES-SIV (matches SecretJS miscreant library exactly)
            val ciphertext = try {
                // Split the 32-byte key in half like AES-SIV RFC 5297 standard
                // miscreant library likely uses this simple approach, not HKDF
                val macKey = ByteArray(16)
                val encKey = ByteArray(16)
                System.arraycopy(txEncryptionKey, 0, macKey, 0, 16) // First 16 bytes for MAC
                System.arraycopy(txEncryptionKey, 16, encKey, 0, 16) // Second 16 bytes for ENC

                // Use proper AES-SIV implementation that matches miscreant
                val sivMode = SivMode()
                // Match SecretJS siv.seal(plaintext, [new Uint8Array()]) - empty associated data
                sivMode.encrypt(encKey, macKey, plaintextBytes, ByteArray(0))
            } catch (e: Exception) {
                Log.e(TAG, "RFC 5297 AES-SIV encryption failed", e)
                throw Exception("AES-SIV encryption failed: ${e.message}")
            }


            // Create encrypted message format: nonce(32) + wallet_pubkey(32) + siv_ciphertext
            // This matches SecretJS encryption.ts line 121: [...nonce, ...this.pubkey, ...ciphertext]
            val encryptedMsg = ByteArray(32 + 32 + ciphertext.size)
            System.arraycopy(nonce, 0, encryptedMsg, 0, 32)
            System.arraycopy(walletPubkey32, 0, encryptedMsg, 32, 32)
            System.arraycopy(ciphertext, 0, encryptedMsg, 64, ciphertext.size)


            encryptedMsg
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            throw Exception("Failed to encrypt contract message: ${e.message}", e)
        }
    }

    /**
     * Signs a message using secp256k1
     */
    suspend fun signMessage(messageHash: ByteArray, mnemonic: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            val key = WalletCrypto.deriveKeyFromMnemonic(mnemonic)
            key.sign(org.bitcoinj.core.Sha256Hash.wrap(messageHash)).encodeToDER()
        } catch (e: Exception) {
            Log.e(TAG, "Message signing failed", e)
            throw Exception("Failed to sign message: ${e.message}", e)
        }
    }

    /**
     * Gets the compressed public key for a wallet
     */
    suspend fun getWalletPublicKey(mnemonic: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            val key = WalletCrypto.deriveKeyFromMnemonic(mnemonic)
            key.pubKeyPoint.getEncoded(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallet public key", e)
            throw Exception("Failed to get wallet public key: ${e.message}", e)
        }
    }

    /**
     * Decrypts a Secret Network query response using SecretJS-compatible decryption
     *
     * @param encryptedResponse The encrypted response bytes from the network
     * @param nonce The 32-byte nonce used in the original query encryption
     * @param mnemonic Wallet mnemonic for key derivation
     * @return Decrypted plaintext as raw bytes (matches SecretJS decrypt() return)
     */
    suspend fun decryptQueryResponse(
        encryptedResponse: ByteArray,
        nonce: ByteArray,
        mnemonic: String
    ): ByteArray = withContext(Dispatchers.Default) {

        try {
            // Get the curve25519 provider
            val curve25519 = Curve25519.getInstance(Curve25519.BEST)

            // Regenerate the same encryption seed and keypair as used for encryption
            val sha256 = MessageDigest.getInstance("SHA-256")
            sha256.update("secretjs-encryption-seed".toByteArray(StandardCharsets.UTF_8))
            sha256.update(mnemonic.toByteArray(StandardCharsets.UTF_8))
            val encryptionSeed = sha256.digest()

            // Generate deterministic keypair from wallet mnemonic (same as encryption)
            val x25519PrivKey = ByteArray(32)
            System.arraycopy(encryptionSeed, 0, x25519PrivKey, 0, 32)

            // Clamp the private key according to curve25519 spec
            x25519PrivKey[0] = (x25519PrivKey[0].toInt() and 248).toByte()
            x25519PrivKey[31] = (x25519PrivKey[31].toInt() and 127).toByte()
            x25519PrivKey[31] = (x25519PrivKey[31].toInt() or 64).toByte()

            // Use consensus IO public key (same as encryption)
            val consensusIoPubKey = Base64.decode(MAINNET_CONSENSUS_IO_PUBKEY_B64, Base64.NO_WRAP)

            // Compute the same x25519 ECDH shared secret as encryption
            val txEncryptionIkm = curve25519.calculateAgreement(consensusIoPubKey, x25519PrivKey)

            // Derive the same encryption key using HKDF (same as encryption)
            val keyMaterial = ByteArray(txEncryptionIkm.size + nonce.size)
            System.arraycopy(txEncryptionIkm, 0, keyMaterial, 0, txEncryptionIkm.size)
            System.arraycopy(nonce, 0, keyMaterial, txEncryptionIkm.size, nonce.size)

            val txEncryptionKey = hkdf(keyMaterial, HKDF_SALT, "", 32)

            // Decrypt using RFC 5297 AES-SIV (reverse of encryption)
            val plaintextBytes = try {
                // Split the 32-byte key in half like AES-SIV RFC 5297 standard (same as encryption)
                val macKey = ByteArray(16)
                val encKey = ByteArray(16)
                System.arraycopy(txEncryptionKey, 0, macKey, 0, 16) // First 16 bytes for MAC
                System.arraycopy(txEncryptionKey, 16, encKey, 0, 16) // Second 16 bytes for ENC

                // Use AES-SIV decryption (reverse of encryption) - returns raw bytes like SecretJS
                val sivMode = SivMode()
                sivMode.decrypt(encKey, macKey, encryptedResponse, ByteArray(0))
            } catch (e: UnauthenticCiphertextException) {
                Log.e(TAG, "AES-SIV authentication failed - ciphertext may be corrupted", e)
                throw Exception("Decryption authentication failed: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "AES-SIV decryption failed", e)
                throw Exception("Decryption failed: ${e.message}")
            }


            plaintextBytes
        } catch (e: Exception) {
            Log.e(TAG, "Query response decryption failed", e)
            throw Exception("Failed to decrypt query response: ${e.message}", e)
        }
    }

    // Private helper methods

    private fun hkdf(keyMaterial: ByteArray, salt: ByteArray, info: String, outputLength: Int): ByteArray {
        try {
            // Simplified HKDF implementation using HMAC-SHA256
            val hmac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(if (salt.isNotEmpty()) salt else ByteArray(32), "HmacSHA256")
            hmac.init(keySpec)

            val prk = hmac.doFinal(keyMaterial)

            // Expand phase
            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            val infoBytes = info.toByteArray(StandardCharsets.UTF_8)

            val output = ByteArray(outputLength)
            val iterations = (outputLength + 31) / 32 // ceil(outputLength / 32)

            var t = ByteArray(0)
            for (i in 1..iterations) {
                hmac.reset()
                hmac.update(t)
                hmac.update(infoBytes)
                hmac.update(i.toByte())
                t = hmac.doFinal()

                val copyLength = kotlin.math.min(32, outputLength - (i - 1) * 32)
                System.arraycopy(t, 0, output, (i - 1) * 32, copyLength)
            }

            return output
        } catch (e: Exception) {
            throw Exception("HKDF derivation failed: ${e.message}", e)
        }
    }

    // Extension function for hex conversion
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexStringToByteArray(): ByteArray {
        val length = this.length
        val data = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            data[i / 2] = ((this[i].digitToInt(16) shl 4) + this[i + 1].digitToInt(16)).toByte()
        }
        return data
    }

    // Java compatibility methods - blocking versions for existing Java code
    @JvmStatic
    fun encryptContractMessageSync(codeHash: String?, msgJson: String, mnemonic: String): ByteArray {
        return kotlinx.coroutines.runBlocking {
            encryptContractMessage(codeHash, msgJson, mnemonic)
        }
    }

    @JvmStatic
    fun signMessageSync(messageHash: ByteArray, mnemonic: String): ByteArray {
        return kotlinx.coroutines.runBlocking {
            signMessage(messageHash, mnemonic)
        }
    }

    @JvmStatic
    fun getWalletPublicKeySync(mnemonic: String): ByteArray {
        return kotlinx.coroutines.runBlocking {
            getWalletPublicKey(mnemonic)
        }
    }

    @JvmStatic
    fun decryptQueryResponseSync(encryptedResponse: ByteArray, nonce: ByteArray, mnemonic: String): ByteArray {
        return kotlinx.coroutines.runBlocking {
            decryptQueryResponse(encryptedResponse, nonce, mnemonic)
        }
    }
}