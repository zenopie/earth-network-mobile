
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

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Log.d(TAG, "createFragment called for position: " + position + " with tokenKey: " + tokenKey);
        switch (position) {
            case 0: 
                Log.d(TAG, "Creating InfoFragment for tokenKey: " + tokenKey);
                return InfoFragment.newInstance(tokenKey);
            case 1: 
                Log.d(TAG, "Creating AddLiquidityFragment for tokenKey: " + tokenKey);
                return AddLiquidityFragment.newInstance(tokenKey);
            case 2: 
                Log.d(TAG, "Creating RemoveLiquidityFragment for tokenKey: " + tokenKey);
                return RemoveLiquidityFragment.newInstance(tokenKey);
            case 3: 
                Log.d(TAG, "Creating UnbondFragment for tokenKey: " + tokenKey);
                if (totalShares > 0 && erthReserve > 0 && tokenBReserve > 0) {
                    Log.d(TAG, "Using pool info for UnbondFragment - ERTH: " + erthReserve + ", Token: " + tokenBReserve + ", Shares: " + totalShares);
                    return UnbondFragment.newInstance(tokenKey, erthReserve, tokenBReserve, totalShares);
                } else {
                    Log.d(TAG, "No pool info available, using basic UnbondFragment constructor");
                    return UnbondFragment.newInstance(tokenKey);
                }
            default: 
                Log.d(TAG, "Creating default InfoFragment for tokenKey: " + tokenKey);
                return InfoFragment.newInstance(tokenKey);
        }
    }

    @Override
    public int getItemCount() {
        return 4; // Info, Add, Remove, Unbond
    }
}