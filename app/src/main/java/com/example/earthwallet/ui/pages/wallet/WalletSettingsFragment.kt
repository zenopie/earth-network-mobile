package com.example.earthwallet.ui.pages.wallet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.fragment.app.Fragment
import com.example.earthwallet.R
import com.example.earthwallet.wallet.services.SecureWalletManager

/**
 * WalletSettingsFragment
 *
 * Provides settings for wallet security features including biometric authentication
 */
class WalletSettingsFragment : Fragment() {

    companion object {
        @JvmStatic
        fun newInstance(): WalletSettingsFragment = WalletSettingsFragment()
    }

    private lateinit var switchBiometricAuth: Switch
    private lateinit var switchTransactionAuth: Switch
    private lateinit var tvBiometricStatus: TextView
    private lateinit var tvSecurityLevel: TextView

    // Interface for communication with parent activity
    interface WalletSettingsListener {
        fun onBackPressed()
    }

    private var listener: WalletSettingsListener? = null

    fun setWalletSettingsListener(listener: WalletSettingsListener) {
        this.listener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_wallet_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        switchBiometricAuth = view.findViewById(R.id.switch_biometric_auth)
        switchTransactionAuth = view.findViewById(R.id.switch_transaction_auth)
        tvBiometricStatus = view.findViewById(R.id.tv_biometric_status)
        tvSecurityLevel = view.findViewById(R.id.tv_security_level)

        // Setup back button
        val btnBack = view.findViewById<ImageButton>(R.id.btn_back)
        btnBack.setOnClickListener {
            listener?.onBackPressed()
        }

        // Initialize security level display
        initializeSecurityLevel()

        // Initialize biometric settings
        initializeBiometricSettings()

        // Setup biometric toggle listener
        switchBiometricAuth.setOnCheckedChangeListener { _, isChecked ->
            handleBiometricToggle(isChecked)
        }

        // Setup transaction auth toggle listener
        switchTransactionAuth.setOnCheckedChangeListener { _, isChecked ->
            handleTransactionAuthToggle(isChecked)
        }
    }

    private fun initializeSecurityLevel() {
        try {
            val securityMessage = SecureWalletManager.getSecurityStatusMessage(requireContext())
            tvSecurityLevel.text = securityMessage
        } catch (e: Exception) {
            tvSecurityLevel.text = "⚠️ Unable to determine security level"
        }
    }

    private fun initializeBiometricSettings() {
        val biometricManager = BiometricManager.from(requireContext())

        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // Biometric authentication is available
                switchBiometricAuth.isEnabled = true
                tvBiometricStatus.visibility = View.GONE

                // Load current settings
                loadBiometricSetting()
                loadTransactionAuthSetting()
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                // No biometric features available on this device
                switchBiometricAuth.isEnabled = false
                switchBiometricAuth.isChecked = false
                tvBiometricStatus.text = "No biometric hardware available on this device"
                tvBiometricStatus.visibility = View.VISIBLE

                // Still load transaction auth setting (it can work with PIN only)
                loadTransactionAuthSetting()
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                // Biometric features are currently unavailable
                switchBiometricAuth.isEnabled = false
                switchBiometricAuth.isChecked = false
                tvBiometricStatus.text = "Biometric hardware is currently unavailable"
                tvBiometricStatus.visibility = View.VISIBLE
                loadTransactionAuthSetting()
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                // The user hasn't enrolled any biometric credentials
                switchBiometricAuth.isEnabled = false
                switchBiometricAuth.isChecked = false
                tvBiometricStatus.text = "No biometric credentials enrolled. Please set up fingerprint or face recognition in device settings"
                tvBiometricStatus.visibility = View.VISIBLE
                loadTransactionAuthSetting()
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                // Biometric authentication is not available due to security update required
                switchBiometricAuth.isEnabled = false
                switchBiometricAuth.isChecked = false
                tvBiometricStatus.text = "Security update required for biometric authentication"
                tvBiometricStatus.visibility = View.VISIBLE
                loadTransactionAuthSetting()
            }
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                // Biometric authentication is not supported
                switchBiometricAuth.isEnabled = false
                switchBiometricAuth.isChecked = false
                tvBiometricStatus.text = "Biometric authentication is not supported on this device"
                tvBiometricStatus.visibility = View.VISIBLE
                loadTransactionAuthSetting()
            }
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> {
                // Status unknown
                switchBiometricAuth.isEnabled = false
                switchBiometricAuth.isChecked = false
                tvBiometricStatus.text = "Biometric authentication status unknown"
                tvBiometricStatus.visibility = View.VISIBLE
                loadTransactionAuthSetting()
            }
        }
    }

    private fun loadBiometricSetting() {
        try {
            val isEnabled = SecureWalletManager.isBiometricAuthEnabled(requireContext())
            switchBiometricAuth.isChecked = isEnabled
        } catch (e: Exception) {
            // Default to false if unable to load setting
            switchBiometricAuth.isChecked = false
        }
    }

    private fun handleBiometricToggle(isChecked: Boolean) {
        try {
            SecureWalletManager.setBiometricAuthEnabled(requireContext(), isChecked)
            val message = if (isChecked) {
                "Biometric authentication enabled"
            } else {
                "Biometric authentication disabled"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Revert the toggle if saving failed
            switchBiometricAuth.setOnCheckedChangeListener(null)
            switchBiometricAuth.isChecked = !isChecked
            switchBiometricAuth.setOnCheckedChangeListener { _, checked ->
                handleBiometricToggle(checked)
            }

            Toast.makeText(requireContext(), "Failed to save biometric setting", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTransactionAuthSetting() {
        try {
            val isEnabled = SecureWalletManager.isTransactionAuthEnabled(requireContext())
            switchTransactionAuth.isChecked = isEnabled
        } catch (e: Exception) {
            // Default to false if unable to load setting
            switchTransactionAuth.isChecked = false
        }
    }

    private fun handleTransactionAuthToggle(isChecked: Boolean) {
        try {
            SecureWalletManager.setTransactionAuthEnabled(requireContext(), isChecked)
            val message = if (isChecked) {
                "Transaction authentication enabled"
            } else {
                "Transaction authentication disabled"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Revert the toggle if saving failed
            switchTransactionAuth.setOnCheckedChangeListener(null)
            switchTransactionAuth.isChecked = !isChecked
            switchTransactionAuth.setOnCheckedChangeListener { _, checked ->
                handleTransactionAuthToggle(checked)
            }

            Toast.makeText(requireContext(), "Failed to save transaction auth setting", Toast.LENGTH_SHORT).show()
        }
    }
}