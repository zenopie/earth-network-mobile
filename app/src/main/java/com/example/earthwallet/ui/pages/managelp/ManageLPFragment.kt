package network.erth.wallet.ui.pages.managelp

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.ui.components.LoadingOverlay
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONArray
import org.json.JSONException
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

    private var executorService: ExecutorService? = null

    private var isManagingLiquidity = false
    private var currentPoolData: Any? = null

    // Mock data - replace with actual pool data
    private val allPoolsData = mutableListOf<PoolData>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        rootView = inflater.inflate(R.layout.fragment_manage_lp, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize services
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

        // Show loading overlay
        showLoading(true)

        // Query exchange contract like React app does
        lifecycleScope.launch {
            try {
                queryExchangeContract()
            } catch (e: Exception) {
                Log.e(TAG, "Error querying pool data", e)
                // Fall back to empty data on error
                showLoading(false)
                allPoolsData.clear()
                updateTotalRewards()
                poolAdapter.notifyDataSetChanged()
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

        // Get all token contracts except ERTH (like React app)
        val poolContracts = mutableListOf<String>()
        val tokenKeys = mutableListOf<String>()

        for (symbol in Tokens.getAllTokens().keys) {
            if (symbol != "ERTH") {
                val token = Tokens.getTokenInfo(symbol)
                if (token != null) {
                    poolContracts.add(token.contract)
                    tokenKeys.add(symbol)
                }
            }
        }

        if (poolContracts.isEmpty()) {
            // Update UI with empty data
            allPoolsData.clear()
            updateTotalRewards()
            poolAdapter.notifyDataSetChanged()
            return
        }

        // Get actual user address
        val userAddress: String
        try {
            userAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""

            if (userAddress.isEmpty()) {
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting wallet address", e)
            throw e
        }

        // Create query JSON
        val poolsArrayJson = poolContracts.joinToString("\",\"", "[\"", "\"]")
        val queryJson = "{\"query_user_info\": {\"pools\": $poolsArrayJson, \"user\": \"$userAddress\"}}"

        // Query the exchange contract
        lifecycleScope.launch {
            try {
                val responseString = SecretKClient.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    queryJson,
                    Constants.EXCHANGE_HASH
                )

                // Try to parse as JSONArray first (this query returns an array)
                try {
                    val poolResults = JSONArray(responseString)
                    val newPoolData = mutableListOf<PoolData>()
                    processPoolArray(poolResults, tokenKeys, newPoolData)
                    updatePoolDataOnUI(newPoolData)
                } catch (e: JSONException) {
                    // If not an array, try as JSONObject (for error handling)
                    val result = JSONObject(responseString)
                    processPoolQueryResults(result, tokenKeys)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query pool data", e)
                Toast.makeText(context, "Failed to load pool data: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processPoolQueryResultsWrapper(result: JSONObject, tokenKeys: List<String>) {


        // Process the results
        processPoolQueryResults(result, tokenKeys)
    }

    private fun processPoolQueryResults(result: JSONObject, tokenKeys: List<String>) {

        val newPoolData = mutableListOf<PoolData>()

        // Handle the decryption_error case where data is embedded in error message
        if (result.has("error") && result.has("decryption_error")) {
            val decryptionError = result.getString("decryption_error")

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
        }
    }

    private fun updateTotalRewards() {
        var totalRewards = 0.0

        for (pool in allPoolsData) {
            try {
                val rewards = pool.pendingRewards.replace(",", "").toDouble()
                totalRewards += rewards
            } catch (e: NumberFormatException) {
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
        lifecycleScope.launch {
            try {
                // Get token contract for this pool
                val tokenInfo = poolData.tokenInfo
                if (tokenInfo == null) {
                    Log.e(TAG, "No token info available for: ${poolData.tokenKey}")
                    return@launch
                }

                // Create claim message: { claim_rewards: { pools: [contract] } }
                val claimMsg = JSONObject()
                val claimRewards = JSONObject()
                val pools = JSONArray()
                pools.put(tokenInfo.contract)
                claimRewards.put("pools", pools)
                claimMsg.put("claim_rewards", claimRewards)

                val result = TransactionExecutor.executeContract(
                    fragment = this@ManageLPFragment,
                    contractAddress = Constants.EXCHANGE_CONTRACT,
                    message = claimMsg,
                    codeHash = Constants.EXCHANGE_HASH,
                    contractLabel = "Exchange Contract:"
                )

                result.onSuccess {
                    // Refresh pool data
                    refreshPoolData()
                    activity?.runOnUiThread {
                        updateTotalRewards()
                        poolAdapter.notifyDataSetChanged()
                    }
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Log.e(TAG, "Claim failed: ${error.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error claiming rewards for pool: ${poolData.tokenKey}", e)
            }
        }
    }

    private fun handleClaimAll() {
        lifecycleScope.launch {
            try {
                // Collect all pools with rewards > 0
                val poolsWithRewards = JSONArray()
                for (pool in allPoolsData) {
                    try {
                        val rewards = pool.pendingRewards.replace(",", "").toDouble()
                        if (rewards > 0 && pool.tokenInfo != null) {
                            poolsWithRewards.put(pool.tokenInfo!!.contract)
                        }
                    } catch (e: NumberFormatException) {
                    }
                }

                if (poolsWithRewards.length() == 0) {
                    return@launch
                }

                // Create claim message: { claim_rewards: { pools: [contracts...] } }
                val claimMsg = JSONObject()
                val claimRewards = JSONObject()
                claimRewards.put("pools", poolsWithRewards)
                claimMsg.put("claim_rewards", claimRewards)

                val result = TransactionExecutor.executeContract(
                    fragment = this@ManageLPFragment,
                    contractAddress = Constants.EXCHANGE_CONTRACT,
                    message = claimMsg,
                    codeHash = Constants.EXCHANGE_HASH,
                    contractLabel = "Exchange Contract:"
                )

                result.onSuccess {
                    // Refresh pool data
                    refreshPoolData()
                    activity?.runOnUiThread {
                        updateTotalRewards()
                        poolAdapter.notifyDataSetChanged()
                    }
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Log.e(TAG, "Claim failed: ${error.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error claiming all rewards", e)
            }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // No longer needed - transactions handled by TransactionExecutor
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