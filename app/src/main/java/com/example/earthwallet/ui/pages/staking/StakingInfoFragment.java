package com.example.earthwallet.ui.pages.staking;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.activities.SecretExecuteActivity;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.bridge.services.SnipQueryService;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment for displaying staking info and rewards
 * Corresponds to the "Info & Rewards" tab in the React component
 */
public class StakingInfoFragment extends Fragment {
    
    private static final String TAG = "StakingInfoFragment";
    private static final int REQ_CLAIM_REWARDS = 4001;
    private static final int REQ_GET_VIEWING_KEY = 4006;
    
    // UI Components
    private TextView stakedAmountText;
    private TextView erthBalanceText;
    private TextView currentAprText;
    private TextView totalStakedText;
    private TextView stakingRewardsText;
    private Button claimRewardsButton;
    private Button getViewingKeyButton;
    
    // Services
    private SecretQueryService queryService;
    private ExecutorService executorService;
    
    // Data
    private double stakedBalance = 0.0;
    private double unstakedBalance = 0.0;
    private double stakingRewards = 0.0;
    private double totalStakedBalance = 0.0;
    private double apr = 0.0;
    
    public static StakingInfoFragment newInstance() {
        return new StakingInfoFragment();
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_staking_info, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize services
        queryService = new SecretQueryService(getContext());
        executorService = Executors.newCachedThreadPool();
        
        initializeViews(view);
        setupClickListeners();
        
        // Load initial data
        refreshData();
    }
    
    private void initializeViews(View view) {
        stakedAmountText = view.findViewById(R.id.staked_amount_text);
        erthBalanceText = view.findViewById(R.id.erth_balance_text);
        currentAprText = view.findViewById(R.id.current_apr_text);
        totalStakedText = view.findViewById(R.id.total_staked_text);
        stakingRewardsText = view.findViewById(R.id.staking_rewards_text);
        claimRewardsButton = view.findViewById(R.id.claim_rewards_button);
        getViewingKeyButton = view.findViewById(R.id.get_viewing_key_button);
    }
    
    private void setupClickListeners() {
        claimRewardsButton.setOnClickListener(v -> handleClaimRewards());
        getViewingKeyButton.setOnClickListener(v -> handleRequestViewingKey());
    }
    
