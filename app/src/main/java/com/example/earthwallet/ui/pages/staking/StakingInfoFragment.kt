package network.erth.wallet.ui.pages.staking

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
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONObject

/**
 * Fragment for displaying staking info and rewards
 * Corresponds to the "Info & Rewards" tab in the React component
 */
class StakingInfoFragment : Fragment() {

    companion object {
        private const val TAG = "StakingInfoFragment"
        private const val REQ_CLAIM_REWARDS = 4001

        @JvmStatic
        fun newInstance(): StakingInfoFragment = StakingInfoFragment()
    }

    // UI Components
    private lateinit var stakedAmountText: TextView
    private lateinit var erthBalanceText: TextView
    private lateinit var currentAprText: TextView
    private lateinit var totalStakedText: TextView
    private lateinit var stakingRewardsText: TextView
    private lateinit var claimRewardsButton: Button
    private lateinit var noRewardsText: TextView
    private lateinit var poolShareText: TextView
    private lateinit var dailyEarningsText: TextView

    // Services
    private var permitManager: PermitManager? = null

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    // Data
    private var stakedBalance = 0.0
    private var unstakedBalance = 0.0
    private var stakingRewards = 0.0
    private var totalStakedBalance = 0.0
    private var apr = 0.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_staking_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize services
        permitManager = PermitManager.getInstance(requireContext())

