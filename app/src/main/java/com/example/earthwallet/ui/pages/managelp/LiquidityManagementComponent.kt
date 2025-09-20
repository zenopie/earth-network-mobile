package com.example.earthwallet.ui.pages.managelp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.earthwallet.R
import com.example.earthwallet.Constants
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.DecimalFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Component for managing liquidity operations (Add/Remove/Unbond)
 * Displays in tabs: Info, Add, Remove, Unbond
 */
class LiquidityManagementComponent : Fragment() {

    // Centralized data structure for all tab information
    class LiquidityData {
        // Pool info
        var totalShares = 0.0
        var userStakedShares = 0.0
        var poolOwnershipPercent = 0.0
        var unbondingPercent = 0.0
        var userErthValue = 0.0
        var userTokenValue = 0.0

        // Balances for Add tab
        var tokenBalance = 0.0
        var erthBalance = 0.0
        var erthReserve = 0.0
        var tokenReserve = 0.0

        // Unbonding data
        var unbondingRequests: MutableList<UnbondingRequest> = mutableListOf()

        class UnbondingRequest {
            var shares: Double = 0.0
            var timeRemaining: String = ""
            var erthValue: Double = 0.0
            var tokenValue: Double = 0.0
        }
    }

    companion object {
        private const val TAG = "LiquidityManagement"
        private const val ARG_TOKEN_KEY = "token_key"
        private const val ARG_PENDING_REWARDS = "pending_rewards"
        private const val ARG_LIQUIDITY = "liquidity"
        private const val ARG_VOLUME = "volume"
        private const val ARG_APR = "apr"

        @JvmStatic
        fun newInstance(poolData: ManageLPFragment.PoolData): LiquidityManagementComponent {
            val fragment = LiquidityManagementComponent()
            val args = Bundle()
            args.putString(ARG_TOKEN_KEY, poolData.tokenKey)
            args.putString(ARG_PENDING_REWARDS, poolData.pendingRewards)
            args.putString(ARG_LIQUIDITY, poolData.liquidity)
            args.putString(ARG_VOLUME, poolData.volume)
            args.putString(ARG_APR, poolData.apr)
            fragment.arguments = args
            return fragment
        }
    }

    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var closeButton: LinearLayout? = null
    private var titleText: TextView? = null
    private var poolTokenLogo: ImageView? = null

    // Current tab views
    private var infoTabView: View? = null
    private var addTabView: View? = null
    private var removeTabView: View? = null
    private var unbondTabView: View? = null

    // Add liquidity fields
    private var erthAmountInput: EditText? = null
    private var tokenAmountInput: EditText? = null
    private var erthBalanceText: TextView? = null
    private var tokenBalanceText: TextView? = null
    private var addLiquidityButton: Button? = null

    // Remove liquidity fields
    private var removeAmountInput: EditText? = null
    private var stakedSharesText: TextView? = null
    private var removeLiquidityButton: Button? = null

    // Info tab fields
    private var totalSharesText: TextView? = null
    private var userSharesText: TextView? = null
    private var poolOwnershipText: TextView? = null
    private var unbondingPercentText: TextView? = null
    private var erthValueText: TextView? = null
    private var tokenValueText: TextView? = null
    private var tokenValueLabel: TextView? = null

    // Pool data
    private var tokenKey: String? = null
    private var pendingRewards: String? = null
    private var liquidity: String? = null
    private var volume: String? = null
    private var apr: String? = null

    // Detailed pool state (from contract queries)
    private var poolState: JSONObject? = null
    private var userInfo: JSONObject? = null
    private var queryService: SecretQueryService? = null
    private var executorService: ExecutorService? = null

    // Centralized data for all tabs
    private var liquidityData: LiquidityData? = null
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.component_liquidity_management, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Extract arguments FIRST before setting up tabs
        arguments?.let { args ->
            tokenKey = args.getString(ARG_TOKEN_KEY)
            pendingRewards = args.getString(ARG_PENDING_REWARDS)
            liquidity = args.getString(ARG_LIQUIDITY)
            volume = args.getString(ARG_VOLUME)
            apr = args.getString(ARG_APR)

            Log.d(TAG, "Managing liquidity for token: $tokenKey")
        }

        initializeViews(view)
        setupTabs()  // Now tokenKey is available
        setupCloseButton()
        setupBroadcastReceiver()
        registerBroadcastReceiver()

