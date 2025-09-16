
package com.example.earthwallet.ui.pages.managelp;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * ViewPager2 adapter for liquidity management tabs
 */
public class LiquidityTabsAdapter extends FragmentStateAdapter {

    private static final String TAG = "LiquidityTabsAdapter";
    private String tokenKey;
    private long erthReserve;
    private long tokenBReserve;
    private long totalShares;

    public LiquidityTabsAdapter(Fragment fragment, String tokenKey) {
        super(fragment);
        this.tokenKey = tokenKey;
        Log.d(TAG, "LiquidityTabsAdapter created with tokenKey: " + tokenKey);
    }
    
    public LiquidityTabsAdapter(Fragment fragment, String tokenKey, long erthReserve, long tokenBReserve, long totalShares) {
        super(fragment);
        this.tokenKey = tokenKey;
        this.erthReserve = erthReserve;
        this.tokenBReserve = tokenBReserve;
        this.totalShares = totalShares;
        Log.d(TAG, "LiquidityTabsAdapter created with tokenKey: " + tokenKey + " and pool info");
    }


    @Override
    public int getItemCount() {
        return 4; // Info, Add, Remove, Unbond
    }

    // Keep references to fragments to notify them of data changes
    private java.util.Map<Integer, Fragment> fragmentCache = new java.util.HashMap<>();

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Log.d(TAG, "createFragment called for position: " + position + " with tokenKey: " + tokenKey);
        Fragment fragment;
        switch (position) {
            case 0:
                Log.d(TAG, "Creating InfoFragment for tokenKey: " + tokenKey);
                fragment = InfoFragment.newInstance(tokenKey);
                break;
            case 1:
                Log.d(TAG, "Creating AddLiquidityFragment for tokenKey: " + tokenKey);
                fragment = AddLiquidityFragment.newInstance(tokenKey);
                break;
            case 2:
                Log.d(TAG, "Creating RemoveLiquidityFragment for tokenKey: " + tokenKey);
                fragment = RemoveLiquidityFragment.newInstance(tokenKey);
                break;
            case 3:
                Log.d(TAG, "Creating UnbondFragment for tokenKey: " + tokenKey);
                if (totalShares > 0 && erthReserve > 0 && tokenBReserve > 0) {
                    Log.d(TAG, "Using pool info for UnbondFragment - ERTH: " + erthReserve + ", Token: " + tokenBReserve + ", Shares: " + totalShares);
                    fragment = UnbondFragment.newInstance(tokenKey, erthReserve, tokenBReserve, totalShares);
                } else {
                    Log.d(TAG, "No pool info available, using basic UnbondFragment constructor");
                    fragment = UnbondFragment.newInstance(tokenKey);
                }
                break;
            default:
                Log.d(TAG, "Creating default InfoFragment for tokenKey: " + tokenKey);
                fragment = InfoFragment.newInstance(tokenKey);
                break;
        }

        // Cache the fragment so we can notify it later
        fragmentCache.put(position, fragment);
        return fragment;
    }

    // Method to notify all fragments that data has changed
    public void notifyDataChanged(LiquidityManagementComponent.LiquidityData data) {
        Log.d(TAG, "Data changed notification - notifying cached fragments");

        // Notify InfoFragment specifically since that's what shows the data
        Fragment infoFragment = fragmentCache.get(0);
        if (infoFragment instanceof InfoFragment) {
            ((InfoFragment) infoFragment).refreshData();
            Log.d(TAG, "Notified InfoFragment of data change");
        }
    }
}