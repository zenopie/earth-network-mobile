package com.example.earthwallet.ui.pages.managelp

import android.app.Activity
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.earthwallet.R
import com.example.earthwallet.Constants
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.wallet.constants.Tokens
import com.example.earthwallet.wallet.services.SecureWalletManager
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

    // Activity Result Launcher for transaction activity
    private lateinit var claimUnbondLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            tokenKey = args.getString("token_key")
            // Get pool information from arguments if provided
            erthReserveMicro = args.getLong("erth_reserve", 0)
            tokenBReserveMicro = args.getLong("token_b_reserve", 0)
            totalSharesMicro = args.getLong("total_shares", 0)

            Log.d(TAG, "Pool info from arguments - ERTH: $erthReserveMicro, Token: $tokenBReserveMicro, Shares: $totalSharesMicro")
        }

        // Use SecureWalletManager for secure operations
        Log.d(TAG, "Using SecureWalletManager for secure operations")

        // Initialize Activity Result Launcher
        claimUnbondLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "Unbonded liquidity claimed successfully")
                // Refresh unbonding requests to update UI
                if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
                    executorService?.execute { loadUnbondingRequests() }
                } else {
                    loadPoolInformationThenUnbondingRequests()
                }
            } else {
                val error = result.data?.getStringExtra(TransactionActivity.EXTRA_ERROR) ?: "Unknown error"
                Log.e(TAG, "Failed to claim unbonded liquidity: $error")
            }
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
                Log.d(TAG, "Received TRANSACTION_SUCCESS broadcast - refreshing unbonding data immediately")

                // Start multiple refresh attempts to ensure UI updates during animation
                if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
                    executorService?.execute { loadUnbondingRequests() }
                } else {
                    loadPoolInformationThenUnbondingRequests()
                }

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Secondary refresh during animation")
                    if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
                        executorService?.execute { loadUnbondingRequests() }
                    } else {
                        loadPoolInformationThenUnbondingRequests()
                    }
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Third refresh during animation")
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
            val filter = IntentFilter("com.example.earthwallet.TRANSACTION_SUCCESS")
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
                }
                Log.d(TAG, "Registered transaction success receiver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register broadcast receiver", e)
            }
        }
    }

    private fun handleCompleteUnbond() {
        Log.d(TAG, "Complete unbond button clicked")

        if (tokenKey == null) {
            Log.e(TAG, "Cannot complete unbond: missing token key")
            return
        }

        try {
            val tokenContract = getTokenContractAddress(tokenKey!!)
            if (tokenContract == null) {
                Log.e(TAG, "Cannot complete unbond: token contract not found for $tokenKey")
                return
            }

            // Create claim unbond liquidity message: { claim_unbond_liquidity: { pool: "contract_address" } }
            val claimMsg = JSONObject()
            val claimUnbondLiquidity = JSONObject()
            claimUnbondLiquidity.put("pool", tokenContract)
            claimMsg.put("claim_unbond_liquidity", claimUnbondLiquidity)

            Log.d(TAG, "Claiming unbonded liquidity for pool: $tokenContract")

            // Use TransactionActivity with SECRET_EXECUTE for claiming unbonded liquidity
            val intent = Intent(activity, TransactionActivity::class.java)
            intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE)
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.EXCHANGE_CONTRACT)
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.EXCHANGE_HASH)
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, claimMsg.toString())

            claimUnbondLauncher.launch(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error claiming unbonded liquidity", e)
        }
    }

    private fun loadCurrentWalletAddress() {
        try {
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
            if (currentWalletAddress.isNotEmpty()) {
                Log.d(TAG, "Loaded wallet address: ${currentWalletAddress.substring(0, minOf(14, currentWalletAddress.length))}...")
            } else {
                Log.w(TAG, "No wallet address available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            currentWalletAddress = ""
        }
    }

    private fun loadPoolInformationThenUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            Log.d(TAG, "Cannot load pool information: missing token key or wallet address")
            return
        }

        executorService?.execute {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) return@execute

                // Query pool information to get reserves and total shares
                val queryMsg = JSONObject()
                val queryPool = JSONObject()
                queryPool.put("pool", tokenContract)
                queryMsg.put("query_pool", queryPool)

                Log.d(TAG, "Querying pool information for $tokenKey")

                val queryService = SecretQueryService(requireContext())
                val result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                )

                Log.d(TAG, "Pool query result: $result")
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

    private fun parsePoolInformation(result: JSONObject) {
        try {
            // Handle the SecretQueryService error case where data is in the error message
            if (result.has("error") && result.has("decryption_error")) {
                val decryptionError = result.getString("decryption_error")
                Log.d(TAG, "Parsing pool info from decryption error")

                // Look for "base64=Value " in the error message
                val base64Marker = "base64=Value "
                val base64Index = decryptionError.indexOf(base64Marker)
                if (base64Index != -1) {
                    val startIndex = base64Index + base64Marker.length
                    val endIndex = decryptionError.indexOf(" of type", startIndex)
                    if (endIndex != -1) {
                        val jsonString = decryptionError.substring(startIndex, endIndex)
                        Log.d(TAG, "Extracted pool JSON: $jsonString")

                        try {
                            val poolData = JSONObject(jsonString)
                            erthReserveMicro = poolData.optLong("erth_reserve", 0)
                            tokenBReserveMicro = poolData.optLong("token_b_reserve", 0)
                            totalSharesMicro = poolData.optLong("total_shares", 0)

                            Log.d(TAG, "Pool info loaded - ERTH: $erthReserveMicro, Token: $tokenBReserveMicro, Shares: $totalSharesMicro")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing pool JSON", e)
                        }
                    }
                }
            }

            // Also try the normal data path
            if (result.has("data")) {
                val poolData = result.getJSONObject("data")
                erthReserveMicro = poolData.optLong("erth_reserve", 0)
                tokenBReserveMicro = poolData.optLong("token_b_reserve", 0)
                totalSharesMicro = poolData.optLong("total_shares", 0)

                Log.d(TAG, "Pool info loaded from data - ERTH: $erthReserveMicro, Token: $tokenBReserveMicro, Shares: $totalSharesMicro")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing pool information", e)
        }
    }

    private fun loadUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            Log.d(TAG, "Cannot load unbonding requests: missing token key or wallet address")
            return
        }

        try {
            val tokenContract = getTokenContractAddress(tokenKey!!)
            if (tokenContract == null) return

            // Query unbonding requests for this user and token
            val queryMsg = JSONObject()
            val queryUnbondingRequests = JSONObject()
            queryUnbondingRequests.put("pool", tokenContract)
            queryUnbondingRequests.put("user", currentWalletAddress)
            queryMsg.put("query_unbonding_requests", queryUnbondingRequests)

            Log.d(TAG, "Querying unbonding requests for $tokenKey with message: $queryMsg")

            val queryService = SecretQueryService(requireContext())
            val result = queryService.queryContract(
                Constants.EXCHANGE_CONTRACT,
                Constants.EXCHANGE_HASH,
                queryMsg
            )

            Log.d(TAG, "Unbonding query result: $result")

            activity?.runOnUiThread {
                parseUnbondingRequests(result)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading unbonding requests", e)
        }
    }

    private fun parseUnbondingRequests(result: JSONObject) {
        try {
            var unbondingArray: JSONArray? = null

            // Handle the SecretQueryService error case where data is in the error message
            if (result.has("error") && result.has("decryption_error")) {
                val decryptionError = result.getString("decryption_error")
                Log.d(TAG, "Parsing unbonding from decryption error: ${decryptionError.substring(0, minOf(200, decryptionError.length))}")

                // Look for "base64=Value " in the error message and extract the JSON
                val base64Marker = "base64=Value "
                val base64Index = decryptionError.indexOf(base64Marker)
                if (base64Index != -1) {
                    val startIndex = base64Index + base64Marker.length
                    val endIndex = decryptionError.indexOf(" of type", startIndex)
                    if (endIndex != -1) {
                        val jsonString = decryptionError.substring(startIndex, endIndex)
                        Log.d(TAG, "Extracted unbonding JSON: $jsonString")

                        try {
                            unbondingArray = JSONArray(jsonString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing unbonding JSON array", e)
                        }
                    }
                }
            }

            // Also try the normal data path
            if (unbondingArray == null && result.has("data")) {
                val data = result.get("data")
                if (data is JSONArray) {
                    unbondingArray = data
                }
            }

            if (unbondingArray != null && unbondingArray.length() > 0) {
                Log.d(TAG, "Found ${unbondingArray.length()} unbonding requests")
                displayUnbondingRequests(unbondingArray)
            } else {
                Log.d(TAG, "No unbonding requests found")
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

            Log.d(TAG, "Created unbonding request view: $displayAmount, $estimatedValues, $status, $timeRemaining")

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
        Log.d(TAG, "UnbondFragment resumed - refreshing unbonding data")
        refreshUnbondingData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) {
            Log.d(TAG, "UnbondFragment became visible - refreshing unbonding data")
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
                Log.d(TAG, "Unregistered transaction success receiver")
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
                Log.d(TAG, "Receiver was not registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }

        if (executorService != null && !executorService!!.isShutdown) {
            executorService!!.shutdown()
        }
    }
}