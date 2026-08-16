package network.erth.wallet.ui.pages.managelp

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Dex
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * Info tab for a pool: total LP shares (the dexlp/{id} denom supply), the user's
 * share balance, their pool ownership, and the underlying ERTH + token value.
 * There is no LP unbonding on earth, so that figure is always 0%.
 */
class InfoFragment : Fragment() {

    companion object {
        private const val TAG = "InfoFragment"

        @JvmStatic
        fun newInstance(tokenKey: String): InfoFragment {
            val fragment = InfoFragment()
            fragment.arguments = Bundle().apply { putString("token_key", tokenKey) }
            return fragment
        }
    }

    private var tokenKey: String? = null

    private lateinit var totalSharesText: TextView
    private lateinit var userSharesText: TextView
    private lateinit var poolOwnershipText: TextView
    private lateinit var unbondingPercentText: TextView
    private lateinit var erthValueText: TextView
    private lateinit var tokenValueText: TextView
    private lateinit var tokenValueLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenKey = arguments?.getString("token_key")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_liquidity_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        refreshData()
    }

    private fun initializeViews(view: View) {
        totalSharesText = view.findViewById(R.id.total_shares_text)
        userSharesText = view.findViewById(R.id.user_shares_text)
        poolOwnershipText = view.findViewById(R.id.pool_ownership_text)
        unbondingPercentText = view.findViewById(R.id.unbonding_percent_text)
        erthValueText = view.findViewById(R.id.erth_value_text)
        tokenValueText = view.findViewById(R.id.token_value_text)
        tokenValueLabel = view.findViewById(R.id.token_value_label)

        tokenKey?.let { tokenValueLabel.text = "$it:" }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) refreshData()
    }

    fun refreshData() {
        val info = tokenKey?.let { Tokens.getTokenInfo(it) } ?: return
        lifecycleScope.launch {
            try {
                val address = SecureWalletManager.getWalletAddress(requireContext()) ?: return@launch
                val data = withContext(Dispatchers.IO) {
                    val pool = Dex.poolForToken(info.denom) ?: return@withContext null
                    val lpDenom = "dexlp/${pool.id}"
                    val totalShares = Bank.supply(lpDenom).toDouble() / 1_000_000.0
                    val userShares = Bank.balance(address, lpDenom).toDouble() / 1_000_000.0
                    val erthReserve = pool.erthReserve.toDouble() / 1_000_000.0
                    val tokenReserve = pool.tokenReserve.toDouble() / 1_000_000.0
                    PoolInfo(totalShares, userShares, erthReserve, tokenReserve)
                } ?: return@launch
                updateUI(data)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading pool data", e)
            }
        }
    }

    private fun updateUI(data: PoolInfo) {
        val ownershipPercent = if (data.totalShares > 0) (data.userShares / data.totalShares) * 100 else 0.0
        totalSharesText.text = String.format("%,.2f", data.totalShares)
        userSharesText.text = String.format("%,.2f", data.userShares)
        poolOwnershipText.text = String.format("%.4f%%", ownershipPercent)
        unbondingPercentText.text = "0%"
        erthValueText.text = String.format("%.6f", data.erthReserve * ownershipPercent / 100.0)
        tokenValueText.text = String.format("%.6f", data.tokenReserve * ownershipPercent / 100.0)
    }

    private data class PoolInfo(
        val totalShares: Double,
        val userShares: Double,
        val erthReserve: Double,
        val tokenReserve: Double,
    )
}
