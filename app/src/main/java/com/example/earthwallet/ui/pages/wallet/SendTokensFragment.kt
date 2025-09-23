package com.example.earthwallet.ui.pages.wallet

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthwallet.R
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.bridge.services.SnipQueryService
import com.example.earthwallet.bridge.utils.PermitManager
import com.example.earthwallet.wallet.constants.Tokens
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.example.earthwallet.wallet.utils.WalletNetwork
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.round

/**
 * SendTokensFragment
 *
 * Handles sending both native SCRT tokens and SNIP-20 tokens:
 * - Native SCRT transfers using cosmos.bank.v1beta1.MsgSend
 * - SNIP-20 token transfers using contract execution
 * - Token selection and amount validation
 * - Recipient address validation
 */
class SendTokensFragment : Fragment(), WalletDisplayFragment.WalletDisplayListener {

    companion object {
        private const val TAG = "SendTokensFragment"
        private const val REQ_SEND_NATIVE = 3001
        private const val REQ_SEND_SNIP = 3002
    }

    // UI Components
    private lateinit var tokenSpinner: Spinner
    private lateinit var recipientEditText: EditText
    private lateinit var pickWalletButton: ImageButton
    private lateinit var contactsButton: ImageButton
    private lateinit var scanQrButton: ImageButton
    private lateinit var clearRecipientButton: Button
    private lateinit var amountEditText: EditText
    private lateinit var memoEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var balanceText: TextView
    private lateinit var tokenLogo: ImageView

    // Data
    private lateinit var tokenOptions: MutableList<TokenOption>
    private lateinit var qrScannerLauncher: ActivityResultLauncher<ScanOptions>
    private var currentWalletAddress: String? = null
    private var balanceLoaded = false
    private lateinit var permitManager: PermitManager

    // Interface for communication with parent
    interface SendTokensListener {
        fun getCurrentWalletAddress(): String
        fun onSendComplete()
    }

    private var listener: SendTokensListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is SendTokensListener -> parentFragment as SendTokensListener
            context is SendTokensListener -> context
            else -> {
                null
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_send_tokens, container, false)

