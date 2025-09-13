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
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.activities.TransactionActivity;

import com.example.earthwallet.bridge.services.SnipQueryService;
import com.example.earthwallet.bridge.services.ViewingKeyService;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONObject;

import android.content.SharedPreferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment for staking and unstaking ERTH tokens
 * Corresponds to the "Stake/Unstake" tab in the React component
 */
public class StakeUnstakeFragment extends Fragment {
    
    private static final String TAG = "StakeUnstakeFragment";
    private static final int REQ_STAKE_ERTH = 4002;
    private static final int REQ_UNSTAKE_ERTH = 4003;
    
    // UI Components - Stake Section
    private TextView stakeBalanceLabel;
    private Button stakeMaxButton;
    private EditText stakeAmountInput;
    private Button stakeButton;
    
    // UI Components - Unstake Section
    private TextView unstakeBalanceLabel;
    private Button unstakeMaxButton;
    private EditText unstakeAmountInput;
    private Button unstakeButton;
    private TextView unbondingNoteText;
    
    // Data
    private double erthBalance = 0.0;
    private double stakedBalance = 0.0;
    
    public static StakeUnstakeFragment newInstance() {
        return new StakeUnstakeFragment();
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stake_unstake, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // No services to initialize in this simplified version
        
        initializeViews(view);
        setupClickListeners();
        
        // Load initial data
        refreshData();
    }
    
    private void initializeViews(View view) {
        // Stake section
        stakeBalanceLabel = view.findViewById(R.id.stake_balance_label);
        stakeMaxButton = view.findViewById(R.id.stake_max_button);
        stakeAmountInput = view.findViewById(R.id.stake_amount_input);
        stakeButton = view.findViewById(R.id.stake_button);
        
        // Unstake section
        unstakeBalanceLabel = view.findViewById(R.id.unstake_balance_label);
        unstakeMaxButton = view.findViewById(R.id.unstake_max_button);
        unstakeAmountInput = view.findViewById(R.id.unstake_amount_input);
        unstakeButton = view.findViewById(R.id.unstake_button);
        unbondingNoteText = view.findViewById(R.id.unbonding_note_text);
    }
    
