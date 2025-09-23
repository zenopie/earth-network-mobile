package com.example.earthwallet.ui.pages.wallet

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
import com.example.earthwallet.R
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.bridge.services.SnipQueryService
import com.example.earthwallet.bridge.utils.PermitManager
import com.example.earthwallet.wallet.constants.Tokens
import com.example.earthwallet.wallet.services.SecureWalletManager
import org.json.JSONObject
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

    // State management
    private val tokenQueryQueue: Queue<Tokens.TokenInfo> = LinkedList()
    private var isQueryingToken = false
    private var walletAddress = ""
    private var currentlyQueryingToken: Tokens.TokenInfo? = null
    private lateinit var permitManager: PermitManager

    // Interface for communication with parent
    interface TokenBalancesListener {
        fun onPermitRequested(token: Tokens.TokenInfo)
        fun onManageViewingKeysRequested()
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

        // Initialize PermitManager
        permitManager = PermitManager.getInstance(requireContext())
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
        if (TextUtils.isEmpty(walletAddress)) {
            loadCurrentWalletAddress()
        }

        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available for token balance refresh")
            return
        }

        // Check if view is ready before proceeding
        if (!::tokenBalancesContainer.isInitialized) {
            Log.w(TAG, "TokenBalancesContainer is null, view not ready yet. Skipping refresh.")
            return
        }

        Log.d(TAG, "refreshTokenBalances called for address: $walletAddress")

        // Clear existing token displays
        tokenBalancesContainer.removeAllViews()

        // Clear any existing queue and reset state properly
        tokenQueryQueue.clear()
        isQueryingToken = false
        currentlyQueryingToken = null

        // Add tokens with permits to the queue and display them immediately with "..."
        for (symbol in Tokens.ALL_TOKENS.keys) {
            val token = Tokens.getTokenInfo(symbol)
            if (token != null) {
                // Only show tokens that have permits - hide others entirely
                if (hasPermit(token.contract)) {
                    // Show token immediately with "..." while we fetch the actual balance
                    addTokenBalanceView(token, "...", true)
                    tokenQueryQueue.offer(token)
                }
                // Tokens without permits are simply not shown (no "Get Permit" button)
            }
        }

        // Start processing the queue
        processNextTokenQuery()
    }

    /**
     * Public method to update wallet address and refresh if changed
     */
    fun updateWalletAddress(newAddress: String) {
        walletAddress = newAddress
        // Always refresh - no caching
        if (::tokenBalancesContainer.isInitialized) {
            refreshTokenBalances()
        } else {
            Log.w(TAG, "Wallet address updated but view not ready, refresh deferred")
        }
    }

    private fun processNextTokenQuery() {
        if (isQueryingToken || tokenQueryQueue.isEmpty()) {
            return
        }

        val token = tokenQueryQueue.poll()
        if (token != null) {
            isQueryingToken = true
            currentlyQueryingToken = token
            queryTokenBalance(token)
        }
    }

    private fun queryTokenBalance(token: Tokens.TokenInfo) {
        try {
            // Check if we have a permit for this token
            val hasPermit = permitManager.hasPermit(walletAddress, token.contract)
            if (!hasPermit) {
                // Token should not have been queued if no permit - skip it
                Log.w(TAG, "Token ${token.symbol} was queued without permit, skipping")

                // Mark query as complete and continue with next token
                isQueryingToken = false
                currentlyQueryingToken = null
                processNextTokenQuery()
                return
            }

            Log.d(TAG, "Querying token ${token.symbol} balance")
            Log.d(TAG, "Contract: ${token.contract}")
            Log.d(TAG, "Hash: ${token.hash}")

            // Use SnipQueryService for cleaner token balance queries
            Thread {
                try {
                    // Check wallet availability without retrieving mnemonic
                    if (!SecureWalletManager.isWalletAvailable(requireContext())) {
                        activity?.runOnUiThread {
                            Log.e(TAG, "No wallet found for token balance query")
                            addTokenBalanceView(token, "Error", false)
                            isQueryingToken = false
                            currentlyQueryingToken = null
                            processNextTokenQuery()
                        }
                        return@Thread
                    }

                    // Use SnipQueryService for the permit-based balance query
                    val result = SnipQueryService.queryBalanceWithPermit(
                        requireContext(), // Use HostActivity context
                        token.symbol,
                        walletAddress
                    )

                    // Handle result on UI thread with the specific token
                    activity?.runOnUiThread {
                        handleTokenBalanceResult(token, result.toString())
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Token balance query failed for ${token.symbol}", e)
                    activity?.runOnUiThread {
                        addTokenBalanceView(token, "Error", false)
                        isQueryingToken = false
                        currentlyQueryingToken = null
                        processNextTokenQuery()
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to query token balance for ${token.symbol}", e)
            addTokenBalanceView(token, "Error", false)

            // Mark query as complete and continue with next token
            isQueryingToken = false
            currentlyQueryingToken = null
            processNextTokenQuery()
        }
    }

    private fun handleTokenBalanceResult(token: Tokens.TokenInfo, json: String) {
        try {
            Log.d(TAG, "Token balance query result for ${token.symbol}: $json")

            if (!TextUtils.isEmpty(json)) {
                val root = JSONObject(json)
                val success = root.optBoolean("success", false)

                if (success) {
                    val result = root.optJSONObject("result")
                    if (result != null) {
                        val balance = result.optJSONObject("balance")
                        if (balance != null) {
                            val amount = balance.optString("amount", "0")
                            val formattedBalance = Tokens.formatTokenAmount(amount, token)
                            updateTokenBalanceView(token, formattedBalance)
                        } else {
                            updateTokenBalanceView(token, "!")
                        }
                    } else {
                        updateTokenBalanceView(token, "!")
                    }
                } else {
                    updateTokenBalanceView(token, "!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse token balance result for ${token.symbol}", e)
            updateTokenBalanceView(token, "!")
        }

        // Only advance the queue if this is still the current token being queried
        if (currentlyQueryingToken == token) {
            isQueryingToken = false
            currentlyQueryingToken = null
            processNextTokenQuery()
        }
    }

    private fun addTokenBalanceView(token: Tokens.TokenInfo, balance: String?, hasPermit: Boolean) {
        try {
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
                Log.w(TAG, "Could not load icon for ${token.symbol}: ${e.message}")
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
            symbolText.setTextColor(resources.getColor(android.R.color.primary_text_light))

            infoContainer.addView(symbolText)

            // Balance or action
            if (hasPermit && balance != null) {
                val balanceText = TextView(context)
                balanceText.text = balance
                balanceText.tag = "balance"

                if ("!" == balance) {
                    balanceText.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                    balanceText.textSize = 20f
                } else {
                    balanceText.setTextColor(resources.getColor(R.color.sidebar_text))
                    balanceText.textSize = 16f
                }

                tokenCard.addView(iconView)
                tokenCard.addView(infoContainer)
                tokenCard.addView(balanceText)
            } else {
                // Show "Get Permit" button
                val getPermitButton = Button(context)
                getPermitButton.text = "Get Permit"
                getPermitButton.tag = "get_key_btn"
                getPermitButton.setOnClickListener {
                    listener?.onPermitRequested(token)
                }

                tokenCard.addView(iconView)
                tokenCard.addView(infoContainer)
                tokenCard.addView(getPermitButton)
            }

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
                    // Found the token card, update the balance
                    for (j in 0 until tokenCard.childCount) {
                        val cardChild = tokenCard.getChildAt(j)
                        if (cardChild is TextView && cardChild.tag == "balance") {
                            cardChild.text = balance

                            // Make error indicator red
                            if ("!" == balance) {
                                cardChild.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                                cardChild.textSize = 20f
                            } else {
                                cardChild.setTextColor(resources.getColor(R.color.sidebar_text))
                                cardChild.textSize = 16f
                            }
                            return
                        } else if (cardChild is Button && cardChild.tag == "get_key_btn") {
                            // Replace the "Get Permit" button with balance text
                            tokenCard.removeView(cardChild)
                            val balanceText = TextView(context)
                            balanceText.text = balance
                            balanceText.tag = "balance"

                            if ("!" == balance) {
                                balanceText.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                                balanceText.textSize = 20f
                            } else {
                                balanceText.setTextColor(resources.getColor(R.color.sidebar_text))
                                balanceText.textSize = 16f
                            }

                            tokenCard.addView(balanceText)
                            return
                        }
                    }
                    return
                }
            }

            // Token view not found, add a new one
            addTokenBalanceView(token, balance, hasPermit(token.contract))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update token balance view for ${token.symbol}", e)
            addTokenBalanceView(token, balance, hasPermit(token.contract))
        }
    }

    /**
     * Public method to update a specific token's balance (called from parent when viewing key is set)
     */
    fun updateTokenBalance(token: Tokens.TokenInfo, balance: String) {
        updateTokenBalanceView(token, balance)
    }

    /**
     * Public method to query a single token balance (called from parent after viewing key is set)
     */
    fun querySingleToken(token: Tokens.TokenInfo) {
        if (!TextUtils.isEmpty(walletAddress)) {
            // Add to front of queue for priority processing
            val tempQueue: Queue<Tokens.TokenInfo> = LinkedList()
            tempQueue.offer(token)
            while (!tokenQueryQueue.isEmpty()) {
                tempQueue.offer(tokenQueryQueue.poll())
            }
            tokenQueryQueue.clear()
            tokenQueryQueue.addAll(tempQueue)

            // Start processing if not already processing
            if (!isQueryingToken) {
                processNextTokenQuery()
            }
        }
    }

    // Helper methods for viewing key management
    /**
     * Check if permit exists for contract
     */
    private fun hasPermit(contractAddress: String): Boolean {
        return permitManager.hasPermit(walletAddress, contractAddress)
    }

    private fun loadCurrentWalletAddress() {
        // Use SecureWalletManager to get wallet address directly
        try {
            walletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
            if (!TextUtils.isEmpty(walletAddress)) {
                Log.d(TAG, "Loaded wallet address: ${walletAddress.substring(0, minOf(14, walletAddress.length))}...")
            } else {
                Log.w(TAG, "No wallet address available")
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