        // Initialize QR scanner launcher
        qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
            if (result.contents != null) {
                handleQRScanResult(result.contents)
            }
        }

        // Listen for contact selection results
        parentFragmentManager.setFragmentResultListener("contact_selected", this) { _, result ->
            val contactName = result.getString("contact_name")
            val contactAddress = result.getString("contact_address")
            if (contactAddress != null) {
                recipientEditText.setText(contactAddress)
                Toast.makeText(context, "Selected: $contactName", Toast.LENGTH_SHORT).show()
            }
        }

        // Initialize UI components
        tokenSpinner = view.findViewById(R.id.tokenSpinner)
        recipientEditText = view.findViewById(R.id.recipientEditText)
        pickWalletButton = view.findViewById(R.id.pickWalletButton)
        contactsButton = view.findViewById(R.id.contactsButton)
        scanQrButton = view.findViewById(R.id.scanQrButton)
        clearRecipientButton = view.findViewById(R.id.clearRecipientButton)
        amountEditText = view.findViewById(R.id.amountEditText)
        memoEditText = view.findViewById(R.id.memoEditText)
        sendButton = view.findViewById(R.id.sendButton)
        balanceText = view.findViewById(R.id.balanceText)
        tokenLogo = view.findViewById(R.id.tokenLogo)

        try {
            permitManager = PermitManager.getInstance(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize permit manager", e)
            Toast.makeText(context, "Failed to initialize wallet", Toast.LENGTH_SHORT).show()
            return view
        }

        // Load current wallet address
        loadCurrentWalletAddress()

        setupTokenSpinner()
        setupClickListeners()

        // Force spinner background to be light
        tokenSpinner.setBackgroundColor(0xFFFFFFFF.toInt()) // White background

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load balance and logo for initially selected token (first item in spinner)
        if (::tokenOptions.isInitialized && tokenOptions.isNotEmpty()) {
            fetchTokenBalance(tokenOptions[0])
            loadTokenLogo(tokenOptions[0])
        }
    }

    private fun setupTokenSpinner() {
        tokenOptions = mutableListOf()

        // Add native SCRT option
        tokenOptions.add(TokenOption("SCRT", "SCRT", true, null))

        // Add SNIP-20 tokens
        for (symbol in Tokens.ALL_TOKENS.keys) {
            val token = Tokens.ALL_TOKENS[symbol]
            tokenOptions.add(TokenOption(symbol, symbol, false, token))
        }

        // Create simple list for spinner (just token symbols like swap fragment)
        val tokenSymbols = tokenOptions.map { it.displayName }

        // Use the same adapter style as SwapTokensMainFragment
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, tokenSymbols)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        tokenSpinner.adapter = adapter

        // Force spinner background to be transparent to blend with input box
        try {
            tokenSpinner.background.alpha = 0
        } catch (e: Exception) {
        }

        // Set up spinner selection listener to load balance and logo
        tokenSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < tokenOptions.size) {
                    val selectedToken = tokenOptions[position]
                    fetchTokenBalance(selectedToken)
                    loadTokenLogo(selectedToken)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    private fun launchQRScanner() {
        val options = ScanOptions().apply {
            setPrompt("Scan QR code to get recipient address")
            setBeepEnabled(true)
            setBarcodeImageEnabled(true)
            setOrientationLocked(true)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setCameraId(0) // Use rear camera
        }

        qrScannerLauncher.launch(options)
    }

    private fun handleQRScanResult(scannedContent: String) {
        val content = scannedContent.trim()

        // Validate that the scanned content is a valid Secret Network address
        if (content.startsWith("secret1") && content.length >= 45) {
            recipientEditText.setText(content)
        } else {
            Toast.makeText(context, "Invalid Secret Network address in QR code", Toast.LENGTH_LONG).show()
        }
    }

    private fun showWalletSelectionDialog() {
        try {
            val walletOptions = mutableListOf<WalletOption>()

            // Load all wallets using SecureWalletManager (safe - no mnemonics exposed)
            val walletsArray = SecureWalletManager.getAllWallets(requireContext())
            val currentAddress = getCurrentWalletAddress()

            for (i in 0 until walletsArray.length()) {
                val wallet = walletsArray.getJSONObject(i)
                val address = wallet.optString("address", "")
                val name = wallet.optString("name", "Wallet ${i + 1}")

                if (!TextUtils.isEmpty(address)) {
                    // Don't include the current wallet in the recipient list
                    if (address != currentAddress) {
                        val displayName = "$name (${address.substring(0, 14)}...)"
                        walletOptions.add(WalletOption(address, displayName, name))
                    }
                }
            }

            if (walletOptions.isEmpty()) {
                Toast.makeText(context, "No other wallets available", Toast.LENGTH_SHORT).show()
                return
            }

            // Create dialog with wallet options
            val builder = AlertDialog.Builder(context)
            builder.setTitle("Select Wallet")

            val walletNames = walletOptions.map { it.displayName }.toTypedArray()

            builder.setItems(walletNames) { _, which ->
                val selected = walletOptions[which]
                recipientEditText.setText(selected.address)
                Toast.makeText(context, "Selected: ${selected.name}", Toast.LENGTH_SHORT).show()
            }

            builder.setNegativeButton("Cancel", null)
            builder.show()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show wallet selection dialog", e)
            Toast.makeText(context, "Failed to load wallets", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showContactsDialog() {
        // Navigate to contacts fragment
        val contactsFragment = ContactsFragment()

        // Replace current fragment with contacts fragment
        parentFragmentManager.beginTransaction()
            .replace(id, contactsFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener { sendTokens() }

        clearRecipientButton.setOnClickListener {
            recipientEditText.setText("")
        }

        pickWalletButton.setOnClickListener { showWalletSelectionDialog() }
        contactsButton.setOnClickListener { showContactsDialog() }
        scanQrButton.setOnClickListener { launchQRScanner() }
    }

    private fun sendTokens() {
        val recipient = recipientEditText.text.toString().trim()
        val amount = amountEditText.text.toString().trim()
        val memo = memoEditText.text.toString().trim()

        if (TextUtils.isEmpty(recipient)) {
            Toast.makeText(context, "Please enter recipient address", Toast.LENGTH_SHORT).show()
            return
        }

        if (TextUtils.isEmpty(amount)) {
            Toast.makeText(context, "Please enter amount", Toast.LENGTH_SHORT).show()
            return
        }

        if (!recipient.startsWith("secret1")) {
            Toast.makeText(context, "Invalid Secret Network address", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPosition = tokenSpinner.selectedItemPosition
        if (selectedPosition < 0 || selectedPosition >= tokenOptions.size) {
            Toast.makeText(context, "Please select a token", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedToken = tokenOptions[selectedPosition]

        try {
            if (selectedToken.isNative) {
                sendNativeToken(recipient, amount, memo)
            } else {
                sendSnipToken(selectedToken, recipient, amount, memo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tokens", e)
            Toast.makeText(context, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendNativeToken(recipient: String, amount: String, memo: String) {
        // Convert amount to microSCRT (6 decimals)
        val amountDouble = amount.toDouble()
        val microScrt = round(amountDouble * 1_000_000).toLong()
        val microScrtString = microScrt.toString()

        // Use the native token sending activity
        val intent = Intent(activity, TransactionActivity::class.java).apply {
            putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_NATIVE_SEND)
            putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, recipient)
            putExtra(TransactionActivity.EXTRA_AMOUNT, microScrtString)
            putExtra(TransactionActivity.EXTRA_MEMO, memo)
        }

        startActivityForResult(intent, REQ_SEND_NATIVE)
    }

    private fun sendSnipToken(tokenOption: TokenOption, recipient: String, amount: String, memo: String) {
        val token = tokenOption.tokenInfo ?: return

        // Convert amount to token's smallest unit
        val amountDouble = amount.toDouble()
        val tokenAmount = round(amountDouble * 10.0.pow(token.decimals.toDouble())).toLong()
        val tokenAmountString = tokenAmount.toString()

        // Create message for SNIP-20 transfer
        val transferMsg = JSONObject()
        val transfer = JSONObject().apply {
            put("recipient", recipient)
            put("amount", tokenAmountString)
            if (!TextUtils.isEmpty(memo)) {
                put("memo", memo)
            }
        }
        transferMsg.put("transfer", transfer)

        // Use TransactionActivity for SNIP-20 token transfer
        val intent = Intent(activity, TransactionActivity::class.java).apply {
            putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SNIP_EXECUTE)
            putExtra(TransactionActivity.EXTRA_TOKEN_CONTRACT, token.contract)
            putExtra(TransactionActivity.EXTRA_TOKEN_HASH, token.hash)
            putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, recipient)
            putExtra(TransactionActivity.EXTRA_RECIPIENT_HASH, "")
            putExtra(TransactionActivity.EXTRA_AMOUNT, tokenAmountString)
            putExtra(TransactionActivity.EXTRA_MESSAGE_JSON, transferMsg.toString())
        }

        startActivityForResult(intent, REQ_SEND_SNIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_SEND_NATIVE || requestCode == REQ_SEND_SNIP) {
            if (resultCode == Activity.RESULT_OK) {
                // Clear form
                clearForm()
                // Refresh balance after successful transaction
                val selectedPosition = tokenSpinner.selectedItemPosition
                if (selectedPosition >= 0 && selectedPosition < tokenOptions.size) {
                    fetchTokenBalance(tokenOptions[selectedPosition])
                }
                // Notify parent
                listener?.onSendComplete()
            } else {
                val error = data?.getStringExtra("error") ?: "Unknown error"
                Toast.makeText(context, "Transaction failed: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh wallet address first (like TokenBalancesFragment)
        loadCurrentWalletAddress()

        // Refresh balance and logo when returning to the screen
        val selectedPosition = tokenSpinner.selectedItemPosition
        if (selectedPosition >= 0 && selectedPosition < tokenOptions.size) {
            val selectedToken = tokenOptions[selectedPosition]
            fetchTokenBalance(selectedToken)
            loadTokenLogo(selectedToken)
        }
    }

    private fun clearForm() {
        recipientEditText.setText("")
        amountEditText.setText("")
        memoEditText.setText("")
    }

    private fun loadCurrentWalletAddress() {
        // Use SecureWalletManager to get wallet address directly
        try {
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext())
            if (!TextUtils.isEmpty(currentWalletAddress)) {
            } else {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            currentWalletAddress = ""
        }
    }

    private fun fetchTokenBalance(tokenOption: TokenOption) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            balanceText.text = "Balance: Connect wallet"
            return
        }

        if (tokenOption.isNative) {
            // Native SCRT balance using coroutines instead of AsyncTask
            balanceText.text = "Balance: Loading..."
            fetchScrtBalance()
        } else {
            // SNIP-20 token balance using permit-based queries
            if (!hasPermitForToken(tokenOption.symbol)) {
                balanceText.text = "Balance: Create permit"
                return
            }

            balanceText.text = "Balance: Loading..."
            fetchSnipTokenBalanceWithPermit(tokenOption.symbol)
        }
    }

    private fun fetchScrtBalance() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Use WalletNetwork's bank query method
                    val microScrt = WalletNetwork.fetchUscrtBalanceMicro(WalletNetwork.DEFAULT_LCD_URL, currentWalletAddress!!)
                    WalletNetwork.formatScrt(microScrt)
                }
                balanceText.text = "Balance: $result"
                balanceLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "SCRT balance query failed", e)
                balanceText.text = "Balance: Error: ${e.message}"
            }
        }
    }

    private fun hasPermitForToken(tokenSymbol: String): Boolean {
        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        if (tokenInfo == null) {
            return false
        }
        return permitManager.hasPermit(currentWalletAddress!!, tokenInfo.contract)
    }

    private fun fetchSnipTokenBalanceWithPermit(tokenSymbol: String) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SnipQueryService.queryBalanceWithPermit(
                        requireContext(),
                        tokenSymbol,
                        currentWalletAddress!!
                    )
                }
                handleSnipBalanceResult(tokenSymbol, result.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Token balance query failed for $tokenSymbol: ${e.message}", e)
                balanceText.text = "Balance: Error loading"
            }
        }
    }

    private fun handleSnipBalanceResult(tokenSymbol: String, json: String) {
        try {
            if (TextUtils.isEmpty(json)) {
                balanceText.text = "Balance: Error loading"
                return
            }

            val root = JSONObject(json)
            val success = root.optBoolean("success", false)

            if (success) {
                val result = root.optJSONObject("result")
                if (result != null) {
                    val balance = result.optJSONObject("balance")
                    if (balance != null) {
                        val amount = balance.optString("amount", "0")
                        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                        if (tokenInfo != null) {
                            var formattedBalance = 0.0
                            if (!TextUtils.isEmpty(amount)) {
                                try {
                                    val rawAmount = amount.toLong()
                                    formattedBalance = rawAmount / 10.0.pow(tokenInfo.decimals.toDouble())
                                } catch (e: NumberFormatException) {
                                    Log.e(TAG, "Failed to parse balance amount: $amount", e)
                                }
                            }
                            balanceText.text = String.format("Balance: %.6f %s", formattedBalance, tokenSymbol)
                        } else {
                            balanceText.text = "Balance: Error loading"
                        }
                    } else {
                        balanceText.text = "Balance: Error loading"
                    }
                } else {
                    balanceText.text = "Balance: Error loading"
                }
            } else {
                balanceText.text = "Balance: Error loading"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle SNIP balance result", e)
            balanceText.text = "Balance: Error loading"
        }
    }

    private fun loadTokenLogo(tokenOption: TokenOption) {
        if (tokenOption.isNative) {
            // Native SCRT - use gas station icon
            tokenLogo.setImageResource(R.drawable.ic_local_gas_station)
        } else {
            // SNIP-20 token - load from assets
            try {
                val tokenInfo = tokenOption.tokenInfo
                if (tokenInfo != null && !TextUtils.isEmpty(tokenInfo.logo)) {
                    // Load logo from assets
                    val bitmap = BitmapFactory.decodeStream(context?.assets?.open(tokenInfo.logo))
                    tokenLogo.setImageBitmap(bitmap)
                } else {
                    // No logo available, use default wallet icon
                    tokenLogo.setImageResource(R.drawable.ic_wallet)
                }
            } catch (e: Exception) {
                tokenLogo.setImageResource(R.drawable.ic_wallet)
            }
        }
    }

    override fun getCurrentWalletAddress(): String {
        return try {
            SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current wallet address", e)
            ""
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    /**
     * Token option for spinner
     */
    private data class TokenOption(
        val symbol: String,
        val displayName: String,
        val isNative: Boolean,
        val tokenInfo: Tokens.TokenInfo?
    ) {
        override fun toString(): String = displayName
    }

    /**
     * Wallet option for recipient spinner
     */
    private data class WalletOption(
        val address: String,
        val displayName: String,
        val name: String
    ) {
        override fun toString(): String = displayName
    }
}