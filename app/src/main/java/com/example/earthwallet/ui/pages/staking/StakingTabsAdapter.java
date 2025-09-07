package com.example.earthwallet.ui.pages.staking;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * ViewPager2 adapter for staking management tabs
 * Based on LiquidityTabsAdapter for consistent styling
 */
public class StakingTabsAdapter extends FragmentStateAdapter {

    private static final String TAG = "StakingTabsAdapter";

    public StakingTabsAdapter(Fragment fragment) {
        super(fragment);
        Log.d(TAG, "StakingTabsAdapter created");
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Log.d(TAG, "createFragment called for position: " + position);
        switch (position) {
            case 0: 
                Log.d(TAG, "Creating StakingInfoFragment");
                return StakingInfoFragment.newInstance();
            case 1: 
                Log.d(TAG, "Creating StakeUnstakeFragment");
                return StakeUnstakeFragment.newInstance();
            case 2: 
                Log.d(TAG, "Creating UnbondingFragment");
                return UnbondingFragment.newInstance();
            default: 
                Log.d(TAG, "Creating default StakingInfoFragment");
                return StakingInfoFragment.newInstance();
        }
    }

    @Override
    public int getItemCount() {
        return 3; // Info & Rewards, Stake/Unstake, Unbonding
    }
}