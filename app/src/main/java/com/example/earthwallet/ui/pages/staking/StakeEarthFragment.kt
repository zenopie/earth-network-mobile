package com.example.earthwallet.ui.pages.staking

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.earthwallet.R
import com.example.earthwallet.Constants
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main staking fragment that manages ERTH token staking
 * Features tabs for Info & Rewards, Stake/Unstake, and Unbonding
 */
class StakeEarthFragment : Fragment() {

    companion object {
        private const val TAG = "StakeEarthFragment"
    }

    // UI Components
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var rootView: View? = null

    // Data and Services
    private var queryService: SecretQueryService? = null
    private var executorService: ExecutorService? = null

    // Adapter
    private var stakingAdapter: StakingTabsAdapter? = null

    // Current tab for styling
    private var currentTab = 0

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
            else -> {
                Log.w(TAG, "Parent does not implement StakeEarthListener")
                null
            }
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

        // Initialize services
        queryService = SecretQueryService(context)
        executorService = Executors.newCachedThreadPool()

        initializeViews(view)
        setupTabs()
    }

    private fun initializeViews(view: View) {
        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
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
                0 -> "Info & Rewards"
                1 -> "Stake/Unstake"
                2 -> "Unbonding"
                else -> "Tab $position"
            }
        }.attach()
    }

    /**
     * Public method to refresh staking data across all fragments
     */
    fun refreshStakingData() {
        Log.d(TAG, "Refreshing staking data for all fragments")

        // With ViewPager2, we need to notify fragments differently
        stakingAdapter?.notifyDataSetChanged()
    }

    /**
     * Query user staking info from contract
     */
    fun queryUserStakingInfo(callback: UserStakingCallback?) {
        executorService?.execute {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(context)
                if (userAddress.isNullOrEmpty()) {
                    Log.w(TAG, "No user address available")
                    return@execute
                }

                // Create query message: { get_user_info: { address: "secret1..." } }
                val queryMsg = JSONObject()
                val getUserInfo = JSONObject()
                getUserInfo.put("address", userAddress)
                queryMsg.put("get_user_info", getUserInfo)

                Log.d(TAG, "Querying staking contract: ${Constants.STAKING_CONTRACT}")
                Log.d(TAG, "Query message: ${queryMsg}")

                val result = queryService?.queryContract(
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH,
                    queryMsg
                )

                Log.d(TAG, "Staking query result: $result")

                if (callback != null && activity != null && result != null) {
                    activity?.runOnUiThread { callback.onStakingDataReceived(result) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying staking info", e)
                if (callback != null && activity != null) {
                    activity?.runOnUiThread { callback.onError(e.message ?: "Unknown error") }
                }
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService?.takeIf { !it.isShutdown }?.shutdown()
    }

    /**
     * Callback interface for staking data queries
     */
    interface UserStakingCallback {
        fun onStakingDataReceived(data: JSONObject)
        fun onError(error: String)
    }
}