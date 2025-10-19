package network.erth.wallet.ui.pages.managelp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.TransactionExecutor
import network.erth.wallet.wallet.services.SecretKClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AddLiquidityFragment : Fragment() {

    companion object {
        private const val TAG = "AddLiquidityFragment"

        @JvmStatic
        fun newInstance(tokenKey: String): AddLiquidityFragment {
            val fragment = AddLiquidityFragment()
            val args = Bundle()
            args.putString("token_key", tokenKey)
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var tokenAmountInput: EditText
    private lateinit var erthAmountInput: EditText
    private lateinit var tokenBalanceText: TextView
    private lateinit var erthBalanceText: TextView
    private lateinit var tokenLabel: TextView
    private lateinit var tokenInputLogo: ImageView
    private lateinit var erthInputLogo: ImageView
    private lateinit var tokenMaxButton: Button
    private lateinit var erthMaxButton: Button
    private lateinit var addLiquidityButton: Button

    private var tokenKey: String? = null
    private var tokenBalance = 0.0
    private var erthBalance = 0.0
    private var currentWalletAddress = ""
    private var permitManager: PermitManager? = null
    private var executorService: ExecutorService? = null

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    // Activity Result Launcher for transaction activity

    // Pool reserves for ratio calculation
    private var erthReserve = 0.0
    private var tokenReserve = 0.0
    private var isUpdatingRatio = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            tokenKey = it.getString("token_key")
        }

        // Use SecureWalletManager for secure operations

        // Initialize permit manager
        permitManager = PermitManager.getInstance(requireContext())

        executorService = Executors.newCachedThreadPool()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_liquidity_add, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupListeners()
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        loadCurrentWalletAddress()
        updateTokenInfo()
        loadTokenBalances()
        loadPoolReserves()
    }

    private fun initializeViews(view: View) {
        tokenAmountInput = view.findViewById(R.id.token_amount_input)
        erthAmountInput = view.findViewById(R.id.erth_amount_input)
        tokenBalanceText = view.findViewById(R.id.token_balance_text)
        erthBalanceText = view.findViewById(R.id.erth_balance_text)
        tokenLabel = view.findViewById(R.id.token_label)
        tokenInputLogo = view.findViewById(R.id.token_input_logo)
        erthInputLogo = view.findViewById(R.id.erth_input_logo)
        tokenMaxButton = view.findViewById(R.id.token_max_button)
        erthMaxButton = view.findViewById(R.id.erth_max_button)
        addLiquidityButton = view.findViewById(R.id.add_liquidity_button)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                // Clear input fields immediately
                tokenAmountInput.setText("")
                erthAmountInput.setText("")

                // Add small delay before refreshing balances to allow blockchain state to settle
                Handler(Looper.getMainLooper()).postDelayed({
                    loadTokenBalances()
                    loadPoolReserves()
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

    private fun setupListeners() {
        tokenMaxButton.setOnClickListener {
            if (tokenBalance > 0) {
                tokenAmountInput.setText(tokenBalance.toString())
                calculateErthFromToken(tokenBalance.toString())
            }
        }

        erthMaxButton.setOnClickListener {
            if (erthBalance > 0) {
                erthAmountInput.setText(erthBalance.toString())
                calculateTokenFromErth(erthBalance.toString())
            }
        }

        // Add text change listeners for ratio calculation
        tokenAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                if (!isUpdatingRatio) {
                    calculateErthFromToken(s.toString())
                }
            }
        })

        erthAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                if (!isUpdatingRatio) {
                    calculateTokenFromErth(s.toString())
                }
            }
        })

        addLiquidityButton.setOnClickListener {
            handleAddLiquidity()
        }
    }

    private fun updateTokenInfo() {
        tokenKey?.let { token ->
            tokenLabel.text = token
            tokenAmountInput.hint = "Amount of $token"
            loadTokenLogo(tokenInputLogo, token)
            loadTokenLogo(erthInputLogo, "ERTH")
        }
    }

    private fun loadTokenLogo(imageView: ImageView, tokenSymbol: String) {
        try {
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
            if (tokenInfo != null) {
                val inputStream = context!!.assets.open(tokenInfo.logo)
                val drawable = Drawable.createFromStream(inputStream, null)
                imageView.setImageDrawable(drawable)
                inputStream.close()
            } else {
                imageView.setImageResource(R.drawable.ic_wallet)
            }
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_wallet)
        }
    }

    private fun loadCurrentWalletAddress() {
        try {
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
            if (currentWalletAddress.isNotEmpty()) {
            } else {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            currentWalletAddress = ""
        }
    }

    private fun loadTokenBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            tokenBalanceText.text = "Balance: Connect wallet"
            erthBalanceText.text = "Balance: Connect wallet"
            return
        }

        // Load token balance
        tokenKey?.let { fetchTokenBalance(it, true) }

        // Load ERTH balance
        fetchTokenBalance("ERTH", false)
    }

    private fun fetchTokenBalance(tokenSymbol: String, isToken: Boolean) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            return
        }

        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        if (tokenInfo == null) {
            return
        }

        if ("SCRT" == tokenSymbol) {
            // Native SCRT - set to 0 for now
            if (isToken) {
                tokenBalance = 0.0
                updateTokenBalanceDisplay()
            } else {
                erthBalance = 0.0
                updateErthBalanceDisplay()
            }
        } else {
            // SNIP-20 token balance query using permits
            if (!hasPermitForToken(tokenSymbol)) {
                // No permit available
                if (isToken) {
                    tokenBalance = -1.0
                    updateTokenBalanceDisplay()
                } else {
                    erthBalance = -1.0
                    updateErthBalanceDisplay()
                }
                return
            }

            // Execute query using lifecycleScope
            lifecycleScope.launch {
                try {
                    val result = SecretKClient.querySnipBalanceWithPermit(
                        requireContext(),
                        tokenSymbol,
                        currentWalletAddress
                    )

                    // Handle result on UI thread
                    handleBalanceResult(tokenSymbol, isToken, result.toString())

                } catch (e: Exception) {
                    Log.e(TAG, "Token balance query failed for $tokenSymbol: ${e.message}", e)
                    if (isToken) {
                        tokenBalance = -1.0
                        updateTokenBalanceDisplay()
                    } else {
                        erthBalance = -1.0
                        updateErthBalanceDisplay()
                    }
                }
            }
        }
    }

    private fun handleBalanceResult(tokenSymbol: String, isToken: Boolean, json: String) {
        try {
            if (TextUtils.isEmpty(json)) {
                if (isToken) {
                    tokenBalance = -1.0
                    updateTokenBalanceDisplay()
                } else {
                    erthBalance = -1.0
                    updateErthBalanceDisplay()
                }
                return
            }

            val root = JSONObject(json)
            val balance = root.optJSONObject("balance")
            if (balance != null) {
                val amount = balance.optString("amount", "0")
                val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                if (tokenInfo != null) {
                    var formattedBalance = 0.0
                    if (!TextUtils.isEmpty(amount)) {
                        try {
                            formattedBalance = amount.toDouble() / Math.pow(10.0, tokenInfo.decimals.toDouble())
                        } catch (e: NumberFormatException) {
                        }
                    }
                    if (isToken) {
                        tokenBalance = formattedBalance
                        updateTokenBalanceDisplay()
                    } else {
                        erthBalance = formattedBalance
                        updateErthBalanceDisplay()
                    }
                }
            } else {
                if (isToken) {
                    tokenBalance = -1.0
                    updateTokenBalanceDisplay()
                } else {
                    erthBalance = -1.0
                    updateErthBalanceDisplay()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse balance query result", e)
            if (isToken) {
                tokenBalance = -1.0
                updateTokenBalanceDisplay()
            } else {
                erthBalance = -1.0
                updateErthBalanceDisplay()
            }
        }
    }

    private fun updateTokenBalanceDisplay() {
        val df = DecimalFormat("#.##")

        if (tokenBalance >= 0) {
            tokenBalanceText.text = "Balance: ${df.format(tokenBalance)}"
            tokenMaxButton.visibility = View.VISIBLE
        } else {
            tokenBalanceText.text = "Balance: Create permit"
            tokenMaxButton.visibility = View.GONE
        }
    }

    private fun updateErthBalanceDisplay() {
        val df = DecimalFormat("#.##")

        if (erthBalance >= 0) {
            erthBalanceText.text = "Balance: ${df.format(erthBalance)}"
            erthMaxButton.visibility = View.VISIBLE
        } else {
            erthBalanceText.text = "Balance: Create permit"
            erthMaxButton.visibility = View.GONE
        }
    }

    private fun hasPermitForToken(tokenSymbol: String): Boolean {
        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        if (tokenInfo == null) {
            return false
        }
        return permitManager?.hasPermit(currentWalletAddress, tokenInfo.contract) == true
    }

    private fun loadPoolReserves() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            return
        }

        lifecycleScope.launch {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) return@launch

                // Query pool info
                val queryJson = "{\"query_user_info\": {\"pools\": [\"$tokenContract\"], \"user\": \"$currentWalletAddress\"}}"

                val responseString = SecretKClient.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    queryJson,
                    Constants.EXCHANGE_HASH
                )

                // Try to parse as JSONArray first, then JSONObject
                val result = try {
                    JSONArray(responseString)
                } catch (e: Exception) {
                    try {
                        JSONObject(responseString)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to parse response as JSON", e2)
                        return@launch
                    }
                }

                parsePoolReserves(result)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading pool reserves", e)
            }
        }
    }

    private fun parsePoolReserves(result: Any) {
        try {
            // Handle both JSONArray and JSONObject responses
            val poolsData = when (result) {
                is JSONArray -> result
                is JSONObject -> {
                    // Handle the SecretQueryService error case where data is in the error message
                    if (result.has("error") && result.has("decryption_error")) {
                        val decryptionError = result.getString("decryption_error")

                        // Look for "base64=" in the error message and extract the array
                        val base64Marker = "base64=Value "
                        val base64Index = decryptionError.indexOf(base64Marker)
                        if (base64Index != -1) {
                            val startIndex = base64Index + base64Marker.length
                            val endIndex = decryptionError.indexOf(" of type org.json.JSONArray", startIndex)
                            if (endIndex != -1) {
                                val jsonArrayString = decryptionError.substring(startIndex, endIndex)

                                try {
                                    JSONArray(jsonArrayString)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing extracted JSON array", e)
                                    return
                                }
                            } else {
                                return
                            }
                        } else {
                            return
                        }
                    } else if (result.has("data")) {
                        result.getJSONArray("data")
                    } else {
                        return
                    }
                }
                else -> return
            }

            if (poolsData.length() > 0) {
                val poolInfo = poolsData.getJSONObject(0)
                extractReserves(poolInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing pool reserves", e)
        }
    }

    private fun extractReserves(poolInfo: JSONObject) {
        try {
            val poolState = poolInfo.getJSONObject("pool_info").getJSONObject("state")

            val erthReserveRaw = poolState.optLong("erth_reserve", 0)
            val tokenReserveRaw = poolState.optLong("token_b_reserve", 0)

            // Convert from microunits to regular units (divide by 10^6)
            erthReserve = erthReserveRaw / 1000000.0
            tokenReserve = tokenReserveRaw / 1000000.0


        } catch (e: Exception) {
            Log.e(TAG, "Error extracting reserves from pool info", e)
        }
    }

    private fun getTokenContractAddress(symbol: String): String? {
        val tokenInfo = Tokens.getTokenInfo(symbol)
        return tokenInfo?.contract
    }

    private fun calculateErthFromToken(tokenAmountStr: String) {
        if (TextUtils.isEmpty(tokenAmountStr) || erthReserve <= 0 || tokenReserve <= 0) {
            return
        }

        try {
            val tokenAmount = tokenAmountStr.toDouble()
            if (tokenAmount > 0) {
                val erthAmount = (tokenAmount * erthReserve) / tokenReserve

                isUpdatingRatio = true
                erthAmountInput.setText(String.format("%.6f", erthAmount))
                isUpdatingRatio = false
            } else {
                isUpdatingRatio = true
                erthAmountInput.setText("")
                isUpdatingRatio = false
            }
        } catch (e: NumberFormatException) {
        }
    }

    private fun calculateTokenFromErth(erthAmountStr: String) {
        if (TextUtils.isEmpty(erthAmountStr) || erthReserve <= 0 || tokenReserve <= 0) {
            return
        }

        try {
            val erthAmount = erthAmountStr.toDouble()
            if (erthAmount > 0) {
                val tokenAmount = (erthAmount * tokenReserve) / erthReserve

                isUpdatingRatio = true
                tokenAmountInput.setText(String.format("%.6f", tokenAmount))
                isUpdatingRatio = false
            } else {
                isUpdatingRatio = true
                tokenAmountInput.setText("")
                isUpdatingRatio = false
            }
        } catch (e: NumberFormatException) {
        }
    }

    /**
     * Handle add liquidity button click
     * Based on the React provideLiquidity function from your web app
     */
    private fun handleAddLiquidity() {
        val tokenAmountStr = tokenAmountInput.text.toString().trim()
        val erthAmountStr = erthAmountInput.text.toString().trim()

        // Validate inputs
        if (TextUtils.isEmpty(tokenAmountStr) || TextUtils.isEmpty(erthAmountStr)) {
            Toast.makeText(context, "Please enter both token amounts", Toast.LENGTH_SHORT).show()
            return
        }

        if (TextUtils.isEmpty(currentWalletAddress)) {
            Toast.makeText(context, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val tokenAmount = tokenAmountStr.toDouble()
            val erthAmount = erthAmountStr.toDouble()

            if (tokenAmount <= 0 || erthAmount <= 0) {
                Toast.makeText(context, "Amounts must be greater than zero", Toast.LENGTH_SHORT).show()
                return
            }

            // Check balances
            if (tokenBalance >= 0 && tokenAmount > tokenBalance) {
                Toast.makeText(context, "Insufficient $tokenKey balance", Toast.LENGTH_SHORT).show()
                return
            }

            if (erthBalance >= 0 && erthAmount > erthBalance) {
                Toast.makeText(context, "Insufficient ERTH balance", Toast.LENGTH_SHORT).show()
                return
            }

            // Execute add liquidity transaction
            executeAddLiquidity(tokenAmount, erthAmount)

        } catch (e: NumberFormatException) {
            Toast.makeText(context, "Please enter valid numbers", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Execute the add liquidity transaction using MultiMessageExecuteActivity
     * This matches the React app's provideLiquidity function with multi-message transaction
     */
    private fun executeAddLiquidity(tokenAmount: Double, erthAmount: Double) {
        try {

            val tokenInfo = Tokens.getTokenInfo(tokenKey!!)
            val erthInfo = Tokens.getTokenInfo("ERTH")

            if (tokenInfo == null || erthInfo == null) {
                Toast.makeText(context, "Token information not found", Toast.LENGTH_SHORT).show()
                return
            }

            // Convert to microunits (multiply by 10^6)
            val tokenMicroAmount = Math.round(tokenAmount * Math.pow(10.0, tokenInfo.decimals.toDouble()))
            val erthMicroAmount = Math.round(erthAmount * Math.pow(10.0, erthInfo.decimals.toDouble()))

            val walletAddress = getCurrentWalletAddress()
            if (TextUtils.isEmpty(walletAddress)) {
                Toast.makeText(context, "No wallet address available", Toast.LENGTH_SHORT).show()
                return
            }

            // Build contract messages directly
            val contractMessages = listOf(
                // 1. ERTH allowance message
                SecretKClient.ContractMessage(
                    contractAddress = erthInfo.contract,
                    handleMsg = JSONObject().apply {
                        put("increase_allowance", JSONObject().apply {
                            put("spender", Constants.EXCHANGE_CONTRACT)
                            put("amount", erthMicroAmount.toString())
                        })
                    }.toString(),
                    codeHash = erthInfo.hash
                ),
                // 2. Token allowance message
                SecretKClient.ContractMessage(
                    contractAddress = tokenInfo.contract,
                    handleMsg = JSONObject().apply {
                        put("increase_allowance", JSONObject().apply {
                            put("spender", Constants.EXCHANGE_CONTRACT)
                            put("amount", tokenMicroAmount.toString())
                        })
                    }.toString(),
                    codeHash = tokenInfo.hash
                ),
                // 3. Add liquidity message
                SecretKClient.ContractMessage(
                    contractAddress = Constants.EXCHANGE_CONTRACT,
                    handleMsg = JSONObject().apply {
                        put("add_liquidity", JSONObject().apply {
                            put("amount_erth", erthMicroAmount.toString())
                            put("amount_b", tokenMicroAmount.toString())
                            put("pool", tokenInfo.contract)
                        })
                    }.toString(),
                    codeHash = Constants.EXCHANGE_HASH
                )
            )

            // Execute using TransactionExecutor
            lifecycleScope.launch {
                val result = TransactionExecutor.executeMultipleContracts(
                    fragment = this@AddLiquidityFragment,
                    messages = contractMessages,
                    memo = "Add liquidity: $tokenAmount $tokenKey + $erthAmount ERTH"
                )

                result.onSuccess {
                    // Clear input fields
                    tokenAmountInput.setText("")
                    erthAmountInput.setText("")
                    // Refresh data
                    loadTokenBalances()
                    loadPoolReserves()
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Log.e(TAG, "Add liquidity transaction failed: ${error.message}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute add liquidity transaction", e)
            Toast.makeText(context, "Failed to create transaction: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Get current wallet address using SecureWalletManager
     */
    private fun getCurrentWalletAddress(): String {
        return try {
            SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wallet address", e)
            ""
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

        if (executorService != null && !executorService!!.isShutdown) {
            executorService!!.shutdown()
        }
    }
}