package network.erth.wallet.ui.pages.gasstation

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter for gas station tabs
 * Based on StakingTabsAdapter for consistent styling
 */
class GasStationTabsAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    companion object {
        private const val TAG = "GasStationTabsAdapter"
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                SwapForGasFragment.newInstance()
            }
            1 -> {
                WrapUnwrapFragment.newInstance()
            }
            else -> {
                SwapForGasFragment.newInstance()
            }
        }
    }

    override fun getItemCount(): Int = 2 // Swap for Gas, Wrap/Unwrap
}