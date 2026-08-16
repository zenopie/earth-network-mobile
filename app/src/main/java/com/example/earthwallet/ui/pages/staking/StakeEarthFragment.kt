package network.erth.wallet.ui.pages.staking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.services.SecureWalletManager
import java.math.BigInteger

/**
 * Native x/staking home: an info panel (staked / total bonded / APR / earnings)
 * above 5 tabs (Rewards, Stake, Withdraw, Redelegate, Unbonding).
 *
 * ERTH staking is native delegation; inflation of 1 ERTH/sec is distributed to
 * stakers, so APR = annual inflation / total bonded.
 */
class StakeEarthFragment : Fragment() {

    companion object {
        private const val TAG = "StakeEarthFragment"
        private const val ANNUAL_INFLATION_ERTH = 31_536_000.0 // 1 ERTH/sec * seconds/year
    }

    // Info section
    private var infoSection: LinearLayout? = null
    private var aprText: TextView? = null
    private var stakedAmountText: TextView? = null
    private var stakedAmountUsd: TextView? = null
    private var totalStakedText: TextView? = null
    private var totalStakedUsd: TextView? = null
    private var poolShareText: TextView? = null
    private var dailyEarningsText: TextView? = null
    private var dailyEarningsUsd: TextView? = null

    // Tabs
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var stakingAdapter: StakingTabsAdapter? = null

    private var transactionSuccessReceiver: BroadcastReceiver? = null

    // Data
    private var stakedBalance = 0.0
    private var totalStakedBalance = 0.0
    private var apr = 0.0
    private var erthPrice: Double? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stake_earth, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        setupTabs()
        refreshStakingData()
    }

    private fun initializeViews(view: View) {
        infoSection = view.findViewById(R.id.info_section)
        aprText = view.findViewById(R.id.apr_text)
        stakedAmountText = view.findViewById(R.id.staked_amount_text)
        stakedAmountUsd = view.findViewById(R.id.staked_amount_usd)
        totalStakedText = view.findViewById(R.id.total_staked_text)
        totalStakedUsd = view.findViewById(R.id.total_staked_usd)
        poolShareText = view.findViewById(R.id.pool_share_text)
        dailyEarningsText = view.findViewById(R.id.daily_earnings_text)
        dailyEarningsUsd = view.findViewById(R.id.daily_earnings_usd)

        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshStakingData()
                Handler(Looper.getMainLooper()).postDelayed({ refreshStakingData() }, 500)
            }
        }
    }

    private fun registerBroadcastReceiver() {
        if (activity != null && transactionSuccessReceiver != null) {
            val filter = IntentFilter("network.erth.wallet.TRANSACTION_SUCCESS")
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register broadcast receiver", e)
            }
        }
    }

    private fun setupTabs() {
        val tabLayout = this.tabLayout ?: return
        val viewPager = this.viewPager ?: return

        stakingAdapter = StakingTabsAdapter(this)
        viewPager.adapter = stakingAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                StakingTabsAdapter.TAB_REWARDS -> "Rewards"
                StakingTabsAdapter.TAB_STAKE -> "Stake"
                StakingTabsAdapter.TAB_UNSTAKE -> "Withdraw"
                StakingTabsAdapter.TAB_REDELEGATE -> "Redelegate"
                StakingTabsAdapter.TAB_UNBONDING -> "Unbonding"
                else -> "Tab $position"
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Show the info panel only on the Rewards tab; hide it on Stake/
                // Withdraw/Unbonding so the amount input isn't pushed under the keyboard.
                if (position == StakingTabsAdapter.TAB_REWARDS) {
                    infoSection?.visibility = View.VISIBLE
                    refreshStakingData() // pull fresh staked/APR/earnings each time it's shown
                } else {
                    infoSection?.visibility = View.GONE
                }
            }
        })
    }

    fun refreshStakingData() {
        if (!isAdded || context == null) return
        lifecycleScope.launch {
            try {
                val address = SecureWalletManager.getWalletAddress(requireContext())
                val (staked, total) = withContext(Dispatchers.IO) {
                    val stakedBase = if (address.isNullOrEmpty()) BigInteger.ZERO else
                        Staking.delegations(address).fold(BigInteger.ZERO) { acc, d ->
                            acc + (d.amount.toBigIntegerOrNull() ?: BigInteger.ZERO)
                        }
                    val totalBase = Staking.totalBonded().toBigIntegerOrNull() ?: BigInteger.ZERO
                    stakedBase to totalBase
                }
                stakedBalance = staked.toDouble() / 1_000_000.0
                totalStakedBalance = total.toDouble() / 1_000_000.0
                apr = if (totalStakedBalance > 0) (ANNUAL_INFLATION_ERTH / totalStakedBalance) * 100 else 0.0

                try {
                    erthPrice = ErthPriceService.fetchErthPrice()
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching ERTH price", e)
                }
                updateUI()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing staking data", e)
            }
        }
    }

    private fun updateUI() {
        aprText?.text = String.format("%.2f%%", apr)
        stakedAmountText?.text = String.format("%,.2f ERTH", stakedBalance)
        stakedAmountUsd?.text = erthPrice?.let { ErthPriceService.formatUSD(stakedBalance * it) } ?: ""

        totalStakedText?.text = String.format("%,.0f ERTH", totalStakedBalance)
        totalStakedUsd?.text = erthPrice?.let { ErthPriceService.formatUSD(totalStakedBalance * it) } ?: ""

        val poolShare = if (totalStakedBalance > 0) (stakedBalance / totalStakedBalance) * 100 else 0.0
        poolShareText?.text = String.format("%.4f%%", poolShare)

        val dailyEarnings = (stakedBalance * apr / 100) / 365
        dailyEarningsText?.text = String.format("%.2f ERTH", dailyEarnings)
        dailyEarningsUsd?.text = erthPrice?.let { ErthPriceService.formatUSD(dailyEarnings * it) } ?: ""
    }

    override fun onResume() {
        super.onResume()
        refreshStakingData()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: Exception) { }
        }
    }
}
