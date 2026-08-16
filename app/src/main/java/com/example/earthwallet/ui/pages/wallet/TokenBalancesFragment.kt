package network.erth.wallet.ui.pages.wallet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * TokenBalancesFragment
 *
 * Lists the wallet's native earth token balances (bank denoms). No SNIP-20,
 * viewing keys, or permits — balances are public on a transparent chain.
 */
class TokenBalancesFragment : Fragment() {

    companion object {
        private const val TAG = "TokenBalancesFragment"
    }

    private lateinit var tokenBalancesContainer: LinearLayout
    private var walletAddress = ""

    interface TokenBalancesListener {
        fun onTokenUsdValueUpdated(totalUsdValue: Double)
    }

    private var listener: TokenBalancesListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is TokenBalancesListener -> parentFragment as TokenBalancesListener
            context is TokenBalancesListener -> context
            else -> null
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_token_balances, container, false)
        tokenBalancesContainer = view.findViewById(R.id.tokenBalancesContainer)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCurrentWalletAddress()
    }

    private fun loadCurrentWalletAddress() {
        walletAddress = try {
            SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e); ""
        }
    }

    /** Sets the active wallet address and refreshes. Called by the parent fragment. */
    fun updateWalletAddress(address: String?) {
        walletAddress = address ?: ""
        refreshTokenBalances()
    }

    /** Refreshes all native token balances. Called by the parent fragment. */
    fun refreshTokenBalances() {
        loadCurrentWalletAddress()
        if (TextUtils.isEmpty(walletAddress)) {
            tokenBalancesContainer.removeAllViews()
            return
        }
        lifecycleScope.launch {
            val balances = try {
                withContext(Dispatchers.IO) { Bank.balances(walletAddress) }
            } catch (e: Exception) {
                Log.e(TAG, "balance query failed", e); emptyMap()
            }
            tokenBalancesContainer.removeAllViews()
            for (token in Tokens.ALL_TOKENS.values) {
                val raw = balances[token.denom] ?: "0"
                tokenBalancesContainer.addView(tokenCard(token, raw))
            }
            listener?.onTokenUsdValueUpdated(0.0)
        }
    }

    private fun tokenCard(token: Tokens.TokenInfo, rawAmount: String): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            try {
                ctx.assets.open(token.logo).use { setImageBitmap(BitmapFactory.decodeStream(it)) }
            } catch (_: Exception) {
                setImageResource(R.mipmap.ic_launcher)
            }
        }

        val symbol = TextView(ctx).apply {
            text = token.symbol
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }

        val balance = TextView(ctx).apply {
            text = Tokens.formatTokenAmount(rawAmount, token)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            gravity = Gravity.END
        }

        card.addView(icon)
        card.addView(symbol)
        card.addView(balance)
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
