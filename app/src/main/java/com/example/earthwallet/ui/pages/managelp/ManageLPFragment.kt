package network.erth.wallet.ui.pages.managelp

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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Dex
import network.erth.wallet.ui.components.LoadingOverlay
import network.erth.wallet.wallet.constants.Tokens

/**
 * Pool overview for the earth x/dex: lists each ERTH-paired pool with its liquidity,
 * and lets the user open a pool to add/remove liquidity. LP rewards auto-compound on
 * earth, so there is no per-pool reward claiming here.
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

    private var isManagingLiquidity = false

    private val allPoolsData = mutableListOf<PoolData>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        rootView = inflater.inflate(R.layout.fragment_manage_lp, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupRecyclerView()
        updateTitleBackground()
        refreshPoolData()
    }

    private fun initializeViews(view: View) {
        poolsRecyclerView = view.findViewById(R.id.pools_recycler_view)
        totalRewardsText = view.findViewById(R.id.total_rewards_text)
        claimAllButton = view.findViewById(R.id.claim_all_button)
        claimAllContainer = view.findViewById(R.id.claim_all_container)
        liquidityManagementContainer = view.findViewById(R.id.liquidity_management_container)
        loadingOverlay = view.findViewById(R.id.loading_overlay)
        loadingOverlay?.initializeWithFragment(this)

        // Rewards auto-compound on earth; there is no manual claim-all.
        claimAllContainer.visibility = View.GONE
    }

    private fun setupRecyclerView() {
        poolAdapter = PoolOverviewAdapter(allPoolsData, object : PoolOverviewAdapter.PoolClickListener {
            override fun onManageClicked(poolData: PoolData) {
                toggleManageLiquidity(poolData)
            }
            override fun onClaimClicked(poolData: PoolData) { /* no-op: rewards auto-compound */ }
        })
        poolsRecyclerView.layoutManager = LinearLayoutManager(context)
        poolsRecyclerView.adapter = poolAdapter
    }

    private fun refreshPoolData() {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val pools = withContext(Dispatchers.IO) { Dex.pools() }
                val newData = pools.mapNotNull { pool ->
                    val info = Tokens.getTokenInfo(pool.tokenDenom) ?: return@mapNotNull null
                    val liquidity = 2.0 * (pool.erthReserve.toDoubleOrNull() ?: 0.0) / 1_000_000.0
                    PoolData(
                        tokenKey = info.symbol,
                        pendingRewards = "0",
                        liquidity = String.format("%.0f", liquidity),
                        volume = "0",
                        apr = "—",
                        unbondingShares = "0",
                        tokenInfo = info,
                    )
                }
                allPoolsData.clear()
                allPoolsData.addAll(newData)
                poolAdapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Error querying pool data", e)
                allPoolsData.clear()
                poolAdapter.notifyDataSetChanged()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay?.let { if (show) it.show() else it.hide() }
    }

    fun toggleManageLiquidity(poolData: PoolData?) {
        if (isManagingLiquidity) {
            isManagingLiquidity = false
            showPoolOverview()
        } else if (poolData != null) {
            isManagingLiquidity = true
            showLiquidityManagement(poolData)
        }
    }

    private fun showPoolOverview() {
        poolsRecyclerView.visibility = View.VISIBLE
        liquidityManagementContainer.visibility = View.GONE
        updateTitleBackground()
    }

    private fun showLiquidityManagement(poolData: PoolData) {
        poolsRecyclerView.visibility = View.GONE
        claimAllContainer.visibility = View.GONE
        liquidityManagementContainer.visibility = View.VISIBLE

        val liquidityComponent = LiquidityManagementComponent.newInstance(poolData)
        childFragmentManager.beginTransaction()
            .replace(R.id.liquidity_management_container, liquidityComponent)
            .commit()
        updateTitleBackground()
    }

    private fun updateTitleBackground() {
        rootView?.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                if (isManagingLiquidity) android.R.color.white else R.color.desktop_bg
            )
        )
    }

    // Data class for pool overview rows.
    data class PoolData(
        val tokenKey: String,
        var pendingRewards: String,
        var liquidity: String,
        var volume: String,
        var apr: String,
        var unbondingShares: String,
        val tokenInfo: Tokens.TokenInfo?
    )

    override fun onDestroy() {
        super.onDestroy()
        if (loadingOverlay != null && context != null) {
            loadingOverlay!!.cleanup(context)
        }
    }
}
