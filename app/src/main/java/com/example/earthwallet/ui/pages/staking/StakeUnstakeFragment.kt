package network.erth.wallet.ui.pages.staking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Editable
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
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.TransactionExecutor
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fragment for staking and unstaking ERTH tokens
 * Corresponds to the "Stake/Unstake" tab in the React component
 */
class StakeUnstakeFragment : Fragment() {

    companion object {
        private const val TAG = "StakeUnstakeFragment"

        @JvmStatic
        fun newInstance(): StakeUnstakeFragment = StakeUnstakeFragment()
    }

    // UI Components - Stake Section
    private lateinit var stakeBalanceLabel: TextView
    private lateinit var stakeMaxButton: Button
    private lateinit var stakeAmountInput: EditText
    private lateinit var stakeButton: Button

    // UI Components - Unstake Section
    private lateinit var unstakeBalanceLabel: TextView
    private lateinit var unstakeMaxButton: Button
    private lateinit var unstakeAmountInput: EditText
    private lateinit var unstakeButton: Button
    private lateinit var unbondingNoteText: TextView

    // Data
    private var erthBalance = 0.0
    private var stakedBalance = 0.0
    private var permitManager: PermitManager? = null

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stake_unstake, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize PermitManager
        permitManager = PermitManager.getInstance(requireContext())

        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        setupClickListeners()

