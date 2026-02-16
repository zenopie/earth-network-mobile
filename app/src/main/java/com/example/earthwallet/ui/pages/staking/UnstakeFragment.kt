package network.erth.wallet.ui.pages.staking

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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONObject

/**
 * Fragment for unstaking ERTH tokens
 */
class UnstakeFragment : Fragment() {

    companion object {
        private const val TAG = "UnstakeFragment"

        @JvmStatic
        fun newInstance(): UnstakeFragment = UnstakeFragment()
    }

    // UI Components
    private lateinit var unstakeBalanceLabel: TextView
    private lateinit var unstakeMaxButton: Button
    private lateinit var unstakeAmountInput: EditText
    private lateinit var unstakeButton: Button

    // Data
    private var stakedBalance = 0.0

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_staking_unstake, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        setupClickListeners()

        refreshData()
    }

    private fun initializeViews(view: View) {
        unstakeBalanceLabel = view.findViewById(R.id.unstake_balance_label)
        unstakeMaxButton = view.findViewById(R.id.unstake_max_button)
        unstakeAmountInput = view.findViewById(R.id.unstake_amount_input)
        unstakeButton = view.findViewById(R.id.unstake_button)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshData()
                Handler(Looper.getMainLooper()).postDelayed({ refreshData() }, 100)
                Handler(Looper.getMainLooper()).postDelayed({ refreshData() }, 500)
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

    private fun setupClickListeners() {
        unstakeMaxButton.setOnClickListener {
            if (stakedBalance > 0) {
                unstakeAmountInput.setText(stakedBalance.toString())
            }
        }

        unstakeAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateUnstakeButton()
            }
        })

        unstakeButton.setOnClickListener { handleUnstake() }
    }

    fun refreshData() {
        queryStakedBalance()
    }

    private fun queryStakedBalance() {
        lifecycleScope.launch {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (TextUtils.isEmpty(userAddress)) {
                    stakedBalance = 0.0
                    updateUI()
                    return@launch
                }

                val queryMsg = JSONObject()
                val getUserInfo = JSONObject()
                getUserInfo.put("address", userAddress)
                queryMsg.put("get_user_info", getUserInfo)

                val result = SecretKClient.queryContractJson(
                    Constants.STAKING_CONTRACT,
                    queryMsg,
                    Constants.STAKING_HASH
                )

                parseStakingResult(result)

            } catch (e: Exception) {
                Log.e(TAG, "Error querying staked balance", e)
                stakedBalance = 0.0
                updateUI()
            }
        }
    }

    private fun parseStakingResult(result: JSONObject) {
        try {
            var dataObj = result
            if (result.has("error") && result.has("decryption_error")) {
                val decryptionError = result.getString("decryption_error")
                val jsonMarker = "base64=Value "
                val jsonIndex = decryptionError.indexOf(jsonMarker)
                if (jsonIndex != -1) {
                    val startIndex = jsonIndex + jsonMarker.length
                    val endIndex = decryptionError.indexOf(" of type", startIndex)
                    if (endIndex != -1) {
                        val jsonString = decryptionError.substring(startIndex, endIndex)
                        try {
                            dataObj = JSONObject(jsonString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON from decryption_error", e)
                        }
                    }
                }
            } else if (result.has("data")) {
                dataObj = result.getJSONObject("data")
            }

            if (dataObj.has("user_info") && !dataObj.isNull("user_info")) {
                val userInfo = dataObj.getJSONObject("user_info")
                if (userInfo.has("staked_amount")) {
                    val stakedAmountMicro = userInfo.getLong("staked_amount")
                    stakedBalance = stakedAmountMicro / 1_000_000.0
                }
            } else {
                stakedBalance = 0.0
            }

            activity?.runOnUiThread { updateUI() }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing staking result", e)
        }
    }

    private fun updateUI() {
        if (activity == null) return

        activity?.runOnUiThread {
            if (stakedBalance > 0) {
                unstakeBalanceLabel.text = String.format("Staked: %,.0f", stakedBalance)
                unstakeMaxButton.visibility = View.VISIBLE
            } else {
                unstakeBalanceLabel.text = "No staked ERTH"
                unstakeMaxButton.visibility = View.GONE
            }

            validateUnstakeButton()
        }
    }

    private fun validateUnstakeButton() {
        val amountText = unstakeAmountInput.text.toString().trim()
        var isValid = false

        if (!TextUtils.isEmpty(amountText)) {
            try {
                val amount = amountText.toDouble()
                isValid = amount > 0 && amount <= stakedBalance
            } catch (e: NumberFormatException) { }
        }

        unstakeButton.isEnabled = isValid
    }

    private fun handleUnstake() {
        val amountText = unstakeAmountInput.text.toString().trim()
        if (TextUtils.isEmpty(amountText)) {
            context?.let {
                Toast.makeText(it, "Please enter an amount to unstake", Toast.LENGTH_SHORT).show()
            }
            return
        }

        lifecycleScope.launch {
            try {
                val amount = amountText.toDouble()
                if (amount <= 0) {
                    context?.let {
                        Toast.makeText(it, "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (amount > stakedBalance) {
                    context?.let {
                        Toast.makeText(it, "Insufficient staked balance", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val amountMicro = Math.round(amount * 1_000_000)

                val withdrawMsg = JSONObject()
                val withdraw = JSONObject()
                withdraw.put("amount", amountMicro.toString())
                withdrawMsg.put("withdraw", withdraw)

                val result = TransactionExecutor.executeContract(
                    fragment = this@UnstakeFragment,
                    contractAddress = Constants.STAKING_CONTRACT,
                    message = withdrawMsg,
                    codeHash = Constants.STAKING_HASH,
                    contractLabel = "Staking Contract:"
                )

                result.onSuccess {
                    unstakeAmountInput.setText("")
                    refreshData()
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        context?.let {
                            Toast.makeText(it, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error unstaking ERTH", e)
                context?.let {
                    Toast.makeText(it, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: Exception) { }
        }
    }
}
