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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import network.erth.wallet.R
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONObject
import org.json.JSONArray
import java.text.DecimalFormat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class RemoveLiquidityFragment : Fragment() {

    companion object {
        private const val TAG = "RemoveLiquidityFragment"

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
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            tokenKey = it.getString("token_key")
        }
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

                // Start multiple refresh attempts to ensure UI updates during animation
                loadUserShares()
                loadUnbondingRequests()

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    loadUserShares()
                    loadUnbondingRequests()
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    loadUserShares()
                    loadUnbondingRequests()
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
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
            if (currentWalletAddress.isNotEmpty()) {
            } else {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            currentWalletAddress = ""
        }
    }

    private fun loadUserShares() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            stakedSharesText.text = "Balance: Connect wallet"
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

                parseUserShares(result)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading user shares", e)
                stakedSharesText.text = "Balance: Error loading"
            }
        }
    }

    private fun parseUserShares(result: Any) {
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
                extractUserShares(poolInfo)
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

            displayUnbondingRequests(unbondingArray)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing unbonding requests", e)
        }
    }

    private fun displayUnbondingRequests(unbondingArray: JSONArray) {
        // TODO: Add UI elements to show unbonding requests with amount and remaining time
        for (i in 0 until unbondingArray.length()) {
            try {
                val request = unbondingArray.getJSONObject(i)
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
        lifecycleScope.launch {
            try {
                val tokenContract = getTokenContractAddress(tokenKey!!)
                if (tokenContract == null) {
                    Toast.makeText(context, "Token contract not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Convert shares to microunits (multiply by 1,000,000)
                val removeAmountMicro = Math.round(removeAmount * 1000000)

                // Create remove liquidity message
                val msg = JSONObject()
                val removeLiquidity = JSONObject()
                removeLiquidity.put("amount", removeAmountMicro.toString())
                removeLiquidity.put("pool", tokenContract)
                msg.put("remove_liquidity", removeLiquidity)

                val result = TransactionExecutor.executeContract(
                    fragment = this@RemoveLiquidityFragment,
                    contractAddress = Constants.EXCHANGE_CONTRACT,
                    message = msg,
                    codeHash = Constants.EXCHANGE_HASH,
                    contractLabel = "Exchange Contract:"
                )

                result.onSuccess {
                    // Clear input field
                    removeAmountInput.setText("")
                    // Refresh data
                    loadUserShares()
                    loadUnbondingRequests()
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Toast.makeText(context, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error creating remove liquidity message", e)
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onResume() {
        super.onResume()
        // Refresh data when tab becomes visible
        loadUserShares()
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
}