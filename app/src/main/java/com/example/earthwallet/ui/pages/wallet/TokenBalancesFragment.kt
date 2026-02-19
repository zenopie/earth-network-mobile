package network.erth.wallet.ui.pages.wallet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import network.erth.wallet.R
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SecretKClient
import org.json.JSONArray
import org.json.JSONObject
import network.erth.wallet.Constants
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * TokenBalancesFragment
 *
 * Handles all token balance display and management functionality:
 * - Querying token balances with viewing keys
 * - Displaying token balance UI
 * - Managing token balance query queue
 * - Updating token balance views
 */
class TokenBalancesFragment : Fragment() {

    companion object {
        private const val TAG = "TokenBalancesFragment"
        private const val REQ_TOKEN_BALANCE = 2001
    }

    // UI Components
    private lateinit var tokenBalancesContainer: LinearLayout

    // State management - using coroutines for cancellable queries
    private var balanceQueryJob: Job? = null
    private var walletAddress = ""
    private var permitManager: PermitManager? = null

    // Price state for USD display
    private var erthPrice: Double? = null
    private var tokenBalances: MutableMap<String, Double> = mutableMapOf()
    private var tokenUsdValues: MutableMap<String, Double> = mutableMapOf()

    // Interface for communication with parent
    interface TokenBalancesListener {
        fun onPermitRequested(token: Tokens.TokenInfo)
        fun onManageViewingKeysRequested()
        fun onTokenUsdValueUpdated(totalUsdValue: Double)
    }

