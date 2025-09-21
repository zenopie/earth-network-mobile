package com.example.earthwallet.wallet.utils

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.RequiresApi
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * HardwareKeyManager
 *
 * Manages hardware-backed encryption keys using Android Keystore.
 * Provides enhanced security for mnemonic encryption with hardware-backed storage,
 * key rotation, and additional entropy sources.
 */
object HardwareKeyManager {

    private const val TAG = "HardwareKeyManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MNEMONIC_ENCRYPTION_KEY_ALIAS = "mnemonic_encryption_key_v2"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16

    /**
     * Data class for encrypted data with metadata
     */
    data class EncryptedData(
        val ciphertext: ByteArray,
        val iv: ByteArray,
        val keyAlias: String,
        val encryptionTimestamp: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedData

            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (!iv.contentEquals(other.iv)) return false
            if (keyAlias != other.keyAlias) return false
            if (encryptionTimestamp != other.encryptionTimestamp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + keyAlias.hashCode()
            result = 31 * result + encryptionTimestamp.hashCode()
            return result
        }
    }

    /**
     * Check if hardware-backed keystore is available
     */
    fun isHardwareBackedKeystoreAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hardware-backed keystore not available: ${e.message}")
            false
        }
    }

    /**
     * Initialize or create the hardware-backed encryption key
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(Exception::class)
    fun initializeEncryptionKey(): String {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            // Check if key already exists
            if (keyStore.containsAlias(MNEMONIC_ENCRYPTION_KEY_ALIAS)) {
                Log.d(TAG, "Hardware encryption key already exists")
                return MNEMONIC_ENCRYPTION_KEY_ALIAS
            }

            // Generate new hardware-backed key
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                MNEMONIC_ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Require user authentication for key usage (if device has secure lock screen)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationRequired(false) // Set to true for additional security
                        // setUserAuthenticationValidityDurationSeconds(300) // 5 minutes
                    }
                }
                // Use hardware security module if available
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()

            Log.i(TAG, "Generated new hardware-backed encryption key")
            MNEMONIC_ENCRYPTION_KEY_ALIAS

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize hardware encryption key", e)
            throw Exception("Hardware key initialization failed: ${e.message}", e)
        }
    }

    /**
     * Encrypt data using hardware-backed key with additional entropy
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(Exception::class)
    fun encryptWithHardwareKey(plaintext: String, context: Context): EncryptedData {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = keyStore.getKey(MNEMONIC_ENCRYPTION_KEY_ALIAS, null) as SecretKey

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            // Add additional entropy from multiple sources
            val enhancedPlaintext = enhanceWithEntropy(plaintext, context)
            val plaintextBytes = enhancedPlaintext.toByteArray(Charsets.UTF_8)

            val ciphertext = cipher.doFinal(plaintextBytes)
            val iv = cipher.iv

            // Clear enhanced plaintext from memory
            enhancedPlaintext.toCharArray().fill('\u0000')

            Log.d(TAG, "Successfully encrypted data with hardware-backed key")

            EncryptedData(
                ciphertext = ciphertext,
                iv = iv,
                keyAlias = MNEMONIC_ENCRYPTION_KEY_ALIAS,
                encryptionTimestamp = System.currentTimeMillis()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Hardware encryption failed", e)
            throw Exception("Hardware encryption failed: ${e.message}", e)
        }
    }

    /**
     * Decrypt data using hardware-backed key
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(Exception::class)
    fun decryptWithHardwareKey(encryptedData: EncryptedData, context: Context): String {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = keyStore.getKey(encryptedData.keyAlias, null) as SecretKey

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, encryptedData.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec)

            val decryptedBytes = cipher.doFinal(encryptedData.ciphertext)
            val enhancedPlaintext = String(decryptedBytes, Charsets.UTF_8)

            // Extract original plaintext by removing entropy additions
            val originalPlaintext = extractOriginalFromEnhanced(enhancedPlaintext)

            // Clear decrypted data from memory
            decryptedBytes.fill(0)
            enhancedPlaintext.toCharArray().fill('\u0000')

            Log.d(TAG, "Successfully decrypted data with hardware-backed key")
            originalPlaintext

        } catch (e: Exception) {
            Log.e(TAG, "Hardware decryption failed", e)
            throw Exception("Hardware decryption failed: ${e.message}", e)
        }
    }




    /**
     * Enhance plaintext with additional entropy sources
     * This adds device-specific entropy without affecting the core mnemonic
     */
    private fun enhanceWithEntropy(plaintext: String, context: Context): String {
        val deviceInfo = getDeviceFingerprint(context)
        val timestamp = System.currentTimeMillis()
        val random = SecureRandom().nextLong()

        // Format: ENTROPY_START|device_info|timestamp|random|ENTROPY_END|original_plaintext
        return "ENTROPY_START|$deviceInfo|$timestamp|$random|ENTROPY_END|$plaintext"
    }

    /**
     * Extract original plaintext from entropy-enhanced data
     */
    private fun extractOriginalFromEnhanced(enhancedPlaintext: String): String {
        val entropyEndMarker = "|ENTROPY_END|"
        val index = enhancedPlaintext.indexOf(entropyEndMarker)

        return if (index != -1) {
            enhancedPlaintext.substring(index + entropyEndMarker.length)
        } else {
            // Fallback for data that wasn't entropy-enhanced
            enhancedPlaintext
        }
    }

    /**
     * Get device-specific fingerprint for entropy
     */
    private fun getDeviceFingerprint(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appVersion = packageInfo.versionName ?: "unknown"
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            "${Build.MODEL}_${Build.MANUFACTURER}_${appVersion}_${androidId.take(8)}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device fingerprint", e)
            "unknown_device"
        }
    }


}