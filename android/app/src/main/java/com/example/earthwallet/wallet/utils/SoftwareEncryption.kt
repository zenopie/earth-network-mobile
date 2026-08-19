package network.erth.wallet.wallet.utils

import android.content.Context
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SoftwareEncryption
 *
 * Provides software-based AES encryption using PIN-derived keys with PBKDF2.
 * Offers strong protection against backup extraction attacks.
 */
object SoftwareEncryption {

    private const val TAG = "SoftwareEncryption"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val KEY_LENGTH = 32 // 256 bits
    private const val PBKDF2_ITERATIONS = 100_000 // Industry standard for mobile

    /**
     * Data class for software encrypted data
     */
    data class EncryptedData(
        val ciphertext: ByteArray,
        val iv: ByteArray,
        val salt: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedData

            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (!iv.contentEquals(other.iv)) return false
            if (!salt.contentEquals(other.salt)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + salt.contentHashCode()
            return result
        }
    }

    /**
     * Encrypt data using PIN-derived key with PBKDF2
     */
    @Throws(Exception::class)
    fun encrypt(plaintext: String, pin: String, context: Context): EncryptedData {
        return try {
            // Generate random salt for this encryption
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)

            // Derive key from PIN + salt using PBKDF2
            val key = deriveKeyFromPin(pin, salt)

            // Generate random IV
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            // Encrypt
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmParameterSpec)

            val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
            val ciphertext = cipher.doFinal(plaintextBytes)

            // Clear sensitive data
            plaintextBytes.fill(0)
            key.encoded?.fill(0)


            EncryptedData(
                ciphertext = ciphertext,
                iv = iv,
                salt = salt
            )

        } catch (e: Exception) {
            Log.e(TAG, "Software encryption failed", e)
            throw Exception("Software encryption failed: ${e.message}", e)
        }
    }

    /**
     * Decrypt data using PIN-derived key with PBKDF2
     */
    @Throws(Exception::class)
    fun decrypt(encryptedData: EncryptedData, pin: String, context: Context): String {
        return try {
            // Derive the same key using stored salt and PIN
            val key = deriveKeyFromPin(pin, encryptedData.salt)

            // Decrypt
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, encryptedData.iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmParameterSpec)

            val decryptedBytes = cipher.doFinal(encryptedData.ciphertext)
            val result = String(decryptedBytes, StandardCharsets.UTF_8)

            // Clear sensitive data
            decryptedBytes.fill(0)
            key.encoded?.fill(0)

            result

        } catch (e: Exception) {
            Log.e(TAG, "Software decryption failed", e)
            throw Exception("Software decryption failed: ${e.message}", e)
        }
    }

    /**
     * Derive encryption key from PIN using PBKDF2 with salt
     */
    @Throws(Exception::class)
    private fun deriveKeyFromPin(pin: String, salt: ByteArray): SecretKey {
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH * 8)
            val key = factory.generateSecret(spec)

            // Clear the PIN from the spec
            spec.clearPassword()

            SecretKeySpec(key.encoded, "AES")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive PIN-based key", e)
            throw Exception("PIN key derivation failed: ${e.message}", e)
        }
    }


    /**
     * Check if software encryption is available
     */
    fun isAvailable(): Boolean {
        return try {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            true
        } catch (e: Exception) {
            false
        }
    }
}