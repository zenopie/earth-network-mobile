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
 * AES-GCM under a key derived from the unlock secret with PBKDF2.
 *
 * How much this is worth depends entirely on what the secret is, and it is
 * worth being blunt about the weak end. Against a four-digit PIN the search
 * space is 10,000; at 600,000 iterations that is about 6x10^9 PBKDF2 rounds to
 * exhaust, which is hours on a laptop rather than minutes and still well under
 * a day on a GPU. Raising the count buys time proportional to a search space
 * that is small whatever the count, so for a PIN-only wallet this stops someone
 * reading the preferences file and does not stop someone who copies it and
 * works offline. UnlockAttempts does not help there either — it guards the
 * unlock screen, not extracted bytes.
 *
 * Two things carry the real weight, and both are outside this file:
 * android:allowBackup="false" in the manifest, which keeps the ciphertext off
 * Google's servers, and UnlockMethod.BIOMETRIC / BOTH, which put 32 random
 * bytes behind the Keystore prompt and make the derived key unguessable. A
 * wallet holding anything worth stealing wants one of those two methods.
 */
object SoftwareEncryption {

    private const val TAG = "SoftwareEncryption"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val KEY_LENGTH = 32 // 256 bits
    // OWASP's figure for PBKDF2-HMAC-SHA256. The iOS vault stretches its PIN
    // 200,000 times with SHA-512 (WalletStore.swift), which is at the matching
    // recommendation for that hash; 100,000 here left Android the weaker half
    // of the same wallet.
    //
    // No migration path and none needed: this is pre-release, so there are no
    // blobs sealed at the old count. Once there are, changing this number
    // without recording the count that sealed each blob makes every existing
    // wallet undecryptable.
    private const val PBKDF2_ITERATIONS = 600_000

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

            // Zero the plaintext. The key is deliberately not zeroed here:
            // SecretKeySpec.getEncoded() hands back a fresh copy, so filling it
            // wipes a throwaway array and leaves the real key material exactly
            // where it was. Writing that line is worse than omitting it — it
            // reads as a wipe that never happened.
            plaintextBytes.fill(0)

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

            // Zero the intermediate buffer. `result` itself cannot be wiped —
            // it is an immutable String and lives until the GC collects it,
            // which is the reason callers hand the mnemonic straight to
            // deriveKeyFromSecureMnemonic rather than holding it. The key is
            // not zeroed for the reason given in encrypt().
            decryptedBytes.fill(0)

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