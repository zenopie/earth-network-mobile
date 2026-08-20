package network.erth.wallet.wallet.utils

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * How this wallet is opened.
 *
 * The wallet is always sealed under a secret; this says what that secret is
 * made of.
 *
 *  - [PIN] — the PIN alone.
 *  - [BIOMETRIC] — 32 random bytes held behind the biometric prompt. Nothing to
 *    remember, stronger than four digits, and gone for good if the key is
 *    invalidated by a new enrolment.
 *  - [BOTH] — the PIN *and* those bytes, combined. Two factors in the real
 *    sense: neither half opens the wallet, so a stolen unlocked phone is not
 *    enough and a shoulder-surfed PIN is not either.
 */
enum class UnlockMethod {
    PIN,
    BIOMETRIC,
    BOTH,
    ;

    val usesPin: Boolean get() = this != BIOMETRIC
    val usesBiometric: Boolean get() = this != PIN

    companion object {
        private const val PREF_FILE = "unlock_method"
        private const val KEY = "method"

        @JvmStatic
        fun current(context: Context): UnlockMethod =
            runCatching {
                valueOf(prefs(context).getString(KEY, PIN.name) ?: PIN.name)
            }.getOrDefault(PIN)

        @JvmStatic
        fun set(context: Context, method: UnlockMethod) {
            prefs(context).edit().putString(KEY, method.name).apply()
        }

        /**
         * Fold a PIN together with the half held behind the prompt.
         *
         * Hashed rather than concatenated, so the PIN's boundary is not visible
         * in the result and the secret is fixed-width whatever the PIN's length.
         */
        @JvmStatic
        fun combine(pin: String, half: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$pin|$half".toByteArray(StandardCharsets.UTF_8))
                .let { Base64.encodeToString(it, Base64.NO_WRAP) }

        /**
         * A secret for a wallet with no PIN, or the half a two-factor wallet
         * keeps behind the prompt. 32 random bytes, so what stands behind the
         * prompt is a real key rather than four digits.
         */
        @JvmStatic
        fun generatedSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }
}