    private var listener: TokenBalancesListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is TokenBalancesListener -> parentFragment as TokenBalancesListener
            context is TokenBalancesListener -> context
            else -> throw RuntimeException("$context must implement TokenBalancesListener")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize PermitManager only if session is active
        try {
            permitManager = PermitManager.getInstance(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PermitManager", e)
            permitManager = null
            // Continue without permit manager - no tokens will be shown until session is active
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_token_balances, container, false)

        tokenBalancesContainer = view.findViewById(R.id.tokenBalancesContainer)

        // Set up manage viewing keys button
        val manageViewingKeysButton = view.findViewById<Button>(R.id.manage_viewing_keys_button)
        manageViewingKeysButton?.setOnClickListener {
            listener?.onManageViewingKeysRequested()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load current wallet address for initialization
        loadCurrentWalletAddress()
        // Note: Don't call refreshTokenBalances() here - let parent fragment control when to refresh
    }

    /**
     * Public method to refresh token balances from parent fragment
     */
    fun refreshTokenBalances() {
        Log.d(TAG, "refreshTokenBalances called on instance ${System.identityHashCode(this)}")
        if (TextUtils.isEmpty(walletAddress)) {
            loadCurrentWalletAddress()
        }

        if (TextUtils.isEmpty(walletAddress)) {
            return
        }

        // Check if view is ready before proceeding
        if (!::tokenBalancesContainer.isInitialized) {
            return
        }

        // Cancel any existing query job
        balanceQueryJob?.cancel()

        // Clear existing token displays and balances
        Log.d(TAG, "Clearing all views, current count: ${tokenBalancesContainer.childCount}")
        tokenBalancesContainer.removeAllViews()
        tokenBalances.clear()
        tokenUsdValues.clear()

        // Fetch ERTH price for USD calculations
        lifecycleScope.launch {
            try {
                erthPrice = ErthPriceService.fetchErthPrice()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch ERTH price", e)
            }
        }

        // Capture the current wallet address for this query session
        val currentWalletAddress = walletAddress

        // First, add all tokens with permits to the UI with loading state
        val tokensToQuery = mutableListOf<Tokens.TokenInfo>()
        for (symbol in Tokens.ALL_TOKENS.keys) {
            val token = Tokens.getTokenInfo(symbol) ?: continue
            // Only show tokens that have permits
            if (!hasPermit(token.contract)) continue
            // Show token immediately with "..." loading state
            addTokenBalanceView(token, "...")
            tokensToQuery.add(token)
        }

        // Start new query job with coroutines to fetch balances
        balanceQueryJob = lifecycleScope.launch {
            for (token in tokensToQuery) {
                // Query balance in background
                try {
                    // Verify wallet hasn't changed before querying
                    if (currentWalletAddress != walletAddress) {
                        throw CancellationException("Wallet changed during query")
                    }

                    // Check if wallet is still available
                    if (!SecureWalletManager.isWalletAvailable(requireContext())) {
                        throw Exception("No wallet found")
                    }

                    // Query balance with permit
                    val result = SecretKClient.querySnipBalanceWithPermit(
                        requireContext(),
                        token.symbol,
                        currentWalletAddress
                    )

                    // Validate wallet still matches before displaying result
                    if (currentWalletAddress == walletAddress) {
                        handleTokenBalanceResult(token, result.toString())
                    }

                } catch (e: CancellationException) {
                    // Query was cancelled, ignore
                    Log.d(TAG, "Token balance query cancelled for ${token.symbol}")
                } catch (e: Exception) {
                    Log.e(TAG, "Token balance query failed for ${token.symbol}", e)
                    if (currentWalletAddress == walletAddress) {
                        updateTokenBalanceView(token, "Error")
                    }
                }
            }
        }
    }

    /**
     * Public method to update wallet address and refresh if changed
     */
    fun updateWalletAddress(newAddress: String) {
        Log.d(TAG, "updateWalletAddress called with: $newAddress on instance ${System.identityHashCode(this)}")
        // Cancel any in-flight queries for the old wallet
        balanceQueryJob?.cancel()

        walletAddress = newAddress
        // Always refresh - no caching
        if (::tokenBalancesContainer.isInitialized) {
            refreshTokenBalances()
        }
    }


    private fun handleTokenBalanceResult(token: Tokens.TokenInfo, json: String) {
        try {
            if (!TextUtils.isEmpty(json)) {
                val root = JSONObject(json)
                val balance = root.optJSONObject("balance")
                if (balance != null) {
                    val amount = balance.optString("amount", "0")
                    val formattedBalance = Tokens.formatTokenAmount(amount, token)

                    // Store numerical balance for USD calculation
                    val numericBalance = amount.toDoubleOrNull()?.div(Math.pow(10.0, token.decimals.toDouble())) ?: 0.0
                    tokenBalances[token.symbol] = numericBalance

                    updateTokenBalanceView(token, formattedBalance)

                    // Calculate and update USD value
                    calculateTokenUsdValue(token, numericBalance)
                } else {
                    updateTokenBalanceView(token, "!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse token balance result for ${token.symbol}", e)
            updateTokenBalanceView(token, "!")
        }
    }

    private fun calculateTokenUsdValue(token: Tokens.TokenInfo, balance: Double) {
        if (balance <= 0) return

        lifecycleScope.launch {
            try {
                val usdValue = getUsdValueForToken(token, balance)
                if (usdValue != null) {
                    tokenUsdValues[token.symbol] = usdValue
                    withContext(Dispatchers.Main) {
                        updateTokenUsdView(token, usdValue)
                        updateTotalUsdValue()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to calculate USD value for ${token.symbol}", e)
            }
        }
    }

    /**
     * Get USD value for a token amount - uses CoinGecko if available, otherwise spot rate from pool
     */
    private suspend fun getUsdValueForToken(token: Tokens.TokenInfo, amount: Double): Double? {
        // If token has coingeckoId, fetch price from CoinGecko
        if (token.coingeckoId != null) {
            val cgPrice = fetchCoingeckoPrice(token.coingeckoId)
            if (cgPrice != null) {
                return amount * cgPrice
            }
        }

        // Otherwise, use spot rate from pool * ERTH price
        val price = erthPrice ?: return null
        val spotRate = getSpotRate(token)
        return if (spotRate != null) amount * spotRate * price else null
    }

    /**
     * Fetch price from CoinGecko API
     */
    private suspend fun fetchCoingeckoPrice(coingeckoId: String): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.coingecko.com/api/v3/simple/price?ids=$coingeckoId&vs_currencies=usd")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tokenObj = json.optJSONObject(coingeckoId)
                    tokenObj?.optDouble("usd", -1.0)?.takeIf { it > 0 }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch CoinGecko price for $coingeckoId", e)
                null
            }
        }
    }

    /**
     * Get spot rate for a token (price per 1 token in ERTH, from pool reserves)
     */
    private suspend fun getSpotRate(token: Tokens.TokenInfo): Double? {
        // ERTH has spot rate of 1
        if (token.symbol == "ERTH") return 1.0

        return withContext(Dispatchers.IO) {
            try {
                val reserves = getPoolReserves(token.contract)
                if (reserves != null && reserves.second > 0) {
                    // Spot rate = ERTH reserve / Token reserve
                    reserves.first / reserves.second
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get spot rate for ${token.symbol}", e)
                null
            }
        }
    }

    /**
     * Query pool reserves for a token
     * Returns Pair(erthReserve, tokenReserve) or null if query fails
     */
    private suspend fun getPoolReserves(tokenContract: String): Pair<Double, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                val queryJson = "{\"query_user_info\": {\"pools\": [\"$tokenContract\"], \"user\": \"$walletAddress\"}}"

                val responseString = SecretKClient.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    queryJson,
                    Constants.EXCHANGE_HASH
                )

                // Parse response
                val result = try {
                    JSONArray(responseString)
                } catch (e: Exception) {
                    try {
                        val obj = JSONObject(responseString)
                        if (obj.has("data")) obj.getJSONArray("data") else return@withContext null
                    } catch (e2: Exception) {
                        return@withContext null
                    }
                }

                if (result.length() > 0) {
                    val poolData = result.getJSONObject(0)
                    val poolInfo = poolData.optJSONObject("pool_info")
                    val state = poolInfo?.optJSONObject("state")

                    if (state != null) {
                        val erthReserve = state.optLong("erth_reserve", 0) / 1_000_000.0
                        val tokenReserve = state.optLong("token_b_reserve", 0) / 1_000_000.0
                        Pair(erthReserve, tokenReserve)
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query pool reserves for $tokenContract", e)
                null
            }
        }
    }

    private fun updateTokenUsdView(token: Tokens.TokenInfo, usdValue: Double) {
        try {
            // Find the existing token card and update USD value
            for (i in 0 until tokenBalancesContainer.childCount) {
                val tokenCard = tokenBalancesContainer.getChildAt(i) as? LinearLayout
                if (tokenCard?.tag == token.symbol) {
                    // Find the balance container (last child should be the balance container)
                    for (j in 0 until tokenCard.childCount) {
                        val child = tokenCard.getChildAt(j)
                        if (child is LinearLayout) {
                            // Look for the usd_value TextView
                            for (k in 0 until child.childCount) {
                                val textView = child.getChildAt(k)
                                if (textView is TextView && textView.tag == "usd_value") {
                                    textView.text = ErthPriceService.formatUSD(usdValue)
                                    return
                                }
                            }
                        }
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update token USD view for ${token.symbol}", e)
        }
    }

    private fun updateTotalUsdValue() {
        val totalUsd = tokenUsdValues.values.sum()
        listener?.onTokenUsdValueUpdated(totalUsd)
    }

    private fun addTokenBalanceView(token: Tokens.TokenInfo, balance: String?) {
        try {
            Log.d(TAG, "addTokenBalanceView called for ${token.symbol} with balance: $balance, current container size: ${tokenBalancesContainer.childCount}")
            val tokenCard = LinearLayout(context)
            tokenCard.orientation = LinearLayout.HORIZONTAL
            tokenCard.setPadding(16, 12, 16, 12)
            tokenCard.tag = token.symbol

            val layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(8, 4, 8, 4)
            tokenCard.layoutParams = layoutParams

            // Token icon
            val iconView = ImageView(context)
            val iconParams = LinearLayout.LayoutParams(60, 60)
            iconParams.setMargins(0, 0, 16, 0)
            iconView.layoutParams = iconParams

            // Load token icon from assets
            try {
                var logoPath = token.logo

                // Handle both old format (coin/ERTH.png) and new format (scrt, etc.)
                if (!logoPath.contains("/") && !logoPath.contains(".")) {
                    // New format - try adding coin/ prefix and .png extension
                    logoPath = "coin/${logoPath.uppercase()}.png"
                }

                try {
                    val bitmap = BitmapFactory.decodeStream(context?.assets?.open(logoPath))
                    iconView.setImageBitmap(bitmap)
                } catch (e2: Exception) {
                    // Try the original logo path if the modified one failed
                    if (logoPath != token.logo) {
                        val bitmap = BitmapFactory.decodeStream(context?.assets?.open(token.logo))
                        iconView.setImageBitmap(bitmap)
                    } else {
                        throw e2
                    }
                }
            } catch (e: Exception) {
                iconView.setImageResource(R.drawable.ic_token_default)
            }

            // Token info container
            val infoContainer = LinearLayout(context)
            infoContainer.orientation = LinearLayout.VERTICAL
            val infoParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            infoContainer.layoutParams = infoParams

            // Token symbol
            val symbolText = TextView(context)
            symbolText.text = token.symbol
            symbolText.textSize = 18f
            symbolText.setTextColor(0xFF333333.toInt())

            infoContainer.addView(symbolText)

            // Balance container with balance and USD value
            val balanceContainer = LinearLayout(context)
            balanceContainer.orientation = LinearLayout.VERTICAL
            balanceContainer.gravity = android.view.Gravity.END

            val balanceText = TextView(context)
            balanceText.text = balance ?: "..."
            balanceText.tag = "balance"

            if ("!" == balance) {
                balanceText.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                balanceText.textSize = 20f
            } else {
                balanceText.setTextColor(0xFF22C55E.toInt())
                balanceText.textSize = 16f
                balanceText.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL))
            }

            val usdText = TextView(context)
            usdText.tag = "usd_value"
            usdText.text = ""
            usdText.textSize = 12f
            usdText.setTextColor(0xFF888888.toInt())

            balanceContainer.addView(balanceText)
            balanceContainer.addView(usdText)

            tokenCard.addView(iconView)
            tokenCard.addView(infoContainer)
            tokenCard.addView(balanceContainer)

            tokenBalancesContainer.addView(tokenCard)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add token balance view for ${token.symbol}", e)
        }
    }

    private fun updateTokenBalanceView(token: Tokens.TokenInfo, balance: String) {
        try {
            // Find the existing token card
            for (i in 0 until tokenBalancesContainer.childCount) {
                val tokenCard = tokenBalancesContainer.getChildAt(i) as? LinearLayout
                if (tokenCard?.tag == token.symbol) {
                    // Found the token card, search for balance text in nested LinearLayouts
                    for (j in 0 until tokenCard.childCount) {
                        val child = tokenCard.getChildAt(j)
                        if (child is LinearLayout) {
                            // Look for the balance TextView inside this container
                            for (k in 0 until child.childCount) {
                                val textView = child.getChildAt(k)
                                if (textView is TextView && textView.tag == "balance") {
                                    textView.text = balance

                                    // Make error indicator red
                                    if ("!" == balance) {
                                        textView.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                                        textView.textSize = 20f
                                    } else {
                                        textView.setTextColor(0xFF22C55E.toInt())
                                        textView.textSize = 16f
                                        textView.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL))
                                    }
                                    return
                                }
                            }
                        }
                    }
                    return
                }
            }

            // Token view not found - this could be from a cancelled/old query
            Log.d(TAG, "Token view not found for ${token.symbol}, container has ${tokenBalancesContainer.childCount} children")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update token balance view for ${token.symbol}", e)
        }
    }

