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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.SecureWalletManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Main staking fragment that manages ERTH token staking
 * Features info section above tabs and 4 tabs: Rewards, Stake, Unstake, Unbonding
 */
class StakeEarthFragment : Fragment() {

    companion object {
        private const val TAG = "StakeEarthFragment"
    }

    // UI Components - Info Section
    private var infoSection: LinearLayout? = null
    private var aprText: TextView? = null
    private var stakedAmountText: TextView? = null
    private var stakedAmountUsd: TextView? = null
    private var totalStakedText: TextView? = null
    private var totalStakedUsd: TextView? = null
    private var poolShareText: TextView? = null
    private var dailyEarningsText: TextView? = null
    private var dailyEarningsUsd: TextView? = null

    // UI Components - Tabs
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var rootView: View? = null

    // Adapter
    private var stakingAdapter: StakingTabsAdapter? = null

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    // Data
    private var stakedBalance = 0.0
    private var totalStakedBalance = 0.0
    private var apr = 0.0
    private var erthPrice: Double? = null

    // Interface for communication with parent
    interface StakeEarthListener {
        fun getCurrentWalletAddress(): String
        fun onStakingOperationComplete()
    }

    private var listener: StakeEarthListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is StakeEarthListener -> parentFragment as StakeEarthListener
            context is StakeEarthListener -> context
            else -> null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        rootView = inflater.inflate(R.layout.fragment_stake_earth, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        setupTabs()

        // Load initial data
        refreshStakingData()
    }

    private fun initializeViews(view: View) {
        // Info section
        infoSection = view.findViewById(R.id.info_section)
        aprText = view.findViewById(R.id.apr_text)
        stakedAmountText = view.findViewById(R.id.staked_amount_text)
        stakedAmountUsd = view.findViewById(R.id.staked_amount_usd)
        totalStakedText = view.findViewById(R.id.total_staked_text)
        totalStakedUsd = view.findViewById(R.id.total_staked_usd)
        poolShareText = view.findViewById(R.id.pool_share_text)
        dailyEarningsText = view.findViewById(R.id.daily_earnings_text)
        dailyEarningsUsd = view.findViewById(R.id.daily_earnings_usd)

        // Tabs
        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshStakingData()
                Handler(Looper.getMainLooper()).postDelayed({ refreshStakingData() }, 100)
                Handler(Looper.getMainLooper()).postDelayed({ refreshStakingData() }, 500)
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

    private fun setupTabs() {
        val tabLayout = this.tabLayout ?: return
        val viewPager = this.viewPager ?: return

        // Create adapter
        stakingAdapter = StakingTabsAdapter(this)
        viewPager.adapter = stakingAdapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                StakingTabsAdapter.TAB_REWARDS -> "Rewards"
                StakingTabsAdapter.TAB_STAKE -> "Stake"
                StakingTabsAdapter.TAB_UNSTAKE -> "Withdraw"
                StakingTabsAdapter.TAB_UNBONDING -> "Unbonding"
                else -> "Tab $position"
            }
        }.attach()

        // Listen for tab changes to hide/show info section
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Hide info section when on Unbonding tab
                if (position == StakingTabsAdapter.TAB_UNBONDING) {
                    infoSection?.visibility = View.GONE
                } else {
                    infoSection?.visibility = View.VISIBLE
                }
            }
        })
    }

    /**
     * Public method to refresh staking data
     */
    fun refreshStakingData() {
        if (!isAdded || context == null) return

        lifecycleScope.launch {
            try {
                // Fetch ERTH price in parallel with staking info
                val priceJob = launch {
                    try {
                        erthPrice = ErthPriceService.fetchErthPrice()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching ERTH price", e)
                    }
                }
                queryStakingInfo()
                priceJob.join() // Wait for price fetch to complete
                activity?.runOnUiThread { updateUI() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing staking data", e)
            }
        }
    }

    private suspend fun queryStakingInfo() {
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

            // Extract total staked
            if (dataObj.has("total_staked")) {
                val totalStakedMicro = dataObj.getLong("total_staked")
                totalStakedBalance = totalStakedMicro / 1_000_000.0
                calculateAPR(totalStakedMicro)
            }

            // Extract user staked amount
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

    private fun calculateAPR(totalStakedMicro: Long) {
        if (totalStakedMicro == 0L) {
            apr = 0.0
            return
        }

        val secondsPerDay = 24 * 60 * 60
        val daysPerYear = 365

        val totalStakedMacro = totalStakedMicro / 1_000_000.0
        val dailyGrowth = secondsPerDay / totalStakedMacro
        val annualGrowth = dailyGrowth * daysPerYear

        apr = annualGrowth * 100
    }

    private fun updateUI() {
        aprText?.text = String.format("%.2f%%", apr)

        if (stakedBalance > 0) {
            stakedAmountText?.text = String.format("%,.0f ERTH", stakedBalance)
        } else {
            stakedAmountText?.text = "0 ERTH"
        }

        // Update staked USD value
        erthPrice?.let { price ->
            val stakedUsd = stakedBalance * price
            stakedAmountUsd?.text = ErthPriceService.formatUSD(stakedUsd)
        } ?: run {
            stakedAmountUsd?.text = ""
        }

        totalStakedText?.text = String.format("%,.0f ERTH", totalStakedBalance)

        // Update total staked USD value
        erthPrice?.let { price ->
            val totalUsd = totalStakedBalance * price
            totalStakedUsd?.text = ErthPriceService.formatUSD(totalUsd)
        } ?: run {
            totalStakedUsd?.text = ""
        }

        // Calculate pool share percentage
        val poolShare = if (totalStakedBalance > 0) (stakedBalance / totalStakedBalance) * 100 else 0.0
        poolShareText?.text = String.format("%.4f%%", poolShare)

        // Calculate estimated daily earnings based on APR
        val dailyEarnings = (stakedBalance * apr / 100) / 365
        dailyEarningsText?.text = String.format("%.2f ERTH", dailyEarnings)

        // Update daily earnings USD value
        erthPrice?.let { price ->
            val dailyUsd = dailyEarnings * price
            dailyEarningsUsd?.text = ErthPriceService.formatUSD(dailyUsd)
        } ?: run {
            dailyEarningsUsd?.text = ""
        }
    }

    /**
     * Query user staking info from contract (for child fragments)
     */
    fun queryUserStakingInfo(callback: UserStakingCallback?) {
        lifecycleScope.launch {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress.isNullOrEmpty()) {
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

                val dataObj = if (result.has("data")) {
                    result.getJSONObject("data")
                } else {
                    result
                }

                if (callback != null && activity != null) {
                    activity?.runOnUiThread { callback.onStakingDataReceived(dataObj) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying staking info", e)
                if (callback != null && activity != null) {
                    activity?.runOnUiThread { callback.onError(e.message ?: "Unknown error") }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStakingData()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: Exception) { }
        }
    }

    /**
     * Callback interface for staking data queries
     */
    interface UserStakingCallback {
        fun onStakingDataReceived(data: JSONObject)
        fun onError(error: String)
    }
}