        // Register broadcast receiver for immediate transaction success notifications
        context?.let {
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
            val filter = IntentFilter("network.erth.wallet.TRANSACTION_SUCCESS")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
            }
        }

        initializeViews(view)
        setupClickListeners()

        // Load initial data
        refreshData()
    }

    private fun initializeViews(view: View) {
        stakedAmountText = view.findViewById(R.id.staked_amount_text)
        erthBalanceText = view.findViewById(R.id.erth_balance_text)
        currentAprText = view.findViewById(R.id.current_apr_text)
        totalStakedText = view.findViewById(R.id.total_staked_text)
        stakingRewardsText = view.findViewById(R.id.staking_rewards_text)
        claimRewardsButton = view.findViewById(R.id.claim_rewards_button)
        noRewardsText = view.findViewById(R.id.no_rewards_text)
        poolShareText = view.findViewById(R.id.pool_share_text)
        dailyEarningsText = view.findViewById(R.id.daily_earnings_text)
    }

    private fun setupClickListeners() {
        claimRewardsButton.setOnClickListener { handleClaimRewards() }
    }

    /**
     * Refresh staking data from contract
     */
    fun refreshData() {
        // Check if fragment is still attached before launching coroutine
        if (!isAdded || context == null) {
            return
        }

        lifecycleScope.launch {
            try {
                queryStakingInfo()
                queryErthBalance()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled (user navigated away) - this is normal, don't show error
                throw e // Re-throw to properly cancel the coroutine
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing staking data", e)
                context?.let {
                    Toast.makeText(it, "Failed to load staking data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun queryStakingInfo() {
        val userAddress = SecureWalletManager.getWalletAddress(requireContext())
        if (TextUtils.isEmpty(userAddress)) {
            return
        }

        // Create query message: { get_user_info: { address: "secret1..." } }
        val queryMsg = JSONObject()
        val getUserInfo = JSONObject()
        getUserInfo.put("address", userAddress)
        queryMsg.put("get_user_info", getUserInfo)

        val result = SecretKClient.queryContractJson(
            Constants.STAKING_CONTRACT,
            queryMsg,
            Constants.STAKING_HASH
        )

        // Parse results
        parseStakingResult(result)
    }

    private fun parseStakingResult(result: JSONObject) {
        try {
            // Handle potential decryption_error format like other fragments
            var dataObj = result
            if (result.has("error") && result.has("decryption_error")) {
                val decryptionError = result.getString("decryption_error")

                // Extract JSON from error message if needed
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

            // Extract staking rewards due (micro units)
            if (dataObj.has("staking_rewards_due")) {
                val stakingRewardsMicro = dataObj.getLong("staking_rewards_due")
                stakingRewards = stakingRewardsMicro / 1_000_000.0 // Convert to macro units
            }

            // Extract total staked (micro units)
            if (dataObj.has("total_staked")) {
                val totalStakedMicro = dataObj.getLong("total_staked")
                totalStakedBalance = totalStakedMicro / 1_000_000.0 // Convert to macro units

                // Calculate APR like in React app
                calculateAPR(totalStakedMicro)
            }

            // Extract user staked amount
            if (dataObj.has("user_info") && !dataObj.isNull("user_info")) {
                val userInfo = dataObj.getJSONObject("user_info")
                if (userInfo.has("staked_amount")) {
                    val stakedAmountMicro = userInfo.getLong("staked_amount")
                    stakedBalance = stakedAmountMicro / 1_000_000.0 // Convert to macro units
                }
            } else {
                stakedBalance = 0.0
            }

            // Update UI on main thread
            activity?.runOnUiThread { updateUI() }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing staking result", e)
        }
    }

    private fun calculateAPR(totalStakedMicro: Long) {
        if (totalStakedMicro == 0L) {
            apr = 0.0
            return
        }

        // APR calculation from React app
        val secondsPerDay = 24 * 60 * 60
        val daysPerYear = 365

        val totalStakedMacro = totalStakedMicro / 1_000_000.0
        val dailyGrowth = secondsPerDay / totalStakedMacro
        val annualGrowth = dailyGrowth * daysPerYear

        apr = annualGrowth * 100 // Convert to percentage
    }

    private fun queryErthBalance() {
        lifecycleScope.launch {
            try {
                val walletAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (walletAddress == null) {
                    unstakedBalance = -1.0
                    updateUI()
                    return@launch
                }

                // Check if permit exists for ERTH
                if (!permitManager!!.hasPermit(walletAddress, Tokens.ERTH.contract)) {
                    unstakedBalance = -1.0 // Indicates "need permit"
                    updateUI()
                    return@launch
                }

                // Query ERTH balance using permit-based queries
                val result = SecretKClient.querySnipBalanceWithPermit(requireContext(), "ERTH", walletAddress)

                if (result != null && result.has("balance")) {
                    val balanceObj = result.getJSONObject("balance")
                    if (balanceObj.has("amount")) {
                        val amountStr = balanceObj.getString("amount")
                        val amount = amountStr.toDouble() / 1_000_000.0 // Convert from micro to macro units
                        unstakedBalance = amount
                    }
                } else {
                    unstakedBalance = 0.0
                }

                // Update UI on main thread
                updateUI()

            } catch (e: Exception) {
                Log.e(TAG, "Error querying ERTH balance for Info tab", e)
                unstakedBalance = -1.0 // Indicates error/need permit
                updateUI()
            }
        }
    }

    private fun updateUI() {
        if (stakedBalance > 0) {
            stakedAmountText.text = String.format("%,.0f ERTH", stakedBalance)
        } else {
            stakedAmountText.text = "0 ERTH"
        }

        if (unstakedBalance >= 0) {
            erthBalanceText.text = String.format("¤%,.0f", unstakedBalance)
        } else {
            erthBalanceText.text = "Create permit"
        }

        currentAprText.text = String.format("%.2f%%", apr)
        totalStakedText.text = String.format("¤%,.0f", totalStakedBalance)

        // Calculate pool share percentage
        val poolShare = if (totalStakedBalance > 0) (stakedBalance / totalStakedBalance) * 100 else 0.0
        poolShareText.text = String.format("%.4f%%", poolShare)

        // Calculate estimated daily earnings based on APR
        val dailyEarnings = (stakedBalance * apr / 100) / 365
        dailyEarningsText.text = String.format("¤%.2f", dailyEarnings)

        if (stakingRewards > 0) {
            stakingRewardsText.text = String.format("%,.2f ERTH", stakingRewards)
            claimRewardsButton.visibility = View.VISIBLE
            claimRewardsButton.isEnabled = true
            noRewardsText.visibility = View.GONE
        } else {
            stakingRewardsText.text = "0 ERTH"
            claimRewardsButton.visibility = View.GONE
            noRewardsText.visibility = View.VISIBLE
        }
    }

    private fun handleClaimRewards() {
        lifecycleScope.launch {
            try {
                // Create claim message: { claim: {} }
                val claimMsg = JSONObject()
                claimMsg.put("claim", JSONObject())

                // Execute transaction using TransactionExecutor
                val result = TransactionExecutor.executeContract(
                    fragment = this@StakingInfoFragment,
                    contractAddress = Constants.STAKING_CONTRACT,
                    message = claimMsg,
                    codeHash = Constants.STAKING_HASH,
                    contractLabel = "Staking Contract:"
                )

                // Handle result
                result.onSuccess {
                    // Refresh data to reflect new balances
                    refreshData()
                }.onFailure { error ->
                    // Only show error toast if it's not a cancellation
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        context?.let {
                            Toast.makeText(it, "Failed to claim rewards: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled - this is normal, don't show error
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error claiming rewards", e)
                context?.let {
                    Toast.makeText(it, "Failed to claim rewards: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_CLAIM_REWARDS) {
            if (resultCode == Activity.RESULT_OK) {
                // Refresh data to reflect new balances
                refreshData()
            } else {
                val error = data?.getStringExtra("error") ?: "Unknown error"
                // Error handling could be added here if needed
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when user navigates to this fragment
        refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister broadcast receiver
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: Exception) {
            }
        }
    }
}