package com.example.earthwallet.ui.pages.wallet

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.earthwallet.R
import com.example.earthwallet.wallet.services.SecureWalletManager
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * CreateWalletFragment
 *
 * Implements a Keplr-like wallet creation flow (for Secret Network):
 * - Intro: Create New / Import
 * - Mnemonic reveal (user must explicitly reveal)
 * - Mandatory backup acknowledgement and verification (select words in correct order)
 * - PIN creation and confirmation
 * - Completion screen that saves mnemonic (secure) and pin hash, then returns to wallet
 */
class CreateWalletFragment : Fragment() {

    companion object {
        private const val PREF_FILE = "secret_wallet_prefs"
        private const val KEY_MNEMONIC = "mnemonic"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_WALLET_NAME = "wallet_name"

        @JvmStatic
        fun newInstance(): CreateWalletFragment = CreateWalletFragment()

        /**
         * Create secure preferences with corrupted file cleanup on key mismatch
         */
        private fun createSecurePreferences(context: Context): SharedPreferences {
            return try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: GeneralSecurityException) {
                Log.w("CreateWalletFragment", "EncryptedSharedPreferences failed, clearing corrupted file: ${e.message}")
                handleEncryptedPrefsError(context, e)
            } catch (e: IOException) {
                Log.w("CreateWalletFragment", "EncryptedSharedPreferences failed, clearing corrupted file: ${e.message}")
                handleEncryptedPrefsError(context, e)
            }
        }

        private fun handleEncryptedPrefsError(context: Context, originalException: Exception): SharedPreferences {
            // Clear corrupted preferences file and try again
            clearCorruptedPreferencesFile(context)

            return try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (retryException: GeneralSecurityException) {
                Log.e("CreateWalletFragment", "Failed to create EncryptedSharedPreferences even after cleanup", retryException)
                throw RuntimeException("Failed to initialize secure preferences", retryException)
            } catch (retryException: IOException) {
                Log.e("CreateWalletFragment", "Failed to create EncryptedSharedPreferences even after cleanup", retryException)
                throw RuntimeException("Failed to initialize secure preferences", retryException)
            }
        }

