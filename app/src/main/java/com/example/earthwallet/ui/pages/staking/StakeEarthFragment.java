package com.example.earthwallet.ui.pages.staking;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main staking fragment that manages ERTH token staking
 * Features tabs for Info & Rewards, Stake/Unstake, and Unbonding
 */
public class StakeEarthFragment extends Fragment {
    
    private static final String TAG = "StakeEarthFragment";
    
    // UI Components
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    
    // Data and Services
    private SecretQueryService queryService;
    private ExecutorService executorService;
    
    // Adapter
    private StakingTabsAdapter stakingAdapter;
    
    // Interface for communication with parent
    public interface StakeEarthListener {
        String getCurrentWalletAddress();
        void onStakingOperationComplete();
    }
    
    private StakeEarthListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof StakeEarthListener) {
            listener = (StakeEarthListener) getParentFragment();
        } else if (context instanceof StakeEarthListener) {
            listener = (StakeEarthListener) context;
        } else {
            Log.w(TAG, "Parent does not implement StakeEarthListener");
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stake_earth, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize services
        queryService = new SecretQueryService(getContext());
        executorService = Executors.newCachedThreadPool();
        
        initializeViews(view);
        setupTabs();
    }
    
    private void initializeViews(View view) {
        tabLayout = view.findViewById(R.id.tab_layout);
        viewPager = view.findViewById(R.id.view_pager);
    }
    
    private void setupTabs() {
        if (tabLayout == null || viewPager == null) return;
        
        // Create adapter
        stakingAdapter = new StakingTabsAdapter(this);
        viewPager.setAdapter(stakingAdapter);
        
        // Connect TabLayout with ViewPager2
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> {
                    switch (position) {
                        case 0: tab.setText("Info & Rewards"); break;
                        case 1: tab.setText("Stake/Unstake"); break;
                        case 2: tab.setText("Unbonding"); break;
                    }
                }).attach();
    }
    
    
    /**
     * Public method to refresh staking data across all fragments
     */
    public void refreshStakingData() {
        Log.d(TAG, "Refreshing staking data for all fragments");
        
        // With ViewPager2, we need to notify fragments differently
        if (stakingAdapter != null) {
            // The fragments are managed by the adapter, so we can trigger refresh
            // through the current fragment if needed
            stakingAdapter.notifyDataSetChanged();
        }
    }
    
    /**
     * Query user staking info from contract
     */
    public void queryUserStakingInfo(UserStakingCallback callback) {
        executorService.execute(() -> {
            try {
                String userAddress = SecureWalletManager.getWalletAddress(getContext());
                if (userAddress == null || userAddress.isEmpty()) {
                    Log.w(TAG, "No user address available");
                    return;
                }
                
                // Create query message: { get_user_info: { address: "secret1..." } }
                JSONObject queryMsg = new JSONObject();
                JSONObject getUserInfo = new JSONObject();
                getUserInfo.put("address", userAddress);
                queryMsg.put("get_user_info", getUserInfo);
                
                Log.d(TAG, "Querying staking contract: " + Constants.STAKING_CONTRACT);
                Log.d(TAG, "Query message: " + queryMsg.toString());
                
                JSONObject result = queryService.queryContract(
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH,
                    queryMsg
                );
                
                Log.d(TAG, "Staking query result: " + result.toString());
                
                if (callback != null && getActivity() != null) {
                    getActivity().runOnUiThread(() -> callback.onStakingDataReceived(result));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error querying staking info", e);
                if (callback != null && getActivity() != null) {
                    getActivity().runOnUiThread(() -> callback.onError(e.getMessage()));
                }
            }
        });
    }
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
    
    /**
     * Callback interface for staking data queries
     */
    public interface UserStakingCallback {
        void onStakingDataReceived(JSONObject data);
        void onError(String error);
    }
}