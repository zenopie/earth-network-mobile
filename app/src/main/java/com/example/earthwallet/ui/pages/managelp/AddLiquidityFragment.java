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

import org.json.JSONObject;

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
            }
        });
        
        erthMaxButton.setOnClickListener(v -> {
            if (erthBalance > 0) {
                erthAmountInput.setText(String.valueOf(erthBalance));
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
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}