    /**
     * Public method to update a specific token's balance (called from parent when viewing key is set)
     */
    fun updateTokenBalance(token: Tokens.TokenInfo, balance: String) {
        updateTokenBalanceView(token, balance)
    }

    /**
     * Public method to query a single token balance (called from parent after permit is set)
     */
    fun querySingleToken(token: Tokens.TokenInfo) {
        if (TextUtils.isEmpty(walletAddress)) {
            return
        }

        val currentWalletAddress = walletAddress

        // Launch a single token query
        lifecycleScope.launch {
            try {
                // Show loading state - update existing view if it exists
                updateTokenBalanceView(token, "...")

                // Verify wallet hasn't changed
                if (currentWalletAddress != walletAddress) {
                    throw CancellationException("Wallet changed during query")
                }

                val result = SecretKClient.querySnipBalanceWithPermit(
                    requireContext(),
                    token.symbol,
                    currentWalletAddress
                )

                // Validate wallet still matches before displaying
                if (currentWalletAddress == walletAddress) {
                    handleTokenBalanceResult(token, result.toString())
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Single token query cancelled for ${token.symbol}")
            } catch (e: Exception) {
                Log.e(TAG, "Single token query failed for ${token.symbol}", e)
                if (currentWalletAddress == walletAddress) {
                    updateTokenBalanceView(token, "Error")
                }
            }
        }
    }

    // Helper methods for viewing key management
    /**
     * Check if permit exists for contract
     */
    private fun hasPermit(contractAddress: String): Boolean {
        return permitManager?.hasPermit(walletAddress, contractAddress) ?: false
    }

    private fun loadCurrentWalletAddress() {
        // Use SecureWalletManager to get wallet address directly
        try {
            walletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
            if (!TextUtils.isEmpty(walletAddress)) {
            } else {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            walletAddress = ""
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}