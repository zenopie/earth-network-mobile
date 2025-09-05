package com.example.earthwallet.ui.pages.managelp;

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
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.Constants;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AddLiquidityFragment extends Fragment {
    
    private static final String TAG = "AddLiquidityFragment";
    
    private EditText tokenAmountInput;
    private EditText erthAmountInput;
    private TextView tokenBalanceText;
    private TextView erthBalanceText;
    private TextView tokenLabel;
    private ImageView tokenInputLogo;
    private ImageView erthInputLogo;
    private Button tokenMaxButton;
    private Button erthMaxButton;
    private Button addLiquidityButton;
    
    private String tokenKey;
    private double tokenBalance = 0.0;
    private double erthBalance = 0.0;
    private String currentWalletAddress = "";
    private SharedPreferences securePrefs;
    private ExecutorService executorService;
    
    // Pool reserves for ratio calculation
    private double erthReserve = 0.0;
    private double tokenReserve = 0.0;
    private boolean isUpdatingRatio = false;
    
    public static AddLiquidityFragment newInstance(String tokenKey) {
        AddLiquidityFragment fragment = new AddLiquidityFragment();
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
        return inflater.inflate(R.layout.tab_liquidity_add, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupListeners();
        loadCurrentWalletAddress();
        updateTokenInfo();
        loadTokenBalances();
        loadPoolReserves();
    }
    
    private void initializeViews(View view) {
        tokenAmountInput = view.findViewById(R.id.token_amount_input);
        erthAmountInput = view.findViewById(R.id.erth_amount_input);
        tokenBalanceText = view.findViewById(R.id.token_balance_text);
        erthBalanceText = view.findViewById(R.id.erth_balance_text);
        tokenLabel = view.findViewById(R.id.token_label);
        tokenInputLogo = view.findViewById(R.id.token_input_logo);
        erthInputLogo = view.findViewById(R.id.erth_input_logo);
        tokenMaxButton = view.findViewById(R.id.token_max_button);
        erthMaxButton = view.findViewById(R.id.erth_max_button);
        addLiquidityButton = view.findViewById(R.id.add_liquidity_button);
    }
    
    private void setupListeners() {
        tokenMaxButton.setOnClickListener(v -> {
            if (tokenBalance > 0) {
                tokenAmountInput.setText(String.valueOf(tokenBalance));
                calculateErthFromToken(String.valueOf(tokenBalance));
            }
        });
        
        erthMaxButton.setOnClickListener(v -> {
            if (erthBalance > 0) {
                erthAmountInput.setText(String.valueOf(erthBalance));
                calculateTokenFromErth(String.valueOf(erthBalance));
            }
        });
        
        // Add text change listeners for ratio calculation
        tokenAmountInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (!isUpdatingRatio) {
                    calculateErthFromToken(s.toString());
                }
            }
        });
        
        erthAmountInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (!isUpdatingRatio) {
                    calculateTokenFromErth(s.toString());
                }
            }
        });
        
        addLiquidityButton.setOnClickListener(v -> {
            // TODO: Execute add liquidity
            Log.d(TAG, "Add liquidity button clicked");
        });
    }
    
    private void updateTokenInfo() {
        if (tokenKey != null) {
            tokenLabel.setText(tokenKey);
            tokenAmountInput.setHint("Amount of " + tokenKey);
            loadTokenLogo(tokenInputLogo, tokenKey);
            loadTokenLogo(erthInputLogo, "ERTH");
        }
    }
    
    private void loadTokenLogo(ImageView imageView, String tokenSymbol) {
        try {
            Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
            if (tokenInfo != null && tokenInfo.logo != null) {
                InputStream inputStream = getContext().getAssets().open(tokenInfo.logo);
                android.graphics.drawable.Drawable drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null);
                imageView.setImageDrawable(drawable);
                inputStream.close();
            } else {
                imageView.setImageResource(R.drawable.ic_wallet);
            }
        } catch (Exception e) {
            Log.d(TAG, "Logo not found for " + tokenSymbol + ", using default icon");
            imageView.setImageResource(R.drawable.ic_wallet);
        }
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
    
    private void loadTokenBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            tokenBalanceText.setText("Balance: Connect wallet");
            erthBalanceText.setText("Balance: Connect wallet");
            return;
        }
        
        // Load token balance
        if (tokenKey != null) {
            fetchTokenBalance(tokenKey, true);
        }
        
        // Load ERTH balance  
        fetchTokenBalance("ERTH", false);
    }
    
    private void fetchTokenBalance(String tokenSymbol, boolean isToken) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            Log.w(TAG, "No wallet address available");
            return;
        }
        
        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
        if (tokenInfo == null) {
            Log.w(TAG, "Token not found: " + tokenSymbol);
            return;
        }
        
        if ("SCRT".equals(tokenSymbol)) {
            // Native SCRT - set to 0 for now
            if (isToken) {
                tokenBalance = 0.0;
                updateTokenBalanceDisplay();
            } else {
                erthBalance = 0.0;
                updateErthBalanceDisplay();
            }
        } else {
            // SNIP-20 token balance query
            String viewingKey = getViewingKeyForToken(tokenSymbol);
            if (TextUtils.isEmpty(viewingKey)) {
                // No viewing key available
                if (isToken) {
                    tokenBalance = -1;
                    updateTokenBalanceDisplay();
                } else {
                    erthBalance = -1;
                    updateErthBalanceDisplay();
                }
                return;
            }
            
            // Execute query in background thread
            executorService.execute(() -> {
                try {
                    JSONObject result = com.example.earthwallet.bridge.services.SnipQueryService.queryBalance(
                        getActivity(),
                        tokenSymbol,
                        currentWalletAddress,
                        viewingKey
                    );
                    
                    // Handle result on UI thread
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            handleBalanceResult(tokenSymbol, isToken, result.toString());
                        });
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Token balance query failed for " + tokenSymbol + ": " + e.getMessage(), e);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (isToken) {
                                tokenBalance = -1;
                                updateTokenBalanceDisplay();
                            } else {
                                erthBalance = -1;
                                updateErthBalanceDisplay();
                            }
                        });
                    }
                }
            });
        }
    }
    
    private void handleBalanceResult(String tokenSymbol, boolean isToken, String json) {
        try {
            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "Balance query result JSON is empty");
                if (isToken) {
                    tokenBalance = -1;
                    updateTokenBalanceDisplay();
                } else {
                    erthBalance = -1;
                    updateErthBalanceDisplay();
                }
                return;
            }
            
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            
            if (success) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    JSONObject balance = result.optJSONObject("balance");
                    if (balance != null) {
                        String amount = balance.optString("amount", "0");
                        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
                        if (tokenInfo != null) {
                            double formattedBalance = 0;
                            if (!TextUtils.isEmpty(amount)) {
                                try {
                                    formattedBalance = Double.parseDouble(amount) / Math.pow(10, tokenInfo.decimals);
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "Invalid amount format: " + amount + ", using 0");
                                }
                            }
                            if (isToken) {
                                tokenBalance = formattedBalance;
                                updateTokenBalanceDisplay();
                            } else {
                                erthBalance = formattedBalance;
                                updateErthBalanceDisplay();
                            }
                        }
                    } else {
                        if (isToken) {
                            tokenBalance = -1;
                            updateTokenBalanceDisplay();
                        } else {
                            erthBalance = -1;
                            updateErthBalanceDisplay();
                        }
                    }
                } else {
                    if (isToken) {
                        tokenBalance = -1;
                        updateTokenBalanceDisplay();
                    } else {
                        erthBalance = -1;
                        updateErthBalanceDisplay();
                    }
                }
            } else {
                String error = root.optString("error", "Unknown error");
                Log.e(TAG, "Balance query failed: " + error);
                if (isToken) {
                    tokenBalance = -1;
                    updateTokenBalanceDisplay();
                } else {
                    erthBalance = -1;
                    updateErthBalanceDisplay();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse balance query result", e);
            if (isToken) {
                tokenBalance = -1;
                updateTokenBalanceDisplay();
            } else {
                erthBalance = -1;
                updateErthBalanceDisplay();
            }
        }
    }
    
    private void updateTokenBalanceDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        
        if (tokenBalance >= 0) {
            tokenBalanceText.setText("Balance: " + df.format(tokenBalance));
            tokenMaxButton.setVisibility(tokenBalance > 0 ? View.VISIBLE : View.VISIBLE);
        } else {
            tokenBalanceText.setText("Balance: Set viewing key");
            tokenMaxButton.setVisibility(View.GONE);
        }
    }
    
    private void updateErthBalanceDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        
        if (erthBalance >= 0) {
            erthBalanceText.setText("Balance: " + df.format(erthBalance));
            erthMaxButton.setVisibility(erthBalance > 0 ? View.VISIBLE : View.VISIBLE);
        } else {
            erthBalanceText.setText("Balance: Set viewing key");
            erthMaxButton.setVisibility(View.GONE);
        }
    }
    
    private String getViewingKeyForToken(String tokenSymbol) {
        try {
            Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
            if (tokenInfo == null || securePrefs == null) {
                return "";
            }
            
            String key = "viewing_key_" + currentWalletAddress + "_" + tokenInfo.contract;
            String viewingKey = securePrefs.getString(key, "");
            Log.d(TAG, "Viewing key lookup - key: " + key + ", found: " + !TextUtils.isEmpty(viewingKey));
            return viewingKey;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get viewing key for " + tokenSymbol, e);
            return "";
        }
    }
    
    private void loadPoolReserves() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
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
                        parsePoolReserves(result);
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading pool reserves", e);
            }
        });
    }
    
    private void parsePoolReserves(JSONObject result) {
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
                                        extractReserves(poolInfo);
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
                    extractReserves(poolInfo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing pool reserves", e);
        }
    }
    
    private void extractReserves(JSONObject poolInfo) {
        try {
            JSONObject poolState = poolInfo.getJSONObject("pool_info").getJSONObject("state");
            
            long erthReserveRaw = poolState.optLong("erth_reserve", 0);
            long tokenReserveRaw = poolState.optLong("token_b_reserve", 0);
            
            // Convert from microunits to regular units (divide by 10^6)
            erthReserve = erthReserveRaw / 1000000.0;
            tokenReserve = tokenReserveRaw / 1000000.0;
            
            Log.d(TAG, "Pool reserves loaded - ERTH: " + erthReserve + ", " + tokenKey + ": " + tokenReserve);
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting reserves from pool info", e);
        }
    }
    
    private String getTokenContractAddress(String symbol) {
        Tokens.TokenInfo tokenInfo = Tokens.getToken(symbol);
        return tokenInfo != null ? tokenInfo.contract : null;
    }
    
    private void calculateErthFromToken(String tokenAmountStr) {
        if (android.text.TextUtils.isEmpty(tokenAmountStr) || erthReserve <= 0 || tokenReserve <= 0) {
            return;
        }
        
        try {
            double tokenAmount = Double.parseDouble(tokenAmountStr);
            if (tokenAmount > 0) {
                double erthAmount = (tokenAmount * erthReserve) / tokenReserve;
                
                isUpdatingRatio = true;
                erthAmountInput.setText(String.format("%.6f", erthAmount));
                isUpdatingRatio = false;
            } else {
                isUpdatingRatio = true;
                erthAmountInput.setText("");
                isUpdatingRatio = false;
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid token amount format: " + tokenAmountStr);
        }
    }
    
    private void calculateTokenFromErth(String erthAmountStr) {
        if (android.text.TextUtils.isEmpty(erthAmountStr) || erthReserve <= 0 || tokenReserve <= 0) {
            return;
        }
        
        try {
            double erthAmount = Double.parseDouble(erthAmountStr);
            if (erthAmount > 0) {
                double tokenAmount = (erthAmount * tokenReserve) / erthReserve;
                
                isUpdatingRatio = true;
                tokenAmountInput.setText(String.format("%.6f", tokenAmount));
                isUpdatingRatio = false;
            } else {
                isUpdatingRatio = true;
                tokenAmountInput.setText("");
                isUpdatingRatio = false;
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid ERTH amount format: " + erthAmountStr);
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