        // Initialize services and data
        queryService = SecretQueryService(requireContext())
        executorService = Executors.newCachedThreadPool()
        liquidityData = LiquidityData()

        tokenKey?.let { token ->
            // Update pool title and logo
            val poolTitle = view.findViewById<TextView>(R.id.pool_title)
            val poolLogo = view.findViewById<ImageView>(R.id.pool_token_logo)

            poolTitle?.text = "$token Pool"
            poolLogo?.let { setTokenLogo(it, token) }

            // Load all liquidity data initially
            loadAllLiquidityData()
        }
    }

    private fun initializeViews(view: View) {
        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
        closeButton = view.findViewById(R.id.close_button)
        titleText = view.findViewById(R.id.title_text)
        poolTokenLogo = view.findViewById(R.id.pool_token_logo)
    }

    private fun setupTabs() {
        if (tabLayout == null || viewPager == null) return

        // Create simplified adapter
        val adapter = LiquidityTabsAdapter(this, tokenKey ?: "")

        viewPager!!.adapter = adapter

        TabLayoutMediator(tabLayout!!, viewPager!!) { tab, position ->
            when (position) {
                0 -> tab.text = "Info"
                1 -> tab.text = "Add"
                2 -> tab.text = "Remove"
                3 -> tab.text = "Unbond"
            }
        }.attach()

        // Listen for tab changes to refresh data when Info tab becomes visible
        viewPager!!.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                Log.d(TAG, "ViewPager page changed to position: $position")

                // When Info tab (position 0) is selected, refresh data
                if (position == 0) {
                    Log.d(TAG, "Info tab selected - refreshing data")
                    loadAllLiquidityData()
                }
            }
        })
    }

    private fun setupCloseButton() {
        closeButton?.setOnClickListener {
            Log.d(TAG, "Closing liquidity management")
            // Return to pool overview
            if (parentFragment is ManageLPFragment) {
                (parentFragment as ManageLPFragment).toggleManageLiquidity(null)
            }
        }
    }

    // Tab content creation methods
    fun createInfoTab(container: ViewGroup): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.tab_liquidity_info, container, false)

        initializeInfoViews(view)
        updateInfoTab()

        return view
    }

    fun createAddTab(container: ViewGroup): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.tab_liquidity_add, container, false)

        initializeAddViews(view)
        setupAddTabListeners()

        return view
    }

    fun createRemoveTab(container: ViewGroup): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.tab_liquidity_remove, container, false)

        initializeRemoveViews(view)
        setupRemoveTabListeners()

        return view
    }

    fun createUnbondTab(container: ViewGroup): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.tab_liquidity_unbond, container, false)

        // TODO: Initialize unbond views and setup listeners

        return view
    }

    private fun initializeInfoViews(view: View) {
        totalSharesText = view.findViewById(R.id.total_shares_text)
        userSharesText = view.findViewById(R.id.user_shares_text)
        poolOwnershipText = view.findViewById(R.id.pool_ownership_text)
        unbondingPercentText = view.findViewById(R.id.unbonding_percent_text)
        erthValueText = view.findViewById(R.id.erth_value_text)
        tokenValueText = view.findViewById(R.id.token_value_text)
        tokenValueLabel = view.findViewById(R.id.token_value_label)

        // Set the token label
        if (tokenValueLabel != null && tokenKey != null) {
            tokenValueLabel!!.text = "$tokenKey:"
        }
    }

    private fun initializeAddViews(view: View) {
        erthAmountInput = view.findViewById(R.id.erth_amount_input)
        tokenAmountInput = view.findViewById(R.id.token_amount_input)
        erthBalanceText = view.findViewById(R.id.erth_balance_text)
        tokenBalanceText = view.findViewById(R.id.token_balance_text)
        addLiquidityButton = view.findViewById(R.id.add_liquidity_button)
    }

    private fun initializeRemoveViews(view: View) {
        removeAmountInput = view.findViewById(R.id.remove_amount_input)
        stakedSharesText = view.findViewById(R.id.staked_shares_text)
        removeLiquidityButton = view.findViewById(R.id.remove_liquidity_button)
    }

    private fun queryPoolState() {
        if (tokenKey == null) return

        executorService?.execute {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress == null) {
                    Log.w(TAG, "No user address available")
                    return@execute
                }

                // Get token contract address based on tokenKey
                val tokenContract = getTokenContract(tokenKey!!)
                if (tokenContract == null) {
                    Log.w(TAG, "No contract found for token: $tokenKey")
                    return@execute
                }

                // Query like React app: query_user_info with pools array
                val queryMsg = JSONObject()
                val queryUserInfo = JSONObject()
                val poolsArray = JSONArray()
                poolsArray.put(tokenContract)
                queryUserInfo.put("pools", poolsArray)
                queryUserInfo.put("user", userAddress)
                queryMsg.put("query_user_info", queryUserInfo)

                Log.d(TAG, "Querying detailed pool state for: $tokenKey")
                val result = queryService!!.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                )

                activity?.runOnUiThread {
                    processPoolStateResult(result)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying pool state", e)
            }
        }
    }

    private fun processPoolStateResult(result: JSONObject) {
        try {
            if (result.has("data")) {
                val dataArray = result.getJSONArray("data")
                if (dataArray.length() > 0) {
                    val poolData = dataArray.getJSONObject(0)

                    // Store the detailed pool state
                    if (poolData.has("pool_info")) {
                        poolState = poolData.getJSONObject("pool_info")
                    }
                    if (poolData.has("user_info")) {
                        userInfo = poolData.getJSONObject("user_info")
                    }

                    // Update the Info tab with real data
                    updateInfoTab()

                    // Don't recreate tabs - just update the existing adapter
                    Log.d(TAG, "Pool state loaded, updating existing tabs with pool information")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing pool state result", e)
        }
    }

    private fun getTokenContract(tokenSymbol: String): String? {
        // Map token symbols to contract addresses (from React tokens.js)
        return when (tokenSymbol.uppercase()) {
            "ANML" -> "secret14p6dhjznntlzw0yysl7p6z069nk0skv5e9qjut"
            "SSCRT" -> "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek"
            else -> null
        }
    }

    private fun updateInfoTab() {
        if (poolState == null || userInfo == null) {
            // Fallback to basic data if detailed state not available
            totalSharesText?.text = "Total Liquidity: ${formatNumber(liquidity)} ERTH"
            userSharesText?.text = "Pending Rewards: ${formatNumber(pendingRewards)} ERTH"
            poolOwnershipText?.text = "7d Volume: ${formatNumber(volume)} ERTH"
            unbondingPercentText?.text = "APR: $apr"
            return
        }

        try {
            // Extract data like React app does
            val state = poolState!!.optJSONObject("state")
            if (state != null) {
                // Total shares and user shares (converted from micro to macro units)
                val totalSharesMicro = state.optLong("total_shares", 0)
                val userStakedMicro = userInfo!!.optLong("amount_staked", 0)
                val unbondingSharesMicro = state.optLong("unbonding_shares", 0)

                val totalShares = totalSharesMicro / 1000000.0
                val userStaked = userStakedMicro / 1000000.0
                val unbondingShares = unbondingSharesMicro / 1000000.0

                // Pool ownership percentage
                val ownershipPercent = if (totalShares > 0) (userStaked / totalShares) * 100 else 0.0

                // Unbonding percentage
                val unbondingPercent = if (totalShares > 0) (unbondingShares / totalShares) * 100 else 0.0

                // Update UI with calculated values
                totalSharesText?.text = String.format("Total Pool Shares: %,.0f", totalShares)
                userSharesText?.text = String.format("Your Shares: %,.0f", userStaked)
                poolOwnershipText?.text = String.format("Pool Ownership: %.4f%%", ownershipPercent)
                unbondingPercentText?.text = String.format("%.4f%%", unbondingPercent)

                // Calculate underlying values like React app
                calculateUnderlyingValues(state, ownershipPercent)

                Log.d(TAG, "Updated Info tab with real pool state data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating info tab", e)
        }
    }

    private fun formatNumber(numberStr: String?): String {
        if (numberStr.isNullOrBlank()) {
            return "0"
        }

        return try {
            val number = numberStr.replace(",", "").toDouble()

            when {
                number == 0.0 -> "0"
                number >= 1000000 -> {
                    val formatter = DecimalFormat("#.#M")
                    formatter.format(number / 1000000)
                }
                number >= 1000 -> {
                    val formatter = DecimalFormat("#.#K")
                    formatter.format(number / 1000)
                }
                number >= 1 -> {
                    val formatter = DecimalFormat("#.#")
                    formatter.format(number)
                }
                else -> {
                    val formatter = DecimalFormat("#.###")
                    formatter.format(number)
                }
            }
        } catch (e: NumberFormatException) {
            numberStr
        }
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Received TRANSACTION_SUCCESS broadcast - refreshing all liquidity data")
                // Refresh all data when any transaction completes
                loadAllLiquidityData()
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

    // Centralized method to load all data for all tabs
    private fun loadAllLiquidityData() {
        if (tokenKey == null) return

        executorService?.execute {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress == null) {
                    Log.w(TAG, "No user address available")
                    return@execute
                }

                val tokenContract = getTokenContract(tokenKey!!)
                if (tokenContract == null) {
                    Log.w(TAG, "No contract found for token: $tokenKey")
                    return@execute
                }

                // Query all pool data in one call
                val queryMsg = JSONObject()
                val queryUserInfo = JSONObject()
                val poolsArray = JSONArray()
                poolsArray.put(tokenContract)
                queryUserInfo.put("pools", poolsArray)
                queryUserInfo.put("user", userAddress)
                queryMsg.put("query_user_info", queryUserInfo)

                Log.d(TAG, "Loading all liquidity data for: $tokenKey")
                val result = queryService!!.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                )

                activity?.runOnUiThread {
                    processAllLiquidityData(result)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading all liquidity data", e)
            }
        }
    }

    private fun processAllLiquidityData(result: JSONObject) {
        try {
            if (result.has("data")) {
                val dataArray = result.getJSONArray("data")
                if (dataArray.length() > 0) {
                    val poolData = dataArray.getJSONObject(0)

                    // Update pool state and user info
                    if (poolData.has("pool_info")) {
                        poolState = poolData.getJSONObject("pool_info")
                    }
                    if (poolData.has("user_info")) {
                        userInfo = poolData.getJSONObject("user_info")
                    }

                    // Process all the data into our centralized structure
                    updateLiquidityData()

                    Log.d(TAG, "Successfully processed all liquidity data")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing all liquidity data", e)
        }
    }

    private fun updateLiquidityData() {
        // Extract all data from poolState and userInfo into liquidityData
        if (poolState != null && userInfo != null && liquidityData != null) {
            try {
                val state = poolState!!.optJSONObject("state")
                if (state != null) {
                    // Pool info calculations
                    val totalSharesMicro = state.optLong("total_shares", 0)
                    val userStakedMicro = userInfo!!.optLong("amount_staked", 0)
                    val unbondingSharesMicro = state.optLong("unbonding_shares", 0)
                    val erthReserveMicro = state.optLong("erth_reserve", 0)
                    val tokenBReserveMicro = state.optLong("token_b_reserve", 0)

                    // Convert to macro units
                    liquidityData!!.totalShares = totalSharesMicro / 1000000.0
                    liquidityData!!.userStakedShares = userStakedMicro / 1000000.0
                    liquidityData!!.erthReserve = erthReserveMicro / 1000000.0
                    liquidityData!!.tokenReserve = tokenBReserveMicro / 1000000.0

                    // Calculate percentages
                    liquidityData!!.poolOwnershipPercent = if (liquidityData!!.totalShares > 0) {
                        (liquidityData!!.userStakedShares / liquidityData!!.totalShares) * 100
                    } else 0.0

                    liquidityData!!.unbondingPercent = if (liquidityData!!.totalShares > 0) {
                        (unbondingSharesMicro / 1000000.0 / liquidityData!!.totalShares) * 100
                    } else 0.0

                    // Calculate underlying values
                    liquidityData!!.userErthValue = (liquidityData!!.erthReserve * liquidityData!!.poolOwnershipPercent) / 100.0
                    liquidityData!!.userTokenValue = (liquidityData!!.tokenReserve * liquidityData!!.poolOwnershipPercent) / 100.0

                    Log.d(TAG, "Updated centralized liquidity data - User shares: ${liquidityData!!.userStakedShares}" +
                          ", Pool ownership: ${liquidityData!!.poolOwnershipPercent}%")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating liquidity data", e)
            }
        }
    }

    // Getter for tabs to access centralized data
    fun getLiquidityData(): LiquidityData? {
        return liquidityData
    }

    // Method called by InfoFragment when it becomes visible to refresh data
    fun refreshInfoTabData() {
        Log.d(TAG, "InfoFragment requested data refresh")
        loadAllLiquidityData()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executorService != null && !executorService!!.isShutdown) {
            executorService!!.shutdown()
        }

        // Unregister broadcast receiver
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
                Log.d(TAG, "Unregistered transaction success receiver")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Receiver was not registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }

    private fun setupAddTabListeners() {
        addLiquidityButton?.setOnClickListener { handleAddLiquidity() }

        // TODO: Setup ratio synchronization between ERTH and token amounts
        // TODO: Setup max buttons for balance inputs
    }

    private fun setupRemoveTabListeners() {
        removeLiquidityButton?.setOnClickListener { handleRemoveLiquidity() }

        // TODO: Setup max button for staked shares
    }

    private fun handleAddLiquidity() {
        Log.d(TAG, "Adding liquidity")

        if (erthAmountInput == null || tokenAmountInput == null) return

        val erthAmount = erthAmountInput!!.text.toString()
        val tokenAmount = tokenAmountInput!!.text.toString()

        if (erthAmount.isEmpty() || tokenAmount.isEmpty()) {
            Log.w(TAG, "Amount inputs are empty")
            return
        }

        Log.d(TAG, "Adding liquidity: $erthAmount ERTH, $tokenAmount token")

        // TODO: Implement actual liquidity provision
        // This should call the exchange contract to provide liquidity
    }

    private fun handleRemoveLiquidity() {
        Log.d(TAG, "Removing liquidity")

        if (removeAmountInput == null) return

        val removeAmount = removeAmountInput!!.text.toString()

        if (removeAmount.isEmpty()) {
            Log.w(TAG, "Remove amount is empty")
            return
        }

        Log.d(TAG, "Removing liquidity: $removeAmount shares")

        // TODO: Implement actual liquidity removal
        // This should call the exchange contract to remove liquidity
    }

    private fun calculateUnderlyingValues(state: JSONObject, ownershipPercent: Double) {
        try {
            if (ownershipPercent <= 0) {
                // No ownership, show zero values
                erthValueText?.text = "0.000000"
                tokenValueText?.text = "0.000000"
                return
            }

            // Get reserves from pool state (in micro units)
            val erthReserveMicro = state.optLong("erth_reserve", 0)
            val tokenBReserveMicro = state.optLong("token_b_reserve", 0)

            // Convert to macro units (divide by 1,000,000) like React app toMacroUnits
            val erthReserveMacro = erthReserveMicro / 1000000.0
            val tokenBReserveMacro = tokenBReserveMicro / 1000000.0

            // Calculate user's underlying value like React app:
            // userErthValue = (erthReserveMacro * ownershipPercent) / 100
            // userTokenBValue = (tokenBReserveMacro * ownershipPercent) / 100
            val userErthValue = (erthReserveMacro * ownershipPercent) / 100.0
            val userTokenBValue = (tokenBReserveMacro * ownershipPercent) / 100.0

            // Update UI with calculated values (6 decimal places like React)
            erthValueText?.text = String.format("%.6f", userErthValue)
            tokenValueText?.text = String.format("%.6f", userTokenBValue)

            Log.d(TAG, String.format("Calculated underlying values - ERTH: %.6f, %s: %.6f (%.4f%% ownership)",
                    userErthValue, tokenKey, userTokenBValue, ownershipPercent))

        } catch (e: Exception) {
            Log.e(TAG, "Error calculating underlying values", e)
        }
    }

    private fun setTokenLogo(imageView: ImageView, tokenKey: String) {
        try {
            // Try to load token logo from assets
            val assetPath = "coin/${tokenKey.uppercase()}.png"
            loadImageFromAssets(assetPath, imageView)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load token logo for $tokenKey: ${e.message}")
            // Fallback to default
            imageView.setImageResource(R.drawable.ic_token_default)
        }
    }

    private fun loadImageFromAssets(assetPath: String, imageView: ImageView) {
        try {
            val inputStream = context!!.assets.open(assetPath)
            val drawable = Drawable.createFromStream(inputStream, null)
            imageView.setImageDrawable(drawable)
            inputStream.close()
            Log.d(TAG, "Successfully loaded logo from: $assetPath")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to load asset: $assetPath, using default")
            imageView.setImageResource(R.drawable.ic_token_default)
        }
    }

    // Interface for communicating with parent fragment
    interface LiquidityManagementListener {
        fun onLiquidityOperationComplete()
        fun onCloseLiquidityManagement()
    }
}