    /**
     * Refresh staking data from contract
     */
    public void refreshData() {
        Log.d(TAG, "Refreshing staking info data");
        
        executorService.execute(() -> {
            try {
                queryStakingInfo();
                queryErthBalance();
            } catch (Exception e) {
                Log.e(TAG, "Error refreshing staking data", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Failed to load staking data", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }
    
    private void queryStakingInfo() throws Exception {
        String userAddress = SecureWalletManager.getWalletAddress(getContext());
        if (TextUtils.isEmpty(userAddress)) {
            Log.w(TAG, "No user address available");
            return;
        }
        
        // Create query message: { get_user_info: { address: "secret1..." } }
        JSONObject queryMsg = new JSONObject();
        JSONObject getUserInfo = new JSONObject();
        getUserInfo.put("address", userAddress);
        queryMsg.put("get_user_info", getUserInfo);
        
        Log.d(TAG, "Querying staking contract for user info");
        
        JSONObject result = queryService.queryContract(
            Constants.STAKING_CONTRACT,
            Constants.STAKING_HASH,
            queryMsg
        );
        
        Log.d(TAG, "Staking query result: " + result.toString());
        
        // Parse results
        parseStakingResult(result);
    }
    
    private void parseStakingResult(JSONObject result) {
        try {
            // Handle potential decryption_error format like other fragments
            JSONObject dataObj = result;
            if (result.has("error") && result.has("decryption_error")) {
                String decryptionError = result.getString("decryption_error");
                Log.d(TAG, "Processing decryption_error for staking data");
                
                // Extract JSON from error message if needed
                String jsonMarker = "base64=Value ";
                int jsonIndex = decryptionError.indexOf(jsonMarker);
                if (jsonIndex != -1) {
                    int startIndex = jsonIndex + jsonMarker.length();
                    int endIndex = decryptionError.indexOf(" of type", startIndex);
                    if (endIndex != -1) {
                        String jsonString = decryptionError.substring(startIndex, endIndex);
                        try {
                            dataObj = new JSONObject(jsonString);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing JSON from decryption_error", e);
                        }
                    }
                }
            } else if (result.has("data")) {
                dataObj = result.getJSONObject("data");
            }
            
            // Extract staking rewards due (micro units)
            if (dataObj.has("staking_rewards_due")) {
                long stakingRewardsMicro = dataObj.getLong("staking_rewards_due");
                stakingRewards = stakingRewardsMicro / 1_000_000.0; // Convert to macro units
                Log.d(TAG, "Staking rewards: " + stakingRewards + " ERTH");
            }
            
            // Extract total staked (micro units)
            if (dataObj.has("total_staked")) {
                long totalStakedMicro = dataObj.getLong("total_staked");
                totalStakedBalance = totalStakedMicro / 1_000_000.0; // Convert to macro units
                Log.d(TAG, "Total staked: " + totalStakedBalance + " ERTH");
                
                // Calculate APR like in React app
                calculateAPR(totalStakedMicro);
            }
            
            // Extract user staked amount
            if (dataObj.has("user_info") && !dataObj.isNull("user_info")) {
                JSONObject userInfo = dataObj.getJSONObject("user_info");
                if (userInfo.has("staked_amount")) {
                    long stakedAmountMicro = userInfo.getLong("staked_amount");
                    stakedBalance = stakedAmountMicro / 1_000_000.0; // Convert to macro units
                    Log.d(TAG, "User staked amount: " + stakedBalance + " ERTH");
                }
            } else {
                stakedBalance = 0.0;
            }
            
            // Update UI on main thread
            if (getActivity() != null) {
                getActivity().runOnUiThread(this::updateUI);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error parsing staking result", e);
        }
    }
    
    private void calculateAPR(long totalStakedMicro) {
        if (totalStakedMicro == 0) {
            apr = 0.0;
            return;
        }
        
        // APR calculation from React app
        final int SECONDS_PER_DAY = 24 * 60 * 60;
        final int DAYS_PER_YEAR = 365;
        
        double totalStakedMacro = totalStakedMicro / 1_000_000.0;
        double dailyGrowth = SECONDS_PER_DAY / totalStakedMacro;
        double annualGrowth = dailyGrowth * DAYS_PER_YEAR;
        
        apr = annualGrowth * 100; // Convert to percentage
        Log.d(TAG, "Calculated APR: " + apr + "%");
    }
    
    private void queryErthBalance() {
        Log.d(TAG, "Querying ERTH balance for Info tab");
        new Thread(() -> {
            try {
                String walletAddress = SecureWalletManager.getWalletAddress(getContext());
                if (walletAddress == null) {
                    Log.w(TAG, "No wallet address available for ERTH balance query");
                    unstakedBalance = -1;
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(this::updateUI);
                    }
                    return;
                }
                
                // Create secure preferences exactly like TokenBalancesFragment does
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    "viewing_keys_prefs",
                    masterKeyAlias,
                    getContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                
                // Get viewing key using exact same pattern as TokenBalancesFragment.getViewingKey()
                String viewingKey = securePrefs.getString("viewing_key_" + walletAddress + "_" + Tokens.ERTH.contract, "");
                
                if (viewingKey == null || viewingKey.isEmpty()) {
                    Log.d(TAG, "No ERTH viewing key available for Info tab");
                    unstakedBalance = -1; // Indicates "need viewing key"
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(this::updateUI);
                    }
                    return;
                }
                
                // Query ERTH balance using SnipQueryService exactly like TokenBalancesFragment does
                Log.d(TAG, "Querying ERTH balance for Info tab with viewing key");
                JSONObject result = SnipQueryService.queryBalance(getContext(), "ERTH", walletAddress, viewingKey);
                
                if (result != null && result.has("result") && result.getJSONObject("result").has("balance")) {
                    JSONObject balanceObj = result.getJSONObject("result").getJSONObject("balance");
                    if (balanceObj.has("amount")) {
                        String amountStr = balanceObj.getString("amount");
                        double amount = Double.parseDouble(amountStr) / 1_000_000.0; // Convert from micro to macro units
                        unstakedBalance = amount;
                        Log.d(TAG, "ERTH balance updated for Info tab: " + unstakedBalance);
                    }
                } else {
                    Log.w(TAG, "Invalid ERTH balance response for Info tab");
                    unstakedBalance = 0.0;
                }
                
                // Update UI on main thread
                if (getActivity() != null) {
                    getActivity().runOnUiThread(this::updateUI);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error querying ERTH balance for Info tab", e);
                unstakedBalance = -1; // Indicates error/need viewing key
                if (getActivity() != null) {
                    getActivity().runOnUiThread(this::updateUI);
                }
            }
        }).start();
    }
    
    private void updateUI() {
        if (stakedAmountText != null) {
            if (stakedBalance > 0) {
                stakedAmountText.setText(String.format("%,.0f ERTH", stakedBalance));
            } else {
                stakedAmountText.setText("0 ERTH");
            }
        }
        
        if (erthBalanceText != null) {
            if (unstakedBalance >= 0) {
                erthBalanceText.setText(String.format("%,.0f ERTH", unstakedBalance));
                getViewingKeyButton.setVisibility(View.GONE);
            } else {
                erthBalanceText.setText("");
                getViewingKeyButton.setVisibility(View.VISIBLE);
            }
        }
        
        if (currentAprText != null) {
            currentAprText.setText(String.format("%.2f%%", apr));
        }
        
        if (totalStakedText != null) {
            totalStakedText.setText(String.format("%,.0f ERTH", totalStakedBalance));
        }
        
        if (stakingRewardsText != null && claimRewardsButton != null) {
            if (stakingRewards > 0) {
                stakingRewardsText.setText(String.format("%,.2f ERTH", stakingRewards));
                claimRewardsButton.setVisibility(View.VISIBLE);
                claimRewardsButton.setEnabled(true);
            } else {
                stakingRewardsText.setText("0 ERTH");
                claimRewardsButton.setVisibility(View.GONE);
            }
        }
    }
    
    private void handleClaimRewards() {
        Log.d(TAG, "Claiming staking rewards");
        
        try {
            // Create claim message: { claim: {} }
            JSONObject claimMsg = new JSONObject();
            claimMsg.put("claim", new JSONObject());
            
            // Use SecretExecuteActivity for claiming rewards
            Intent intent = new Intent(getActivity(), SecretExecuteActivity.class);
            intent.putExtra(SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, Constants.STAKING_CONTRACT);
            intent.putExtra(SecretExecuteActivity.EXTRA_CODE_HASH, Constants.STAKING_HASH);
            intent.putExtra(SecretExecuteActivity.EXTRA_EXECUTE_JSON, claimMsg.toString());
            
            startActivityForResult(intent, REQ_CLAIM_REWARDS);
            
        } catch (Exception e) {
            Log.e(TAG, "Error claiming rewards", e);
            Toast.makeText(getContext(), "Failed to claim rewards: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void handleRequestViewingKey() {
        Log.d(TAG, "Requesting ERTH viewing key");
        
        try {
            Tokens.TokenInfo erthToken = Tokens.getToken("ERTH");
            if (erthToken != null) {
                // Create viewing key request message: { create_viewing_key: { entropy: "random_string" } }
                JSONObject viewingKeyMsg = new JSONObject();
                JSONObject createViewingKey = new JSONObject();
                createViewingKey.put("entropy", generateRandomEntropy());
                viewingKeyMsg.put("create_viewing_key", createViewingKey);
                
                // Use SecretExecuteActivity to set viewing key
                Intent intent = new Intent(getActivity(), SecretExecuteActivity.class);
                intent.putExtra(SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, erthToken.contract);
                intent.putExtra(SecretExecuteActivity.EXTRA_CODE_HASH, erthToken.hash);
                intent.putExtra(SecretExecuteActivity.EXTRA_EXECUTE_JSON, viewingKeyMsg.toString());
                
                startActivityForResult(intent, REQ_GET_VIEWING_KEY);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting viewing key", e);
            Toast.makeText(getContext(), "Failed to request viewing key: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private String generateRandomEntropy() {
        // Generate random entropy for viewing key creation
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] entropy = new byte[16];
        random.nextBytes(entropy);
        return android.util.Base64.encodeToString(entropy, android.util.Base64.NO_WRAP);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_CLAIM_REWARDS) {
            if (resultCode == getActivity().RESULT_OK) {
                Toast.makeText(getContext(), "Rewards claimed successfully!", Toast.LENGTH_SHORT).show();
                // Refresh data to reflect new balances
                refreshData();
            } else {
                String error = data != null ? data.getStringExtra("error") : "Unknown error";
                Toast.makeText(getContext(), "Failed to claim rewards: " + error, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_GET_VIEWING_KEY) {
            if (resultCode == getActivity().RESULT_OK) {
                Toast.makeText(getContext(), "Viewing key set successfully!", Toast.LENGTH_SHORT).show();
                // Refresh data to show ERTH balance
                refreshData();
            } else {
                String error = data != null ? data.getStringExtra("error") : "Unknown error";
                Toast.makeText(getContext(), "Failed to set viewing key: " + error, Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}