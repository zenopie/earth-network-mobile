package com.example.earthwallet.ui.pages.managelp

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter for liquidity management tabs
 * Simplified approach following StakingTabsAdapter pattern
 */
class LiquidityTabsAdapter(
    fragment: Fragment,
    private val tokenKey: String
) : FragmentStateAdapter(fragment) {

    companion object {
        private const val TAG = "LiquidityTabsAdapter"
    }

    init {
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                InfoFragment.newInstance(tokenKey)
            }
            1 -> {
                AddLiquidityFragment.newInstance(tokenKey)
            }
            2 -> {
                RemoveLiquidityFragment.newInstance(tokenKey)
            }
            3 -> {
                UnbondFragment.newInstance(tokenKey)
            }
            else -> {
                InfoFragment.newInstance(tokenKey)
            }
        }
    }

    override fun getItemCount(): Int = 4 // Info, Add, Remove, Unbond
}