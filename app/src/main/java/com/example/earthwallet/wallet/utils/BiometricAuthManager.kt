package network.erth.wallet.wallet.utils

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * BiometricAuthManager
 *
 * Handles biometric authentication for wallet security
 */
object BiometricAuthManager {

    private const val TAG = "BiometricAuthManager"

    interface BiometricAuthCallback {
        fun onAuthenticationSucceeded()
        fun onAuthenticationError(errorMessage: String)
        fun onAuthenticationFailed()
    }

    /**
     * Check if biometric authentication should be used instead of PIN
     */
    fun shouldUseBiometricAuth(context: Context): Boolean {
        return try {
            // Check if biometric is available and enabled in settings
            val biometricManager = BiometricManager.from(context)
            val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            val isEnabled = SecureWalletManager.isBiometricAuthEnabled(context)

            canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS && isEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking biometric auth availability", e)
            false
        }
    }

    /**
     * Authenticate user with biometric prompt (Fragment version)
     */
    fun authenticateUser(
        fragment: Fragment,
        title: String = "Biometric Authentication",
        subtitle: String = "Use your biometric credential to authenticate",
        callback: BiometricAuthCallback
    ) {
        val context = fragment.requireContext()

        // Double check biometric availability
        if (!shouldUseBiometricAuth(context)) {
            callback.onAuthenticationError("Biometric authentication not available")
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = BiometricPrompt(fragment, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)

                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> {
                        // User cancelled, don't show error message
                        callback.onAuthenticationError("Authentication cancelled")
                    }
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                        // User clicked negative button, show PIN fallback
                        callback.onAuthenticationError("Use PIN instead")
                    }
                    else -> {
                        callback.onAuthenticationError("Authentication error: $errString")
                    }
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                callback.onAuthenticationSucceeded()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                callback.onAuthenticationFailed()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting biometric authentication", e)
            callback.onAuthenticationError("Failed to start biometric authentication")
        }
    }

    /**
     * Authenticate user with biometric prompt (Activity version)
     */
    fun authenticateUser(
        activity: FragmentActivity,
        title: String = "Biometric Authentication",
        subtitle: String = "Use your biometric credential to authenticate",
        callback: BiometricAuthCallback
    ) {
        // Double check biometric availability
        if (!shouldUseBiometricAuth(activity)) {
            callback.onAuthenticationError("Biometric authentication not available")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)

                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> {
                        // User cancelled, don't show error message
                        callback.onAuthenticationError("Authentication cancelled")
                    }
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                        // User clicked negative button, show PIN fallback
                        callback.onAuthenticationError("Use PIN instead")
                    }
                    else -> {
                        callback.onAuthenticationError("Authentication error: $errString")
                    }
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                callback.onAuthenticationSucceeded()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                callback.onAuthenticationFailed()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting biometric authentication", e)
            callback.onAuthenticationError("Failed to start biometric authentication")
        }
    }

    /**
     * Get biometric capability status message
     */
    fun getBiometricCapabilityMessage(context: Context): String {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> "Biometric authentication available"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No biometric hardware available"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware unavailable"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No biometric credentials enrolled"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "Biometric authentication not supported"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "Biometric status unknown"
            else -> "Unknown biometric status"
        }
    }
}