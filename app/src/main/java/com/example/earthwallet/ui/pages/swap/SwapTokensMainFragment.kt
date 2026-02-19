package network.erth.wallet.ui.pages.swap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.wallet.services.SessionManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import network.erth.wallet.wallet.utils.WalletNetwork
import org.json.JSONObject
import java.text.DecimalFormat
import kotlin.math.pow

/**
 * SwapTokensMainFragment
 *
 * Implements token swapping functionality similar to the React web app:
 * - From/To token selection with balance display
 * - Swap simulation and execution
 * - Slippage tolerance settings
 * - Viewing key management integration
 */
class SwapTokensMainFragment : Fragment() {

    companion object {
        private const val TAG = "SwapTokensFragment"
    }

    // UI Components
    private var fromTokenSpinner: Spinner? = null
    private var toTokenSpinner: Spinner? = null
    private var fromAmountInput: EditText? = null
    private var toAmountInput: EditText? = null
    private var slippageInput: EditText? = null
    private var fromBalanceText: TextView? = null
    private var toBalanceText: TextView? = null
    private var fromUsdValue: TextView? = null
    private var toUsdValue: TextView? = null
    private var rateText: TextView? = null
    private var minReceivedText: TextView? = null
    private var fromMaxButton: Button? = null
    private var toMaxButton: Button? = null
    private var toggleButton: ImageButton? = null
    private var swapButton: Button? = null
    private var detailsToggle: Button? = null
    private var detailsContainer: LinearLayout? = null
    private var fromTokenLogo: ImageView? = null
    private var toTokenLogo: ImageView? = null

    // GAS pseudo-token constant
    private val GAS_TOKEN = "GAS"

    // State
    private var currentWalletAddress = ""
    private var tokenSymbols: List<String> = listOf()
    private var fromToken = "ANML"
    private var toToken = "ERTH"
    private var fromBalance = 0.0
    private var toBalance = 0.0
    private var slippage = 1.0
    private var isSimulatingSwap = false
    private var detailsVisible = false
    private val inputHandler = Handler(Looper.getMainLooper())
    private var simulationRunnable: Runnable? = null

    // Price state for USD display
    private var erthPrice: Double? = null
    private var scrtPrice: Double? = null

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null
    private var permitManager: PermitManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize token list - use getAllTokens() with GAS pseudo-token at the start
        tokenSymbols = listOf(GAS_TOKEN) + ArrayList(Tokens.getAllTokens().keys)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_swap_tokens_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupSpinners()
        setupClickListeners()
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        loadCurrentWalletAddress()

        // Initialize permit manager only if session is active
        if (SessionManager.isSessionActive()) {
            try {
                permitManager = PermitManager.getInstance(requireContext())
            } catch (e: Exception) {
                Log.e("SwapTokensMainFragment", "Failed to initialize PermitManager", e)
                permitManager = null
            }
        } else {
            Log.d("SwapTokensMainFragment", "No active session - PermitManager will be initialized after login")
            permitManager = null
        }

