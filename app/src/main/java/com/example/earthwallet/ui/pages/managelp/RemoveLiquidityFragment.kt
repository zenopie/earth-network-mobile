package com.example.earthwallet.ui.pages.managelp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.earthwallet.R
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.wallet.constants.Tokens
import com.example.earthwallet.Constants
import com.example.earthwallet.bridge.services.SecretQueryService
import org.json.JSONObject
import org.json.JSONArray
import java.text.DecimalFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RemoveLiquidityFragment : Fragment() {

    companion object {
        private const val TAG = "RemoveLiquidityFragment"
        private const val REQ_REMOVE_LIQUIDITY = 5002

        @JvmStatic
        fun newInstance(tokenKey: String): RemoveLiquidityFragment {
            val fragment = RemoveLiquidityFragment()
            val args = Bundle()
            args.putString("token_key", tokenKey)
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var removeAmountInput: EditText
    private lateinit var stakedSharesText: TextView
    private lateinit var sharesMaxButton: Button
    private lateinit var removeLiquidityButton: Button

    private var tokenKey: String? = null
    private var userStakedShares = 0.0
    private var currentWalletAddress = ""
    private var securePrefs: SharedPreferences? = null
    private var executorService: ExecutorService? = null
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            tokenKey = it.getString("token_key")
        }

        // Get secure preferences from HostActivity
        try {
            if (activity is com.example.earthwallet.ui.host.HostActivity) {
                securePrefs = (activity as com.example.earthwallet.ui.host.HostActivity).getSecurePrefs()
                Log.d(TAG, "Successfully got securePrefs from HostActivity")
            } else {
                Log.e(TAG, "Activity is not HostActivity")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get securePrefs from HostActivity", e)
        }

        executorService = Executors.newCachedThreadPool()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_liquidity_remove, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupListeners()
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        loadCurrentWalletAddress()
        loadUserShares()
        loadUnbondingRequests()
    }

    private fun initializeViews(view: View) {
        removeAmountInput = view.findViewById(R.id.remove_amount_input)
        stakedSharesText = view.findViewById(R.id.staked_shares_text)
        sharesMaxButton = view.findViewById(R.id.shares_max_button)
        removeLiquidityButton = view.findViewById(R.id.remove_liquidity_button)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Received TRANSACTION_SUCCESS broadcast - refreshing liquidity data immediately")

                // Start multiple refresh attempts to ensure UI updates during animation
                loadUserShares()
                loadUnbondingRequests()

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Secondary refresh during animation")
                    loadUserShares()
                    loadUnbondingRequests()
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Third refresh during animation")
                    loadUserShares()
                    loadUnbondingRequests()
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

    private fun setupListeners() {
        sharesMaxButton.setOnClickListener {
            if (userStakedShares > 0) {
                removeAmountInput.setText(userStakedShares.toString())
            }
        }

        removeLiquidityButton.setOnClickListener {
            val removeAmountStr = removeAmountInput.text.toString().trim()
            if (TextUtils.isEmpty(removeAmountStr)) {
                Toast.makeText(context, "Please enter amount to remove", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                val removeAmount = removeAmountStr.toDouble()
                if (removeAmount <= 0) {
                    Toast.makeText(context, "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (removeAmount > userStakedShares) {
                    Toast.makeText(context, "Amount exceeds your staked shares", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                executeRemoveLiquidity(removeAmount)

            } catch (e: NumberFormatException) {
                Toast.makeText(context, "Invalid amount format", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadCurrentWalletAddress() {
        try {
            securePrefs?.let { prefs ->
                val walletsJson = prefs.getString("wallets", "[]") ?: "[]"
                val walletsArray = JSONArray(walletsJson)
                val selectedIndex = prefs.getInt("selected_wallet_index", -1)

                if (walletsArray.length() > 0) {
                    val selectedWallet = if (selectedIndex >= 0 && selectedIndex < walletsArray.length()) {
                        walletsArray.getJSONObject(selectedIndex)
                    } else {
                        walletsArray.getJSONObject(0)
                    }
                    currentWalletAddress = selectedWallet.optString("address", "")
                    Log.d(TAG, "Loaded wallet address: $currentWalletAddress")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
        }
    }

    private fun loadUserShares() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            stakedSharesText.text = "Balance: Connect wallet"
            return
        }

        executorService?.execute {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) return@execute

                // Query pool info
                val queryMsg = JSONObject()
                val queryUserInfo = JSONObject()
                val poolsArray = JSONArray()
                poolsArray.put(tokenContract)
                queryUserInfo.put("pools", poolsArray)
                queryUserInfo.put("user", currentWalletAddress)
                queryMsg.put("query_user_info", queryUserInfo)

                val queryService = SecretQueryService(context)
                val result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                )

                activity?.runOnUiThread {
                    parseUserShares(result)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading user shares", e)
                activity?.runOnUiThread {
                    stakedSharesText.text = "Balance: Error loading"
                }
            }
        }
    }

    private fun parseUserShares(result: JSONObject) {
        try {
            // Handle the SecretQueryService error case where data is in the error message
            if (result.has("error") && result.has("decryption_error")) {
                val decryptionError = result.getString("decryption_error")
                Log.d(TAG, "Got decryption error, checking for base64 data: $decryptionError")

                // Look for "base64=" in the error message and extract the array
                val base64Marker = "base64=Value "
                val base64Index = decryptionError.indexOf(base64Marker)
                if (base64Index != -1) {
                    val startIndex = base64Index + base64Marker.length
                    val endIndex = decryptionError.indexOf(" of type org.json.JSONArray", startIndex)
                    if (endIndex != -1) {
                        val jsonArrayString = decryptionError.substring(startIndex, endIndex)
                        Log.d(TAG, "Extracted JSON array from error: ${jsonArrayString.substring(0, minOf(100, jsonArrayString.length))}")

                        try {
                            val poolsData = JSONArray(jsonArrayString)
                            if (poolsData.length() > 0) {
                                // Find the pool that matches our token
                                for (i in 0 until poolsData.length()) {
                                    val poolInfo = poolsData.getJSONObject(i)
                                    val config = poolInfo.getJSONObject("pool_info").getJSONObject("config")
                                    val tokenSymbol = config.getString("token_b_symbol")
                                    if (tokenKey == tokenSymbol) {
                                        Log.d(TAG, "Found matching pool for $tokenKey")
                                        extractUserShares(poolInfo)
                                        return
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing extracted JSON array", e)
                        }
                    }
                }
            }

            // Also try the normal data path
            if (result.has("data")) {
                val poolsData = result.getJSONArray("data")
                if (poolsData.length() > 0) {
                    val poolInfo = poolsData.getJSONObject(0)
                    extractUserShares(poolInfo)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing user shares", e)
            stakedSharesText.text = "Balance: Error"
        }
    }

    private fun extractUserShares(poolInfo: JSONObject) {
        try {
            val userInfo = poolInfo.getJSONObject("user_info")
            val userStakedRaw = userInfo.optLong("amount_staked", 0)

            // Convert from microunits to regular units (divide by 10^6)
            userStakedShares = userStakedRaw / 1000000.0

            Log.d(TAG, "User staked shares loaded: $userStakedShares")

            updateSharesDisplay()

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting user shares from pool info", e)
            stakedSharesText.text = "Balance: Error"
        }
    }

    private fun updateSharesDisplay() {
        val df = DecimalFormat("#.##")

        if (userStakedShares >= 0) {
            stakedSharesText.text = "Balance: ${df.format(userStakedShares)}"
            sharesMaxButton.visibility = View.VISIBLE
        } else {
            stakedSharesText.text = "Balance: Error"
            sharesMaxButton.visibility = View.GONE
        }
    }

    private fun loadUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            Log.d(TAG, "Cannot load unbonding requests: missing token key or wallet address")
            return
        }

        executorService?.execute {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) return@execute

                // Query unbonding requests for this user and token
                val queryMsg = JSONObject()
                val queryUnbonding = JSONObject()
                queryUnbonding.put("user", currentWalletAddress)
                queryUnbonding.put("token", tokenContract)
                queryMsg.put("query_unbonding", queryUnbonding)

                Log.d(TAG, "Querying unbonding requests for $tokenKey with message: $queryMsg")

                val queryService = SecretQueryService(context)
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
    }

    private fun parseUnbondingRequests(result: JSONObject) {
        try {
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
                            val unbondingArray = JSONArray(jsonString)
                            Log.d(TAG, "Found ${unbondingArray.length()} unbonding requests")

                            // TODO: Display unbonding requests in UI
                            displayUnbondingRequests(unbondingArray)
                            return
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing unbonding JSON array", e)
                        }
                    }
                }
            }

            // Also try the normal data path
            if (result.has("data")) {
                val data = result.get("data")
                if (data is JSONArray) {
                    Log.d(TAG, "Found ${data.length()} unbonding requests in data")
                    displayUnbondingRequests(data)
                } else {
                    Log.d(TAG, "No unbonding requests found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing unbonding requests", e)
        }
    }

    private fun displayUnbondingRequests(unbondingArray: JSONArray) {
        // TODO: Add UI elements to show unbonding requests with amount and remaining time
        Log.d(TAG, "Displaying ${unbondingArray.length()} unbonding requests")
        for (i in 0 until unbondingArray.length()) {
            try {
                val request = unbondingArray.getJSONObject(i)
                Log.d(TAG, "Unbonding request $i: $request")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing unbonding request $i", e)
            }
        }
    }

    private fun getTokenContractAddress(symbol: String): String? {
        val tokenInfo = Tokens.getTokenInfo(symbol)
        return tokenInfo?.contract
    }

    private fun executeRemoveLiquidity(removeAmount: Double) {
        try {
            val tokenContract = getTokenContractAddress(tokenKey!!)
            if (tokenContract == null) {
                Toast.makeText(context, "Token contract not found", Toast.LENGTH_SHORT).show()
                return
            }

            // Convert shares to microunits (multiply by 1,000,000)
            val removeAmountMicro = Math.round(removeAmount * 1000000)

            // Create remove liquidity message
            val msg = JSONObject()
            val removeLiquidity = JSONObject()
            removeLiquidity.put("amount", removeAmountMicro.toString())
            removeLiquidity.put("pool", tokenContract)
            msg.put("remove_liquidity", removeLiquidity)

            Log.d(TAG, "Remove liquidity message: $msg")

            // Use TransactionActivity with SECRET_EXECUTE transaction type
            val intent = Intent(context, TransactionActivity::class.java)
            intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE)
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.EXCHANGE_CONTRACT)
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.EXCHANGE_HASH)
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, msg.toString())
            intent.putExtra(TransactionActivity.EXTRA_MEMO, "Remove liquidity for $tokenKey")

            startActivityForResult(intent, REQ_REMOVE_LIQUIDITY)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating remove liquidity message", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_REMOVE_LIQUIDITY) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "Remove liquidity transaction succeeded")
                // Clear input field
                removeAmountInput.setText("")
                // Refresh data (broadcast receiver will also handle this)
                loadUserShares()
                loadUnbondingRequests()
            } else {
                val error = data?.getStringExtra(TransactionActivity.EXTRA_ERROR) ?: "Transaction failed"
                Log.e(TAG, "Remove liquidity transaction failed: $error")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when tab becomes visible
        Log.d(TAG, "RemoveLiquidityFragment resumed - refreshing user shares")
        loadUserShares()
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