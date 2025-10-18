package network.erth.wallet.ui.pages.swap

import android.app.Activity
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
import network.erth.wallet.bridge.activities.TransactionActivity
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.bridge.models.Permit
import network.erth.wallet.wallet.services.SessionManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecretKClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
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
        private const val REQ_SIMULATE_SWAP = 3001
        private const val REQ_EXECUTE_SWAP = 3002
        private const val REQ_BALANCE_QUERY = 3003
        private const val REQUEST_SWAP_SIMULATION = 3004
        private const val REQUEST_TOKEN_BALANCE = 3005
        private const val REQUEST_SWAP_EXECUTION = 3006
        private const val REQ_SNIP_EXECUTE = 3008
    }

    // UI Components
    private var fromTokenSpinner: Spinner? = null
    private var toTokenSpinner: Spinner? = null
    private var fromAmountInput: EditText? = null
    private var toAmountInput: EditText? = null
    private var slippageInput: EditText? = null
    private var fromBalanceText: TextView? = null
    private var toBalanceText: TextView? = null
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

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null
    private var permitManager: PermitManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize token list - use getAllTokens() instead of ALL_TOKENS for full registry
        tokenSymbols = ArrayList(Tokens.getAllTokens().keys)
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
    }

    private fun initializeViews(view: View) {
        fromTokenSpinner = view.findViewById(R.id.from_token_spinner)
        toTokenSpinner = view.findViewById(R.id.to_token_spinner)
        fromAmountInput = view.findViewById(R.id.from_amount_input)
        toAmountInput = view.findViewById(R.id.to_amount_input)
        slippageInput = view.findViewById(R.id.slippage_input)

        fromBalanceText = view.findViewById(R.id.from_balance_text)
        toBalanceText = view.findViewById(R.id.to_balance_text)
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
                val toTokenInfo = Tokens.getTokenInfo(toToken)
                val decimals = toTokenInfo?.decimals ?: 6
                val minDf = DecimalFormat("#.${"0".repeat(decimals)}")
                minReceivedText?.text = "${minDf.format(minReceived)} $toToken"

            } catch (e: NumberFormatException) {
                Log.e(TAG, "Error updating details display", e)
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

        fetchTokenBalanceWithContract(fromTokenSymbol, true)
        fetchTokenBalanceWithContract(toTokenSymbol, false)
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
        Thread {
            try {
                // Create permit with correct parameters
                val contractAddresses = listOf(tokenInfo.contract)
                val permissions = listOf("balance", "history", "allowance")
                val permitName = "EarthWallet"

                val permit = permitManager?.createPermit(requireContext(), currentWalletAddress, contractAddresses, permitName, permissions)

                // Update UI on main thread
                activity?.runOnUiThread {
                    if (permit != null) {
                        // Refresh balances to update the UI
                        fetchBalances()
                    } else {
                        Toast.makeText(context, "Failed to create permit", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating permit for $tokenSymbol", e)
                activity?.runOnUiThread {
                    Toast.makeText(context, "Error creating permit: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_SNIP_EXECUTE) {
            if (resultCode == Activity.RESULT_OK) {
                clearAmounts()
                fetchBalances() // Refresh balances
            } else {
                val error = data?.getStringExtra(TransactionActivity.EXTRA_ERROR) ?: "Unknown error"
                Toast.makeText(context, "Swap failed: $error", Toast.LENGTH_LONG).show()
            }
        } else if (resultCode == Activity.RESULT_OK && data != null) {
            val json: String?

            if (requestCode == REQ_EXECUTE_SWAP || requestCode == REQUEST_SWAP_EXECUTION) {
                // Legacy handling for old flow - can be removed later
                json = data.getStringExtra(TransactionActivity.EXTRA_RESULT_JSON)
                handleSwapExecutionResult(json)
            } else {
                // Use generic result key for other requests
                json = data.getStringExtra("EXTRA_RESULT_JSON")

                when (requestCode) {
                    REQ_BALANCE_QUERY -> handleBalanceQueryResult(data, json)
                    REQ_SIMULATE_SWAP, REQUEST_SWAP_SIMULATION -> handleSwapSimulationResult(json)
                    REQUEST_TOKEN_BALANCE -> handleTokenBalanceResult(data, json)
                }
            }
        }
    }

    private fun handleBalanceQueryResult(data: Intent, json: String?) {
        try {
            val isFromToken = data.getBooleanExtra("is_from_token", false)
            val tokenSymbol = data.getStringExtra("token_symbol")

            val root = JSONObject(json ?: "")
            val success = root.optBoolean("success", false)

            if (success) {
                val result = root.optJSONObject("result")
                result?.let {
                    val balance = it.optJSONObject("balance")
                    balance?.let { bal ->
                        val amount = bal.optString("amount", "0")
                        val token = Tokens.getTokenInfo(tokenSymbol!!)
                        token?.let { tokenInfo ->
                            val balanceValue = amount.toDouble() / 10.0.pow(tokenInfo.decimals)

                            if (isFromToken) {
                                fromBalance = balanceValue
                                updateFromBalanceDisplay()
                            } else {
                                toBalance = balanceValue
                                updateToBalanceDisplay()
                            }
                        }
                    }
                }
            } else {
                // Balance query failed
                if (isFromToken) {
                    fromBalance = -1.0
                    updateFromBalanceDisplay()
                } else {
                    toBalance = -1.0
                    updateToBalanceDisplay()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse balance query result", e)
        }
    }

    private fun handleSwapSimulationResult(json: String?) {
        try {
            val root = JSONObject(json ?: "")
            val success = root.optBoolean("success", false)

            if (success) {
                val result = root.optJSONObject("result")
                result?.let {
                    // Parse output_amount to match React web app response format
                    val outputAmount = it.optString("output_amount", "0")
                    val toTokenSymbol = tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]
                    val toTokenInfo = Tokens.getTokenInfo(toTokenSymbol)
                    toTokenInfo?.let { tokenInfo ->
                        val formattedOutput = outputAmount.toDouble() / 10.0.pow(tokenInfo.decimals)
                        val df = DecimalFormat("#.######")

                        // Update output amount without requesting focus to avoid dismissing keyboard
                        toAmountInput?.setText(df.format(formattedOutput))
                        updateDetailsDisplay()
                    }
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

    private fun handleSwapExecutionResult(json: String?) {

        try {
            val root = JSONObject(json ?: "")

            // Check for success based on transaction code (0 = success)
            var success = false
            if (root.has("tx_response")) {
                val txResponse = root.getJSONObject("tx_response")
                val code = txResponse.optInt("code", -1)
                success = (code == 0)

                if (success) {
                    val txHash = txResponse.optString("txhash", "")
                }
            } else {
                // Fallback to old success field
                success = root.optBoolean("success", false)
            }

            if (success) {
                clearAmounts()
                fetchBalances() // Refresh balances
            } else {
                Toast.makeText(context, "Swap failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse swap execution result", e)
            Toast.makeText(context, "Swap failed", Toast.LENGTH_SHORT).show()
        }
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


        // Get token info for from token
        val fromTokenInfo = Tokens.getTokenInfo(fromTokenSymbol)
        if (fromTokenInfo == null) {
            Log.e(TAG, "From token not supported: $fromTokenSymbol")
            Toast.makeText(context, "Token not supported", Toast.LENGTH_SHORT).show()
            isSimulatingSwap = false
            updateSwapButton()
            return
        }

        // Build swap simulation query to match React web app format
        val toTokenInfo = Tokens.getTokenInfo(toTokenSymbol)
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

                // Format result to match expected format
                val response = JSONObject()
                response.put("success", true)
                response.put("result", result)

                // Handle result
                handleSwapSimulationResult(response.toString())

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
        val inputAmount = fromAmountInput?.text.toString().toDouble()

        val fromTokenInfo = Tokens.getTokenInfo(fromTokenSymbol)
        if (fromTokenInfo == null) {
            Toast.makeText(context, "Token not supported", Toast.LENGTH_SHORT).show()
            return
        }

        // Build swap execution message to match React web app format
        // Use SNIP execution with "send" message like the React app's snip() function
        val toTokenInfo = Tokens.getTokenInfo(toTokenSymbol)
        if (toTokenInfo == null) {
            Toast.makeText(context, "To token not supported", Toast.LENGTH_SHORT).show()
            return
        }

        // Build the message that will be base64 encoded (like snipmsg in React app)
        val swapMessage = String.format(
            "{\"swap\": {\"output_token\": \"%s\", \"min_received\": \"%s\"}}",
            toTokenInfo.contract,
            calculateMinAmountOut(inputAmount)
        )

        val inputAmountMicro = (inputAmount * 10.0.pow(fromTokenInfo.decimals)).toLong()


        val intent = Intent(context, TransactionActivity::class.java)
        intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SNIP_EXECUTE)
        intent.putExtra(TransactionActivity.EXTRA_TOKEN_CONTRACT, fromTokenInfo.contract)
        intent.putExtra(TransactionActivity.EXTRA_TOKEN_HASH, fromTokenInfo.hash)
        intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, Constants.EXCHANGE_CONTRACT)
        intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_HASH, Constants.EXCHANGE_HASH)
        intent.putExtra(TransactionActivity.EXTRA_AMOUNT, inputAmountMicro.toString())
        intent.putExtra(TransactionActivity.EXTRA_MESSAGE_JSON, swapMessage)

        startActivityForResult(intent, REQ_SNIP_EXECUTE)
    }

    private fun calculateMinAmountOut(inputAmount: Double): String {
        // Calculate minimum output based on slippage tolerance
        return try {
            val slippage = (slippageInput?.text.toString().toDouble() ?: 1.0) / 100.0
            val expectedOutput = toAmountInput?.text.toString().toDouble() ?: 0.0
            val minOutput = expectedOutput * (1.0 - slippage)

            val toTokenSymbol = tokenSymbols[toTokenSpinner?.selectedItemPosition ?: 0]
            val toTokenInfo = Tokens.getTokenInfo(toTokenSymbol)
            val decimals = toTokenInfo?.decimals ?: 6

            (minOutput * 10.0.pow(decimals)).toLong().toString()
        } catch (e: Exception) {
            "0"
        }
    }

    private fun hasPermitForToken(tokenSymbol: String): Boolean {
        val tokenInfo = Tokens.getTokenInfo(tokenSymbol) ?: return false
        return permitManager?.hasPermit(currentWalletAddress, tokenInfo.contract) == true
    }

    private fun handleTokenBalanceResult(data: Intent, json: String?) {
        try {
            val isFromToken = data.getBooleanExtra("isFromToken", false)
            val tokenSymbol = data.getStringExtra("tokenSymbol")

            val root = JSONObject(json ?: "")
            val success = root.optBoolean("success", false)

            if (success) {
                val result = root.optJSONObject("result")
                result?.let {
                    var balanceStr = "0"
                    if ("SCRT" == tokenSymbol) {
                        // Native SCRT balance
                        val balances = it.optJSONArray("balances")
                        if (balances != null && balances.length() > 0) {
                            val balance = balances.getJSONObject(0)
                            balanceStr = balance.optString("amount", "0")
                        }
                    } else {
                        // SNIP-20 token balance
                        val balance = it.optJSONObject("balance")
                        balance?.let { bal ->
                            balanceStr = bal.optString("amount", "0")
                        }
                    }

                    val tokenInfo = Tokens.getTokenInfo(tokenSymbol!!)
                    tokenInfo?.let { info ->
                        val balanceValue = balanceStr.toDouble() / 10.0.pow(info.decimals)

                        if (isFromToken) {
                            fromBalance = balanceValue
                        } else {
                            toBalance = balanceValue
                        }
                        updateBalanceDisplay()
                    }
                }
            } else {
                // Balance query failed - set to -1 to show "Get Viewing Key" button
                if (isFromToken) {
                    fromBalance = -1.0
                } else {
                    toBalance = -1.0
                }
                updateBalanceDisplay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse token balance result", e)
            if (data.getBooleanExtra("isFromToken", false)) {
                fromBalance = -1.0
            } else {
                toBalance = -1.0
            }
            updateBalanceDisplay()
        }
    }
}