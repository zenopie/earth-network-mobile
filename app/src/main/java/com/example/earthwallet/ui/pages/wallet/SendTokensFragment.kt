package network.erth.wallet.ui.pages.wallet

import android.app.AlertDialog
import android.content.Context
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.Constants
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * SendTokensFragment
 *
 * Sends native earth tokens (bank denoms) via cosmos.bank.v1beta1.MsgSend. No
 * SNIP-20, permits, or viewing keys — every token is a native denom.
 */
class SendTokensFragment : Fragment(), WalletDisplayFragment.WalletDisplayListener {

    companion object {
        private const val TAG = "SendTokensFragment"
    }

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

    private val tokens: List<Tokens.TokenInfo> = Tokens.ALL_TOKENS.values.toList()
    private lateinit var qrScannerLauncher: ActivityResultLauncher<ScanOptions>
    private var currentWalletAddress: String? = null

    interface SendTokensListener {
        fun onSendComplete()
    }

    private var listener: SendTokensListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is SendTokensListener -> parentFragment as SendTokensListener
            context is SendTokensListener -> context
            else -> null
        }
    }

    // WalletDisplayFragment.WalletDisplayListener
    override fun getCurrentWalletAddress(): String = currentWalletAddress ?: ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_send_tokens, container, false)

        qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { handleQRScanResult(it) }
        }

        parentFragmentManager.setFragmentResultListener("contact_selected", this) { _, result ->
            result.getString("contact_address")?.let { recipientEditText.setText(it) }
        }

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

        loadCurrentWalletAddress()
        setupTokenSpinner()
        setupClickListeners()
        try { tokenSpinner.setBackgroundColor(0xFFFFFFFF.toInt()) } catch (_: Exception) {}
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (tokens.isNotEmpty()) {
            fetchTokenBalance(tokens[0])
            loadTokenLogo(tokens[0])
        }
    }

    override fun onResume() {
        super.onResume()
        loadCurrentWalletAddress()
        selectedToken()?.let { fetchTokenBalance(it); loadTokenLogo(it) }
    }

    private fun selectedToken(): Tokens.TokenInfo? =
        tokens.getOrNull(tokenSpinner.selectedItemPosition)

    private fun setupTokenSpinner() {
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, tokens.map { it.symbol })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        tokenSpinner.adapter = adapter
        try { tokenSpinner.background.alpha = 0 } catch (_: Exception) {}
        tokenSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                tokens.getOrNull(position)?.let { fetchTokenBalance(it); loadTokenLogo(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener { sendTokens() }
        clearRecipientButton.setOnClickListener { recipientEditText.setText("") }
        pickWalletButton.setOnClickListener { showWalletSelectionDialog() }
        contactsButton.setOnClickListener { showContactsDialog() }
        scanQrButton.setOnClickListener { launchQRScanner() }
    }

    private fun sendTokens() {
        val recipient = recipientEditText.text.toString().trim()
        val amountStr = amountEditText.text.toString().trim()
        val token = selectedToken()

        if (TextUtils.isEmpty(recipient)) {
            toast("Please enter recipient address"); return
        }
        if (!recipient.startsWith(Constants.EARTH_PREFIX + "1")) {
            toast("Invalid earth address"); return
        }
        if (TextUtils.isEmpty(amountStr)) {
            toast("Please enter amount"); return
        }
        if (token == null) { toast("Please select a token"); return }
        val amount = Tokens.parseTokenAmount(amountStr, token.symbol)
        if (amount == null || amount <= 0) { toast("Invalid amount"); return }

        lifecycleScope.launch {
            try {
                val txHash = withContext(Dispatchers.IO) {
                    SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                        val key = EarthWallet.deriveKey(mnemonic)
                        val from = EarthWallet.address(key)
                        EarthTx.broadcast(key, listOf(Bank.msgSend(from, recipient, token.denom, amount.toString())))
                    }
                }
                Log.i(TAG, "send ok: $txHash")
                clearForm()
                fetchTokenBalance(token)
                listener?.onSendComplete()
            } catch (e: Exception) {
                Log.e(TAG, "send failed", e)
                toast("Send failed: ${e.message}")
            }
        }
    }

    private fun fetchTokenBalance(token: Tokens.TokenInfo) {
        val address = currentWalletAddress
        if (TextUtils.isEmpty(address)) { balanceText.text = "Balance: Connect wallet"; return }
        balanceText.text = "Balance: Loading..."
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { Bank.balance(address!!, token.denom) }
                balanceText.text = "Balance: ${Tokens.formatTokenAmount(raw, token)} ${token.symbol}"
            } catch (e: Exception) {
                balanceText.text = "Balance: —"
            }
        }
    }

    private fun loadTokenLogo(token: Tokens.TokenInfo) {
        try {
            requireContext().assets.open(token.logo).use { input ->
                tokenLogo.setImageBitmap(BitmapFactory.decodeStream(input))
            }
        } catch (_: Exception) {
            tokenLogo.setImageResource(R.mipmap.ic_launcher)
        }
    }

    private fun loadCurrentWalletAddress() {
        currentWalletAddress = try {
            SecureWalletManager.getWalletAddress(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e); ""
        }
    }

    private fun launchQRScanner() {
        qrScannerLauncher.launch(ScanOptions().apply {
            setPrompt("Scan QR code to get recipient address")
            setBeepEnabled(true)
            setOrientationLocked(true)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setCameraId(0)
        })
    }

    private fun handleQRScanResult(scannedContent: String) {
        val content = scannedContent.trim()
        if (content.startsWith(Constants.EARTH_PREFIX + "1")) {
            recipientEditText.setText(content)
        } else {
            toast("Invalid earth address in QR code")
        }
    }

    private fun showWalletSelectionDialog() {
        try {
            val walletsArray = SecureWalletManager.getAllWallets(requireContext())
            val current = getCurrentWalletAddress()
            val options = ArrayList<Pair<String, String>>() // address to display
            for (i in 0 until walletsArray.length()) {
                val w = walletsArray.getJSONObject(i)
                val address = w.optString("address", "")
                val name = w.optString("name", "Wallet ${i + 1}")
                if (address.isNotEmpty() && address != current) {
                    options.add(address to "$name (${address.take(14)}...)")
                }
            }
            if (options.isEmpty()) { toast("No other wallets available"); return }
            AlertDialog.Builder(requireContext())
                .setTitle("Select Wallet")
                .setItems(options.map { it.second }.toTypedArray()) { _, which ->
                    recipientEditText.setText(options[which].first)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "wallet selection failed", e); toast("Failed to load wallets")
        }
    }

    private fun showContactsDialog() {
        parentFragmentManager.beginTransaction()
            .replace(id, ContactsFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun clearForm() {
        recipientEditText.setText("")
        amountEditText.setText("")
        memoEditText.setText("")
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
