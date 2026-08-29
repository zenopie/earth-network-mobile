package network.erth.wallet.wallet.utils

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ties the sealed wallet to this specific device.
 *
 * ## The problem
 *
 * [SoftwareEncryption] derives its key from the unlock secret with PBKDF2, and
 * for a PIN-only wallet that secret is four digits. Ten thousand candidates at
 * a hundred thousand iterations is about 10^9 PBKDF2 rounds — minutes on a
 * laptop, less on a GPU. Every part of that is fine while the ciphertext stays
 * on the device, and worth nothing the moment a copy of it does not: iteration
 * count buys time proportional to the search space, and a four-digit search
 * space is small enough that no realistic count helps.
 *
 * [UnlockAttempts] does not cover this. It rate-limits the unlock screen, which
 * is not where this attack happens.
 *
 * ## What this does
 *
 * Wraps the already-encrypted blob in a second layer of AES-GCM, under a key
 * generated inside the Android Keystore and marked non-exportable. The key
 * material never enters app memory — encryption and decryption happen in the
 * TEE, or in StrongBox on hardware that has it — so the wrapped blob cannot be
 * opened anywhere but on this device, by this app.
 *
 * The PIN still gates the inner layer, so a stolen unlocked phone is no easier
 * than before. What changes is the offline attack: copying the preferences file
 * now yields bytes that no amount of PIN guessing will open, because the outer
 * key is not in the file and cannot be extracted from the hardware holding it.
 *
 * Deliberately **not** [KeyGenParameterSpec.Builder.setUserAuthenticationRequired].
 * That is [BiometricVault]'s job and it prompts; this key exists to bind the
 * blob to the device, not to authenticate anybody, and it has to work for a
 * wallet whose owner has no biometrics enrolled.
 *
 * ## What it costs
 *
 * The key is destroyed by a factory reset, by clearing the app's data, and by
 * some Keystore corruption. When it goes, the wallet goes with it and only the
 * recovery phrase brings it back.
 *
 * That costs nothing that was previously available. `allowBackup="false"` means
 * the ciphertext never leaves the device in the first place, so there was no
 * copy to restore from and no path that survived a wipe. What it does is make
 * the recovery phrase the single thing standing between a user and their funds,
 * which the onboarding flow already says and should keep saying loudly.
 *
 * [unwrap] returns null rather than throwing when the key has gone, so callers
 * can tell "this device cannot open this" apart from "the PIN was wrong" and
 * say something true to the user.
 */
object DeviceBinding {

    private const val TAG = "DeviceBinding"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "earth_device_binding"
    private const val TRANSFORMATION =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
    private const val GCM_TAG_BITS = 128

    /** A wrapped blob and the IV needed to unwrap it. */
    data class Wrapped(val ciphertext: ByteArray, val iv: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Wrapped
            return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
        }

        override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
    }

    /**
     * Wrap `plaintext` under the device key, creating that key on first use.
     *
     * Throws if the key cannot be created: refusing to save is the right
     * failure, because the alternative is silently writing a blob weaker than
     * the one it replaced.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun wrap(plaintext: ByteArray): Wrapped {
        val secret = key() ?: createKey() ?: throw IllegalStateException(
            "the Keystore would not produce a device binding key"
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        return Wrapped(ciphertext = cipher.doFinal(plaintext), iv = cipher.iv)
    }

    /**
     * Unwrap a blob written by [wrap].
     *
     * Returns null when the device key is gone — a factory reset, cleared app
     * data, a restore onto different hardware. That is unrecoverable and the
     * caller should say so plainly rather than reporting a wrong PIN.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun unwrap(wrapped: Wrapped): ByteArray? {
        val secret = key() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
        return cipher.doFinal(wrapped.ciphertext)
    }

    /** The existing key, or null if there is not one. */
    private fun key(): SecretKey? =
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.getKey(ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            Log.w(TAG, "could not read the device binding key: ${e.message}")
            null
        }

    /**
     * Generate the key, preferring StrongBox where the hardware has it.
     *
     * StrongBox is a separate security chip rather than a mode of the main
     * processor, so a key held there survives attacks that reach the TEE. Not
     * every device has one and the Keystore throws
     * StrongBoxUnavailableException rather than falling back, so the fallback
     * is here.
     */
    private fun createKey(): SecretKey? {
        for (strongBox in booleanArrayOf(true, false)) {
            if (strongBox && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) continue
            try {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // No setUserAuthenticationRequired: see the class comment.
                    .apply {
                        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            setIsStrongBoxBacked(true)
                        }
                    }
                    .build()
                generator.init(spec)
                val secret = generator.generateKey()
                Log.i(TAG, "device binding key created (strongbox=$strongBox)")
                return secret
            } catch (e: Exception) {
                Log.w(TAG, "device binding key generation failed (strongbox=$strongBox): ${e.message}")
            }
        }
        return null
    }
}
