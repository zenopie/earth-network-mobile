package com.example.earthwallet.ui.pages.managelp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InfoFragment extends Fragment {
    
    private static final String TAG = "InfoFragment";
    
    private String tokenKey;
    
    private TextView totalSharesText;
    private TextView userSharesText;
    private TextView poolOwnershipText;
    private TextView unbondingPercentText;
    private TextView erthValueText;
    private TextView tokenValueText;
    private TextView tokenValueLabel;
    
    private SecretQueryService queryService;
    private ExecutorService executorService;
    
    public static InfoFragment newInstance(String tokenKey) {
        InfoFragment fragment = new InfoFragment();
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
        Log.d(TAG, "InfoFragment created with tokenKey: " + tokenKey);
        queryService = new SecretQueryService(getContext());
        executorService = Executors.newSingleThreadExecutor();
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tab_liquidity_info, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        loadPoolInfo();
    }
    
    private void initializeViews(View view) {
        totalSharesText = view.findViewById(R.id.total_shares_text);
        userSharesText = view.findViewById(R.id.user_shares_text);
        poolOwnershipText = view.findViewById(R.id.pool_ownership_text);
        unbondingPercentText = view.findViewById(R.id.unbonding_percent_text);
        erthValueText = view.findViewById(R.id.erth_value_text);
        tokenValueText = view.findViewById(R.id.token_value_text);
        tokenValueLabel = view.findViewById(R.id.token_value_label);
        
        // Update token label
        if (tokenKey != null) {
            tokenValueLabel.setText(tokenKey + ":");
        }
    }
    
    private void loadPoolInfo() {
        if (tokenKey == null) return;
        
        executorService.execute(() -> {
            try {
                String userAddress = SecureWalletManager.getWalletAddress(getContext());
                if (userAddress == null) return;
                
                String tokenContract = getTokenContract(tokenKey);
                if (tokenContract == null) return;
                
                JSONObject queryMsg = new JSONObject();
                JSONObject queryUserInfo = new JSONObject();
                JSONArray poolsArray = new JSONArray();
                poolsArray.put(tokenContract);
                queryUserInfo.put("pools", poolsArray);
                queryUserInfo.put("user", userAddress);
                queryMsg.put("query_user_info", queryUserInfo);
                
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                );
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        processPoolData(result);
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading pool info", e);
            }
        });
    }
    
    private void processPoolData(JSONObject result) {
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
                                        updateUI(poolInfo);
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
                    updateUI(poolInfo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing pool data", e);
        }
    }
    
    private void updateUI(JSONObject poolInfo) {
        try {
            JSONObject poolState = poolInfo.getJSONObject("pool_info").getJSONObject("state");
            JSONObject userInfo = poolInfo.getJSONObject("user_info");
            
            // Format numbers
            DecimalFormat formatter = new DecimalFormat("#,###");
            DecimalFormat percentFormatter = new DecimalFormat("0.0000");
            DecimalFormat valueFormatter = new DecimalFormat("0.000000");
            
            long totalShares = poolState.optLong("total_shares", 0);
            long userShares = userInfo.optLong("amount_staked", 0);
            long unbondingShares = poolState.optLong("unbonding_shares", 0);
            
            // Calculate ownership percentage
            double ownershipPercent = totalShares > 0 ? (userShares * 100.0) / totalShares : 0.0;
            double unbondingPercent = totalShares > 0 ? (unbondingShares * 100.0) / totalShares : 0.0;
            
            // Update UI
            totalSharesText.setText(formatter.format(totalShares / 1000000.0));
            userSharesText.setText(formatter.format(userShares / 1000000.0));
            poolOwnershipText.setText(percentFormatter.format(ownershipPercent) + "%");
            unbondingPercentText.setText(percentFormatter.format(unbondingPercent) + "%");
            
            // Calculate underlying values
            long erthReserveMicro = poolState.optLong("erth_reserve", 0);
            long tokenBReserveMicro = poolState.optLong("token_b_reserve", 0);
            double erthReserveMacro = erthReserveMicro / 1000000.0;
            double tokenBReserveMacro = tokenBReserveMicro / 1000000.0;
            
            double userErthValue = (erthReserveMacro * ownershipPercent) / 100.0;
            double userTokenBValue = (tokenBReserveMacro * ownershipPercent) / 100.0;
            
            erthValueText.setText(valueFormatter.format(userErthValue));
            tokenValueText.setText(valueFormatter.format(userTokenBValue));
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating UI", e);
        }
    }
    
    private String getTokenContract(String tokenKey) {
        // Map token keys to contract addresses - this should match the React app
        switch (tokenKey) {
            case "ANML": return "secret14p6dhjznntlzw0yysl7p6z069nk0skv5e9qjut";
            case "sSCRT": return "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek";
            default: return null;
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}