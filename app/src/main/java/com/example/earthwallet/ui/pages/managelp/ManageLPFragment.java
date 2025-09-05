package com.example.earthwallet.ui.pages.managelp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import androidx.core.content.ContextCompat;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main fragment for managing liquidity pools
 * Displays pool overviews and manages LP functionality
 */
public class ManageLPFragment extends Fragment {

    private static final String TAG = "ManageLPFragment";
    private static final String LCD_URL = "https://lcd.mainnet.secretsaturn.net";
    
    private RecyclerView poolsRecyclerView;
    private PoolOverviewAdapter poolAdapter;
    private TextView totalRewardsText;
    private Button claimAllButton;
    private LinearLayout claimAllContainer;
    private View liquidityManagementContainer;
    private View rootView;
    
    private SecretQueryService queryService;
    private ExecutorService executorService;
    
    private boolean isManagingLiquidity = false;
    private Object currentPoolData = null;
    
    // Mock data - replace with actual pool data
    private List<PoolData> allPoolsData = new ArrayList<>();
    
    public ManageLPFragment() {
        // Required empty public constructor
    }

    public static ManageLPFragment newInstance() {
        return new ManageLPFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.d(TAG, "Creating ManageLP view");
        rootView = inflater.inflate(R.layout.fragment_manage_lp, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize services
        queryService = new SecretQueryService(getContext());
        executorService = Executors.newCachedThreadPool();
        
        initializeViews(view);
        setupRecyclerView();
        setupClaimAllButton();
        
        // Set initial title background
        updateTitleBackground();
        
        // Load initial data
        refreshPoolData();
    }
    
    private void initializeViews(View view) {
        poolsRecyclerView = view.findViewById(R.id.pools_recycler_view);
        totalRewardsText = view.findViewById(R.id.total_rewards_text);
        claimAllButton = view.findViewById(R.id.claim_all_button);
        claimAllContainer = view.findViewById(R.id.claim_all_container);
        liquidityManagementContainer = view.findViewById(R.id.liquidity_management_container);
    }
    
    private void setupRecyclerView() {
        poolAdapter = new PoolOverviewAdapter(allPoolsData, new PoolOverviewAdapter.PoolClickListener() {
            @Override
            public void onManageClicked(PoolData poolData) {
                toggleManageLiquidity(poolData);
            }
            
            @Override
            public void onClaimClicked(PoolData poolData) {
                handleClaimRewards(poolData);
            }
        });
        
        poolsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        poolsRecyclerView.setAdapter(poolAdapter);
    }
    
    private void setupClaimAllButton() {
        if (claimAllButton != null) {
            claimAllButton.setOnClickListener(v -> handleClaimAll());
        }
    }
    
    private void refreshPoolData() {
        Log.d(TAG, "Refreshing pool data with real contract queries");
        
        // Query exchange contract like React app does
        executorService.execute(() -> {
            try {
                queryExchangeContract();
            } catch (Exception e) {
                Log.e(TAG, "Error querying pool data", e);
                // Fall back to empty data on error
                getActivity().runOnUiThread(() -> {
                    allPoolsData.clear();
                    updateTotalRewards();
                    poolAdapter.notifyDataSetChanged();
                });
            }
        });
    }
    
    private void queryExchangeContract() throws Exception {
        Log.d(TAG, "Querying exchange contract for pool data");
        
        // Get all token contracts except ERTH (like React app)
        List<String> poolContracts = new ArrayList<>();
        List<String> tokenKeys = new ArrayList<>();
        
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            if (!symbol.equals("ERTH")) {
                Tokens.TokenInfo token = Tokens.getToken(symbol);
                if (token != null) {
                    poolContracts.add(token.contract);
                    tokenKeys.add(symbol);
                    Log.d(TAG, "Added pool contract: " + symbol + " -> " + token.contract);
                }
            }
        }
        
        if (poolContracts.isEmpty()) {
            Log.w(TAG, "No pool contracts found, loading empty data");
            // Update UI with empty data
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allPoolsData.clear();
                    updateTotalRewards();
                    poolAdapter.notifyDataSetChanged();
                });
            }
            return;
        }
        
        // Get actual user address
        String userAddress;
        try {
            userAddress = SecureWalletManager.getWalletAddress(getContext());
            Log.d(TAG, "User address: " + userAddress);
            
            if (userAddress == null || userAddress.isEmpty()) {
                Log.w(TAG, "No user address available, cannot query pools");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting wallet address", e);
            throw e;
        }
        
        // Create query message like React app: { query_user_info: { pools, user: address } }
        JSONObject queryMsg = new JSONObject();
        JSONObject queryUserInfo = new JSONObject();
        queryUserInfo.put("pools", new JSONArray(poolContracts));
        queryUserInfo.put("user", userAddress);
        queryMsg.put("query_user_info", queryUserInfo);
        
        Log.d(TAG, "Exchange contract: " + Constants.EXCHANGE_CONTRACT);
        Log.d(TAG, "Exchange hash: " + Constants.EXCHANGE_HASH);
        Log.d(TAG, "LCD URL: " + LCD_URL);
        Log.d(TAG, "Query message: " + queryMsg.toString());
        
        // Query the exchange contract (uses hardcoded LCD endpoint)
        JSONObject result = queryService.queryContract(
            Constants.EXCHANGE_CONTRACT,
            Constants.EXCHANGE_HASH,
            queryMsg
        );
        
        Log.d(TAG, "Query result: " + result.toString());
        
        // Process the results
        processPoolQueryResults(result, tokenKeys);
    }
    
    private void processPoolQueryResults(JSONObject result, List<String> tokenKeys) throws Exception {
        Log.d(TAG, "Processing pool query results");
        
        List<PoolData> newPoolData = new ArrayList<>();
        
        // Handle the decryption_error case where data is embedded in error message
        if (result.has("error") && result.has("decryption_error")) {
            String decryptionError = result.getString("decryption_error");
            Log.d(TAG, "Processing decryption_error for unbonding shares");
            
            // Look for base64-decoded JSON in the error message
            String jsonMarker = "base64=Value ";
            int jsonIndex = decryptionError.indexOf(jsonMarker);
            if (jsonIndex != -1) {
                int startIndex = jsonIndex + jsonMarker.length();
                int endIndex = decryptionError.indexOf(" of type org.json.JSONArray", startIndex);
                if (endIndex != -1) {
                    String jsonArrayString = decryptionError.substring(startIndex, endIndex);
                    try {
                        JSONArray poolResults = new JSONArray(jsonArrayString);
                        processPoolArray(poolResults, tokenKeys, newPoolData);
                        updatePoolDataOnUI(newPoolData);
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing JSON from decryption_error", e);
                    }
                }
            }
        }
        
        // The result should be an array of pool data
        if (result.has("data") && result.get("data") instanceof JSONArray) {
            JSONArray poolResults = result.getJSONArray("data");
            processPoolArray(poolResults, tokenKeys, newPoolData);
            updatePoolDataOnUI(newPoolData);
        } else {
            Log.w(TAG, "No valid pool data found in result");
            updatePoolDataOnUI(newPoolData);
        }
    }
    
    private void processPoolArray(JSONArray poolResults, List<String> tokenKeys, List<PoolData> newPoolData) throws Exception {
        for (int i = 0; i < poolResults.length() && i < tokenKeys.size(); i++) {
            String tokenKey = tokenKeys.get(i);
            JSONObject poolInfo = poolResults.getJSONObject(i);
            Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenKey);
                
            // Extract pool data like React app does
            String pendingRewards = "0.0";
            String liquidity = "0.0";
            String volume = "0.0";
            String apr = "0.0%";
            String unbondingShares = "0.0";
            
            // Parse user_info for rewards
            if (poolInfo.has("user_info") && !poolInfo.isNull("user_info")) {
                JSONObject userInfo = poolInfo.getJSONObject("user_info");
                if (userInfo.has("pending_rewards")) {
                    long rewardsMicro = userInfo.getLong("pending_rewards");
                    double rewardsMacro = rewardsMicro / 1000000.0; // Convert from micro to macro
                    pendingRewards = String.format("%.1f", rewardsMacro);
                }
            }
                
            // Parse pool_info for liquidity, volume, APR, and unbonding shares
            if (poolInfo.has("pool_info") && !poolInfo.isNull("pool_info")) {
                JSONObject poolState = poolInfo.getJSONObject("pool_info");
                if (poolState.has("state") && !poolState.isNull("state")) {
                    JSONObject state = poolState.getJSONObject("state");
                    
                    // Extract unbonding shares for this user
                    if (state.has("unbonding_shares")) {
                        long unbondingSharesMicro = state.getLong("unbonding_shares");
                        double unbondingSharesMacro = unbondingSharesMicro / 1000000.0;
                        unbondingShares = String.format("%.2f", unbondingSharesMacro);
                        Log.d(TAG, tokenKey + " unbonding shares: " + unbondingShares);
                    }
                    
                    // Calculate liquidity (2 * ERTH reserve)
                    if (state.has("erth_reserve")) {
                        long erthReserveMicro = state.getLong("erth_reserve");
                        double erthReserveMacro = erthReserveMicro / 1000000.0;
                        double totalLiquidity = 2 * erthReserveMacro;
                        liquidity = String.format("%.0f", totalLiquidity);
                    }
                    
                    // Calculate volume (sum of last 7 days)
                    if (state.has("daily_volumes")) {
                        JSONArray volumes = state.getJSONArray("daily_volumes");
                        long totalVolumeMicro = 0;
                        for (int v = 0; v < Math.min(7, volumes.length()); v++) {
                            totalVolumeMicro += volumes.getLong(v);
                        }
                        double totalVolumeMacro = totalVolumeMicro / 1000000.0;
                        volume = String.format("%.0f", totalVolumeMacro);
                    }
                    
                    // Calculate APR (weekly rewards / liquidity * 52)
                    if (state.has("daily_rewards")) {
                        JSONArray rewards = state.getJSONArray("daily_rewards");
                        long weeklyRewardsMicro = 0;
                        for (int r = 0; r < Math.min(7, rewards.length()); r++) {
                            weeklyRewardsMicro += rewards.getLong(r);
                        }
                        double weeklyRewardsMacro = weeklyRewardsMicro / 1000000.0;
                        double liquidityValue = Double.parseDouble(liquidity.replace(",", ""));
                        if (liquidityValue > 0) {
                            double aprValue = (weeklyRewardsMacro / liquidityValue) * 52 * 100;
                            apr = String.format("%.2f%%", aprValue);
                        }
                    }
                }
            }
            
            newPoolData.add(new PoolData(tokenKey, pendingRewards, liquidity, volume, apr, unbondingShares, tokenInfo));
        }
    }
    
    private void updatePoolDataOnUI(List<PoolData> newPoolData) {
        // Update UI on main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                allPoolsData.clear();
                allPoolsData.addAll(newPoolData);
                updateTotalRewards();
                poolAdapter.notifyDataSetChanged();
                Log.d(TAG, "Updated UI with " + newPoolData.size() + " pools");
            });
        }
    }
    
    private void loadMockPoolData() {
        // Load actual tokens from Tokens.java - will be replaced with actual contract queries
        allPoolsData.clear();
        
        // Get all tokens except ERTH (like in React code)
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            if (!symbol.equals("ERTH")) {
                Tokens.TokenInfo token = Tokens.getToken(symbol);
                if (token != null) {
                    // TODO: Query exchange contract for actual pool data
                    // This should call query_user_info with pools array and user address
                    allPoolsData.add(new PoolData(symbol, "0.0", "0.0", "0.0", "0.0%", "0.0", token));
                }
            }
        }
    }
    
    private void updateTotalRewards() {
        double totalRewards = 0.0;
        
        for (PoolData pool : allPoolsData) {
            try {
                double rewards = Double.parseDouble(pool.getPendingRewards().replace(",", ""));
                totalRewards += rewards;
            } catch (NumberFormatException e) {
                Log.w(TAG, "Error parsing rewards for pool: " + pool.getTokenKey());
            }
        }
        
        if (totalRewards > 0) {
            totalRewardsText.setText(String.format("Total Rewards: %.0f ERTH", totalRewards));
            claimAllContainer.setVisibility(View.VISIBLE);
        } else {
            claimAllContainer.setVisibility(View.GONE);
        }
    }
    
    public void toggleManageLiquidity(PoolData poolData) {
        if (poolData != null) {
            Log.d(TAG, "Toggle manage liquidity for: " + poolData.getTokenKey());
        }
        
        if (isManagingLiquidity) {
            // Return to pool overview
            isManagingLiquidity = false;
            currentPoolData = null;
            showPoolOverview();
        } else if (poolData != null) {
            // Show liquidity management
            isManagingLiquidity = true;
            currentPoolData = poolData;
            showLiquidityManagement(poolData);
        }
    }
    
    private void showPoolOverview() {
        // Show the RecyclerView with pool overviews
        poolsRecyclerView.setVisibility(View.VISIBLE);
        
        // Hide liquidity management
        if (liquidityManagementContainer != null) {
            liquidityManagementContainer.setVisibility(View.GONE);
        }
        
        updateTotalRewards();
        updateTitleBackground();
    }
    
    private void showLiquidityManagement(PoolData poolData) {
        // Hide pool overview and show liquidity management
        poolsRecyclerView.setVisibility(View.GONE);
        claimAllContainer.setVisibility(View.GONE);
        
        // Show liquidity management component
        if (liquidityManagementContainer != null) {
            liquidityManagementContainer.setVisibility(View.VISIBLE);
            
            // Create and add the LiquidityManagementComponent
            LiquidityManagementComponent liquidityComponent = LiquidityManagementComponent.newInstance(poolData);
            
            getChildFragmentManager().beginTransaction()
                .replace(R.id.liquidity_management_container, liquidityComponent)
                .commit();
                
            Log.d(TAG, "Showing liquidity management for: " + poolData.getTokenKey());
        }
        
        updateTitleBackground();
    }
    
    private void updateTitleBackground() {
        if (rootView != null) {
            if (isManagingLiquidity) {
                // White background for manage liquidity
                rootView.setBackgroundColor(ContextCompat.getColor(getContext(), android.R.color.white));
            } else {
                // Off-white background for pool overview
                rootView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.desktop_bg));
            }
        }
    }
    
    private void handleClaimRewards(PoolData poolData) {
        Log.d(TAG, "Claiming rewards for pool: " + poolData.getTokenKey());
        
        // TODO: Implement actual claim rewards functionality
        // This should call the exchange contract to claim rewards for specific pool
    }
    
    private void handleClaimAll() {
        Log.d(TAG, "Claiming all rewards");
        
        // TODO: Implement claim all functionality
        // This should call the exchange contract to claim rewards for all pools with rewards
    }
    
    // Data class for pool information
    public static class PoolData {
        private String tokenKey;
        private String pendingRewards;
        private String liquidity;
        private String volume;
        private String apr;
        private String unbondingShares;
        private Tokens.TokenInfo tokenInfo;
        
        public PoolData(String tokenKey, String pendingRewards, String liquidity, String volume, String apr, String unbondingShares, Tokens.TokenInfo tokenInfo) {
            this.tokenKey = tokenKey;
            this.pendingRewards = pendingRewards;
            this.liquidity = liquidity;
            this.volume = volume;
            this.apr = apr;
            this.unbondingShares = unbondingShares;
            this.tokenInfo = tokenInfo;
        }
        
        // Getters
        public String getTokenKey() { return tokenKey; }
        public String getPendingRewards() { return pendingRewards; }
        public String getLiquidity() { return liquidity; }
        public String getVolume() { return volume; }
        public String getApr() { return apr; }
        public String getUnbondingShares() { return unbondingShares; }
        public Tokens.TokenInfo getTokenInfo() { return tokenInfo; }
        
        // Setters
        public void setPendingRewards(String pendingRewards) { this.pendingRewards = pendingRewards; }
        public void setLiquidity(String liquidity) { this.liquidity = liquidity; }
        public void setVolume(String volume) { this.volume = volume; }
        public void setApr(String apr) { this.apr = apr; }
        public void setUnbondingShares(String unbondingShares) { this.unbondingShares = unbondingShares; }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}