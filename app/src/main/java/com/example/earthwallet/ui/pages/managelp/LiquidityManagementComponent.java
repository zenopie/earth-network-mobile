package com.example.earthwallet.ui.pages.managelp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Component for managing liquidity operations (Add/Remove/Unbond)
 * Displays in tabs: Info, Add, Remove, Unbond
 */
public class LiquidityManagementComponent extends Fragment {

    // Centralized data structure for all tab information
    public static class LiquidityData {
        // Pool info
        public double totalShares = 0.0;
        public double userStakedShares = 0.0;
        public double poolOwnershipPercent = 0.0;
        public double unbondingPercent = 0.0;
        public double userErthValue = 0.0;
        public double userTokenValue = 0.0;

        // Balances for Add tab
        public double tokenBalance = 0.0;
        public double erthBalance = 0.0;
        public double erthReserve = 0.0;
        public double tokenReserve = 0.0;

        // Unbonding data
        public java.util.List<UnbondingRequest> unbondingRequests = new java.util.ArrayList<>();

        public static class UnbondingRequest {
            public double shares;
            public String timeRemaining;
            public double erthValue;
            public double tokenValue;
        }
    }

    private static final String TAG = "LiquidityManagement";
    private static final String ARG_TOKEN_KEY = "token_key";
    private static final String ARG_PENDING_REWARDS = "pending_rewards";
    private static final String ARG_LIQUIDITY = "liquidity";
    private static final String ARG_VOLUME = "volume";
    private static final String ARG_APR = "apr";
    
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private LinearLayout closeButton;
    private TextView titleText;
    private ImageView poolTokenLogo;
    private ManageLPFragment.PoolData poolData;
    
    // Current tab views
    private View infoTabView;
    private View addTabView;
    private View removeTabView;
    private View unbondTabView;
    
    // Add liquidity fields
    private EditText erthAmountInput;
    private EditText tokenAmountInput;
    private TextView erthBalanceText;
    private TextView tokenBalanceText;
    private Button addLiquidityButton;
    
    // Remove liquidity fields
    private EditText removeAmountInput;
    private TextView stakedSharesText;
    private Button removeLiquidityButton;
    
    // Info tab fields
    private TextView totalSharesText;
    private TextView userSharesText;
    private TextView poolOwnershipText;
    private TextView unbondingPercentText;
    private TextView erthValueText;
    private TextView tokenValueText;
    private TextView tokenValueLabel;
    
    // Pool data
    private String tokenKey;
    private String pendingRewards;
    private String liquidity;
    private String volume;
    private String apr;
    
    // Detailed pool state (from contract queries)
    private JSONObject poolState;
    private JSONObject userInfo;
    private SecretQueryService queryService;
    private ExecutorService executorService;

    // Centralized data for all tabs
    private LiquidityData liquidityData;
    private BroadcastReceiver transactionSuccessReceiver;


    public LiquidityManagementComponent() {
        // Required empty public constructor
    }

    public static LiquidityManagementComponent newInstance(ManageLPFragment.PoolData poolData) {
        LiquidityManagementComponent fragment = new LiquidityManagementComponent();
        Bundle args = new Bundle();
        args.putString(ARG_TOKEN_KEY, poolData.getTokenKey());
        args.putString(ARG_PENDING_REWARDS, poolData.getPendingRewards());
        args.putString(ARG_LIQUIDITY, poolData.getLiquidity());
        args.putString(ARG_VOLUME, poolData.getVolume());
        args.putString(ARG_APR, poolData.getApr());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.component_liquidity_management, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Extract arguments FIRST before setting up tabs
        if (getArguments() != null) {
            tokenKey = getArguments().getString(ARG_TOKEN_KEY);
            pendingRewards = getArguments().getString(ARG_PENDING_REWARDS);
            liquidity = getArguments().getString(ARG_LIQUIDITY);
            volume = getArguments().getString(ARG_VOLUME);
            apr = getArguments().getString(ARG_APR);
            
            Log.d(TAG, "Managing liquidity for token: " + tokenKey);
        }
        
        initializeViews(view);
        setupTabs();  // Now tokenKey is available
        setupCloseButton();
        setupBroadcastReceiver();
        registerBroadcastReceiver();

        // Initialize services and data
        queryService = new SecretQueryService(getContext());
        executorService = Executors.newCachedThreadPool();
        liquidityData = new LiquidityData();
        
        if (tokenKey != null) {
            // Update pool title and logo
            TextView poolTitle = getView() != null ? getView().findViewById(R.id.pool_title) : null;
            ImageView poolLogo = getView() != null ? getView().findViewById(R.id.pool_token_logo) : null;
            
            if (poolTitle != null) {
                poolTitle.setText(tokenKey + " Pool");
            }
            
            if (poolLogo != null) {
                setTokenLogo(poolLogo, tokenKey);
            }
            
            // Load all liquidity data initially
            loadAllLiquidityData();
        }
    }
    
