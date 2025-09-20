package com.example.earthwallet.ui.pages.wallet

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.earthwallet.R
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.example.earthwallet.wallet.utils.BiometricAuthManager
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * WalletListFragment
 *
 * Lists saved wallets (from "wallets" JSON array in secure prefs) and shows address,
 * with Show (requires PIN) and Delete (with irreversible confirmation) actions.
 */
class WalletListFragment : Fragment() {

    companion object {
        private const val PREF_FILE = "secret_wallet_prefs"

        @JvmStatic
        fun newInstance(): WalletListFragment = WalletListFragment()
    }

    // Removed - using SecureWalletManager instead
    private lateinit var container: LinearLayout
    private lateinit var inflater: LayoutInflater

    // Interface for communication with parent activity
    interface WalletListListener {
        fun onWalletSelected(walletIndex: Int)
        fun onCreateWalletRequested()
    }

    private var listener: WalletListListener? = null

    fun setWalletListListener(listener: WalletListListener) {
        this.listener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.activity_wallet_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Wallet initialization failed", Toast.LENGTH_LONG).show()
        }

        this.inflater = LayoutInflater.from(requireContext())
        this.container = view.findViewById(R.id.wallet_list_container)

        loadAndRenderWallets()

        // Wire Add button (new wallet flow)
        val addBtn = view.findViewById<View>(R.id.btn_add_wallet_list)
        addBtn?.setOnClickListener {
            listener?.onCreateWalletRequested()
        }
    }

    // Removed - using SecureWalletManager instead of direct preferences access

    fun loadAndRenderWallets() {
        // Remove any rows except the title (first child)
        if (container.childCount > 1) {
            container.removeViews(1, container.childCount - 1)
        }

        try {
            // Ensure all existing wallets have addresses migrated
            SecureWalletManager.ensureAllWalletsHaveAddresses(requireContext())

            val arr = SecureWalletManager.getAllWallets(requireContext())
            if (arr.length() == 0) {
                val empty = TextView(requireContext())
                empty.text = "No wallets found"
                empty.setPadding(0, 16, 0, 0)
                container.addView(empty)
                return
            }

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val index = i
                val walletName = obj.optString("name", "Wallet $i")
                val address = obj.optString("address", "")
                val finalAddress = if (TextUtils.isEmpty(address)) "No address" else address

                val row = inflater.inflate(R.layout.item_wallet_row, container, false)
                val tvName = row.findViewById<TextView>(R.id.wallet_row_name)
                val tvAddr = row.findViewById<TextView>(R.id.wallet_row_address)
                val btnShow = row.findViewById<ImageButton>(R.id.wallet_row_show)
                val btnDelete = row.findViewById<ImageButton>(R.id.wallet_row_delete)

                tvName.text = walletName
                tvAddr.text = finalAddress

                btnShow.setOnClickListener { askPinAndShowMnemonic(index) }
                btnDelete.setOnClickListener { confirmDeleteWallet(index) }

                // Row click to select the wallet
                row.setOnClickListener {
                    try {
                        SecureWalletManager.selectWallet(requireContext(), index)
                        listener?.onWalletSelected(index)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Failed to select wallet", Toast.LENGTH_SHORT).show()
                    }
                }

                container.addView(row)
            }
        } catch (e: Exception) {
            val errorText = TextView(requireContext())
            errorText.text = "Error loading wallets: ${e.message}"
            errorText.setPadding(0, 16, 0, 0)
            container.addView(errorText)
        }
    }

    private fun confirmDeleteWallet(walletIndex: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Wallet")
            .setMessage("This action is IRREVERSIBLE. Are you sure you want to delete this wallet?")
            .setPositiveButton("DELETE") { _, _ -> deleteWalletAtIndex(walletIndex) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askPinAndShowMnemonic(walletIndex: Int) {
        // Check if biometric authentication should be used
        if (BiometricAuthManager.shouldUseBiometricAuth(requireContext())) {
            authenticateWithBiometric(walletIndex)
        } else {
            authenticateWithPin(walletIndex)
        }
    }

    private fun authenticateWithBiometric(walletIndex: Int) {
        BiometricAuthManager.authenticateUser(
            fragment = this,
            title = "Authenticate",
            subtitle = "Use your biometric credential to view wallet recovery phrase",
            callback = object : BiometricAuthManager.BiometricAuthCallback {
                override fun onAuthenticationSucceeded() {
                    showMnemonic(walletIndex)
                }

                override fun onAuthenticationError(errorMessage: String) {
                    if (errorMessage == "Use PIN instead") {
                        // User chose to use PIN instead
                        authenticateWithPin(walletIndex)
                    } else if (errorMessage != "Authentication cancelled") {
                        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(requireContext(), "Authentication failed. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun authenticateWithPin(walletIndex: Int) {
        val pinEdit = EditText(requireContext())
        pinEdit.hint = "Enter PIN"
        pinEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(requireContext())
            .setTitle("Enter PIN")
            .setView(pinEdit)
            .setPositiveButton("OK") { _, _ ->
                val pin = pinEdit.text.toString().trim()
                if (TextUtils.isEmpty(pin)) {
                    Toast.makeText(requireContext(), "Enter PIN", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(pin.toByteArray(StandardCharsets.UTF_8))
                    val sb = StringBuilder()
                    for (b in digest) sb.append(String.format("%02x", b))
                    val pinHash = sb.toString()
                    if (SecureWalletManager.verifyPinHash(requireContext(), pinHash)) {
                        showMnemonic(walletIndex)
                    } else {
                        Toast.makeText(requireContext(), "Invalid PIN", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error checking PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMnemonic(walletIndex: Int) {
        try {
            SecureWalletManager.executeWithWalletMnemonic(requireContext(), walletIndex) { mnemonic ->
                activity?.runOnUiThread {
                    val tv = TextView(requireContext())
                    tv.text = mnemonic
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    tv.setPadding(pad, pad, pad, pad)
                    tv.setTextIsSelectable(true)
                    AlertDialog.Builder(requireContext())
                        .setTitle("Recovery Phrase")
                        .setView(tv)
                        .setPositiveButton("Close", null)
                        .show()
                }
                null // Return type for MnemonicOperation
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to retrieve mnemonic", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteWalletAtIndex(index: Int) {
        try {
            SecureWalletManager.deleteWallet(requireContext(), index)
            Toast.makeText(requireContext(), "Wallet deleted", Toast.LENGTH_SHORT).show()
            loadAndRenderWallets()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to delete wallet", Toast.LENGTH_SHORT).show()
        }
    }
}