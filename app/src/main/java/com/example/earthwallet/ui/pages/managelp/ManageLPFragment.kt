package com.example.earthwallet.ui.pages.managelp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.earthwallet.R
import com.example.earthwallet.Constants
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.ui.components.LoadingOverlay
import com.example.earthwallet.wallet.constants.Tokens
import com.example.earthwallet.wallet.services.SecureWalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main fragment for managing liquidity pools
 * Displays pool overviews and manages LP functionality
 */
class ManageLPFragment : Fragment() {

    companion object {
        private const val TAG = "ManageLPFragment"
        private const val REQ_CLAIM_INDIVIDUAL = 5001
        private const val REQ_CLAIM_ALL = 5002

        @JvmStatic
        fun newInstance(): ManageLPFragment = ManageLPFragment()
    }

    private lateinit var poolsRecyclerView: RecyclerView
    private lateinit var poolAdapter: PoolOverviewAdapter
    private lateinit var totalRewardsText: TextView
    private lateinit var claimAllButton: Button
    private lateinit var claimAllContainer: LinearLayout
    private lateinit var liquidityManagementContainer: View
    private var rootView: View? = null
    private var loadingOverlay: LoadingOverlay? = null

    private var queryService: SecretQueryService? = null
    private var executorService: ExecutorService? = null

    private var isManagingLiquidity = false
    private var currentPoolData: Any? = null

