package com.example.earthwallet.ui.pages.wallet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
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
import com.example.earthwallet.bridge.utils.PermitManager
import com.example.earthwallet.wallet.constants.Tokens
import kotlin.math.round

/**
 * ManagePermitsFragment
 *
 * Allows users to manage their stored SNIP-24 permits:
 * - View all tokens that have permits set
 * - Remove permits for specific tokens
 * - Create new permits for token access
 * - Navigate back to token balances
 */
class ManagePermitsFragment : Fragment() {

    companion object {
        private const val TAG = "ManagePermitsFragment"
    }

    // UI Components
    private lateinit var permitsContainer: LinearLayout
    private lateinit var emptyStateMessage: TextView

    // State management
    private lateinit var permitManager: PermitManager
    private var walletAddress = ""

    // Interface for communication with parent
    interface ManagePermitsListener {
        fun getCurrentWalletAddress(): String
        fun onPermitRemoved(token: Tokens.TokenInfo)
        fun onPermitRequested(token: Tokens.TokenInfo)
    }

    private var listener: ManagePermitsListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is ManagePermitsListener) {
            listener = context
        }
        // Note: We don't require the listener since this fragment can work independently
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize PermitManager
        permitManager = PermitManager.getInstance(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_manage_permits, container, false)

        permitsContainer = view.findViewById(R.id.permits_container)
        emptyStateMessage = view.findViewById(R.id.empty_state_message)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get wallet address from arguments first
        arguments?.let { args ->
            walletAddress = args.getString("wallet_address", "")
        }

        // Fallback to listener if available
        if (TextUtils.isEmpty(walletAddress) && listener != null) {
            walletAddress = listener?.getCurrentWalletAddress() ?: ""
        }

        // Final fallback: get wallet address directly from SecureWalletManager
        if (TextUtils.isEmpty(walletAddress)) {
            try {
                walletAddress = com.example.earthwallet.wallet.services.SecureWalletManager.getWalletAddress(requireContext()) ?: ""
                Log.d(TAG, "Loaded wallet address directly: ${if (walletAddress.isNotEmpty()) walletAddress.substring(0, 14) + "..." else "EMPTY"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load wallet address directly", e)
            }
        }

        // If we still don't have a wallet address, we cannot proceed
        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available - cannot manage permits")
        }

        Log.d(TAG, "onViewCreated - walletAddress: $walletAddress")
        Log.d(TAG, "Number of tokens in ALL_TOKENS: ${Tokens.ALL_TOKENS.size}")

        // Load and display permits
        loadPermits()
    }

    /**
     * Load all tokens and display them with their permit status
     */
    private fun loadPermits() {
        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available")
            showEmptyState()
            return
        }

        permitsContainer.removeAllViews()

        val allTokens = getAllTokensWithPermitStatus()

        if (allTokens.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
            for (tokenInfo in allTokens) {
                addTokenItem(tokenInfo)
            }
        }
    }

    /**
     * Get all tokens with their permit status
     */
    private fun getAllTokensWithPermitStatus(): List<TokenPermitInfo> {
        val allTokens = mutableListOf<TokenPermitInfo>()

        try {
            Log.d(TAG, "Loading all tokens with permit status, wallet address: $walletAddress")

            // Check each token to see if it has a permit
            for (symbol in Tokens.ALL_TOKENS.keys) {
                val token = Tokens.getTokenInfo(symbol)
                if (token != null) {
                    val permit = permitManager.getPermit(walletAddress, token.contract)
                    Log.d(TAG, "Token $symbol (${token.contract}) permit: ${if (permit == null) "NONE" else "EXISTS"}")

                    val tokenInfo = TokenPermitInfo().apply {
                        this.token = token
                        this.permit = permit
                    }
                    allTokens.add(tokenInfo)
                    Log.d(TAG, "Added token $symbol (has permit: ${permit != null})")
                }
            }

            Log.d(TAG, "Loaded ${allTokens.size} tokens total")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokens", e)
        }

        return allTokens
    }

    /**
     * Add a token item to the UI (with or without permit)
     */
    private fun addTokenItem(tokenInfo: TokenPermitInfo) {
        try {
            // Create a row for each token
            val tokenRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
                background = resources.getDrawable(R.drawable.card_rounded_bg)
            }

            // Add margin between rows
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            tokenRow.layoutParams = rowParams

            // Token logo
            if (!TextUtils.isEmpty(tokenInfo.token.logo)) {
                val logoView = ImageView(context).apply {
                    val logoParams = LinearLayout.LayoutParams(
                        dpToPx(32), dpToPx(32)
                    ).apply {
                        setMargins(0, 0, dpToPx(12), 0)
                    }
                    layoutParams = logoParams
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }

                // Load logo from assets with proper format handling
                try {
                    var logoPath = tokenInfo.token.logo

                    // Handle both old format (coin/ERTH.png) and new format (scrt, etc.)
                    if (!logoPath.contains("/") && !logoPath.contains(".")) {
                        // New format - try adding coin/ prefix and .png extension
                        logoPath = "coin/${logoPath.uppercase()}.png"
                    }

                    try {
                        val bitmap = BitmapFactory.decodeStream(context?.assets?.open(logoPath))
                        logoView.setImageBitmap(bitmap)
                        tokenRow.addView(logoView)
                    } catch (e2: Exception) {
                        // Try the original logo path if the modified one failed
                        if (logoPath != tokenInfo.token.logo) {
                            val bitmap = BitmapFactory.decodeStream(context?.assets?.open(tokenInfo.token.logo))
                            logoView.setImageBitmap(bitmap)
                            tokenRow.addView(logoView)
                        } else {
                            throw e2
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load logo for ${tokenInfo.token.symbol}: ${tokenInfo.token.logo}", e)
                    // Add a default icon
                    logoView.setImageResource(R.drawable.ic_token_default)
                    tokenRow.addView(logoView)
                }
            }

            // Token info container
            val tokenInfoContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val infoParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
                )
                layoutParams = infoParams
            }

            // Token symbol
            val symbolText = TextView(context).apply {
                text = tokenInfo.token.symbol
                textSize = 16f
                setTextColor(Color.parseColor("#1e3a8a"))
                typeface = Typeface.DEFAULT_BOLD
            }
            tokenInfoContainer.addView(symbolText)

            // Status text (permit status and permissions)
            val statusText = TextView(context).apply {
                val permit = tokenInfo.permit
                if (permit == null) {
                    text = "No permit set"
                    setTextColor(resources.getColor(R.color.wallet_row_address))
                } else {
                    val permissions = permit.permissions
                    var permissionsText = permissions?.joinToString(", ") ?: "No permissions"
                    if (permissionsText.length > 25) {
                        permissionsText = permissionsText.substring(0, 25) + "..."
                    }
                    text = "Permissions: $permissionsText"
                    setTextColor(resources.getColor(R.color.wallet_row_address))
                }
                textSize = 12f
            }
            tokenInfoContainer.addView(statusText)

            tokenRow.addView(tokenInfoContainer)

            // Action button (Get or Remove)
            val actionButton = Button(context).apply {
                textSize = 12f
                setPadding(16, 8, 16, 8)
                minWidth = 0
                minHeight = 0
                elevation = 0f
                stateListAnimator = null

                val buttonParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutParams = buttonParams
            }

            if (tokenInfo.permit == null) {
                // No permit - show "Add" button
                actionButton.apply {
                    text = "Add"
                    val getBackground = GradientDrawable().apply {
                        setColor(Color.parseColor("#4caf50"))
                        cornerRadius = 8 * resources.displayMetrics.density
                    }
                    background = getBackground
                    setTextColor(resources.getColor(android.R.color.white))
                    setOnClickListener { requestPermit(tokenInfo.token) }
                }
            } else {
                // Has permit - show "Remove" button
                actionButton.apply {
                    text = "Remove"
                    val removeBackground = GradientDrawable().apply {
                        setColor(Color.parseColor("#f44336"))
                        cornerRadius = 8 * resources.displayMetrics.density
                    }
                    background = removeBackground
                    setTextColor(resources.getColor(android.R.color.white))
                    setOnClickListener { removePermit(tokenInfo) }
                }
            }

            tokenRow.addView(actionButton)
            permitsContainer.addView(tokenRow)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add token item for ${tokenInfo.token.symbol}", e)
        }
    }

    /**
     * Remove the permit for a token
     */
    private fun removePermit(permitInfo: TokenPermitInfo) {
        try {
            // Remove permit using PermitManager
            permitManager.removePermit(walletAddress, permitInfo.token.contract)

            Toast.makeText(context, "Permit removed for ${permitInfo.token.symbol}", Toast.LENGTH_SHORT).show()

            // Notify parent if available
            listener?.onPermitRemoved(permitInfo.token)

            // Reload the permits list
            loadPermits()

            Log.i(TAG, "Successfully removed permit for ${permitInfo.token.symbol}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove permit for ${permitInfo.token.symbol}", e)
            Toast.makeText(context, "Failed to remove permit: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Show empty state when no permits are found
     */
    private fun showEmptyState() {
        permitsContainer.visibility = View.GONE
        emptyStateMessage.visibility = View.VISIBLE
    }

    /**
     * Hide empty state when permits are available
     */
    private fun hideEmptyState() {
        permitsContainer.visibility = View.VISIBLE
        emptyStateMessage.visibility = View.GONE
    }

    /**
     * Convert dp to pixels
     */
    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return round(dp * density).toInt()
    }

    /**
     * Check if permit exists for a contract
     */
    private fun hasPermit(contractAddress: String): Boolean {
        if (TextUtils.isEmpty(walletAddress)) {
            return false
        }
        return permitManager.hasPermit(walletAddress, contractAddress)
    }

    /**
     * Public method to update wallet address
     */
    fun updateWalletAddress(newAddress: String) {
        walletAddress = newAddress
        // Always refresh - no caching
        loadPermits()
    }

    /**
     * Request permit creation for a token
     */
    private fun requestPermit(token: Tokens.TokenInfo) {
        Log.i(TAG, "Requesting permit for ${token.symbol}")

        // Create permit directly with default permissions
        createPermit(token)
    }

    /**
     * Create permit with default permissions (balance, history)
     */
    private fun createPermit(token: Tokens.TokenInfo) {
        val defaultPermissions = listOf("balance", "history") // lowercase for SNIP-24
        createPermitWithPermissions(token, defaultPermissions)
    }

    /**
     * Create permit with specific permissions - directly using PermitManager (no transaction flow)
     */
    private fun createPermitWithPermissions(token: Tokens.TokenInfo, permissions: List<String>) {
        try {
            Log.d(TAG, "Creating permit directly for ${token.symbol} with permissions: $permissions")

            // Create permit directly using PermitManager in the background
            permitManager.createPermit(
                requireContext(),
                walletAddress,
                listOf(token.contract),
                "EarthWallet", // permit name
                permissions
            )

            // Notify parent if available
            listener?.onPermitRequested(token)

            // Refresh the display
            loadPermits()

            Log.i(TAG, "Successfully created permit for ${token.symbol}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create permit for ${token.symbol}", e)
            Toast.makeText(context, "Failed to create permit: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    /**
     * Helper class to hold token and permit information
     */
    private class TokenPermitInfo {
        lateinit var token: Tokens.TokenInfo
        var permit: com.example.earthwallet.bridge.models.Permit? = null
    }
}