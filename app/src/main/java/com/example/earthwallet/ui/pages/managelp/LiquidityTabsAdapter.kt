package com.example.earthwallet.ui.pages.managelp

import android.util.Log
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
        Log.d(TAG, "LiquidityTabsAdapter created with tokenKey: $tokenKey")
    }

    override fun createFragment(position: Int): Fragment {
        Log.d(TAG, "createFragment called for position: $position with tokenKey: $tokenKey")
        return when (position) {
            0 -> {
                Log.d(TAG, "Creating InfoFragment for tokenKey: $tokenKey")
                InfoFragment.newInstance(tokenKey)
            }
            1 -> {
                Log.d(TAG, "Creating AddLiquidityFragment for tokenKey: $tokenKey")
                AddLiquidityFragment.newInstance(tokenKey)
            }
            2 -> {
                Log.d(TAG, "Creating RemoveLiquidityFragment for tokenKey: $tokenKey")
                RemoveLiquidityFragment.newInstance(tokenKey)
            }
            3 -> {
                Log.d(TAG, "Creating UnbondFragment for tokenKey: $tokenKey")
                UnbondFragment.newInstance(tokenKey)
            }
            else -> {
                Log.d(TAG, "Creating default InfoFragment for tokenKey: $tokenKey")
                InfoFragment.newInstance(tokenKey)
            }
        }
    }

    override fun getItemCount(): Int = 4 // Info, Add, Remove, Unbond
}