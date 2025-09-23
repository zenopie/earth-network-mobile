package network.erth.wallet.ui.pages.staking

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter for staking management tabs
 * Based on LiquidityTabsAdapter for consistent styling
 */
class StakingTabsAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    companion object {
        private const val TAG = "StakingTabsAdapter"
    }

    init {
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                StakingInfoFragment.newInstance()
            }
            1 -> {
                StakeUnstakeFragment.newInstance()
            }
            2 -> {
                UnbondingFragment.newInstance()
            }
            else -> {
                StakingInfoFragment.newInstance()
            }
        }
    }

    override fun getItemCount(): Int = 3 // Info & Rewards, Stake/Unstake, Unbonding
}