        updateTokenLogos()
        fetchBalances()
        fetchPrices()
    }

    private fun fetchPrices() {
        lifecycleScope.launch {
            try {
                erthPrice = ErthPriceService.fetchErthPrice()
                // Also fetch SCRT price for GAS token via CoinGecko
                scrtPrice = fetchScrtPrice()
                updateUsdValues()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch prices", e)
            }
        }
    }

    private suspend fun fetchScrtPrice(): Double? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("https://api.coingecko.com/api/v3/simple/price?ids=secret&vs_currencies=usd")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
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
    }

    private fun initializeViews(view: View) {
        fromTokenSpinner = view.findViewById(R.id.from_token_spinner)
        toTokenSpinner = view.findViewById(R.id.to_token_spinner)
        fromAmountInput = view.findViewById(R.id.from_amount_input)
        toAmountInput = view.findViewById(R.id.to_amount_input)
        slippageInput = view.findViewById(R.id.slippage_input)

        fromBalanceText = view.findViewById(R.id.from_balance_text)
        toBalanceText = view.findViewById(R.id.to_balance_text)
        fromUsdValue = view.findViewById(R.id.from_usd_value)
        toUsdValue = view.findViewById(R.id.to_usd_value)
        rateText = view.findViewById(R.id.rate_text)
        minReceivedText = view.findViewById(R.id.min_received_text)

        fromMaxButton = view.findViewById(R.id.from_max_button)
        toMaxButton = view.findViewById(R.id.to_max_button)

        toggleButton = view.findViewById(R.id.toggle_button)
        swapButton = view.findViewById(R.id.swap_button)
        detailsToggle = view.findViewById(R.id.details_toggle)
        detailsContainer = view.findViewById(R.id.details_container)

        fromTokenLogo = view.findViewById(R.id.from_token_logo)
        toTokenLogo = view.findViewById(R.id.to_token_logo)
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, tokenSymbols)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        fromTokenSpinner?.adapter = adapter
        toTokenSpinner?.adapter = adapter

        // Set initial selections
        fromTokenSpinner?.setSelection(tokenSymbols.indexOf(fromToken))
        toTokenSpinner?.setSelection(tokenSymbols.indexOf(toToken))

        fromTokenSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = tokenSymbols[position]
                if (selected != fromToken) {
                    if (selected == toToken) {
                        // Swap tokens if selecting the same as 'to'
                        toToken = fromToken
                        toTokenSpinner?.setSelection(tokenSymbols.indexOf(toToken))
                    }
                    fromToken = selected
                    onTokenSelectionChanged()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        toTokenSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = tokenSymbols[position]
                if (selected != toToken) {
                    if (selected == fromToken) {
                        // Swap tokens if selecting the same as 'from'
                        fromToken = toToken
                        fromTokenSpinner?.setSelection(tokenSymbols.indexOf(fromToken))
                    }
                    toToken = selected
                    onTokenSelectionChanged()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        // Add delayed simulation TextWatcher to prevent clearing and keyboard dismissal
        fromAmountInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Cancel previous simulation if still pending
                simulationRunnable?.let { inputHandler.removeCallbacks(it) }

                // Schedule new simulation with delay
                simulationRunnable = Runnable { onFromAmountChangedDelayed() }
                inputHandler.postDelayed(simulationRunnable!!, 500) // 500ms delay - no keyboard dismissal with direct service
            }
        })

        fromMaxButton?.setOnClickListener { handleFromButtonClick() }
        toMaxButton?.setOnClickListener { handleToButtonClick() }

        toggleButton?.setOnClickListener { toggleTokenPair() }
        swapButton?.setOnClickListener { executeSwap() }

        detailsToggle?.setOnClickListener { toggleDetails() }

        slippageInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSlippage()
            }
        })
    }

    private fun onTokenSelectionChanged() {
        updateTokenLogos()
        clearAmounts()
        fetchBalances()
    }

    private fun onFromAmountChangedDelayed() {
        val amountStr = fromAmountInput?.text.toString()
        if (TextUtils.isEmpty(amountStr)) {
            toAmountInput?.setText("")
            updateSwapButton()
            return
        }

        try {
            val amount = amountStr.toDouble()
            if (amount > 0) {
                simulateSwap(amount)
            } else {
                toAmountInput?.setText("")
            }
        } catch (e: NumberFormatException) {
            toAmountInput?.setText("")
        }
        updateSwapButton()
    }

    private fun simulateSwap(inputAmount: Double) {
        if (isSimulatingSwap) return
        isSimulatingSwap = true
        simulateSwapWithContract(inputAmount)
    }

    private fun handleFromButtonClick() {
        if (fromBalance >= 0) {
            // Max button behavior
            setMaxFromAmount()
        } else {
            // Add button behavior
            requestPermit(fromToken)
        }
    }

    private fun handleToButtonClick() {
        // To button is only for Add permit functionality
        requestPermit(toToken)
    }

    private fun setMaxFromAmount() {
        if (fromBalance > 0) {
            fromAmountInput?.setText(fromBalance.toString())
        }
    }

    private fun toggleTokenPair() {
        val tempToken = fromToken
        fromToken = toToken
        toToken = tempToken

        fromTokenSpinner?.setSelection(tokenSymbols.indexOf(fromToken))
        toTokenSpinner?.setSelection(tokenSymbols.indexOf(toToken))

        updateTokenLogos()
        clearAmounts()
        fetchBalances()
    }

    private fun toggleDetails() {
        detailsVisible = !detailsVisible
        detailsContainer?.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        detailsToggle?.text = if (detailsVisible) "Hide Details ▲" else "Show Details ▼"
    }

    private fun clearAmounts() {
        fromAmountInput?.setText("")
        toAmountInput?.setText("")
        fromUsdValue?.text = "$0.00"
        toUsdValue?.text = "$0.00"
        updateSwapButton()
    }

    private fun updateTokenLogos() {
        val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
        val toTokenSymbol = tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]

        loadTokenLogo(fromTokenLogo, fromTokenSymbol)
        loadTokenLogo(toTokenLogo, toTokenSymbol)
    }

    private fun loadTokenLogo(imageView: ImageView?, tokenSymbol: String) {
        try {
            // Handle GAS pseudo-token with gas pump icon
            if (tokenSymbol == GAS_TOKEN) {
                imageView?.setImageResource(R.drawable.ic_gas_pump)
                return
            }

            // Get token info and logo path
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
            if (tokenInfo?.logo != null) {
                var logoPath = tokenInfo.logo

                // Handle both old format (coin/ERTH.png) and new format (erth, scrt, etc.)
                if (!logoPath.contains("/") && !logoPath.contains(".")) {
                    // New format - try adding coin/ prefix and .png extension
                    logoPath = "coin/${logoPath.uppercase()}.png"
                }

                try {
                    val inputStream = context?.assets?.open(logoPath)
                    val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                    imageView?.setImageDrawable(drawable)
                    inputStream?.close()
                } catch (e2: Exception) {
                    // Try the original logo path if the modified one failed
                    if (logoPath != tokenInfo.logo) {
                        val inputStream = context?.assets?.open(tokenInfo.logo)
                        val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                        imageView?.setImageDrawable(drawable)
                        inputStream?.close()
                    } else {
                        throw e2
                    }
                }
            } else {
                // No token info or logo path, use default
                imageView?.setImageResource(R.drawable.ic_wallet)
            }
        } catch (e: Exception) {
            // If logo not found, use default wallet icon
            imageView?.setImageResource(R.drawable.ic_wallet)
        }
    }

    private fun updateSwapButton() {
        val fromAmountStr = fromAmountInput?.text.toString()
        val toAmountStr = toAmountInput?.text.toString()

        val enabled = !TextUtils.isEmpty(fromAmountStr) &&
                     !TextUtils.isEmpty(toAmountStr) &&
                     fromAmountStr != "0" &&
                     toAmountStr != "0"

        swapButton?.isEnabled = enabled

        // Details toggle is always visible now - no layout shift
    }

    private fun updateSlippage() {
        val slippageStr = slippageInput?.text.toString()
        try {
            slippage = slippageStr.toDouble()
            updateDetailsDisplay()
        } catch (e: NumberFormatException) {
            slippage = 1.0
        }
    }

    private fun updateDetailsDisplay() {
        val fromAmountStr = fromAmountInput?.text.toString()
        val toAmountStr = toAmountInput?.text.toString()

        if (!TextUtils.isEmpty(fromAmountStr) && !TextUtils.isEmpty(toAmountStr)) {
            try {
                val fromAmount = fromAmountStr.toDouble()
                val toAmount = toAmountStr.toDouble()

                // Update rate
                val rate = toAmount / fromAmount
                val df = DecimalFormat("#.######")
                rateText?.text = "1 $fromToken = ${df.format(rate)} $toToken"

                // Update minimum received
                val minReceived = toAmount * (1 - slippage / 100)
                val decimals = if (toToken == GAS_TOKEN) 6 else Tokens.getTokenInfo(toToken)?.decimals ?: 6
                val minDf = DecimalFormat("#.${"0".repeat(decimals)}")
                minReceivedText?.text = "${minDf.format(minReceived)} $toToken"

            } catch (e: NumberFormatException) {
                Log.e(TAG, "Error updating details display", e)
            }
        }

        // Also update USD values
        updateUsdValues()
    }

    private fun updateUsdValues() {
        val fromAmountStr = fromAmountInput?.text.toString()
        val toAmountStr = toAmountInput?.text.toString()

        // Calculate FROM USD value
        if (!TextUtils.isEmpty(fromAmountStr)) {
            try {
                val fromAmount = fromAmountStr.toDouble()
                if (fromAmount > 0) {
                    val usdValue = getUsdValueForToken(fromToken, fromAmount)
                    fromUsdValue?.text = ErthPriceService.formatUSD(usdValue ?: 0.0)
                } else {
                    fromUsdValue?.text = "$0.00"
                }
            } catch (e: NumberFormatException) {
                fromUsdValue?.text = "$0.00"
            }
        } else {
            fromUsdValue?.text = "$0.00"
        }

        // Calculate TO USD value
        if (!TextUtils.isEmpty(toAmountStr)) {
            try {
                val toAmount = toAmountStr.toDouble()
                if (toAmount > 0) {
                    val usdValue = getUsdValueForToken(toToken, toAmount)
                    toUsdValue?.text = ErthPriceService.formatUSD(usdValue ?: 0.0)
                } else {
                    toUsdValue?.text = "$0.00"
                }
            } catch (e: NumberFormatException) {
                toUsdValue?.text = "$0.00"
            }
        } else {
            toUsdValue?.text = "$0.00"
        }
    }

    private fun getUsdValueForToken(token: String, amount: Double): Double? {
        return when (token) {
            GAS_TOKEN -> scrtPrice?.let { it * amount }
            "sSCRT" -> scrtPrice?.let { it * amount }
            "ERTH" -> erthPrice?.let { it * amount }
            else -> {
                // For other tokens, use ERTH price with spot rate approximation
                // For simplicity, we just use ERTH price as base (tokens paired with ERTH)
                erthPrice?.let { it * amount }
            }
        }
    }

    private fun loadCurrentWalletAddress() {
        try {
            // Use SecureWalletManager to get current wallet address
            currentWalletAddress = network.erth.wallet.wallet.services.SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            currentWalletAddress = ""
        }
    }

    private fun fetchBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            fromBalanceText?.text = "Balance: Connect wallet"
            toBalanceText?.text = "Balance: Connect wallet"
            return
        }

        val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
        val toTokenSymbol = tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]

        // Handle GAS (native SCRT) balance separately
        if (fromTokenSymbol == GAS_TOKEN) {
            fetchNativeScrtBalance(true)
        } else {
            fetchTokenBalanceWithContract(fromTokenSymbol, true)
        }

        if (toTokenSymbol == GAS_TOKEN) {
            fetchNativeScrtBalance(false)
        } else {
            fetchTokenBalanceWithContract(toTokenSymbol, false)
        }
    }

    private fun fetchNativeScrtBalance(isFromToken: Boolean) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            if (isFromToken) {
                fromBalance = 0.0
                updateFromBalanceDisplay()
            } else {
                toBalance = 0.0
                updateToBalanceDisplay()
            }
            return
        }

        // Query native SCRT balance in background thread
        Thread {
            try {
                val microScrt = WalletNetwork.fetchUscrtBalanceMicro(
                    WalletNetwork.DEFAULT_LCD_URL,
                    currentWalletAddress
                )
                val scrtAmount = microScrt.toDouble() / 1_000_000.0

                activity?.runOnUiThread {
                    if (isFromToken) {
                        fromBalance = scrtAmount
                        updateFromBalanceDisplay()
                    } else {
                        toBalance = scrtAmount
                        updateToBalanceDisplay()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SCRT balance query failed", e)
                activity?.runOnUiThread {
                    if (isFromToken) {
                        fromBalance = -1.0
                        updateFromBalanceDisplay()
                    } else {
                        toBalance = -1.0
                        updateToBalanceDisplay()
                    }
                }
            }
        }.start()
    }

    private fun requestPermit(tokenSymbol: String) {
        if (permitManager == null) {
            Toast.makeText(context, "Permit manager not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        if (tokenInfo == null) {
            Toast.makeText(context, "Token not supported", Toast.LENGTH_SHORT).show()
            return
        }

        // Create permit in background thread
        lifecycleScope.launch {
            try {
                // Create permit with correct parameters
                val contractAddresses = listOf(tokenInfo.contract)
                val permissions = listOf("balance", "history", "allowance")
                val permitName = "EarthWallet"

                val permit = permitManager?.createPermit(requireContext(), currentWalletAddress, contractAddresses, permitName, permissions)

                // Update UI
                if (permit != null) {
                    // Refresh balances to update the UI
                    fetchBalances()
                } else {
                    Toast.makeText(context, "Failed to create permit", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating permit for $tokenSymbol", e)
                Toast.makeText(context, "Error creating permit: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeSwap() {
        val fromAmountStr = fromAmountInput?.text.toString()
        if (TextUtils.isEmpty(fromAmountStr)) return

        try {
            val inputAmount = fromAmountStr.toDouble()
            if (inputAmount <= 0 || inputAmount > fromBalance) {
                Toast.makeText(context, "Invalid amount", Toast.LENGTH_SHORT).show()
                return
            }

            executeSwapWithContract()

        } catch (e: NumberFormatException) {
            Toast.makeText(context, "Invalid amount", Toast.LENGTH_SHORT).show()
        }
    }


    private fun handleSwapSimulationResult(json: String?, effectiveToToken: String? = null) {
        try {
            val root = JSONObject(json ?: "")
            val success = root.optBoolean("success", false)

            if (success) {
                val result = root.optJSONObject("result")
                result?.let {
                    // Parse output_amount to match React web app response format
                    val outputAmount = it.optString("output_amount", "0")
                    // Use effectiveToToken if provided (for GAS swaps), otherwise use selected token
                    val tokenForDecimals = effectiveToToken ?: tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]
                    val toTokenInfo = Tokens.getTokenInfo(tokenForDecimals)
                    val decimals = toTokenInfo?.decimals ?: 6
                    val formattedOutput = outputAmount.toDouble() / 10.0.pow(decimals)
                    val df = DecimalFormat("#.######")

                    // Update output amount without requesting focus to avoid dismissing keyboard
                    toAmountInput?.setText(df.format(formattedOutput))
                    updateDetailsDisplay()
                }
            } else {
                Toast.makeText(context, "Swap simulation failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse swap simulation result", e)
            Toast.makeText(context, "Error simulating swap", Toast.LENGTH_SHORT).show()
        }

        isSimulatingSwap = false
        updateSwapButton()
    }

    private fun updateFromBalanceDisplay() {
        val df = DecimalFormat("#.##")

        when {
            fromBalance >= 0 -> {
                fromBalanceText?.text = "Balance: ${df.format(fromBalance)}"
                if (fromBalance > 0) {
                    fromMaxButton?.text = "Max"
                    fromMaxButton?.visibility = View.VISIBLE
                } else {
                    fromMaxButton?.visibility = View.GONE
                }
            }
            fromBalance == -1.0 -> {
                fromBalanceText?.text = "Balance: "
                fromMaxButton?.text = "Add"
                fromMaxButton?.visibility = View.VISIBLE
            }
            else -> {
                fromBalanceText?.text = "Balance: ..."
                fromMaxButton?.visibility = View.GONE
            }
        }
    }

    private fun updateToBalanceDisplay() {
        val df = DecimalFormat("#.##")

        when {
            toBalance >= 0 -> {
                toBalanceText?.text = "Balance: ${df.format(toBalance)}"
                toMaxButton?.visibility = View.GONE
            }
            toBalance == -1.0 -> {
                toBalanceText?.text = "Balance: "
                toMaxButton?.text = "Add"
                toMaxButton?.visibility = View.VISIBLE
            }
            else -> {
                toBalanceText?.text = "Balance: ..."
                toMaxButton?.visibility = View.GONE
            }
        }
    }

    private fun simulateSwapWithContract(inputAmount: Double) {
        val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
        val toTokenSymbol = tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]

        // Handle GAS <-> sSCRT direct wrap/unwrap (1:1)
        if ((fromTokenSymbol == GAS_TOKEN && toTokenSymbol == "sSCRT") ||
            (fromTokenSymbol == "sSCRT" && toTokenSymbol == GAS_TOKEN)) {
            val df = DecimalFormat("#.######")
            toAmountInput?.setText(df.format(inputAmount))
            isSimulatingSwap = false
            updateSwapButton()
            updateDetailsDisplay()
            return
        }

        // For GAS swaps, use sSCRT as the actual token for simulation
        val effectiveFromToken = if (fromTokenSymbol == GAS_TOKEN) "sSCRT" else fromTokenSymbol
        val effectiveToToken = if (toTokenSymbol == GAS_TOKEN) "sSCRT" else toTokenSymbol

        // Get token info for from token
        val fromTokenInfo = Tokens.getTokenInfo(effectiveFromToken)
        if (fromTokenInfo == null) {
            Log.e(TAG, "From token not supported: $effectiveFromToken")
            Toast.makeText(context, "Token not supported", Toast.LENGTH_SHORT).show()
            isSimulatingSwap = false
            updateSwapButton()
            return
        }

        // Build swap simulation query to match React web app format
        val toTokenInfo = Tokens.getTokenInfo(effectiveToToken)
        if (toTokenInfo == null) {
            Toast.makeText(context, "To token not supported", Toast.LENGTH_SHORT).show()
            isSimulatingSwap = false
            updateSwapButton()
            return
        }

        val queryJson = String.format(
            "{\"simulate_swap\": {\"input_token\": \"%s\", \"amount\": \"%s\", \"output_token\": \"%s\"}}",
            fromTokenInfo.contract,
            (inputAmount * 10.0.pow(fromTokenInfo.decimals)).toLong().toString(),
            toTokenInfo.contract
        )


        // Use SecretKClient in lifecycleScope
        lifecycleScope.launch {
            try {
                // Check wallet availability using the correct wallet preferences
                if (!network.erth.wallet.wallet.services.SecureWalletManager.isWalletAvailable(requireContext())) {
                    Toast.makeText(context, "No wallet found", Toast.LENGTH_SHORT).show()
                    isSimulatingSwap = false
                    updateSwapButton()
                    return@launch
                }

                val result = SecretKClient.queryContractJson(
                    Constants.EXCHANGE_CONTRACT,
                    JSONObject(queryJson),
                    Constants.EXCHANGE_HASH
                )

                // Extract data from wrapper
                val actualResult = if (result.has("data")) {
                    result.getJSONObject("data")
                } else {
                    result
                }

                // Format result to match expected format
                val response = JSONObject()
                response.put("success", true)
                response.put("result", actualResult)

                // Handle result - pass the effective toToken for proper decimal parsing
                handleSwapSimulationResult(response.toString(), effectiveToToken)

            } catch (e: Exception) {
                Log.e(TAG, "Swap simulation failed", e)
                Toast.makeText(context, "Swap simulation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                isSimulatingSwap = false
                updateSwapButton()
            }
        }
    }

    private fun fetchTokenBalanceWithContract(tokenSymbol: String, isFromToken: Boolean) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            return
        }

        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        if (tokenInfo == null) {
            return
        }

        if ("SCRT" == tokenSymbol) {
            // Native SCRT balance query - handle directly since it doesn't use contract queries
            // For now, set to 0 balance as SCRT balance queries need different handling
            if (isFromToken) {
                fromBalance = 0.0
                updateFromBalanceDisplay()
            } else {
                toBalance = 0.0
                updateToBalanceDisplay()
            }
        } else {
            // SNIP-20 token balance query using permit-based queries
            if (!hasPermitForToken(tokenSymbol)) {
                // No permit available - set balance to error state
                if (isFromToken) {
                    fromBalance = -1.0
                    updateFromBalanceDisplay()
                } else {
                    toBalance = -1.0
                    updateToBalanceDisplay()
                }
                return
            }

            // Execute query using lifecycleScope
            lifecycleScope.launch {
                try {
                    val result = SecretKClient.querySnipBalanceWithPermit(
                        requireContext(), // Use context instead of Fragment context
                        tokenSymbol,
                        currentWalletAddress
                    )

                    // Handle result on UI thread
                    handleSnipBalanceResult(tokenSymbol, isFromToken, result.toString())

                } catch (e: Exception) {
                    Log.e(TAG, "Token balance query failed for $tokenSymbol: ${e.message}", e)
                    if (isFromToken) {
                        fromBalance = -1.0
                        updateFromBalanceDisplay()
                    } else {
                        toBalance = -1.0
                        updateToBalanceDisplay()
                    }
                }
            }
        }
    }

    private fun handleSnipBalanceResult(tokenSymbol: String, isFromToken: Boolean, json: String) {
        try {
            if (TextUtils.isEmpty(json)) {
                if (isFromToken) {
                    fromBalance = -1.0
                    updateFromBalanceDisplay()
                } else {
                    toBalance = -1.0
                    updateToBalanceDisplay()
                }
                return
            }

            val root = JSONObject(json)
            val balance = root.optJSONObject("balance")
            balance?.let { bal ->
                val amount = bal.optString("amount", "0")
                val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                tokenInfo?.let { info ->
                    // Always process amount, even if it's "0" or empty (like wallet display does)
                    var formattedBalance = 0.0
                    if (!TextUtils.isEmpty(amount)) {
                        try {
                            formattedBalance = amount.toDouble() / 10.0.pow(info.decimals)
                        } catch (e: NumberFormatException) {
                        }
                    }
                    if (isFromToken) {
                        fromBalance = formattedBalance
                        updateFromBalanceDisplay()
                    } else {
                        toBalance = formattedBalance
                        updateToBalanceDisplay()
                    }
                } ?: run {
                    if (isFromToken) {
                        fromBalance = -1.0
                        updateFromBalanceDisplay()
                    } else {
                        toBalance = -1.0
                        updateToBalanceDisplay()
                    }
                }
            } ?: run {
                if (isFromToken) {
                    fromBalance = -1.0
                    updateFromBalanceDisplay()
                } else {
                    toBalance = -1.0
                    updateToBalanceDisplay()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SNIP balance query result", e)
            if (isFromToken) {
                fromBalance = -1.0
                updateFromBalanceDisplay()
            } else {
                toBalance = -1.0
                updateToBalanceDisplay()
            }
        }
    }

    private fun updateBalanceDisplay() {
        updateFromBalanceDisplay()
        updateToBalanceDisplay()
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {

                // Clear amounts immediately
                clearAmounts()

                // Add small delay before refreshing balances to allow blockchain state to settle
                Handler(Looper.getMainLooper()).postDelayed({
                    fetchBalances()
                }, 200) // 200ms delay
            }
        }
    }

    private fun registerBroadcastReceiver() {
        if (activity != null && transactionSuccessReceiver != null) {
            val filter = IntentFilter("network.erth.wallet.TRANSACTION_SUCCESS")
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register broadcast receiver", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister broadcast receiver
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }

    private fun executeSwapWithContract() {
        val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
        val toTokenSymbol = tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]
        val inputAmountStr = fromAmountInput?.text.toString()

        lifecycleScope.launch {
            try {
                val inputAmount = inputAmountStr.toDouble()
                val inputAmountMicro = (inputAmount * 1_000_000).toLong()

                // Handle different swap scenarios
                when {
                    // GAS -> sSCRT: Wrap SCRT to sSCRT
                    fromTokenSymbol == GAS_TOKEN && toTokenSymbol == "sSCRT" -> {
                        executeWrapScrt(inputAmountMicro)
                    }

                    // sSCRT -> GAS: Unwrap sSCRT to SCRT
                    fromTokenSymbol == "sSCRT" && toTokenSymbol == GAS_TOKEN -> {
                        executeUnwrapSscrt(inputAmountMicro)
                    }

                    // GAS -> Token: Wrap SCRT to sSCRT, then swap sSCRT to token
                    fromTokenSymbol == GAS_TOKEN -> {
                        executeGasToTokenSwap(inputAmountMicro, toTokenSymbol)
                    }

                    // Token -> GAS: Swap token to sSCRT, then unwrap sSCRT
                    toTokenSymbol == GAS_TOKEN -> {
                        executeTokenToGasSwap(inputAmount, fromTokenSymbol)
                    }

                    // Regular token swap
                    else -> {
                        executeRegularSwap(inputAmount, fromTokenSymbol, toTokenSymbol)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Swap failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun executeWrapScrt(amountMicro: Long) {
        val sscrtInfo = Tokens.getTokenInfo("sSCRT") ?: return

        // Wrap SCRT -> sSCRT using deposit message with sent_funds
        val depositMsg = JSONObject().apply {
            put("deposit", JSONObject())
        }

        val result = TransactionExecutor.executeContract(
            fragment = this@SwapTokensMainFragment,
            contractAddress = sscrtInfo.contract,
            message = depositMsg,
            codeHash = sscrtInfo.hash,
            sentFunds = listOf(io.eqoty.cosmwasm.std.types.Coin(amountMicro.toString(), "uscrt")),
            gasLimit = 150_000,
            contractLabel = "Wrap SCRT:"
        )

        result.onSuccess {
            clearAmounts()
            fetchBalances()
        }.onFailure { error ->
            if (error.message != "Transaction cancelled by user" &&
                error.message != "Authentication failed") {
                Toast.makeText(context, "Wrap failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun executeUnwrapSscrt(amountMicro: Long) {
        val sscrtInfo = Tokens.getTokenInfo("sSCRT") ?: return

        // Unwrap sSCRT -> SCRT using redeem message
        val redeemMsg = JSONObject().apply {
            put("redeem", JSONObject().apply {
                put("amount", amountMicro.toString())
            })
        }

        val result = TransactionExecutor.executeContract(
            fragment = this@SwapTokensMainFragment,
            contractAddress = sscrtInfo.contract,
            message = redeemMsg,
            codeHash = sscrtInfo.hash,
            gasLimit = 150_000,
            contractLabel = "Unwrap sSCRT:"
        )

        result.onSuccess {
            clearAmounts()
            fetchBalances()
        }.onFailure { error ->
            if (error.message != "Transaction cancelled by user" &&
                error.message != "Authentication failed") {
                Toast.makeText(context, "Unwrap failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun executeGasToTokenSwap(inputAmountMicro: Long, toTokenSymbol: String) {
        val sscrtInfo = Tokens.getTokenInfo("sSCRT") ?: return
        val toTokenInfo = Tokens.getTokenInfo(toTokenSymbol) ?: return

        // Build swap message for the second step
        val swapMessage = JSONObject().apply {
            put("swap", JSONObject().apply {
                put("output_token", toTokenInfo.contract)
                put("min_received", calculateMinAmountOut(inputAmountMicro / 1_000_000.0))
            })
        }
        val encodedSwapMsg = android.util.Base64.encodeToString(
            swapMessage.toString().toByteArray(),
            android.util.Base64.NO_WRAP
        )

        // Message 1: Wrap SCRT to sSCRT (deposit with sent_funds)
        val wrapMsg = SecretKClient.ContractMessage(
            contractAddress = sscrtInfo.contract,
            handleMsg = "{\"deposit\":{}}",
            sentFunds = listOf(io.eqoty.cosmwasm.std.types.Coin(inputAmountMicro.toString(), "uscrt")),
            codeHash = sscrtInfo.hash
        )

        // Message 2: Swap sSCRT to target token
        val sendMsg = JSONObject().apply {
            put("send", JSONObject().apply {
                put("recipient", Constants.EXCHANGE_CONTRACT)
                put("recipient_code_hash", Constants.EXCHANGE_HASH)
                put("amount", inputAmountMicro.toString())
                put("msg", encodedSwapMsg)
            })
        }

        val swapMsgObj = SecretKClient.ContractMessage(
            contractAddress = sscrtInfo.contract,
            handleMsg = sendMsg.toString(),
            codeHash = sscrtInfo.hash
        )

        val result = TransactionExecutor.executeMultipleContracts(
            fragment = this@SwapTokensMainFragment,
            messages = listOf(wrapMsg, swapMsgObj),
            gasLimit = 500_000,
            contractLabel = "Swap GAS:"
        )

        result.onSuccess {
            clearAmounts()
            fetchBalances()
        }.onFailure { error ->
            if (error.message != "Transaction cancelled by user" &&
                error.message != "Authentication failed") {
                Toast.makeText(context, "Swap failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun executeTokenToGasSwap(inputAmount: Double, fromTokenSymbol: String) {
        val fromTokenInfo = Tokens.getTokenInfo(fromTokenSymbol) ?: return
        val sscrtInfo = Tokens.getTokenInfo("sSCRT") ?: return

        val inputAmountMicro = (inputAmount * 10.0.pow(fromTokenInfo.decimals)).toLong()
        val minSscrtMicro = calculateMinAmountOut(inputAmount).toLong()

        // Build swap message to get sSCRT
        val swapMessage = JSONObject().apply {
            put("swap", JSONObject().apply {
                put("output_token", sscrtInfo.contract)
                put("min_received", minSscrtMicro.toString())
            })
        }
        val encodedSwapMsg = android.util.Base64.encodeToString(
            swapMessage.toString().toByteArray(),
            android.util.Base64.NO_WRAP
        )

        // Message 1: Swap token to sSCRT
        val sendMsg = JSONObject().apply {
            put("send", JSONObject().apply {
                put("recipient", Constants.EXCHANGE_CONTRACT)
                put("recipient_code_hash", Constants.EXCHANGE_HASH)
                put("amount", inputAmountMicro.toString())
                put("msg", encodedSwapMsg)
            })
        }

        val swapMsgObj = SecretKClient.ContractMessage(
            contractAddress = fromTokenInfo.contract,
            handleMsg = sendMsg.toString(),
            codeHash = fromTokenInfo.hash
        )

        // Message 2: Unwrap sSCRT to SCRT (using min_received as the amount)
        val redeemMsg = JSONObject().apply {
            put("redeem", JSONObject().apply {
                put("amount", minSscrtMicro.toString())
            })
        }

        val redeemMsgObj = SecretKClient.ContractMessage(
            contractAddress = sscrtInfo.contract,
            handleMsg = redeemMsg.toString(),
            codeHash = sscrtInfo.hash
        )

        val result = TransactionExecutor.executeMultipleContracts(
            fragment = this@SwapTokensMainFragment,
            messages = listOf(swapMsgObj, redeemMsgObj),
            gasLimit = 500_000,
            contractLabel = "Swap to GAS:"
        )

        result.onSuccess {
            clearAmounts()
            fetchBalances()
        }.onFailure { error ->
            if (error.message != "Transaction cancelled by user" &&
                error.message != "Authentication failed") {
                Toast.makeText(context, "Swap failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun executeRegularSwap(inputAmount: Double, fromTokenSymbol: String, toTokenSymbol: String) {
        val fromTokenInfo = Tokens.getTokenInfo(fromTokenSymbol) ?: return
        val toTokenInfo = Tokens.getTokenInfo(toTokenSymbol) ?: return

        // Build the message that will be base64 encoded (like snipmsg in React app)
        val swapMessage = JSONObject().apply {
            put("swap", JSONObject().apply {
                put("output_token", toTokenInfo.contract)
                put("min_received", calculateMinAmountOut(inputAmount))
            })
        }

        val inputAmountMicro = (inputAmount * 10.0.pow(fromTokenInfo.decimals)).toLong()

        // Use TransactionExecutor to send token to exchange contract
        val result = TransactionExecutor.sendSnip20Token(
            fragment = this@SwapTokensMainFragment,
            tokenContract = fromTokenInfo.contract,
            tokenHash = fromTokenInfo.hash,
            recipient = Constants.EXCHANGE_CONTRACT,
            recipientHash = Constants.EXCHANGE_HASH,
            amount = inputAmountMicro.toString(),
            message = swapMessage,
            gasLimit = 300_000
        )

        result.onSuccess {
            clearAmounts()
            fetchBalances()
        }.onFailure { error ->
            if (error.message != "Transaction cancelled by user" &&
                error.message != "Authentication failed") {
                Toast.makeText(context, "Swap failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun calculateMinAmountOut(inputAmount: Double): String {
        // Calculate minimum output based on slippage tolerance
        return try {
            val slippage = (slippageInput?.text.toString().toDouble() ?: 1.0) / 100.0
            val expectedOutput = toAmountInput?.text.toString().toDouble() ?: 0.0
            val minOutput = expectedOutput * (1.0 - slippage)

            val toTokenSymbol = tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]
            // For GAS, use 6 decimals (same as SCRT)
            val decimals = if (toTokenSymbol == GAS_TOKEN) 6 else Tokens.getTokenInfo(toTokenSymbol)?.decimals ?: 6

            (minOutput * 10.0.pow(decimals)).toLong().toString()
        } catch (e: Exception) {
            "0"
        }
    }

    private fun hasPermitForToken(tokenSymbol: String): Boolean {
        // GAS (native SCRT) doesn't need permits
        if (tokenSymbol == GAS_TOKEN) return true
        val tokenInfo = Tokens.getTokenInfo(tokenSymbol) ?: return false
        return permitManager?.hasPermit(currentWalletAddress, tokenInfo.contract) == true
    }
}