        // Load initial data
        refreshData()
    }

    private fun initializeViews(view: View) {
        // Stake section
        stakeBalanceLabel = view.findViewById(R.id.stake_balance_label)
        stakeMaxButton = view.findViewById(R.id.stake_max_button)
        stakeAmountInput = view.findViewById(R.id.stake_amount_input)
        stakeButton = view.findViewById(R.id.stake_button)

        // Unstake section
        unstakeBalanceLabel = view.findViewById(R.id.unstake_balance_label)
        unstakeMaxButton = view.findViewById(R.id.unstake_max_button)
        unstakeAmountInput = view.findViewById(R.id.unstake_amount_input)
        unstakeButton = view.findViewById(R.id.unstake_button)
        unbondingNoteText = view.findViewById(R.id.unbonding_note_text)
    }

    private fun setupClickListeners() {
        stakeMaxButton.setOnClickListener {
            if (erthBalance > 0) {
                stakeAmountInput.setText(erthBalance.toString())
            }
        }

        unstakeMaxButton.setOnClickListener {
            if (stakedBalance > 0) {
                unstakeAmountInput.setText(stakedBalance.toString())
            }
        }

        // Add text watchers for validation
        stakeAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateStakeButton()
            }
        })

        unstakeAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateUnstakeButton()
            }
        })

        stakeButton.setOnClickListener { handleStake() }
        unstakeButton.setOnClickListener { handleUnstake() }
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                // Start multiple refresh attempts to ensure UI updates during animation
                refreshData() // First immediate refresh

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    refreshData()
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    refreshData()
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

    override fun onResume() {
        super.onResume()

        // Refresh data when fragment resumes to update staked balance
        // which may have been affected by other tabs
        refreshData()
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

    /**
     * Refresh balance data
     */
    fun refreshData() {
        // Query ERTH balance using SnipQueryService
        queryErthBalance()

        // Query staked balance from parent fragment
        if (parentFragment is StakeEarthFragment) {
            val parentFragment = parentFragment as StakeEarthFragment
            parentFragment.queryUserStakingInfo(object : StakeEarthFragment.UserStakingCallback {
                override fun onStakingDataReceived(data: JSONObject) {
                    try {
                        // Extract staked balance
                        if (data.has("user_info") && !data.isNull("user_info")) {
                            val userInfo = data.getJSONObject("user_info")
                            if (userInfo.has("staked_amount")) {
                                val stakedAmountMicro = userInfo.getLong("staked_amount")
                                stakedBalance = stakedAmountMicro / 1_000_000.0 // Convert to macro units
                                updateUnstakeSection()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing staked balance", e)
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Error querying staking data: $error")
                }
            })
        }
    }

    private fun queryErthBalance() {
        lifecycleScope.launch {
            try {
                val walletAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (walletAddress == null) {
                    erthBalance = -1.0
                    updateStakeSection()
                    return@launch
                }

                // Check if permit exists for ERTH
                if (!permitManager!!.hasPermit(walletAddress, Tokens.ERTH.contract)) {
                    erthBalance = -1.0 // Indicates "need permit"
                    updateStakeSection()
                    return@launch
                }

                // Query ERTH balance using permit-based queries
                val result = SecretKClient.querySnipBalanceWithPermit(requireContext(), "ERTH", walletAddress)

                if (result.has("balance")) {
                    val balanceObj = result.getJSONObject("balance")
                    if (balanceObj.has("amount")) {
                        val amountStr = balanceObj.getString("amount")
                        val amount = amountStr.toDouble() / 1_000_000.0 // Convert from micro to macro units
                        erthBalance = amount
                    }
                } else {
                    erthBalance = 0.0
                }

                // Update UI
                updateStakeSection()

            } catch (e: Exception) {
                Log.e(TAG, "Error querying ERTH balance", e)
                erthBalance = -1.0 // Indicates error/need permit
                updateStakeSection()
            }
        }
    }

    private fun updateStakeSection() {
        if (activity == null) return

        activity?.runOnUiThread {
            if (erthBalance >= 0) {
                stakeBalanceLabel.text = String.format("Balance: %,.0f", erthBalance)
                stakeMaxButton.visibility = View.VISIBLE
            } else {
                stakeBalanceLabel.text = "Balance: Create permit"
                stakeMaxButton.visibility = View.GONE
            }

            validateStakeButton()
        }
    }

    private fun updateUnstakeSection() {
        if (activity == null) return

        activity?.runOnUiThread {
            if (stakedBalance > 0) {
                unstakeBalanceLabel.text = String.format("Balance: %,.0f", stakedBalance)
                unstakeMaxButton.visibility = View.VISIBLE
            } else {
                unstakeBalanceLabel.text = "No staked ERTH"
                unstakeMaxButton.visibility = View.GONE
            }

            validateUnstakeButton()
        }
    }

    private fun validateStakeButton() {
        val amountText = stakeAmountInput.text.toString().trim()
        var isValid = false

        if (!TextUtils.isEmpty(amountText)) {
            try {
                val amount = amountText.toDouble()
                isValid = amount > 0 && amount <= erthBalance
            } catch (e: NumberFormatException) {
                // Invalid number format
            }
        }

        stakeButton.isEnabled = isValid
    }

    private fun validateUnstakeButton() {
        val amountText = unstakeAmountInput.text.toString().trim()
        var isValid = false

        if (!TextUtils.isEmpty(amountText)) {
            try {
                val amount = amountText.toDouble()
                isValid = amount > 0 && amount <= stakedBalance
            } catch (e: NumberFormatException) {
                // Invalid number
            }
        }

        unstakeButton.isEnabled = isValid
    }

    private fun handleStake() {
        val amountText = stakeAmountInput.text.toString().trim()
        if (TextUtils.isEmpty(amountText)) {
            Toast.makeText(context, "Please enter an amount to stake", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val amount = amountText.toDouble()
                if (amount <= 0) {
                    Toast.makeText(context, "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (amount > erthBalance) {
                    Toast.makeText(context, "Insufficient balance", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Convert amount to micro units
                val amountMicro = Math.round(amount * 1_000_000)

                // Create SNIP-20 transfer message: { stake_erth: {} }
                val stakeMsg = JSONObject()
                stakeMsg.put("stake_erth", JSONObject())

                // Use TransactionExecutor to send ERTH to staking contract
                val erthToken = Tokens.getTokenInfo("ERTH")
                val result = TransactionExecutor.sendSnip20Token(
                    fragment = this@StakeUnstakeFragment,
                    tokenContract = erthToken?.contract ?: "",
                    tokenHash = erthToken?.hash ?: "",
                    recipient = Constants.STAKING_CONTRACT,
                    recipientHash = Constants.STAKING_HASH,
                    amount = amountMicro.toString(),
                    message = stakeMsg
                )

                result.onSuccess {
                    stakeAmountInput.setText("") // Clear input
                    refreshData() // Refresh balances
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Toast.makeText(context, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error staking ERTH", e)
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleUnstake() {
        val amountText = unstakeAmountInput.text.toString().trim()
        if (TextUtils.isEmpty(amountText)) {
            Toast.makeText(context, "Please enter an amount to unstake", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val amount = amountText.toDouble()
                if (amount <= 0) {
                    Toast.makeText(context, "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (amount > stakedBalance) {
                    Toast.makeText(context, "Insufficient staked balance", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Convert amount to micro units
                val amountMicro = Math.round(amount * 1_000_000)

                // Create withdraw message: { withdraw: { amount: "123456" } }
                val withdrawMsg = JSONObject()
                val withdraw = JSONObject()
                withdraw.put("amount", amountMicro.toString())
                withdrawMsg.put("withdraw", withdraw)

                val result = TransactionExecutor.executeContract(
                    fragment = this@StakeUnstakeFragment,
                    contractAddress = Constants.STAKING_CONTRACT,
                    message = withdrawMsg,
                    codeHash = Constants.STAKING_HASH,
                    contractLabel = "Staking Contract:"
                )

                result.onSuccess {
                    unstakeAmountInput.setText("") // Clear input
                    refreshData() // Refresh balances
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Toast.makeText(context, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error unstaking ERTH", e)
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}