    private void setupClickListeners() {
        stakeMaxButton.setOnClickListener(v -> {
            if (erthBalance > 0) {
                stakeAmountInput.setText(String.valueOf(erthBalance));
            }
        });
        
        unstakeMaxButton.setOnClickListener(v -> {
            if (stakedBalance > 0) {
                unstakeAmountInput.setText(String.valueOf(stakedBalance));
            }
        });
        
        // Add text watchers for validation
        stakeAmountInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(android.text.Editable s) {
                validateStakeButton();
            }
        });
        
        unstakeAmountInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(android.text.Editable s) {
                validateUnstakeButton();
            }
        });
        
        stakeButton.setOnClickListener(v -> handleStake());
        unstakeButton.setOnClickListener(v -> handleUnstake());
    }
    
    /**
     * Refresh balance data
     */
    public void refreshData() {
        Log.d(TAG, "Refreshing stake/unstake data");
        
        // Query ERTH balance using SnipQueryService
        queryErthBalance();
        
        // Query staked balance from parent fragment
        if (getParentFragment() instanceof StakeEarthFragment) {
            StakeEarthFragment parentFragment = (StakeEarthFragment) getParentFragment();
            parentFragment.queryUserStakingInfo(new StakeEarthFragment.UserStakingCallback() {
                @Override
                public void onStakingDataReceived(JSONObject data) {
                    try {
                        // Extract staked balance
                        if (data.has("user_info") && !data.isNull("user_info")) {
                            JSONObject userInfo = data.getJSONObject("user_info");
                            if (userInfo.has("staked_amount")) {
                                long stakedAmountMicro = userInfo.getLong("staked_amount");
                                stakedBalance = stakedAmountMicro / 1_000_000.0; // Convert to macro units
                                Log.d(TAG, "Staked balance: " + stakedBalance);
                                updateUnstakeSection();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing staked balance", e);
                    }
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Error querying staking data: " + error);
                }
            });
        }
    }
    
    private void queryErthBalance() {
        Log.d(TAG, "queryErthBalance called");
        new Thread(() -> {
            try {
                String walletAddress = SecureWalletManager.getWalletAddress(getContext());
                Log.d(TAG, "Wallet address: " + walletAddress);
                if (walletAddress == null) {
                    Log.w(TAG, "No wallet address available for ERTH balance query");
                    erthBalance = -1;
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(this::updateStakeSection);
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
                Log.d(TAG, "Looking for key: viewing_key_" + walletAddress + "_" + Tokens.ERTH.contract);
                Log.d(TAG, "Found viewing key: " + (viewingKey != null && !viewingKey.isEmpty()));
                
                if (viewingKey == null || viewingKey.isEmpty()) {
                    Log.d(TAG, "No ERTH viewing key available");
                    erthBalance = -1; // Indicates "need viewing key"
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(this::updateStakeSection);
                    }
                    return;
                }
                
                // Query ERTH balance using SnipQueryService exactly like TokenBalancesFragment does
                Log.d(TAG, "Querying ERTH balance with viewing key");
                JSONObject result = SnipQueryService.queryBalance(getContext(), "ERTH", walletAddress, viewingKey);
                Log.d(TAG, "Query result: " + (result != null ? result.toString() : "null"));
                
                if (result != null && result.has("result") && result.getJSONObject("result").has("balance")) {
                    JSONObject balanceObj = result.getJSONObject("result").getJSONObject("balance");
                    if (balanceObj.has("amount")) {
                        String amountStr = balanceObj.getString("amount");
                        double amount = Double.parseDouble(amountStr) / 1_000_000.0; // Convert from micro to macro units
                        erthBalance = amount;
                        Log.d(TAG, "ERTH balance updated: " + erthBalance);
                    }
                } else {
                    Log.w(TAG, "Invalid ERTH balance response");
                    erthBalance = 0.0;
                }
                
                // Update UI on main thread
                if (getActivity() != null) {
                    getActivity().runOnUiThread(this::updateStakeSection);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error querying ERTH balance", e);
                erthBalance = -1; // Indicates error/need viewing key
                if (getActivity() != null) {
                    getActivity().runOnUiThread(this::updateStakeSection);
                }
            }
        }).start();
    }
    
    private void updateStakeSection() {
        if (getActivity() == null) return;
        
        getActivity().runOnUiThread(() -> {
            if (erthBalance >= 0) {
                stakeBalanceLabel.setText(String.format("Balance: %,.0f", erthBalance));
                stakeMaxButton.setVisibility(View.VISIBLE);
            } else {
                stakeBalanceLabel.setText("Balance: Error");
                stakeMaxButton.setVisibility(View.GONE);
            }
            
            validateStakeButton();
        });
    }
    
    private void updateUnstakeSection() {
        if (getActivity() == null) return;
        
        getActivity().runOnUiThread(() -> {
            if (stakedBalance > 0) {
                unstakeBalanceLabel.setText(String.format("Balance: %,.0f", stakedBalance));
                unstakeMaxButton.setVisibility(View.VISIBLE);
            } else {
                unstakeBalanceLabel.setText("No staked ERTH");
                unstakeMaxButton.setVisibility(View.GONE);
            }
            
            validateUnstakeButton();
        });
    }
    
    private void validateStakeButton() {
        String amountText = stakeAmountInput.getText().toString().trim();
        boolean isValid = false;
        
        Log.d(TAG, "validateStakeButton - amountText: '" + amountText + "', erthBalance: " + erthBalance);
        
        if (!TextUtils.isEmpty(amountText)) {
            try {
                double amount = Double.parseDouble(amountText);
                isValid = amount > 0 && amount <= erthBalance;
                Log.d(TAG, "validateStakeButton - parsed amount: " + amount + ", isValid: " + isValid);
            } catch (NumberFormatException e) {
                Log.d(TAG, "validateStakeButton - NumberFormatException: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "validateStakeButton - empty amount text");
        }
        
        Log.d(TAG, "validateStakeButton - setting button enabled: " + isValid);
        stakeButton.setEnabled(isValid);
    }
    
    private void validateUnstakeButton() {
        String amountText = unstakeAmountInput.getText().toString().trim();
        boolean isValid = false;
        
        if (!TextUtils.isEmpty(amountText)) {
            try {
                double amount = Double.parseDouble(amountText);
                isValid = amount > 0 && amount <= stakedBalance;
            } catch (NumberFormatException e) {
                // Invalid number
            }
        }
        
        unstakeButton.setEnabled(isValid);
    }
    
    private void handleStake() {
        String amountText = stakeAmountInput.getText().toString().trim();
        if (TextUtils.isEmpty(amountText)) {
            Toast.makeText(getContext(), "Please enter an amount to stake", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            double amount = Double.parseDouble(amountText);
            if (amount <= 0) {
                Toast.makeText(getContext(), "Amount must be greater than 0", Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (amount > erthBalance) {
                Toast.makeText(getContext(), "Insufficient balance", Toast.LENGTH_SHORT).show();
                return;
            }
            
            Log.d(TAG, "Staking " + amount + " ERTH");
            
            // Convert amount to micro units
            long amountMicro = Math.round(amount * 1_000_000);
            
            // Create SNIP-20 transfer message: { stake_erth: {} }
            JSONObject stakeMsg = new JSONObject();
            stakeMsg.put("stake_erth", new JSONObject());
            
            // Use SnipExecuteActivity to send ERTH to staking contract
            Tokens.TokenInfo erthToken = Tokens.getToken("ERTH");
            Intent intent = new Intent(getActivity(), TransactionActivity.class);
            intent.putExtra(TransactionActivity.EXTRA_TOKEN_CONTRACT, erthToken.contract);
            intent.putExtra(TransactionActivity.EXTRA_TOKEN_HASH, erthToken.hash);
            intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, Constants.STAKING_CONTRACT);
            intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_HASH, Constants.STAKING_HASH);
            intent.putExtra(TransactionActivity.EXTRA_AMOUNT, String.valueOf(amountMicro));
            intent.putExtra(TransactionActivity.EXTRA_MESSAGE_JSON, stakeMsg.toString());
            
            startActivityForResult(intent, REQ_STAKE_ERTH);
            
        } catch (Exception e) {
            Log.e(TAG, "Error staking ERTH", e);
            Toast.makeText(getContext(), "Failed to stake: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void handleUnstake() {
        String amountText = unstakeAmountInput.getText().toString().trim();
        if (TextUtils.isEmpty(amountText)) {
            Toast.makeText(getContext(), "Please enter an amount to unstake", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            double amount = Double.parseDouble(amountText);
            if (amount <= 0) {
                Toast.makeText(getContext(), "Amount must be greater than 0", Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (amount > stakedBalance) {
                Toast.makeText(getContext(), "Insufficient staked balance", Toast.LENGTH_SHORT).show();
                return;
            }
            
            Log.d(TAG, "Unstaking " + amount + " ERTH");
            
            // Convert amount to micro units
            long amountMicro = Math.round(amount * 1_000_000);
            
            // Create withdraw message: { withdraw: { amount: "123456" } }
            JSONObject withdrawMsg = new JSONObject();
            JSONObject withdraw = new JSONObject();
            withdraw.put("amount", String.valueOf(amountMicro));
            withdrawMsg.put("withdraw", withdraw);
            
            // Use SecretExecuteActivity for unstaking
            Intent intent = new Intent(getActivity(), TransactionActivity.class);
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.STAKING_CONTRACT);
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.STAKING_HASH);
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, withdrawMsg.toString());
            
            startActivityForResult(intent, REQ_UNSTAKE_ERTH);
            
        } catch (Exception e) {
            Log.e(TAG, "Error unstaking ERTH", e);
            Toast.makeText(getContext(), "Failed to unstake: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_STAKE_ERTH) {
            if (resultCode == getActivity().RESULT_OK) {
                Toast.makeText(getContext(), "Staking successful!", Toast.LENGTH_SHORT).show();
                stakeAmountInput.setText(""); // Clear input
                refreshData(); // Refresh balances
            } else {
                String error = data != null ? data.getStringExtra("error") : "Unknown error";
                Toast.makeText(getContext(), "Staking failed: " + error, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_UNSTAKE_ERTH) {
            if (resultCode == getActivity().RESULT_OK) {
                Toast.makeText(getContext(), "Unstaking successful! Tokens are now unbonding.", Toast.LENGTH_SHORT).show();
                unstakeAmountInput.setText(""); // Clear input
                refreshData(); // Refresh balances
            } else {
                String error = data != null ? data.getStringExtra("error") : "Unknown error";
                Toast.makeText(getContext(), "Unstaking failed: " + error, Toast.LENGTH_LONG).show();
            }
        }
    }
}