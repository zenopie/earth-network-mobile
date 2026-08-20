package network.erth.wallet.wallet.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The wallet's sealing secret, held behind a fingerprint or face.
 *
 * Not a gate in front of something already readable. The secret this stores is
 * what the wallet is encrypted with, so without a successful authentication
 * the stored bytes decrypt to nothing — the Keystore key that unwraps them
 * requires the user, and the key never leaves the secure hardware.
 *
 * [KeyGenParameterSpec.Builder.setInvalidatedByBiometricEnrollment] is on: adding
 * a new fingerprint or face invalidates the key and loses the secret. That is
 * the point. Without it, anyone who can reach a device's settings could enrol
 * their own finger and inherit the wallet.
 *
 * ## Two slots
 *
 * There are two of everything — two Keystore aliases, two stored payloads —
 * and a pointer saying which is live. Changing the secret writes the new one
 * into the *spare* slot and leaves the live slot untouched, so the old secret
 * still opens the wallet the whole time the new one is being set up. Only once
 * the wallet has actually been re-encrypted does [commit] move the pointer,
 * which is a single preferences write, and only then is the old slot destroyed.
 *
 * The alternative — overwrite in place, then re-encrypt — has a window where
 * the old half is gone and the wallet is still sealed by it. Nothing recovers
 * from that but the recovery phrase, and it is avoidable, so it is avoided.
 * Android's Keystore cannot rename a key, which is why this is a pointer flip
 * rather than a move.
 */
object BiometricVault {

    private const val PREF_FILE = "biometric_vault"
    private const val KEY_LIVE_SLOT = "live_slot"
    private const val TRANSFORMATION =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"

    private val slots = listOf("a", "b")

    private fun alias(slot: String) = "earth_unlock_secret_$slot"
    private fun payloadKey(slot: String) = "${slot}_payload"
    private fun ivKey(slot: String) = "${slot}_iv"

    private fun liveSlot(context: Context): String =
        prefs(context).getString(KEY_LIVE_SLOT, slots.first()) ?: slots.first()

    private fun spareSlot(context: Context): String =
        slots.first { it != liveSlot(context) }

    /** Whether this device can authenticate the user biometrically at all. */
    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Whether a live secret is held. Does not prompt. */
    @JvmStatic
    fun isEnrolled(context: Context): Boolean {
        val slot = liveSlot(context)
        return prefs(context).contains(payloadKey(slot)) && keystoreKey(slot) != null
    }

    /**
     * Write a secret into the spare slot, behind the prompt.
     *
     * Returns the slot it landed in, or null if the user refused. The live slot
     * is untouched either way, so a refusal costs nothing and the wallet still
     * opens the way it did before.
     *
     * The prompt is raised for the write as well as the read: the key requires
     * the user for every use, and confirming here is worth having anyway — it
     * proves the thing that will be asked for later actually works before the
     * wallet depends on it.
     */
    @JvmStatic
    fun stage(
        activity: FragmentActivity,
        secret: String,
        onResult: (String?) -> Unit,
    ) {
        val slot = spareSlot(activity)
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, freshKey(slot)) }
        } catch (e: Exception) {
            onResult(null)
            return
        }

        prompt(activity, "Confirm to enable unlocking", cipher) { authenticated ->
            if (!authenticated) {
                discard(activity, slot)
                return@prompt onResult(null)
            }
            val ok = runCatching {
                val sealed = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
                prefs(activity).edit()
                    .putString(payloadKey(slot), Base64.encodeToString(sealed, Base64.NO_WRAP))
                    .putString(ivKey(slot), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .apply()
            }.isSuccess
            if (!ok) discard(activity, slot)
            onResult(if (ok) slot else null)
        }
    }

    /**
     * Make a staged slot the live one.
     *
     * Call only once the wallet is sealed under the staged secret. The pointer
     * moves in one write, and the slot it used to name is destroyed after —
     * losing power between the two leaves a stale key nobody reads, which the
     * next [stage] overwrites.
     */
    @JvmStatic
    fun commit(context: Context, slot: String) {
        val previous = liveSlot(context)
        if (previous == slot) return
        prefs(context).edit().putString(KEY_LIVE_SLOT, slot).commit()
        discard(context, previous)
    }

    /** Throw away a staged slot after a change that did not go through. */
    @JvmStatic
    fun discard(context: Context, slot: String) {
        if (slot == liveSlot(context)) return
        prefs(context).edit().remove(payloadKey(slot)).remove(ivKey(slot)).apply()
        deleteKey(slot)
    }

    /** Ask for the live secret. Null means the user refused, or the key is gone. */
    @JvmStatic
    fun retrieve(
        activity: FragmentActivity,
        reason: String,
        onResult: (String?) -> Unit,
    ) {
        val slot = liveSlot(activity)
        val stored = prefs(activity).getString(payloadKey(slot), null)
        val iv = prefs(activity).getString(ivKey(slot), null)
        val key = keystoreKey(slot)
        if (stored == null || iv == null || key == null) return onResult(null)

        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
        } catch (e: Exception) {
            // Thrown when a new biometric was enrolled since: the key is gone
            // and the secret with it, which is the intended outcome.
            forget(activity)
            onResult(null)
            return
        }

        prompt(activity, reason, cipher) { authenticated ->
            if (!authenticated) return@prompt onResult(null)
            val secret = runCatching {
                String(cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
            }.getOrNull()
            onResult(secret)
        }
    }

    /** Forget everything, both slots. */
    @JvmStatic
    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
        slots.forEach(::deleteKey)
    }

    private fun prompt(
        activity: FragmentActivity,
        reason: String,
        cipher: Cipher,
        onResult: (Boolean) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Earth Wallet")
            .setSubtitle(reason)
            .setNegativeButtonText("Cancel")
            // Strong only, and no device-credential fallback: the key is bound
            // to biometrics, so a passcode fallback could not unwrap it and
            // would offer a door that opens onto nothing.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    onResult(false)
                }
                // Deliberately not overriding onAuthenticationFailed: a single
                // unrecognised finger is not the end of the attempt, and the
                // prompt stays up for another try.
            },
        ).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun freshKey(slot: String): SecretKey {
        // Replaced rather than reused, so a slot cannot decrypt anything sealed
        // under whatever it held before.
        deleteKey(slot)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias(slot),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKey(slot: String) {
        runCatching { keystore().deleteEntry(alias(slot)) }
    }

    private fun keystoreKey(slot: String): SecretKey? = runCatching {
        keystore().getKey(alias(slot), null) as? SecretKey
    }.getOrNull()

    private fun keystore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
}
