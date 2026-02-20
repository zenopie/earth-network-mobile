package network.erth.wallet.ui.pages.wallet

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.fragment.app.Fragment
import network.erth.wallet.R
import network.erth.wallet.wallet.services.SecureWalletManager

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
    private lateinit var btnRemoveAppData: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_wallet_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        switchBiometricAuth = view.findViewById(R.id.switch_biometric_auth)
        switchTransactionAuth = view.findViewById(R.id.switch_transaction_auth)
        tvBiometricStatus = view.findViewById(R.id.tv_biometric_status)
        btnRemoveAppData = view.findViewById(R.id.btn_remove_app_data)

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


        // Setup remove app data button listener
        btnRemoveAppData.setOnClickListener {
            showRemoveAppDataDialog()
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

    private fun showRemoveAppDataDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All App Data")
            .setMessage("⚠️ This will permanently delete:\n\n• All wallets and mnemonics\n• PIN and security settings\n• All app preferences\n• Viewing keys and permits\n\nThis action cannot be undone. Are you sure?")
            .setPositiveButton("Clear Data") { _, _ ->
                clearAppData()
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }


    private fun clearAppData() {
        try {
            val context = requireContext()

            // Clear software-encrypted preferences
            try {
                val softwarePrefs = context.getSharedPreferences("secret_wallet_prefs_software", Context.MODE_PRIVATE)
                softwarePrefs.edit().clear().apply()
            } catch (e: Exception) {
                // Software preferences might not exist, continue
            }

            // Clear all shared preferences files
            val prefsDir = context.applicationInfo.dataDir + "/shared_prefs"
            val prefsFolder = java.io.File(prefsDir)
            if (prefsFolder.exists()) {
                prefsFolder.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".xml")) {
                        file.delete()
                    }
                }
            }

            // Clear app databases
            val dbDir = context.applicationInfo.dataDir + "/databases"
            val dbFolder = java.io.File(dbDir)
            if (dbFolder.exists()) {
                dbFolder.listFiles()?.forEach { file ->
                    file.delete()
                }
            }

            Toast.makeText(context, "App data cleared successfully. Please restart the app.", Toast.LENGTH_LONG).show()

            // Exit the app
            requireActivity().finishAffinity()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to clear app data: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}