    private void initializeViews(View view) {
        tabLayout = view.findViewById(R.id.tab_layout);
        viewPager = view.findViewById(R.id.view_pager);
        closeButton = view.findViewById(R.id.close_button);
        titleText = view.findViewById(R.id.title_text);
        poolTokenLogo = view.findViewById(R.id.pool_token_logo);
    }
    
    private void setupTabs() {
        if (tabLayout == null || viewPager == null) return;
        
        // Create simplified adapter
        LiquidityTabsAdapter adapter = new LiquidityTabsAdapter(this, tokenKey);
        
        viewPager.setAdapter(adapter);
        
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> {
                    switch (position) {
                        case 0: tab.setText("Info"); break;
                        case 1: tab.setText("Add"); break;
                        case 2: tab.setText("Remove"); break;
                        case 3: tab.setText("Unbond"); break;
                    }
                }).attach();

        // Listen for tab changes to refresh data when Info tab becomes visible
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                Log.d(TAG, "ViewPager page changed to position: " + position);

                // When Info tab (position 0) is selected, refresh data
                if (position == 0) {
                    Log.d(TAG, "Info tab selected - refreshing data");
                    loadAllLiquidityData();
                }
            }
        });
    }
    
    private void setupCloseButton() {
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> {
                Log.d(TAG, "Closing liquidity management");
                // Return to pool overview
                if (getParentFragment() instanceof ManageLPFragment) {
                    ((ManageLPFragment) getParentFragment()).toggleManageLiquidity(null);
                }
            });
        }
    }
    
    // Tab content creation methods
    public View createInfoTab(ViewGroup container) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.tab_liquidity_info, container, false);
        
        initializeInfoViews(view);
        updateInfoTab();
        
        return view;
    }
    
    public View createAddTab(ViewGroup container) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.tab_liquidity_add, container, false);
        
        initializeAddViews(view);
        setupAddTabListeners();
        
        return view;
    }
    
    public View createRemoveTab(ViewGroup container) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.tab_liquidity_remove, container, false);
        
        initializeRemoveViews(view);
        setupRemoveTabListeners();
        
        return view;
    }
    
    public View createUnbondTab(ViewGroup container) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.tab_liquidity_unbond, container, false);
        
        // TODO: Initialize unbond views and setup listeners
        
        return view;
    }
    
    private void initializeInfoViews(View view) {
        totalSharesText = view.findViewById(R.id.total_shares_text);
        userSharesText = view.findViewById(R.id.user_shares_text);
        poolOwnershipText = view.findViewById(R.id.pool_ownership_text);
        unbondingPercentText = view.findViewById(R.id.unbonding_percent_text);
        erthValueText = view.findViewById(R.id.erth_value_text);
        tokenValueText = view.findViewById(R.id.token_value_text);
        tokenValueLabel = view.findViewById(R.id.token_value_label);
        
        // Set the token label
        if (tokenValueLabel != null && tokenKey != null) {
            tokenValueLabel.setText(tokenKey + ":");
        }
    }
    
    private void initializeAddViews(View view) {
        erthAmountInput = view.findViewById(R.id.erth_amount_input);
        tokenAmountInput = view.findViewById(R.id.token_amount_input);
        erthBalanceText = view.findViewById(R.id.erth_balance_text);
        tokenBalanceText = view.findViewById(R.id.token_balance_text);
        addLiquidityButton = view.findViewById(R.id.add_liquidity_button);
    }
    
    private void initializeRemoveViews(View view) {
        removeAmountInput = view.findViewById(R.id.remove_amount_input);
        stakedSharesText = view.findViewById(R.id.staked_shares_text);
        removeLiquidityButton = view.findViewById(R.id.remove_liquidity_button);
    }
    
    private void queryPoolState() {
        if (tokenKey == null) return;
        
        executorService.execute(() -> {
            try {
                String userAddress = SecureWalletManager.getWalletAddress(getContext());
                if (userAddress == null) {
                    Log.w(TAG, "No user address available");
                    return;
                }
                
                // Get token contract address based on tokenKey
                String tokenContract = getTokenContract(tokenKey);
                if (tokenContract == null) {
                    Log.w(TAG, "No contract found for token: " + tokenKey);
                    return;
                }
                
                // Query like React app: query_user_info with pools array
                JSONObject queryMsg = new JSONObject();
                JSONObject queryUserInfo = new JSONObject();
                JSONArray poolsArray = new JSONArray();
                poolsArray.put(tokenContract);
                queryUserInfo.put("pools", poolsArray);
                queryUserInfo.put("user", userAddress);
                queryMsg.put("query_user_info", queryUserInfo);
                
                Log.d(TAG, "Querying detailed pool state for: " + tokenKey);
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                );
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        processPoolStateResult(result);
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error querying pool state", e);
            }
        });
    }
    
    private void processPoolStateResult(JSONObject result) {
        try {
            if (result != null && result.has("data")) {
                JSONArray dataArray = result.getJSONArray("data");
                if (dataArray.length() > 0) {
                    JSONObject poolData = dataArray.getJSONObject(0);
                    
                    // Store the detailed pool state
                    if (poolData.has("pool_info")) {
                        poolState = poolData.getJSONObject("pool_info");
                    }
                    if (poolData.has("user_info")) {
                        userInfo = poolData.getJSONObject("user_info");
                    }
                    
                    // Update the Info tab with real data
                    updateInfoTab();

                    // Don't recreate tabs - just update the existing adapter
                    Log.d(TAG, "Pool state loaded, updating existing tabs with pool information");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing pool state result", e);
        }
    }
    
    private String getTokenContract(String tokenSymbol) {
        // Map token symbols to contract addresses (from React tokens.js)
        switch (tokenSymbol.toUpperCase()) {
            case "ANML":
                return "secret14p6dhjznntlzw0yysl7p6z069nk0skv5e9qjut";
            case "SSCRT":
                return "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek";
            default:
                return null;
        }
    }
    
    private void updateInfoTab() {
        if (poolState == null || userInfo == null) {
            // Fallback to basic data if detailed state not available
            if (totalSharesText != null) {
                totalSharesText.setText("Total Liquidity: " + formatNumber(liquidity) + " ERTH");
            }
            if (userSharesText != null) {
                userSharesText.setText("Pending Rewards: " + formatNumber(pendingRewards) + " ERTH");
            }
            if (poolOwnershipText != null) {
                poolOwnershipText.setText("7d Volume: " + formatNumber(volume) + " ERTH");
            }
            if (unbondingPercentText != null) {
                unbondingPercentText.setText("APR: " + apr);
            }
            return;
        }
        
        try {
            // Extract data like React app does
            JSONObject state = poolState.optJSONObject("state");
            if (state != null) {
                // Total shares and user shares (converted from micro to macro units)
                long totalSharesMicro = state.optLong("total_shares", 0);
                long userStakedMicro = userInfo.optLong("amount_staked", 0);
                long unbondingSharesMicro = state.optLong("unbonding_shares", 0);
                
                double totalShares = totalSharesMicro / 1000000.0;
                double userStaked = userStakedMicro / 1000000.0;
                double unbondingShares = unbondingSharesMicro / 1000000.0;
                
                // Pool ownership percentage
                double ownershipPercent = totalShares > 0 ? (userStaked / totalShares) * 100 : 0;
                
                // Unbonding percentage
                double unbondingPercent = totalShares > 0 ? (unbondingShares / totalShares) * 100 : 0;
                
                // Update UI with calculated values
                if (totalSharesText != null) {
                    totalSharesText.setText(String.format("Total Pool Shares: %,.0f", totalShares));
                }
                
                if (userSharesText != null) {
                    userSharesText.setText(String.format("Your Shares: %,.0f", userStaked));
                }
                
                if (poolOwnershipText != null) {
                    poolOwnershipText.setText(String.format("Pool Ownership: %.4f%%", ownershipPercent));
                }
                
                if (unbondingPercentText != null) {
                    unbondingPercentText.setText(String.format("%.4f%%", unbondingPercent));
                }
                
                // Calculate underlying values like React app
                calculateUnderlyingValues(state, ownershipPercent);
                
                Log.d(TAG, "Updated Info tab with real pool state data");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating info tab", e);
        }
    }
    
    private String formatNumber(String numberStr) {
        if (numberStr == null || numberStr.trim().isEmpty()) {
            return "0";
        }
        
        try {
            double number = Double.parseDouble(numberStr.replace(",", ""));
            
            if (number == 0) {
                return "0";
            }
            
            java.text.DecimalFormat formatter;
            if (number >= 1000000) {
                formatter = new java.text.DecimalFormat("#.#M");
                return formatter.format(number / 1000000);
            } else if (number >= 1000) {
                formatter = new java.text.DecimalFormat("#.#K");
                return formatter.format(number / 1000);
            } else if (number >= 1) {
                formatter = new java.text.DecimalFormat("#.#");
                return formatter.format(number);
            } else {
                formatter = new java.text.DecimalFormat("#.###");
                return formatter.format(number);
            }
        } catch (NumberFormatException e) {
            return numberStr;
        }
    }
    

    private void setupBroadcastReceiver() {
        transactionSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Received TRANSACTION_SUCCESS broadcast - refreshing all liquidity data");
                // Refresh all data when any transaction completes
                loadAllLiquidityData();
            }
        };
    }

    private void registerBroadcastReceiver() {
        if (getActivity() != null && transactionSuccessReceiver != null) {
            IntentFilter filter = new IntentFilter("com.example.earthwallet.TRANSACTION_SUCCESS");
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().getApplicationContext().registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    requireActivity().getApplicationContext().registerReceiver(transactionSuccessReceiver, filter);
                }
                Log.d(TAG, "Registered transaction success receiver");
            } catch (Exception e) {
                Log.e(TAG, "Failed to register broadcast receiver", e);
            }
        }
    }

    // Centralized method to load all data for all tabs
    private void loadAllLiquidityData() {
        if (tokenKey == null) return;

        executorService.execute(() -> {
            try {
                String userAddress = SecureWalletManager.getWalletAddress(getContext());
                if (userAddress == null) {
                    Log.w(TAG, "No user address available");
                    return;
                }

                String tokenContract = getTokenContract(tokenKey);
                if (tokenContract == null) {
                    Log.w(TAG, "No contract found for token: " + tokenKey);
                    return;
                }

                // Query all pool data in one call
                JSONObject queryMsg = new JSONObject();
                JSONObject queryUserInfo = new JSONObject();
                JSONArray poolsArray = new JSONArray();
                poolsArray.put(tokenContract);
                queryUserInfo.put("pools", poolsArray);
                queryUserInfo.put("user", userAddress);
                queryMsg.put("query_user_info", queryUserInfo);

                Log.d(TAG, "Loading all liquidity data for: " + tokenKey);
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                );

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        processAllLiquidityData(result);
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error loading all liquidity data", e);
            }
        });
    }

    private void processAllLiquidityData(JSONObject result) {
        try {
            if (result != null && result.has("data")) {
                JSONArray dataArray = result.getJSONArray("data");
                if (dataArray.length() > 0) {
                    JSONObject poolData = dataArray.getJSONObject(0);

                    // Update pool state and user info
                    if (poolData.has("pool_info")) {
                        poolState = poolData.getJSONObject("pool_info");
                    }
                    if (poolData.has("user_info")) {
                        userInfo = poolData.getJSONObject("user_info");
                    }

                    // Process all the data into our centralized structure
                    updateLiquidityData();

                    Log.d(TAG, "Successfully processed all liquidity data");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing all liquidity data", e);
        }
    }

    private void updateLiquidityData() {
        // Extract all data from poolState and userInfo into liquidityData
        if (poolState != null && userInfo != null) {
            try {
                JSONObject state = poolState.optJSONObject("state");
                if (state != null) {
                    // Pool info calculations
                    long totalSharesMicro = state.optLong("total_shares", 0);
                    long userStakedMicro = userInfo.optLong("amount_staked", 0);
                    long unbondingSharesMicro = state.optLong("unbonding_shares", 0);
                    long erthReserveMicro = state.optLong("erth_reserve", 0);
                    long tokenBReserveMicro = state.optLong("token_b_reserve", 0);

                    // Convert to macro units
                    liquidityData.totalShares = totalSharesMicro / 1000000.0;
                    liquidityData.userStakedShares = userStakedMicro / 1000000.0;
                    liquidityData.erthReserve = erthReserveMicro / 1000000.0;
                    liquidityData.tokenReserve = tokenBReserveMicro / 1000000.0;

                    // Calculate percentages
                    liquidityData.poolOwnershipPercent = liquidityData.totalShares > 0 ?
                        (liquidityData.userStakedShares / liquidityData.totalShares) * 100 : 0;
                    liquidityData.unbondingPercent = liquidityData.totalShares > 0 ?
                        (unbondingSharesMicro / 1000000.0 / liquidityData.totalShares) * 100 : 0;

                    // Calculate underlying values
                    liquidityData.userErthValue = (liquidityData.erthReserve * liquidityData.poolOwnershipPercent) / 100.0;
                    liquidityData.userTokenValue = (liquidityData.tokenReserve * liquidityData.poolOwnershipPercent) / 100.0;

                    Log.d(TAG, "Updated centralized liquidity data - User shares: " + liquidityData.userStakedShares +
                          ", Pool ownership: " + liquidityData.poolOwnershipPercent + "%");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating liquidity data", e);
            }
        }
    }


    // Getter for tabs to access centralized data
    public LiquidityData getLiquidityData() {
        return liquidityData;
    }

    // Method called by InfoFragment when it becomes visible to refresh data
    public void refreshInfoTabData() {
        Log.d(TAG, "InfoFragment requested data refresh");
        loadAllLiquidityData();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        // Unregister broadcast receiver
        if (transactionSuccessReceiver != null && getContext() != null) {
            try {
                requireActivity().getApplicationContext().unregisterReceiver(transactionSuccessReceiver);
                Log.d(TAG, "Unregistered transaction success receiver");
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Receiver was not registered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }
    }
    
    private void setupAddTabListeners() {
        if (addLiquidityButton != null) {
            addLiquidityButton.setOnClickListener(v -> handleAddLiquidity());
        }
        
        // TODO: Setup ratio synchronization between ERTH and token amounts
        // TODO: Setup max buttons for balance inputs
    }
    
    private void setupRemoveTabListeners() {
        if (removeLiquidityButton != null) {
            removeLiquidityButton.setOnClickListener(v -> handleRemoveLiquidity());
        }
        
        // TODO: Setup max button for staked shares
    }
    
    private void handleAddLiquidity() {
        Log.d(TAG, "Adding liquidity");
        
        if (erthAmountInput == null || tokenAmountInput == null) return;
        
        String erthAmount = erthAmountInput.getText().toString();
        String tokenAmount = tokenAmountInput.getText().toString();
        
        if (erthAmount.isEmpty() || tokenAmount.isEmpty()) {
            Log.w(TAG, "Amount inputs are empty");
            return;
        }
        
        Log.d(TAG, "Adding liquidity: " + erthAmount + " ERTH, " + tokenAmount + " token");
        
        // TODO: Implement actual liquidity provision
        // This should call the exchange contract to provide liquidity
    }
    
    private void handleRemoveLiquidity() {
        Log.d(TAG, "Removing liquidity");
        
        if (removeAmountInput == null) return;
        
        String removeAmount = removeAmountInput.getText().toString();
        
        if (removeAmount.isEmpty()) {
            Log.w(TAG, "Remove amount is empty");
            return;
        }
        
        Log.d(TAG, "Removing liquidity: " + removeAmount + " shares");
        
        // TODO: Implement actual liquidity removal
        // This should call the exchange contract to remove liquidity
    }
    
    private void calculateUnderlyingValues(JSONObject state, double ownershipPercent) {
        try {
            if (ownershipPercent <= 0) {
                // No ownership, show zero values
                if (erthValueText != null) {
                    erthValueText.setText("0.000000");
                }
                if (tokenValueText != null) {
                    tokenValueText.setText("0.000000");
                }
                return;
            }
            
            // Get reserves from pool state (in micro units)
            long erthReserveMicro = state.optLong("erth_reserve", 0);
            long tokenBReserveMicro = state.optLong("token_b_reserve", 0);
            
            // Convert to macro units (divide by 1,000,000) like React app toMacroUnits
            double erthReserveMacro = erthReserveMicro / 1000000.0;
            double tokenBReserveMacro = tokenBReserveMicro / 1000000.0;
            
            // Calculate user's underlying value like React app:
            // userErthValue = (erthReserveMacro * ownershipPercent) / 100
            // userTokenBValue = (tokenBReserveMacro * ownershipPercent) / 100
            double userErthValue = (erthReserveMacro * ownershipPercent) / 100.0;
            double userTokenBValue = (tokenBReserveMacro * ownershipPercent) / 100.0;
            
            // Update UI with calculated values (6 decimal places like React)
            if (erthValueText != null) {
                erthValueText.setText(String.format("%.6f", userErthValue));
            }
            
            if (tokenValueText != null) {
                tokenValueText.setText(String.format("%.6f", userTokenBValue));
            }
            
            Log.d(TAG, String.format("Calculated underlying values - ERTH: %.6f, %s: %.6f (%.4f%% ownership)", 
                    userErthValue, tokenKey, userTokenBValue, ownershipPercent));
                    
        } catch (Exception e) {
            Log.e(TAG, "Error calculating underlying values", e);
        }
    }
    
    private void setTokenLogo(ImageView imageView, String tokenKey) {
        if (imageView == null || getContext() == null || tokenKey == null) return;
        
        try {
            // Try to load token logo from assets
            String assetPath = "coin/" + tokenKey.toUpperCase() + ".png";
            loadImageFromAssets(assetPath, imageView);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load token logo for " + tokenKey + ": " + e.getMessage());
            // Fallback to default
            imageView.setImageResource(R.drawable.ic_token_default);
        }
    }
    
    private void loadImageFromAssets(String assetPath, ImageView imageView) {
        try {
            InputStream inputStream = getContext().getAssets().open(assetPath);
            Drawable drawable = Drawable.createFromStream(inputStream, null);
            imageView.setImageDrawable(drawable);
            inputStream.close();
            Log.d(TAG, "Successfully loaded logo from: " + assetPath);
        } catch (IOException e) {
            Log.w(TAG, "Failed to load asset: " + assetPath + ", using default");
            imageView.setImageResource(R.drawable.ic_token_default);
        }
    }
    
    // Interface for communicating with parent fragment
    public interface LiquidityManagementListener {
        void onLiquidityOperationComplete();
        void onCloseLiquidityManagement();
    }
}