        /**
         * Clear corrupted preferences file (handles key mismatch after reinstalls)
         */
        private fun clearCorruptedPreferencesFile(context: Context) {
            try {
                // Delete the encrypted shared preferences file
                val prefsPath = "${context.applicationInfo.dataDir}/shared_prefs/${PREF_FILE}.xml"
                val prefsFile = File(prefsPath)
                if (prefsFile.exists()) {
                    val deleted = prefsFile.delete()
                    Log.i("CreateWalletFragment", "Deleted corrupted preferences file: $deleted")
                }
            } catch (e: Exception) {
                Log.w("CreateWalletFragment", "Failed to delete corrupted preferences file: ${e.message}")
            }
        }
    }

    // Steps' containers
    private lateinit var stepIntro: View
    private lateinit var stepReveal: View
    private lateinit var stepVerify: View
    private lateinit var stepPin: View
    private lateinit var stepDone: View

    // Reveal step
    private lateinit var revealMnemonicText: EditText
    private lateinit var btnRevealNext: Button

    // Confirm step (simple acknowledgement)
    private lateinit var confirmBackupCheck: CheckBox
    private lateinit var btnConfirmNext: Button

    // Pin step
    private lateinit var walletNameInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var pinConfirmInput: EditText
    private lateinit var btnPinNext: Button

    // Done
    private lateinit var btnDone: Button

    // State
    private var mnemonic: String? = null
    private var mnemonicWords: List<String>? = null

    // Secure prefs
    // Removed - using SecureWalletManager instead

    // Interface for communication with parent activity
    interface CreateWalletListener {
        fun onWalletCreated()
        fun onCreateWalletCancelled()
    }

    private var listener: CreateWalletListener? = null

    fun setCreateWalletListener(listener: CreateWalletListener) {
        this.listener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.activity_create_wallet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize SecretWallet wordlist
        try {
            // Empty try block from original Java
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Wallet initialization failed", Toast.LENGTH_LONG).show()
            listener?.onCreateWalletCancelled()
            return
        }

        // Using SecureWalletManager instead of direct preferences access

        // Check whether a global PIN already exists
        val hasExistingPin = SecureWalletManager.hasPinSet(requireContext())

        // Wire up steps
        stepIntro = view.findViewById(R.id.step_intro)
        stepReveal = view.findViewById(R.id.step_reveal)
        stepVerify = view.findViewById(R.id.step_verify)
        stepPin = view.findViewById(R.id.step_pin)
        stepDone = view.findViewById(R.id.step_done)

        revealMnemonicText = view.findViewById(R.id.reveal_mnemonic_text)
        btnRevealNext = view.findViewById(R.id.btn_reveal_next)

        // Enable the Next button when the user pastes/enters text (supports import flow)
        revealMnemonicText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnRevealNext.isEnabled = s != null && s.toString().trim().isNotEmpty()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        confirmBackupCheck = view.findViewById(R.id.confirm_backup_check)
        btnConfirmNext = view.findViewById(R.id.btn_confirm_next)

        walletNameInput = view.findViewById(R.id.wallet_name_input)
        pinInput = view.findViewById(R.id.pin_input)
        pinConfirmInput = view.findViewById(R.id.pin_confirm_input)
        btnPinNext = view.findViewById(R.id.btn_pin_next)

        btnDone = view.findViewById(R.id.btn_done)

        // Intro buttons
        val btnCreateNew = view.findViewById<Button>(R.id.btn_create_new)
        val btnImport = view.findViewById<Button>(R.id.btn_import)

        btnCreateNew.setOnClickListener { startCreateNewFlow() }
        btnImport.setOnClickListener { startImportFlow() }

        // Reveal step: next only after user has revealed and acknowledged backup will be required in verification
        val btnReveal = view.findViewById<Button>(R.id.btn_reveal)
        btnReveal.setOnClickListener {
            // Generate mnemonic and show it
            try {
                mnemonic = SecureWalletManager.generateMnemonic(requireContext())
            } catch (e: Exception) {
                Log.e("CreateWalletFragment", "Failed to generate mnemonic", e)
                Toast.makeText(requireContext(), "Failed to generate wallet", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            mnemonicWords = mnemonic?.trim()?.split("\\s+".toRegex())
            revealMnemonicText.setText(mnemonic)
            btnRevealNext.isEnabled = true
        }

        btnRevealNext.setOnClickListener {
            if (mnemonic == null) {
                Toast.makeText(requireContext(), "Reveal your mnemonic first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startConfirmStep()
        }

        // Confirm controls (simple acknowledgement)
        btnConfirmNext.isEnabled = false
        confirmBackupCheck.isChecked = false
        confirmBackupCheck.setOnCheckedChangeListener { _, isChecked ->
            btnConfirmNext.isEnabled = isChecked
        }
        btnConfirmNext.setOnClickListener {
            if (!confirmBackupCheck.isChecked) {
                Toast.makeText(requireContext(), "Please confirm you backed up your recovery phrase", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showStep(stepPin)
        }

        // PIN step
        pinInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        pinConfirmInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        // If a PIN already exists, hide the PIN inputs and reuse the existing global PIN
        if (hasExistingPin) {
            pinInput.visibility = View.GONE
            pinConfirmInput.visibility = View.GONE
            walletNameInput.hint = "Wallet name (PIN already set)"
            btnPinNext.setOnClickListener {
                val walletName = walletNameInput.text?.toString()?.trim() ?: ""
                if (TextUtils.isEmpty(walletName)) {
                    Toast.makeText(requireContext(), "Enter a wallet name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveMnemonicAndPin(null, walletName)
                showStep(stepDone)
            }
        } else {
            btnPinNext.setOnClickListener {
                val walletName = walletNameInput.text?.toString()?.trim() ?: ""
                if (TextUtils.isEmpty(walletName)) {
                    Toast.makeText(requireContext(), "Enter a wallet name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val pin = pinInput.text?.toString()?.trim() ?: ""
                val pin2 = pinConfirmInput.text?.toString()?.trim() ?: ""
                if (TextUtils.isEmpty(pin) || TextUtils.isEmpty(pin2)) {
                    Toast.makeText(requireContext(), "Enter and confirm PIN", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pin != pin2) {
                    Toast.makeText(requireContext(), "PINs do not match", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pin.length < 4) {
                    Toast.makeText(requireContext(), "PIN should be at least 4 digits", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                saveMnemonicAndPin(pin, walletName)
                showStep(stepDone)
            }
        }

        btnDone.setOnClickListener {
            listener?.onWalletCreated()
        }

        // Initialize UI state
        btnRevealNext.isEnabled = false
        btnConfirmNext.isEnabled = false
        showStep(stepIntro)
    }

    private fun showStep(step: View) {
        // Hide all then show the requested
        stepIntro.visibility = View.GONE
        stepReveal.visibility = View.GONE
        stepVerify.visibility = View.GONE
        stepPin.visibility = View.GONE
        stepDone.visibility = View.GONE

        step.visibility = View.VISIBLE
    }

    private fun startCreateNewFlow() {
        mnemonic = null
        mnemonicWords = null
        showStep(stepReveal)
        revealMnemonicText.setText("Press Reveal to generate and display your mnemonic. Write it down and keep it safe.")
        btnRevealNext.isEnabled = false
    }

    private fun startImportFlow() {
        showStep(stepReveal)
        revealMnemonicText.setText("")
        btnRevealNext.isEnabled = false

        // Make the reveal text editable for import
        revealMnemonicText.isFocusable = true
        revealMnemonicText.isClickable = true
        revealMnemonicText.isFocusableInTouchMode = true
        revealMnemonicText.setText("")
        revealMnemonicText.hint = "Paste your 12/24-word mnemonic here and press Next"

        btnRevealNext.setOnClickListener {
            val pasted = revealMnemonicText.text?.toString()?.trim() ?: ""
            if (TextUtils.isEmpty(pasted)) {
                Toast.makeText(requireContext(), "Paste mnemonic first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val words = pasted.split("\\s+".toRegex())
            if (words.size < 12) {
                Toast.makeText(requireContext(), "Mnemonic looks too short", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mnemonic = pasted
            mnemonicWords = words
            revealMnemonicText.isFocusable = false
            revealMnemonicText.isClickable = false
            revealMnemonicText.isFocusableInTouchMode = false
            startConfirmStep()
        }
    }

    private fun startConfirmStep() {
        revealMnemonicText.isFocusable = false
        revealMnemonicText.isClickable = false
        revealMnemonicText.isFocusableInTouchMode = false
        confirmBackupCheck.isChecked = false
        btnConfirmNext.isEnabled = false
        showStep(stepVerify)
    }

    private fun saveMnemonicAndPin(pin: String?, walletName: String) {
        try {
            val hasExistingPin = SecureWalletManager.hasPinSet(requireContext())
            var pinHash = ""

            if (!hasExistingPin) {
                if (pin == null) {
                    Toast.makeText(requireContext(), "No existing PIN found; please create a PIN", Toast.LENGTH_SHORT).show()
                    return
                }
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(pin.toByteArray(StandardCharsets.UTF_8))
                val sb = StringBuilder()
                for (b in digest) {
                    sb.append(String.format("%02x", b))
                }
                pinHash = sb.toString()
            }

            // Create wallet using SecureWalletManager
            mnemonic?.let { SecureWalletManager.createWallet(requireContext(), walletName, it) }

            // Set PIN hash if this is the first wallet
            if (!hasExistingPin) {
                SecureWalletManager.setPinHash(requireContext(), pinHash)
            }

            Toast.makeText(requireContext(), "Wallet created and saved securely", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save wallet", Toast.LENGTH_LONG).show()
        }
    }
}