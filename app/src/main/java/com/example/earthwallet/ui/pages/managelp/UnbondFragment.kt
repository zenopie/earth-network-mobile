package network.erth.wallet.ui.pages.managelp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UnbondFragment : Fragment() {

    companion object {
        private const val TAG = "UnbondFragment"

        @JvmStatic
        fun newInstance(tokenKey: String): UnbondFragment {
            val fragment = UnbondFragment()
            val args = Bundle()
            args.putString("token_key", tokenKey)
            fragment.arguments = args
            return fragment
        }

        @JvmStatic
        fun newInstance(tokenKey: String, erthReserve: Long, tokenBReserve: Long, totalShares: Long): UnbondFragment {
            val fragment = UnbondFragment()
            val args = Bundle()
            args.putString("token_key", tokenKey)
            args.putLong("erth_reserve", erthReserve)
            args.putLong("token_b_reserve", tokenBReserve)
            args.putLong("total_shares", totalShares)
            fragment.arguments = args
            return fragment
        }
    }

    private var tokenKey: String? = null
    private var currentWalletAddress = ""
    private var executorService: ExecutorService? = null

    // Pool information for calculating estimated values
    private var erthReserveMicro = 0L
    private var tokenBReserveMicro = 0L
    private var totalSharesMicro = 0L

    private lateinit var completeUnbondButton: Button
    private lateinit var unbondingRequestsTitle: TextView
    private lateinit var unbondingRequestsContainer: LinearLayout
    private lateinit var noUnbondingMessage: TextView

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            tokenKey = args.getString("token_key")
            // Get pool information from arguments if provided
            erthReserveMicro = args.getLong("erth_reserve", 0)
            tokenBReserveMicro = args.getLong("token_b_reserve", 0)
            totalSharesMicro = args.getLong("total_shares", 0)
        }

        executorService = Executors.newCachedThreadPool()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_liquidity_unbond, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupListeners()
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        loadCurrentWalletAddress()
        // If pool info is available from arguments, go straight to unbonding requests
        if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
            executorService?.execute { loadUnbondingRequests() }
        } else {
            // Fall back to loading pool info first if not provided
            loadPoolInformationThenUnbondingRequests()
        }
    }

    private fun initializeViews(view: View) {
        completeUnbondButton = view.findViewById(R.id.complete_unbond_button)
        unbondingRequestsTitle = view.findViewById(R.id.unbonding_requests_title)
        unbondingRequestsContainer = view.findViewById(R.id.unbonding_requests_container)
        noUnbondingMessage = view.findViewById(R.id.no_unbonding_message)
    }

    private fun setupListeners() {
        completeUnbondButton.setOnClickListener { handleCompleteUnbond() }
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                // Start multiple refresh attempts to ensure UI updates during animation
                if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
                    executorService?.execute { loadUnbondingRequests() }
                } else {
                    loadPoolInformationThenUnbondingRequests()
                }

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
                        executorService?.execute { loadUnbondingRequests() }
                    } else {
                        loadPoolInformationThenUnbondingRequests()
                    }
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
                        executorService?.execute { loadUnbondingRequests() }
                    } else {
                        loadPoolInformationThenUnbondingRequests()
                    }
                }, 500) // 500ms delay
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

    private fun handleCompleteUnbond() {
        if (tokenKey == null) {
            Log.e(TAG, "Cannot complete unbond: missing token key")
            return
        }

        lifecycleScope.launch {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) {
                    Log.e(TAG, "Cannot complete unbond: token contract not found for $tokenKey")
                    return@launch
                }

                // Create claim unbond liquidity message: { claim_unbond_liquidity: { pool: "contract_address" } }
                val claimMsg = JSONObject()
                val claimUnbondLiquidity = JSONObject()
                claimUnbondLiquidity.put("pool", tokenContract)
                claimMsg.put("claim_unbond_liquidity", claimUnbondLiquidity)

                val result = TransactionExecutor.executeContract(
                    fragment = this@UnbondFragment,
                    contractAddress = Constants.EXCHANGE_CONTRACT,
                    message = claimMsg,
                    codeHash = Constants.EXCHANGE_HASH,
                    contractLabel = "Exchange Contract:"
                )

                result.onSuccess {
                    // Refresh unbonding requests to update UI
                    if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
                        loadUnbondingRequests()
                    } else {
                        loadPoolInformationThenUnbondingRequests()
                    }
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Log.e(TAG, "Failed: ${error.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error claiming unbonded liquidity", e)
            }
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

    private fun loadPoolInformationThenUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            return
        }

        lifecycleScope.launch {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) return@launch

                // Query pool information to get reserves and total shares
                val queryJson = "{\"query_pool_info\": {\"pool\": \"$tokenContract\"}}"

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

                parsePoolInformation(result)

                // Now load unbonding requests after pool info is loaded
                loadUnbondingRequests()

            } catch (e: Exception) {
                Log.e(TAG, "Error loading pool information", e)
                // Still try to load unbonding requests even if pool info fails
                loadUnbondingRequests()
            }
        }
    }

    private fun parsePoolInformation(result: Any) {
        try {
            // Handle both JSONArray and JSONObject responses
            val poolData = when (result) {
                is JSONObject -> {
                    // Handle the SecretQueryService error case where data is in the error message
                    if (result.has("error") && result.has("decryption_error")) {
                        val decryptionError = result.getString("decryption_error")

                        // Look for "base64=Value " in the error message
                        val base64Marker = "base64=Value "
                        val base64Index = decryptionError.indexOf(base64Marker)
                        if (base64Index != -1) {
                            val startIndex = base64Index + base64Marker.length
                            val endIndex = decryptionError.indexOf(" of type", startIndex)
                            if (endIndex != -1) {
                                val jsonString = decryptionError.substring(startIndex, endIndex)

                                try {
                                    JSONObject(jsonString)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing pool JSON", e)
                                    return
                                }
                            } else {
                                return
                            }
                        } else {
                            return
                        }
                    } else if (result.has("data")) {
                        result.getJSONObject("data")
                    } else {
                        // Direct JSONObject response
                        result
                    }
                }
                else -> return
            }

            // query_pool_info returns {state: {...}, config: {...}}
            // Extract the state object
            val state = if (poolData.has("state")) {
                poolData.getJSONObject("state")
            } else {
                // Fallback if already at state level
                poolData
            }

            erthReserveMicro = state.optLong("erth_reserve", 0)
            tokenBReserveMicro = state.optLong("token_b_reserve", 0)
            totalSharesMicro = state.optLong("total_shares", 0)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing pool information", e)
        }
    }

    private fun loadUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            return
        }

        lifecycleScope.launch {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) return@launch

                // Query unbonding requests for this user and token
                val queryJson = "{\"query_unbonding_requests\": {\"pool\": \"$tokenContract\", \"user\": \"$currentWalletAddress\"}}"

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

                parseUnbondingRequests(result)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading unbonding requests", e)
            }
        }
    }

    private fun parseUnbondingRequests(result: Any) {
        try {
            // Handle both JSONArray and JSONObject responses
            val unbondingArray = when (result) {
                is JSONArray -> result
                is JSONObject -> {
                    // Handle the SecretQueryService error case where data is in the error message
                    if (result.has("error") && result.has("decryption_error")) {
                        val decryptionError = result.getString("decryption_error")

                        // Look for "base64=Value " in the error message and extract the JSON
                        val base64Marker = "base64=Value "
                        val base64Index = decryptionError.indexOf(base64Marker)
                        if (base64Index != -1) {
                            val startIndex = base64Index + base64Marker.length
                            val endIndex = decryptionError.indexOf(" of type", startIndex)
                            if (endIndex != -1) {
                                val jsonString = decryptionError.substring(startIndex, endIndex)

                                try {
                                    JSONArray(jsonString)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing unbonding JSON array", e)
                                    return
                                }
                            } else {
                                return
                            }
                        } else {
                            return
                        }
                    } else if (result.has("data")) {
                        val data = result.get("data")
                        if (data is JSONArray) {
                            data
                        } else {
                            return
                        }
                    } else {
                        return
                    }
                }
                else -> return
            }

            if (unbondingArray.length() > 0) {
                displayUnbondingRequests(unbondingArray)
            } else {
                showNoUnbondingMessage()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing unbonding requests", e)
            showNoUnbondingMessage()
        }
    }

    private fun displayUnbondingRequests(unbondingArray: JSONArray) {
        // Show the unbonding requests section
        noUnbondingMessage.visibility = View.GONE
        unbondingRequestsTitle.visibility = View.VISIBLE
        unbondingRequestsContainer.visibility = View.VISIBLE

        // Clear any existing views
        unbondingRequestsContainer.removeAllViews()

        var hasCompletedRequests = false

        for (i in 0 until unbondingArray.length()) {
            try {
                val request = unbondingArray.getJSONObject(i)
                val requestView = createUnbondingRequestView(request, i)
                if (requestView != null) {
                    unbondingRequestsContainer.addView(requestView)

                    // Check if this request is ready to complete
                    if (isRequestCompleted(request)) {
                        hasCompletedRequests = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing unbonding request $i", e)
            }
        }

        // Show complete unbond button if there are completed requests
        completeUnbondButton.visibility = if (hasCompletedRequests) View.VISIBLE else View.GONE
    }

    private fun createUnbondingRequestView(request: JSONObject, @Suppress("UNUSED_PARAMETER") index: Int): View? {
        return try {
            val view = LayoutInflater.from(context).inflate(R.layout.item_unbonding_request, unbondingRequestsContainer, false)

            val amountText = view.findViewById<TextView>(R.id.unbonding_amount_text)
            val estimatedText = view.findViewById<TextView>(R.id.unbonding_estimated_text)
            val statusText = view.findViewById<TextView>(R.id.unbonding_status_text)
            val timeText = view.findViewById<TextView>(R.id.unbonding_time_text)

            // Parse the request data based on React app structure
            var displayAmount = "0 Shares"
            var estimatedValues = ""
            var status = "Unbonding"
            var timeRemaining = "Unknown"

            if (request.has("amount")) {
                val sharesMicro = request.getLong("amount")
                val sharesMacro = sharesMicro / 1000000.0

                // Display shares amount with capital S
                displayAmount = String.format("%.2f Shares", sharesMacro)

                // Calculate estimated token values based on pool reserves (like InfoFragment does)
                if (totalSharesMicro > 0 && erthReserveMicro > 0 && tokenBReserveMicro > 0) {
                    // Calculate ownership percentage for these shares
                    val ownershipPercent = (sharesMicro * 100.0) / totalSharesMicro

                    // Calculate estimated underlying values
                    val erthReserveMacro = erthReserveMicro / 1000000.0
                    val tokenBReserveMacro = tokenBReserveMicro / 1000000.0

                    val estimatedErth = (erthReserveMacro * ownershipPercent) / 100.0
                    val estimatedToken = (tokenBReserveMacro * ownershipPercent) / 100.0

                    estimatedValues = String.format("~%.2f ERTH + %.2f %s", estimatedErth, estimatedToken, tokenKey)
                }
            }

            if (request.has("start_time")) {
                val startTime = request.getLong("start_time")
                val unbondSeconds = 7 * 24 * 60 * 60L // 7 days
                val claimableAt = startTime + unbondSeconds
                val currentTime = System.currentTimeMillis() / 1000

                if (currentTime >= claimableAt) {
                    status = "Ready to claim"
                    timeRemaining = "Ready"
                } else {
                    val remainingSeconds = claimableAt - currentTime
                    timeRemaining = formatTimeRemaining(remainingSeconds)
                }
            }

            amountText.text = displayAmount
            statusText.text = status
            timeText.text = timeRemaining

            // Set estimated values or hide if empty
            if (estimatedValues.isNotEmpty()) {
                estimatedText.text = estimatedValues
                estimatedText.visibility = View.VISIBLE
            } else {
                estimatedText.visibility = View.GONE
            }


            view
        } catch (e: Exception) {
            Log.e(TAG, "Error creating unbonding request view", e)
            null
        }
    }

    private fun isRequestCompleted(request: JSONObject): Boolean {
        return try {
            if (request.has("start_time")) {
                val startTime = request.getLong("start_time")
                val unbondSeconds = 7 * 24 * 60 * 60L // 7 days
                val claimableAt = startTime + unbondSeconds
                val currentTime = System.currentTimeMillis() / 1000
                currentTime >= claimableAt
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if request is completed", e)
            false
        }
    }

    private fun formatTimeRemaining(seconds: Long): String {
        if (seconds <= 0) {
            return "Ready"
        }

        val days = seconds / (24 * 3600)
        val hours = (seconds % (24 * 3600)) / 3600
        val minutes = (seconds % 3600) / 60

        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    private fun showNoUnbondingMessage() {
        noUnbondingMessage.visibility = View.VISIBLE
        unbondingRequestsTitle.visibility = View.GONE
        unbondingRequestsContainer.visibility = View.GONE
        completeUnbondButton.visibility = View.GONE
    }

    private fun getTokenContractAddress(symbol: String): String? {
        val tokenInfo = Tokens.getTokenInfo(symbol)
        return tokenInfo?.contract
    }


    override fun onResume() {
        super.onResume()
        // Refresh data when tab becomes visible
        refreshUnbondingData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) {
            refreshUnbondingData()
        }
    }

    private fun refreshUnbondingData() {
        if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
            executorService?.execute { loadUnbondingRequests() }
        } else {
            loadPoolInformationThenUnbondingRequests()
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