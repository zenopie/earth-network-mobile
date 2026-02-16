package network.erth.wallet.ui.pages.staking

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
 * Fragment for displaying and claiming staking rewards
 */
class RewardsFragment : Fragment() {

    companion object {
        private const val TAG = "RewardsFragment"

        @JvmStatic
        fun newInstance(): RewardsFragment = RewardsFragment()
    }

    // UI Components
    private lateinit var stakingRewardsText: TextView
    private lateinit var claimRewardsButton: Button
    private lateinit var noRewardsContainer: LinearLayout

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    // Data
    private var stakingRewards = 0.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_staking_rewards, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        setupClickListeners()

        // Load initial data
        refreshData()
    }

    private fun initializeViews(view: View) {
        stakingRewardsText = view.findViewById(R.id.staking_rewards_text)
        claimRewardsButton = view.findViewById(R.id.claim_rewards_button)
        noRewardsContainer = view.findViewById(R.id.no_rewards_container)
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
        claimRewardsButton.setOnClickListener { handleClaimRewards() }
    }

    fun refreshData() {
        if (!isAdded || context == null) return

        lifecycleScope.launch {
            try {
                queryStakingRewards()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing rewards data", e)
            }
        }
    }

    private suspend fun queryStakingRewards() {
        val userAddress = SecureWalletManager.getWalletAddress(requireContext())
        if (TextUtils.isEmpty(userAddress)) return

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

            if (dataObj.has("staking_rewards_due")) {
                val stakingRewardsMicro = dataObj.getLong("staking_rewards_due")
                stakingRewards = stakingRewardsMicro / 1_000_000.0
            }

            activity?.runOnUiThread { updateUI() }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing staking result", e)
        }
    }

    private fun updateUI() {
        if (stakingRewards > 0) {
            stakingRewardsText.text = String.format("%,.2f ERTH", stakingRewards)
            claimRewardsButton.visibility = View.VISIBLE
            claimRewardsButton.isEnabled = true
            noRewardsContainer.visibility = View.GONE
        } else {
            stakingRewardsText.text = "0 ERTH"
            claimRewardsButton.visibility = View.GONE
            noRewardsContainer.visibility = View.VISIBLE
        }
    }

    private fun handleClaimRewards() {
        lifecycleScope.launch {
            try {
                val claimMsg = JSONObject()
                claimMsg.put("claim", JSONObject())

                val result = TransactionExecutor.executeContract(
                    fragment = this@RewardsFragment,
                    contractAddress = Constants.STAKING_CONTRACT,
                    message = claimMsg,
                    codeHash = Constants.STAKING_HASH,
                    contractLabel = "Staking Contract:"
                )

                result.onSuccess {
                    refreshData()
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        context?.let {
                            Toast.makeText(it, "Failed to claim rewards: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error claiming rewards", e)
                context?.let {
                    Toast.makeText(it, "Failed to claim rewards: ${e.message}", Toast.LENGTH_SHORT).show()
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
