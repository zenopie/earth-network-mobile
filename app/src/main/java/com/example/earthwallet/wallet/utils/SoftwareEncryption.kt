package com.example.earthwallet.wallet.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SoftwareEncryption
 *
 * Provides software-based AES encryption using device-derived keys as fallback
 * when hardware-backed encryption is not available.
 */
object SoftwareEncryption {

    private const val TAG = "SoftwareEncryption"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val KEY_LENGTH = 32 // 256 bits

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
     * Encrypt data using device-derived key
     */
    @Throws(Exception::class)
    fun encrypt(plaintext: String, context: Context): EncryptedData {
        return try {
            // Generate random salt for this encryption
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)

            // Derive key from device characteristics + salt
            val key = deriveKeyFromDevice(context, salt)

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

            Log.d(TAG, "Successfully encrypted data with software-based key")

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
     * Decrypt data using device-derived key
     */
    @Throws(Exception::class)
    fun decrypt(encryptedData: EncryptedData, context: Context): String {
        return try {
            // Derive the same key using stored salt
            val key = deriveKeyFromDevice(context, encryptedData.salt)

            // Decrypt
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, encryptedData.iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmParameterSpec)

            val decryptedBytes = cipher.doFinal(encryptedData.ciphertext)
            val result = String(decryptedBytes, StandardCharsets.UTF_8)

            // Clear sensitive data
            decryptedBytes.fill(0)
            key.encoded?.fill(0)

            Log.d(TAG, "Successfully decrypted data with software-based key")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Software decryption failed", e)
            throw Exception("Software decryption failed: ${e.message}", e)
        }
    }

    /**
     * Derive encryption key from device characteristics and salt
     */
    @Throws(Exception::class)
    private fun deriveKeyFromDevice(context: Context, salt: ByteArray): SecretKey {
        return try {
            // Collect device characteristics
            val deviceId = getDeviceIdentifier(context)
            val appSignature = getAppSignature(context)
            val deviceInfo = "${Build.MODEL}_${Build.MANUFACTURER}_${Build.FINGERPRINT}"

            // Combine all sources with salt
            val keyMaterial = "$deviceId|$appSignature|$deviceInfo".toByteArray(StandardCharsets.UTF_8)
            val saltedMaterial = keyMaterial + salt

            // Hash to create consistent key
            val digest = MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(saltedMaterial)

            // Clear intermediate data
            keyMaterial.fill(0)
            saltedMaterial.fill(0)

            SecretKeySpec(keyBytes, "AES")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive device key", e)
            throw Exception("Device key derivation failed: ${e.message}", e)
        }
    }

    /**
     * Get device identifier (Android ID or fallback)
     */
    private fun getDeviceIdentifier(context: Context): String {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown_device_${System.currentTimeMillis()}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Android ID", e)
            "fallback_device_${Build.MODEL.hashCode()}"
        }
    }

    /**
     * Get app signature for additional entropy
     */
    private fun getAppSignature(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            }

            signature?.let {
                MessageDigest.getInstance("SHA-256").digest(it).let {
                    Base64.encodeToString(it, Base64.NO_WRAP).take(16)
                }
            } ?: "unknown_signature"

        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app signature", e)
            "fallback_signature"
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
            Log.w(TAG, "Software encryption not available", e)
            false
        }
    }
}