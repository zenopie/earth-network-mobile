package network.erth.wallet.ui.pages.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import network.erth.wallet.R
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.utils.WalletNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * WalletDisplayFragment
 *
 * Handles wallet information display:
 * - Wallet address display and copying
 * - SCRT balance querying and display
 * - QR code generation and display
 * - Wallet navigation (switch wallet, create wallet)
 */
class WalletDisplayFragment : Fragment() {

    companion object {
        private const val TAG = "WalletDisplayFragment"
    }

    // UI Components
    private lateinit var addressText: TextView
    private lateinit var balanceText: TextView
    private lateinit var gasUsdValue: TextView
    private lateinit var qrCodeView: ImageView
    private lateinit var sendButton: ImageButton
    private lateinit var receiveButton: ImageButton
    private lateinit var addressContainer: LinearLayout

    // State
    private var currentAddress = ""
    private var balanceLoaded = false
    private var scrtPrice: Double? = null
    private var currentGasBalance: Double = 0.0

    // Interface for communication with parent
    interface WalletDisplayListener {
        fun getCurrentWalletAddress(): String
        fun onGasUsdValueUpdated(usdValue: Double)
        fun updateGasBalanceDisplay(balance: Double, usdValue: Double)
    }

    private var listener: WalletDisplayListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is WalletDisplayListener -> parentFragment as WalletDisplayListener
            context is WalletDisplayListener -> context
            else -> throw RuntimeException("$context must implement WalletDisplayListener")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_wallet_display, container, false)

        // Initialize UI components
        addressText = view.findViewById(R.id.addressText)
        balanceText = view.findViewById(R.id.balanceText)
        gasUsdValue = view.findViewById(R.id.gasUsdValue)
        qrCodeView = view.findViewById(R.id.qrCodeView)
        sendButton = view.findViewById(R.id.sendButton)
        receiveButton = view.findViewById(R.id.receiveButton)
        addressContainer = view.findViewById(R.id.addressContainer)

        // Set white tint on button icons
        sendButton.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        receiveButton.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)

        // Set up click listeners
        setupClickListeners()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load wallet information from parent
        updateWalletInfo()
    }

    private fun setupClickListeners() {
        // Set up address container click listener to copy address
        addressContainer.setOnClickListener { copyAddressToClipboard() }

        // Set up send button click listener
        sendButton.setOnClickListener { openSendTokens() }

        // Set up receive button click listener
        receiveButton.setOnClickListener { openReceiveTokens() }
    }

    /**
     * Public method to update wallet information
     */
    fun updateWalletInfo() {
        listener?.let { listener ->
            val newAddress = listener.getCurrentWalletAddress()

            // Always refresh - no caching
            currentAddress = newAddress
            updateUI()
            refreshBalance()
            generateQRCode()
        }
    }

    /**
     * Public method to update just the address (for efficiency)
     */
    fun updateAddress(address: String) {
        if (address != currentAddress) {
            currentAddress = address
            updateUI()
            generateQRCode()
        }
    }

    private fun updateUI() {
        if (!TextUtils.isEmpty(currentAddress)) {
            addressText.text = formatAddress(currentAddress)
            addressContainer.visibility = View.VISIBLE
        } else {
            addressText.text = " "
            addressContainer.visibility = View.VISIBLE
        }
    }

    private fun formatAddress(address: String): String {
        // Display full address without truncation
        return address
    }

    private fun copyAddressToClipboard() {
        if (!TextUtils.isEmpty(currentAddress)) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Wallet Address", currentAddress)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshBalance() {
        if (TextUtils.isEmpty(currentAddress)) {
            balanceText.text = "0"
            gasUsdValue.text = ""
            return
        }

        // Show loading state
        balanceText.text = " "
        gasUsdValue.text = ""

        // Fetch balance and price using coroutines
        lifecycleScope.launch {
            try {
                // Fetch balance and price in parallel
                val balanceResult = withContext(Dispatchers.IO) {
                    fetchScrtBalanceValue(currentAddress)
                }
                val price = withContext(Dispatchers.IO) {
                    fetchScrtPrice()
                }

                currentGasBalance = balanceResult
                scrtPrice = price
                balanceText.text = formatBalance(balanceResult)
                balanceLoaded = true

                // Update USD value
                updateGasUsdValue()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching balance", e)
                balanceText.text = "Error"
            }
        }
    }

    private fun updateGasUsdValue() {
        val price = scrtPrice
        val usdValue = if (price != null && currentGasBalance > 0) {
            currentGasBalance * price
        } else {
            0.0
        }

        // Update local display (if still using it)
        if (usdValue > 0) {
            gasUsdValue.text = ErthPriceService.formatUSD(usdValue)
        } else {
            gasUsdValue.text = ""
        }

        // Notify parent
        listener?.onGasUsdValueUpdated(usdValue)
        listener?.updateGasBalanceDisplay(currentGasBalance, usdValue)
    }

    private fun formatBalance(balance: Double): String {
        return if (balance < 0.01 && balance > 0) {
            String.format("%.4f", balance)
        } else {
            String.format("%.2f", balance)
        }
    }

    private suspend fun fetchScrtPrice(): Double? {
        return try {
            val url = URL("https://api.coingecko.com/api/v3/simple/price?ids=secret&vs_currencies=usd")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val secretObj = json.optJSONObject("secret")
                secretObj?.optDouble("usd", -1.0)?.takeIf { it > 0 }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch SCRT price", e)
            null
        }
    }

    private suspend fun fetchScrtBalanceValue(address: String): Double {
        return try {
            // Use WalletNetwork's bank query method
            val microScrt = WalletNetwork.fetchUscrtBalanceMicro(WalletNetwork.DEFAULT_LCD_URL, address)
            microScrt / 1_000_000.0 // Convert micro to SCRT
        } catch (e: Exception) {
            Log.e(TAG, "SCRT balance query failed", e)
            0.0
        }
    }

    private fun generateQRCode() {
        if (!TextUtils.isEmpty(currentAddress)) {
            // TODO: Implement QR code generation
            qrCodeView.visibility = View.GONE
        }
    }

    private fun openSendTokens() {
        try {
            // Use HostActivity's showFragment method to ensure bottom navigation is shown
            val activity = activity
            if (activity is network.erth.wallet.ui.host.HostActivity) {
                activity.showFragment("send")
            } else {
                // Fallback for non-HostActivity contexts
                val sendFragment = SendTokensFragment()
                requireActivity().supportFragmentManager
                    .beginTransaction()
                    .replace(android.R.id.content, sendFragment)
                    .addToBackStack("send_tokens")
                    .commit()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open send tokens", e)
            Toast.makeText(context, "Failed to open send", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openReceiveTokens() {
        try {
            // Use HostActivity's showFragment method to ensure bottom navigation is shown
            val activity = activity
            if (activity is network.erth.wallet.ui.host.HostActivity) {
                activity.showFragment("receive")
            } else {
                // Fallback for non-HostActivity contexts
                val receiveFragment = ReceiveTokensFragment()
                requireActivity().supportFragmentManager
                    .beginTransaction()
                    .replace(android.R.id.content, receiveFragment)
                    .addToBackStack("receive_tokens")
                    .commit()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open receive tokens", e)
            Toast.makeText(context, "Failed to open receive", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}