    // Mock data - replace with actual pool data
    private val allPoolsData = mutableListOf<PoolData>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "Creating ManageLP view")
        rootView = inflater.inflate(R.layout.fragment_manage_lp, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize services
        queryService = SecretQueryService(requireContext())
        executorService = Executors.newCachedThreadPool()

        initializeViews(view)
        setupRecyclerView()
        setupClaimAllButton()

        // Set initial title background
        updateTitleBackground()

        // Load initial data
        refreshPoolData()
    }

    private fun initializeViews(view: View) {
        poolsRecyclerView = view.findViewById(R.id.pools_recycler_view)
        totalRewardsText = view.findViewById(R.id.total_rewards_text)
        claimAllButton = view.findViewById(R.id.claim_all_button)
        claimAllContainer = view.findViewById(R.id.claim_all_container)
        liquidityManagementContainer = view.findViewById(R.id.liquidity_management_container)
        loadingOverlay = view.findViewById(R.id.loading_overlay)

        // Initialize the loading overlay with this fragment for Glide
        loadingOverlay?.initializeWithFragment(this)
    }

    private fun setupRecyclerView() {
        poolAdapter = PoolOverviewAdapter(allPoolsData, object : PoolOverviewAdapter.PoolClickListener {
            override fun onManageClicked(poolData: PoolData) {
                toggleManageLiquidity(poolData)
            }

            override fun onClaimClicked(poolData: PoolData) {
                handleClaimRewards(poolData)
            }
        })

        poolsRecyclerView.layoutManager = LinearLayoutManager(context)
        poolsRecyclerView.adapter = poolAdapter
    }

    private fun setupClaimAllButton() {
        claimAllButton.setOnClickListener { handleClaimAll() }
    }

    private fun refreshPoolData() {
        Log.d(TAG, "Refreshing pool data with real contract queries")

        // Show loading overlay
        showLoading(true)

        // Query exchange contract like React app does
        executorService?.execute {
            try {
                queryExchangeContract()
            } catch (e: Exception) {
                Log.e(TAG, "Error querying pool data", e)
                // Fall back to empty data on error
                activity?.runOnUiThread {
                    showLoading(false)
                    allPoolsData.clear()
                    updateTotalRewards()
                    poolAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay?.let { overlay ->
            if (show) {
                overlay.show()
            } else {
                overlay.hide()
            }
        }
    }

    private fun queryExchangeContract() {
        Log.d(TAG, "Querying exchange contract for pool data")

        // Get all token contracts except ERTH (like React app)
        val poolContracts = mutableListOf<String>()
        val tokenKeys = mutableListOf<String>()

        for (symbol in Tokens.getAllTokens().keys) {
            if (symbol != "ERTH") {
                val token = Tokens.getTokenInfo(symbol)
                if (token != null) {
                    poolContracts.add(token.contract)
                    tokenKeys.add(symbol)
                    Log.d(TAG, "Added pool contract: $symbol -> ${token.contract}")
                }
            }
        }

        if (poolContracts.isEmpty()) {
            Log.w(TAG, "No pool contracts found, loading empty data")
            // Update UI with empty data
            activity?.runOnUiThread {
                allPoolsData.clear()
                updateTotalRewards()
                poolAdapter.notifyDataSetChanged()
            }
            return
        }

        // Get actual user address
        val userAddress: String
        try {
            userAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
            Log.d(TAG, "User address: $userAddress")

            if (userAddress.isEmpty()) {
                Log.w(TAG, "No user address available, cannot query pools")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting wallet address", e)
            throw e
        }

        // Create query message like React app: { query_user_info: { pools, user: address } }
        val queryMsg = JSONObject()
        val queryUserInfo = JSONObject()
        queryUserInfo.put("pools", JSONArray(poolContracts))
        queryUserInfo.put("user", userAddress)
        queryMsg.put("query_user_info", queryUserInfo)

        Log.d(TAG, "Exchange contract: ${Constants.EXCHANGE_CONTRACT}")
        Log.d(TAG, "Exchange hash: ${Constants.EXCHANGE_HASH}")
        Log.d(TAG, "Query message: $queryMsg")

        // Query the exchange contract
        val result = queryService!!.queryContract(
            Constants.EXCHANGE_CONTRACT,
            Constants.EXCHANGE_HASH,
            queryMsg
        )

        Log.d(TAG, "Query result: $result")

        // Process the results
        processPoolQueryResults(result, tokenKeys)
    }

    private fun processPoolQueryResults(result: JSONObject, tokenKeys: List<String>) {
        Log.d(TAG, "Processing pool query results")

        val newPoolData = mutableListOf<PoolData>()

        // Handle the decryption_error case where data is embedded in error message
        if (result.has("error") && result.has("decryption_error")) {
            val decryptionError = result.getString("decryption_error")
            Log.d(TAG, "Processing decryption_error for unbonding shares")

            // Look for base64-decoded JSON in the error message
            val jsonMarker = "base64=Value "
            val jsonIndex = decryptionError.indexOf(jsonMarker)
            if (jsonIndex != -1) {
                val startIndex = jsonIndex + jsonMarker.length
                val endIndex = decryptionError.indexOf(" of type org.json.JSONArray", startIndex)
                if (endIndex != -1) {
                    val jsonArrayString = decryptionError.substring(startIndex, endIndex)
                    try {
                        val poolResults = JSONArray(jsonArrayString)
                        processPoolArray(poolResults, tokenKeys, newPoolData)
                        updatePoolDataOnUI(newPoolData)
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing JSON from decryption_error", e)
                    }
                }
            }
        }

        // The result should be an array of pool data
        if (result.has("data") && result.get("data") is JSONArray) {
            val poolResults = result.getJSONArray("data")
            processPoolArray(poolResults, tokenKeys, newPoolData)
            updatePoolDataOnUI(newPoolData)
        } else {
            Log.w(TAG, "No valid pool data found in result")
            updatePoolDataOnUI(newPoolData)
        }
    }

    private fun processPoolArray(poolResults: JSONArray, tokenKeys: List<String>, newPoolData: MutableList<PoolData>) {
        for (i in 0 until minOf(poolResults.length(), tokenKeys.size)) {
            val tokenKey = tokenKeys[i]
            val poolInfo = poolResults.getJSONObject(i)
            val tokenInfo = Tokens.getTokenInfo(tokenKey)

            // Extract pool data like React app does
            var pendingRewards = "0.0"
            var liquidity = "0.0"
            var volume = "0.0"
            var apr = "0.0%"
            var unbondingShares = "0.0"

            // Parse user_info for rewards
            if (poolInfo.has("user_info") && !poolInfo.isNull("user_info")) {
                val userInfo = poolInfo.getJSONObject("user_info")
                if (userInfo.has("pending_rewards")) {
                    val rewardsMicro = userInfo.getLong("pending_rewards")
                    val rewardsMacro = rewardsMicro / 1000000.0 // Convert from micro to macro
                    pendingRewards = String.format("%.1f", rewardsMacro)
                }
            }

            // Parse pool_info for liquidity, volume, APR, and unbonding shares
            if (poolInfo.has("pool_info") && !poolInfo.isNull("pool_info")) {
                val poolState = poolInfo.getJSONObject("pool_info")
                if (poolState.has("state") && !poolState.isNull("state")) {
                    val state = poolState.getJSONObject("state")

                    // Extract unbonding shares for this user
                    if (state.has("unbonding_shares")) {
                        val unbondingSharesMicro = state.getLong("unbonding_shares")
                        val unbondingSharesMacro = unbondingSharesMicro / 1000000.0
                        unbondingShares = String.format("%.2f", unbondingSharesMacro)
                        Log.d(TAG, "$tokenKey unbonding shares: $unbondingShares")
                    }

                    // Calculate liquidity (2 * ERTH reserve)
                    if (state.has("erth_reserve")) {
                        val erthReserveMicro = state.getLong("erth_reserve")
                        val erthReserveMacro = erthReserveMicro / 1000000.0
                        val totalLiquidity = 2 * erthReserveMacro
                        liquidity = String.format("%.0f", totalLiquidity)
                    }

                    // Calculate volume (sum of last 7 days)
                    if (state.has("daily_volumes")) {
                        val volumes = state.getJSONArray("daily_volumes")
                        var totalVolumeMicro = 0L
                        for (v in 0 until minOf(7, volumes.length())) {
                            totalVolumeMicro += volumes.getLong(v)
                        }
                        val totalVolumeMacro = totalVolumeMicro / 1000000.0
                        volume = String.format("%.0f", totalVolumeMacro)
                    }

                    // Calculate APR (weekly rewards / liquidity * 52)
                    if (state.has("daily_rewards")) {
                        val rewards = state.getJSONArray("daily_rewards")
                        var weeklyRewardsMicro = 0L
                        for (r in 0 until minOf(7, rewards.length())) {
                            weeklyRewardsMicro += rewards.getLong(r)
                        }
                        val weeklyRewardsMacro = weeklyRewardsMicro / 1000000.0
                        val liquidityValue = liquidity.replace(",", "").toDouble()
                        if (liquidityValue > 0) {
                            val aprValue = (weeklyRewardsMacro / liquidityValue) * 52 * 100
                            apr = String.format("%.2f%%", aprValue)
                        }
                    }
                }
            }

            newPoolData.add(PoolData(tokenKey, pendingRewards, liquidity, volume, apr, unbondingShares, tokenInfo))
        }
    }

    private fun updatePoolDataOnUI(newPoolData: List<PoolData>) {
        // Update UI on main thread
        activity?.runOnUiThread {
            showLoading(false)
            allPoolsData.clear()
            allPoolsData.addAll(newPoolData)
            updateTotalRewards()
            poolAdapter.notifyDataSetChanged()
            Log.d(TAG, "Updated UI with ${newPoolData.size} pools")
        }
    }

    private fun updateTotalRewards() {
        var totalRewards = 0.0

        for (pool in allPoolsData) {
            try {
                val rewards = pool.pendingRewards.replace(",", "").toDouble()
                totalRewards += rewards
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Error parsing rewards for pool: ${pool.tokenKey}")
            }
        }

        if (totalRewards > 0) {
            totalRewardsText.text = String.format("Total Rewards: %.0f ERTH", totalRewards)
            claimAllContainer.visibility = View.VISIBLE
        } else {
            claimAllContainer.visibility = View.GONE
        }
    }

    fun toggleManageLiquidity(poolData: PoolData?) {
        if (poolData != null) {
            Log.d(TAG, "Toggle manage liquidity for: ${poolData.tokenKey}")
        }

        if (isManagingLiquidity) {
            // Return to pool overview
            isManagingLiquidity = false
            currentPoolData = null
            showPoolOverview()
        } else if (poolData != null) {
            // Show liquidity management
            isManagingLiquidity = true
            currentPoolData = poolData
            showLiquidityManagement(poolData)
        }
    }

    private fun showPoolOverview() {
        // Show the RecyclerView with pool overviews
        poolsRecyclerView.visibility = View.VISIBLE

        // Hide liquidity management
        liquidityManagementContainer.visibility = View.GONE

        updateTotalRewards()
        updateTitleBackground()
    }

    private fun showLiquidityManagement(poolData: PoolData) {
        // Hide pool overview and show liquidity management
        poolsRecyclerView.visibility = View.GONE
        claimAllContainer.visibility = View.GONE

        // Show liquidity management component
        liquidityManagementContainer.visibility = View.VISIBLE

        // Create and add the LiquidityManagementComponent
        val liquidityComponent = LiquidityManagementComponent.newInstance(poolData)

        childFragmentManager.beginTransaction()
            .replace(R.id.liquidity_management_container, liquidityComponent)
            .commit()

        Log.d(TAG, "Showing liquidity management for: ${poolData.tokenKey}")

        updateTitleBackground()
    }

    private fun updateTitleBackground() {
        rootView?.let { root ->
            if (isManagingLiquidity) {
                // White background for manage liquidity
                root.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            } else {
                // Off-white background for pool overview
                root.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.desktop_bg))
            }
        }
    }

    private fun handleClaimRewards(poolData: PoolData) {
        Log.d(TAG, "Claiming rewards for pool: ${poolData.tokenKey}")

        try {
            // Get token contract for this pool
            val tokenInfo = poolData.tokenInfo
            if (tokenInfo == null) {
                Log.e(TAG, "No token info available for: ${poolData.tokenKey}")
                return
            }

            // Create claim message: { claim_rewards: { pools: [contract] } }
            val claimMsg = JSONObject()
            val claimRewards = JSONObject()
            val pools = JSONArray()
            pools.put(tokenInfo.contract)
            claimRewards.put("pools", pools)
            claimMsg.put("claim_rewards", claimRewards)

            Log.d(TAG, "Claiming rewards for pool ${poolData.tokenKey} with message: $claimMsg")

            // Use SecretExecuteActivity for claiming rewards
            val intent = Intent(activity, TransactionActivity::class.java)
            intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE)
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.EXCHANGE_CONTRACT)
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.EXCHANGE_HASH)
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, claimMsg.toString())

            startActivityForResult(intent, REQ_CLAIM_INDIVIDUAL)

        } catch (e: Exception) {
            Log.e(TAG, "Error claiming rewards for pool: ${poolData.tokenKey}", e)
        }
    }

    private fun handleClaimAll() {
        Log.d(TAG, "Claiming all rewards")

        try {
            // Collect all pools with rewards > 0
            val poolsWithRewards = JSONArray()
            for (pool in allPoolsData) {
                try {
                    val rewards = pool.pendingRewards.replace(",", "").toDouble()
                    if (rewards > 0 && pool.tokenInfo != null) {
                        poolsWithRewards.put(pool.tokenInfo!!.contract)
                        Log.d(TAG, "Adding pool ${pool.tokenKey} to claim all (rewards: $rewards)")
                    }
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Error parsing rewards for pool: ${pool.tokenKey}")
                }
            }

            if (poolsWithRewards.length() == 0) {
                Log.w(TAG, "No pools with rewards to claim")
                return
            }

            // Create claim message: { claim_rewards: { pools: [contracts...] } }
            val claimMsg = JSONObject()
            val claimRewards = JSONObject()
            claimRewards.put("pools", poolsWithRewards)
            claimMsg.put("claim_rewards", claimRewards)

            Log.d(TAG, "Claiming all rewards with message: $claimMsg")

            // Use SecretExecuteActivity for claiming all rewards
            val intent = Intent(activity, TransactionActivity::class.java)
            intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE)
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.EXCHANGE_CONTRACT)
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.EXCHANGE_HASH)
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, claimMsg.toString())

            startActivityForResult(intent, REQ_CLAIM_ALL)

        } catch (e: Exception) {
            Log.e(TAG, "Error claiming all rewards", e)
        }
    }

    // Data class for pool information
    data class PoolData(
        val tokenKey: String,
        var pendingRewards: String,
        var liquidity: String,
        var volume: String,
        var apr: String,
        var unbondingShares: String,
        val tokenInfo: Tokens.TokenInfo?
    )

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_CLAIM_INDIVIDUAL || requestCode == REQ_CLAIM_ALL) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "Claim transaction completed successfully")
                // Refresh pool data and UI to reflect new balances
                refreshPoolData()
                // Also refresh the pool overview display immediately
                activity?.runOnUiThread {
                    updateTotalRewards()
                    poolAdapter.notifyDataSetChanged()
                }
            } else {
                val error = data?.getStringExtra("error") ?: "Unknown error"
                Log.e(TAG, "Claim transaction failed: $error")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executorService != null && !executorService!!.isShutdown) {
            executorService!!.shutdown()
        }
        if (loadingOverlay != null && context != null) {
            loadingOverlay!!.cleanup(context)
        }
    }
}