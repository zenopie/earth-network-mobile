package network.erth.wallet.ui.pages.staking

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter for staking management tabs
 * 4 tabs: Rewards, Stake, Unstake, Unbonding
 */
class StakingTabsAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    companion object {
        const val TAB_REWARDS = 0
        const val TAB_STAKE = 1
        const val TAB_UNSTAKE = 2
        const val TAB_UNBONDING = 3
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            TAB_REWARDS -> RewardsFragment.newInstance()
            TAB_STAKE -> StakeFragment.newInstance()
            TAB_UNSTAKE -> UnstakeFragment.newInstance()
            TAB_UNBONDING -> UnbondingFragment.newInstance()
            else -> RewardsFragment.newInstance()
        }
    }

    override fun getItemCount(): Int = 4
}
