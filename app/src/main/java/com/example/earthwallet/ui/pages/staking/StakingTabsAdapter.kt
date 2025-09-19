package com.example.earthwallet.ui.pages.staking

import android.util.Log
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
        Log.d(TAG, "StakingTabsAdapter created")
    }

    override fun createFragment(position: Int): Fragment {
        Log.d(TAG, "createFragment called for position: $position")
        return when (position) {
            0 -> {
                Log.d(TAG, "Creating StakingInfoFragment")
                StakingInfoFragment.newInstance()
            }
            1 -> {
                Log.d(TAG, "Creating StakeUnstakeFragment")
                StakeUnstakeFragment.newInstance()
            }
            2 -> {
                Log.d(TAG, "Creating UnbondingFragment")
                UnbondingFragment.newInstance()
            }
            else -> {
                Log.d(TAG, "Creating default StakingInfoFragment")
                StakingInfoFragment.newInstance()
            }
        }
    }

    override fun getItemCount(): Int = 3 // Info & Rewards, Stake/Unstake, Unbonding
}