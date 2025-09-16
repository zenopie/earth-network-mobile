package com.example.earthwallet.ui.pages.managelp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.bridge.activities.TransactionActivity;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.Constants;

import org.json.JSONObject;
import org.json.JSONArray;

import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoveLiquidityFragment extends Fragment {

    private static final String TAG = "RemoveLiquidityFragment";
    private static final int REQ_REMOVE_LIQUIDITY = 5002;
    
    private EditText removeAmountInput;
    private TextView stakedSharesText;
    private Button sharesMaxButton;
    private Button removeLiquidityButton;
    
    private String tokenKey;
    private double userStakedShares = 0.0;
    private String currentWalletAddress = "";
    private SharedPreferences securePrefs;
    private ExecutorService executorService;

    // Broadcast receiver for transaction success
    private BroadcastReceiver transactionSuccessReceiver;
    
    public static RemoveLiquidityFragment newInstance(String tokenKey) {
        RemoveLiquidityFragment fragment = new RemoveLiquidityFragment();
        Bundle args = new Bundle();
        args.putString("token_key", tokenKey);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            tokenKey = getArguments().getString("token_key");
        }
        
        // Get secure preferences from HostActivity
        try {
            if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                securePrefs = ((com.example.earthwallet.ui.host.HostActivity) getActivity()).getSecurePrefs();
                Log.d(TAG, "Successfully got securePrefs from HostActivity");
            } else {
                Log.e(TAG, "Activity is not HostActivity");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get securePrefs from HostActivity", e);
        }
        
        executorService = Executors.newCachedThreadPool();
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tab_liquidity_remove, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupListeners();
        setupBroadcastReceiver();
        registerBroadcastReceiver();
        loadCurrentWalletAddress();
        loadUserShares();
        loadUnbondingRequests();
    }
    
    private void initializeViews(View view) {
        removeAmountInput = view.findViewById(R.id.remove_amount_input);
        stakedSharesText = view.findViewById(R.id.staked_shares_text);
        sharesMaxButton = view.findViewById(R.id.shares_max_button);
        removeLiquidityButton = view.findViewById(R.id.remove_liquidity_button);
    }
    
    private void setupBroadcastReceiver() {
        transactionSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Received TRANSACTION_SUCCESS broadcast - refreshing liquidity data immediately");

                // Start multiple refresh attempts to ensure UI updates during animation
                loadUserShares();
                loadUnbondingRequests();

                // Stagger additional refreshes to catch the UI during animation
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Secondary refresh during animation");
                    loadUserShares();
                    loadUnbondingRequests();
                }, 100); // 100ms delay

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Third refresh during animation");
                    loadUserShares();
                    loadUnbondingRequests();
                }, 500); // 500ms delay
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

    private void setupListeners() {
        sharesMaxButton.setOnClickListener(v -> {
            if (userStakedShares > 0) {
                removeAmountInput.setText(String.valueOf(userStakedShares));
            }
        });
        
        removeLiquidityButton.setOnClickListener(v -> {
            String removeAmountStr = removeAmountInput.getText().toString().trim();
            if (TextUtils.isEmpty(removeAmountStr)) {
                android.widget.Toast.makeText(getContext(), "Please enter amount to remove", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                double removeAmount = Double.parseDouble(removeAmountStr);
                if (removeAmount <= 0) {
                    android.widget.Toast.makeText(getContext(), "Amount must be greater than 0", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                
                if (removeAmount > userStakedShares) {
                    android.widget.Toast.makeText(getContext(), "Amount exceeds your staked shares", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                
                executeRemoveLiquidity(removeAmount);
                
            } catch (NumberFormatException e) {
                android.widget.Toast.makeText(getContext(), "Invalid amount format", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void loadCurrentWalletAddress() {
        try {
            if (securePrefs != null) {
                String walletsJson = securePrefs.getString("wallets", "[]");
                org.json.JSONArray walletsArray = new org.json.JSONArray(walletsJson);
                int selectedIndex = securePrefs.getInt("selected_wallet_index", -1);
                
                if (walletsArray.length() > 0) {
                    org.json.JSONObject selectedWallet;
                    if (selectedIndex >= 0 && selectedIndex < walletsArray.length()) {
                        selectedWallet = walletsArray.getJSONObject(selectedIndex);
                    } else {
                        selectedWallet = walletsArray.getJSONObject(0);
                    }
                    currentWalletAddress = selectedWallet.optString("address", "");
                    Log.d(TAG, "Loaded wallet address: " + currentWalletAddress);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallet address", e);
        }
    }
    
    private void loadUserShares() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            stakedSharesText.setText("Balance: Connect wallet");
            return;
        }
        
        executorService.execute(() -> {
            try {
                String tokenContract = getTokenContractAddress(tokenKey);
                if (tokenContract == null) return;
                
                // Query pool info
                JSONObject queryMsg = new JSONObject();
                JSONObject queryUserInfo = new JSONObject();
                JSONArray poolsArray = new JSONArray();
                poolsArray.put(tokenContract);
                queryUserInfo.put("pools", poolsArray);
                queryUserInfo.put("user", currentWalletAddress);
                queryMsg.put("query_user_info", queryUserInfo);
                
                com.example.earthwallet.bridge.services.SecretQueryService queryService = new com.example.earthwallet.bridge.services.SecretQueryService(getContext());
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                );
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        parseUserShares(result);
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading user shares", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        stakedSharesText.setText("Balance: Error loading");
                    });
                }
            }
        });
    }
    
    private void parseUserShares(JSONObject result) {
        try {
            // Handle the SecretQueryService error case where data is in the error message
            if (result.has("error") && result.has("decryption_error")) {
                String decryptionError = result.getString("decryption_error");
                Log.d(TAG, "Got decryption error, checking for base64 data: " + decryptionError);
                
                // Look for "base64=" in the error message and extract the array
                String base64Marker = "base64=Value ";
                int base64Index = decryptionError.indexOf(base64Marker);
                if (base64Index != -1) {
                    int startIndex = base64Index + base64Marker.length();
                    int endIndex = decryptionError.indexOf(" of type org.json.JSONArray", startIndex);
                    if (endIndex != -1) {
                        String jsonArrayString = decryptionError.substring(startIndex, endIndex);
                        Log.d(TAG, "Extracted JSON array from error: " + jsonArrayString.substring(0, Math.min(100, jsonArrayString.length())));
                        
                        try {
                            JSONArray poolsData = new JSONArray(jsonArrayString);
                            if (poolsData.length() > 0) {
                                // Find the pool that matches our token
                                for (int i = 0; i < poolsData.length(); i++) {
                                    JSONObject poolInfo = poolsData.getJSONObject(i);
                                    JSONObject config = poolInfo.getJSONObject("pool_info").getJSONObject("config");
                                    String tokenSymbol = config.getString("token_b_symbol");
                                    if (tokenKey.equals(tokenSymbol)) {
                                        Log.d(TAG, "Found matching pool for " + tokenKey);
                                        extractUserShares(poolInfo);
                                        return;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing extracted JSON array", e);
                        }
                    }
                }
            }
            
            // Also try the normal data path
            if (result.has("data")) {
                JSONArray poolsData = result.getJSONArray("data");
                if (poolsData.length() > 0) {
                    JSONObject poolInfo = poolsData.getJSONObject(0);
                    extractUserShares(poolInfo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing user shares", e);
            stakedSharesText.setText("Balance: Error");
        }
    }
    
    private void extractUserShares(JSONObject poolInfo) {
        try {
            JSONObject userInfo = poolInfo.getJSONObject("user_info");
            
            long userStakedRaw = userInfo.optLong("amount_staked", 0);
            
            // Convert from microunits to regular units (divide by 10^6)
            userStakedShares = userStakedRaw / 1000000.0;
            
            Log.d(TAG, "User staked shares loaded: " + userStakedShares);
            
            updateSharesDisplay();
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting user shares from pool info", e);
            stakedSharesText.setText("Balance: Error");
        }
    }
    
    private void updateSharesDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        
        if (userStakedShares >= 0) {
            stakedSharesText.setText("Balance: " + df.format(userStakedShares));
            sharesMaxButton.setVisibility(userStakedShares > 0 ? View.VISIBLE : View.VISIBLE);
        } else {
            stakedSharesText.setText("Balance: Error");
            sharesMaxButton.setVisibility(View.GONE);
        }
    }
    
    private void loadUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            Log.d(TAG, "Cannot load unbonding requests: missing token key or wallet address");
            return;
        }
        
        executorService.execute(() -> {
            try {
                String tokenContract = getTokenContractAddress(tokenKey);
                if (tokenContract == null) return;
                
                // Query unbonding requests for this user and token
                JSONObject queryMsg = new JSONObject();
                JSONObject queryUnbonding = new JSONObject();
                queryUnbonding.put("user", currentWalletAddress);
                queryUnbonding.put("token", tokenContract);
                queryMsg.put("query_unbonding", queryUnbonding);
                
                Log.d(TAG, "Querying unbonding requests for " + tokenKey + " with message: " + queryMsg.toString());
                
                com.example.earthwallet.bridge.services.SecretQueryService queryService = new com.example.earthwallet.bridge.services.SecretQueryService(getContext());
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                );
                
                Log.d(TAG, "Unbonding query result: " + result.toString());
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        parseUnbondingRequests(result);
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading unbonding requests", e);
            }
        });
    }
    
    private void parseUnbondingRequests(JSONObject result) {
        try {
            // Handle the SecretQueryService error case where data is in the error message
            if (result.has("error") && result.has("decryption_error")) {
                String decryptionError = result.getString("decryption_error");
                Log.d(TAG, "Parsing unbonding from decryption error: " + decryptionError.substring(0, Math.min(200, decryptionError.length())));
                
                // Look for "base64=Value " in the error message and extract the JSON
                String base64Marker = "base64=Value ";
                int base64Index = decryptionError.indexOf(base64Marker);
                if (base64Index != -1) {
                    int startIndex = base64Index + base64Marker.length();
                    int endIndex = decryptionError.indexOf(" of type", startIndex);
                    if (endIndex != -1) {
                        String jsonString = decryptionError.substring(startIndex, endIndex);
                        Log.d(TAG, "Extracted unbonding JSON: " + jsonString);
                        
                        try {
                            JSONArray unbondingArray = new JSONArray(jsonString);
                            Log.d(TAG, "Found " + unbondingArray.length() + " unbonding requests");
                            
                            // TODO: Display unbonding requests in UI
                            displayUnbondingRequests(unbondingArray);
                            return;
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing unbonding JSON array", e);
                        }
                    }
                }
            }
            
            // Also try the normal data path
            if (result.has("data")) {
                Object data = result.get("data");
                if (data instanceof JSONArray) {
                    JSONArray unbondingArray = (JSONArray) data;
                    Log.d(TAG, "Found " + unbondingArray.length() + " unbonding requests in data");
                    displayUnbondingRequests(unbondingArray);
                } else {
                    Log.d(TAG, "No unbonding requests found");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing unbonding requests", e);
        }
    }
    
    private void displayUnbondingRequests(JSONArray unbondingArray) {
        // TODO: Add UI elements to show unbonding requests with amount and remaining time
        Log.d(TAG, "Displaying " + unbondingArray.length() + " unbonding requests");
        for (int i = 0; i < unbondingArray.length(); i++) {
            try {
                JSONObject request = unbondingArray.getJSONObject(i);
                Log.d(TAG, "Unbonding request " + i + ": " + request.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error processing unbonding request " + i, e);
            }
        }
    }
    
    private String getTokenContractAddress(String symbol) {
        Tokens.TokenInfo tokenInfo = Tokens.getToken(symbol);
        return tokenInfo != null ? tokenInfo.contract : null;
    }
    
    private void executeRemoveLiquidity(double removeAmount) {
        try {
            String tokenContract = getTokenContractAddress(tokenKey);
            if (tokenContract == null) {
                android.widget.Toast.makeText(getContext(), "Token contract not found", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Convert shares to microunits (multiply by 1,000,000)
            long removeAmountMicro = Math.round(removeAmount * 1000000);
            
            // Create remove liquidity message
            JSONObject msg = new JSONObject();
            JSONObject removeLiquidity = new JSONObject();
            removeLiquidity.put("amount", String.valueOf(removeAmountMicro));
            removeLiquidity.put("pool", tokenContract);
            msg.put("remove_liquidity", removeLiquidity);
            
            Log.d(TAG, "Remove liquidity message: " + msg.toString());
            
            // Use TransactionActivity with SECRET_EXECUTE transaction type
            Intent intent = new Intent(getContext(), TransactionActivity.class);
            intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE);
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.EXCHANGE_CONTRACT);
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.EXCHANGE_HASH);
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, msg.toString());
            intent.putExtra(TransactionActivity.EXTRA_MEMO, "Remove liquidity for " + tokenKey);

            startActivityForResult(intent, REQ_REMOVE_LIQUIDITY);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating remove liquidity message", e);
            android.widget.Toast.makeText(getContext(), "Error: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_REMOVE_LIQUIDITY) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "Remove liquidity transaction succeeded");
                // Clear input field
                removeAmountInput.setText("");
                // Refresh data (broadcast receiver will also handle this)
                loadUserShares();
                loadUnbondingRequests();
            } else {
                String error = (data != null) ? data.getStringExtra(TransactionActivity.EXTRA_ERROR) : "Transaction failed";
                Log.e(TAG, "Remove liquidity transaction failed: " + error);
            }
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // Refresh data when tab becomes visible
        Log.d(TAG, "RemoveLiquidityFragment resumed - refreshing user shares");
        loadUserShares();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Unregister broadcast receiver
        if (transactionSuccessReceiver != null && getContext() != null) {
            try {
                requireActivity().getApplicationContext().unregisterReceiver(transactionSuccessReceiver);
                Log.d(TAG, "Unregistered transaction success receiver");
            } catch (IllegalArgumentException e) {
                // Receiver was not registered, ignore
                Log.d(TAG, "Receiver was not registered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}