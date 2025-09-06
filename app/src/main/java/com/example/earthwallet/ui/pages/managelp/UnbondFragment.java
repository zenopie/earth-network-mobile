package com.example.earthwallet.ui.pages.managelp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.wallet.constants.Tokens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UnbondFragment extends Fragment {
    
    private static final String TAG = "UnbondFragment";
    
    private String tokenKey;
    private String currentWalletAddress = "";
    private SharedPreferences securePrefs;
    private ExecutorService executorService;
    
    // Pool information for calculating estimated values
    private long erthReserveMicro = 0;
    private long tokenBReserveMicro = 0;
    private long totalSharesMicro = 0;
    
    private Button completeUnbondButton;
    private TextView unbondingRequestsTitle;
    private LinearLayout unbondingRequestsContainer;
    private TextView noUnbondingMessage;
    
    public static UnbondFragment newInstance(String tokenKey) {
        UnbondFragment fragment = new UnbondFragment();
        Bundle args = new Bundle();
        args.putString("token_key", tokenKey);
        fragment.setArguments(args);
        return fragment;
    }
    
    public static UnbondFragment newInstance(String tokenKey, long erthReserve, long tokenBReserve, long totalShares) {
        UnbondFragment fragment = new UnbondFragment();
        Bundle args = new Bundle();
        args.putString("token_key", tokenKey);
        args.putLong("erth_reserve", erthReserve);
        args.putLong("token_b_reserve", tokenBReserve);
        args.putLong("total_shares", totalShares);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            tokenKey = getArguments().getString("token_key");
            // Get pool information from arguments if provided
            erthReserveMicro = getArguments().getLong("erth_reserve", 0);
            tokenBReserveMicro = getArguments().getLong("token_b_reserve", 0);
            totalSharesMicro = getArguments().getLong("total_shares", 0);
            
            Log.d(TAG, "Pool info from arguments - ERTH: " + erthReserveMicro + ", Token: " + tokenBReserveMicro + ", Shares: " + totalSharesMicro);
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
        return inflater.inflate(R.layout.tab_liquidity_unbond, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupListeners();
        loadCurrentWalletAddress();
        // If pool info is available from arguments, go straight to unbonding requests
        if (erthReserveMicro > 0 && tokenBReserveMicro > 0 && totalSharesMicro > 0) {
            executorService.execute(() -> loadUnbondingRequests());
        } else {
            // Fall back to loading pool info first if not provided
            loadPoolInformationThenUnbondingRequests();
        }
    }
    
    private void initializeViews(View view) {
        completeUnbondButton = view.findViewById(R.id.complete_unbond_button);
        unbondingRequestsTitle = view.findViewById(R.id.unbonding_requests_title);
        unbondingRequestsContainer = view.findViewById(R.id.unbonding_requests_container);
        noUnbondingMessage = view.findViewById(R.id.no_unbonding_message);
    }
    
    private void setupListeners() {
        completeUnbondButton.setOnClickListener(v -> {
            // TODO: Implement complete unbond functionality
            Log.d(TAG, "Complete unbond button clicked");
        });
    }
    
    private void loadCurrentWalletAddress() {
        try {
            if (securePrefs != null) {
                String walletsJson = securePrefs.getString("wallets", "[]");
                JSONArray walletsArray = new JSONArray(walletsJson);
                int selectedIndex = securePrefs.getInt("selected_wallet_index", -1);
                
                if (walletsArray.length() > 0) {
                    JSONObject selectedWallet;
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
    
    private void loadPoolInformationThenUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            Log.d(TAG, "Cannot load pool information: missing token key or wallet address");
            return;
        }
        
        executorService.execute(() -> {
            try {
                String tokenContract = getTokenContractAddress(tokenKey);
                if (tokenContract == null) return;
                
                // Query pool information to get reserves and total shares
                JSONObject queryMsg = new JSONObject();
                JSONObject queryPool = new JSONObject();
                queryPool.put("pool", tokenContract);
                queryMsg.put("query_pool", queryPool);
                
                Log.d(TAG, "Querying pool information for " + tokenKey);
                
                com.example.earthwallet.bridge.services.SecretQueryService queryService = new com.example.earthwallet.bridge.services.SecretQueryService(getContext());
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                );
                
                Log.d(TAG, "Pool query result: " + result.toString());
                parsePoolInformation(result);
                
                // Now load unbonding requests after pool info is loaded
                loadUnbondingRequests();
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading pool information", e);
                // Still try to load unbonding requests even if pool info fails
                loadUnbondingRequests();
            }
        });
    }
    
    private void loadPoolInformation() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            Log.d(TAG, "Cannot load pool information: missing token key or wallet address");
            return;
        }
        
        executorService.execute(() -> {
            try {
                String tokenContract = getTokenContractAddress(tokenKey);
                if (tokenContract == null) return;
                
                // Query pool information to get reserves and total shares
                JSONObject queryMsg = new JSONObject();
                JSONObject queryPool = new JSONObject();
                queryPool.put("pool", tokenContract);
                queryMsg.put("query_pool", queryPool);
                
                Log.d(TAG, "Querying pool information for " + tokenKey);
                
                com.example.earthwallet.bridge.services.SecretQueryService queryService = new com.example.earthwallet.bridge.services.SecretQueryService(getContext());
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                );
                
                Log.d(TAG, "Pool query result: " + result.toString());
                parsePoolInformation(result);
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading pool information", e);
            }
        });
    }
    
    private void parsePoolInformation(JSONObject result) {
        try {
            // Handle the SecretQueryService error case where data is in the error message
            if (result.has("error") && result.has("decryption_error")) {
                String decryptionError = result.getString("decryption_error");
                Log.d(TAG, "Parsing pool info from decryption error");
                
                // Look for "base64=Value " in the error message
                String base64Marker = "base64=Value ";
                int base64Index = decryptionError.indexOf(base64Marker);
                if (base64Index != -1) {
                    int startIndex = base64Index + base64Marker.length();
                    int endIndex = decryptionError.indexOf(" of type", startIndex);
                    if (endIndex != -1) {
                        String jsonString = decryptionError.substring(startIndex, endIndex);
                        Log.d(TAG, "Extracted pool JSON: " + jsonString);
                        
                        try {
                            JSONObject poolData = new JSONObject(jsonString);
                            erthReserveMicro = poolData.optLong("erth_reserve", 0);
                            tokenBReserveMicro = poolData.optLong("token_b_reserve", 0);
                            totalSharesMicro = poolData.optLong("total_shares", 0);
                            
                            Log.d(TAG, "Pool info loaded - ERTH: " + erthReserveMicro + ", Token: " + tokenBReserveMicro + ", Shares: " + totalSharesMicro);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing pool JSON", e);
                        }
                    }
                }
            }
            
            // Also try the normal data path
            if (result.has("data")) {
                JSONObject poolData = result.getJSONObject("data");
                erthReserveMicro = poolData.optLong("erth_reserve", 0);
                tokenBReserveMicro = poolData.optLong("token_b_reserve", 0);
                totalSharesMicro = poolData.optLong("total_shares", 0);
                
                Log.d(TAG, "Pool info loaded from data - ERTH: " + erthReserveMicro + ", Token: " + tokenBReserveMicro + ", Shares: " + totalSharesMicro);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing pool information", e);
        }
    }
    
    private void loadUnbondingRequests() {
        if (tokenKey == null || TextUtils.isEmpty(currentWalletAddress)) {
            Log.d(TAG, "Cannot load unbonding requests: missing token key or wallet address");
            return;
        }
        
        try {
            String tokenContract = getTokenContractAddress(tokenKey);
            if (tokenContract == null) return;
            
            // Query unbonding requests for this user and token
            JSONObject queryMsg = new JSONObject();
            JSONObject queryUnbondingRequests = new JSONObject();
            queryUnbondingRequests.put("pool", tokenContract);
            queryUnbondingRequests.put("user", currentWalletAddress);
            queryMsg.put("query_unbonding_requests", queryUnbondingRequests);
            
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
    }
    
    private void parseUnbondingRequests(JSONObject result) {
        try {
            JSONArray unbondingArray = null;
            
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
                            unbondingArray = new JSONArray(jsonString);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing unbonding JSON array", e);
                        }
                    }
                }
            }
            
            // Also try the normal data path
            if (unbondingArray == null && result.has("data")) {
                Object data = result.get("data");
                if (data instanceof JSONArray) {
                    unbondingArray = (JSONArray) data;
                }
            }
            
            if (unbondingArray != null && unbondingArray.length() > 0) {
                Log.d(TAG, "Found " + unbondingArray.length() + " unbonding requests");
                displayUnbondingRequests(unbondingArray);
            } else {
                Log.d(TAG, "No unbonding requests found");
                showNoUnbondingMessage();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing unbonding requests", e);
            showNoUnbondingMessage();
        }
    }
    
    private void displayUnbondingRequests(JSONArray unbondingArray) {
        // Show the unbonding requests section
        noUnbondingMessage.setVisibility(View.GONE);
        unbondingRequestsTitle.setVisibility(View.VISIBLE);
        unbondingRequestsContainer.setVisibility(View.VISIBLE);
        
        // Clear any existing views
        unbondingRequestsContainer.removeAllViews();
        
        boolean hasCompletedRequests = false;
        
        for (int i = 0; i < unbondingArray.length(); i++) {
            try {
                JSONObject request = unbondingArray.getJSONObject(i);
                View requestView = createUnbondingRequestView(request, i);
                if (requestView != null) {
                    unbondingRequestsContainer.addView(requestView);
                    
                    // Check if this request is ready to complete
                    if (isRequestCompleted(request)) {
                        hasCompletedRequests = true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing unbonding request " + i, e);
            }
        }
        
        // Show complete unbond button if there are completed requests
        completeUnbondButton.setVisibility(hasCompletedRequests ? View.VISIBLE : View.GONE);
    }
    
    private View createUnbondingRequestView(JSONObject request, int index) {
        try {
            View view = LayoutInflater.from(getContext()).inflate(R.layout.item_unbonding_request, unbondingRequestsContainer, false);
            
            TextView amountText = view.findViewById(R.id.unbonding_amount_text);
            TextView estimatedText = view.findViewById(R.id.unbonding_estimated_text);
            TextView statusText = view.findViewById(R.id.unbonding_status_text);
            TextView timeText = view.findViewById(R.id.unbonding_time_text);
            
            // Parse the request data based on React app structure
            String displayAmount = "0 Shares";
            String estimatedValues = "";
            String status = "Unbonding";
            String timeRemaining = "Unknown";
            
            if (request.has("amount")) {
                long sharesMicro = request.getLong("amount");
                double sharesMacro = sharesMicro / 1000000.0;
                
                // Display shares amount with capital S
                displayAmount = String.format("%.2f Shares", sharesMacro);
                
                // Calculate estimated token values based on pool reserves (like InfoFragment does)
                if (totalSharesMicro > 0 && erthReserveMicro > 0 && tokenBReserveMicro > 0) {
                    // Calculate ownership percentage for these shares
                    double ownershipPercent = (sharesMicro * 100.0) / totalSharesMicro;
                    
                    // Calculate estimated underlying values
                    double erthReserveMacro = erthReserveMicro / 1000000.0;
                    double tokenBReserveMacro = tokenBReserveMicro / 1000000.0;
                    
                    double estimatedErth = (erthReserveMacro * ownershipPercent) / 100.0;
                    double estimatedToken = (tokenBReserveMacro * ownershipPercent) / 100.0;
                    
                    estimatedValues = String.format("~%.2f ERTH + %.2f %s", estimatedErth, estimatedToken, tokenKey);
                }
            }
            
            if (request.has("start_time")) {
                long startTime = request.getLong("start_time");
                long unbondSeconds = 7 * 24 * 60 * 60; // 7 days
                long claimableAt = startTime + unbondSeconds;
                long currentTime = System.currentTimeMillis() / 1000;
                
                if (currentTime >= claimableAt) {
                    status = "Ready to claim";
                    timeRemaining = "Ready";
                } else {
                    long remainingSeconds = claimableAt - currentTime;
                    timeRemaining = formatTimeRemaining(remainingSeconds);
                }
            }
            
            amountText.setText(displayAmount);
            statusText.setText(status);
            timeText.setText(timeRemaining);
            
            // Set estimated values or hide if empty
            if (estimatedValues != null && !estimatedValues.isEmpty()) {
                estimatedText.setText(estimatedValues);
                estimatedText.setVisibility(View.VISIBLE);
            } else {
                estimatedText.setVisibility(View.GONE);
            }
            
            Log.d(TAG, "Created unbonding request view: " + displayAmount + ", " + estimatedValues + ", " + status + ", " + timeRemaining);
            
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error creating unbonding request view", e);
            return null;
        }
    }
    
    private boolean isRequestCompleted(JSONObject request) {
        try {
            if (request.has("start_time")) {
                long startTime = request.getLong("start_time");
                long unbondSeconds = 7 * 24 * 60 * 60; // 7 days
                long claimableAt = startTime + unbondSeconds;
                long currentTime = System.currentTimeMillis() / 1000;
                return currentTime >= claimableAt;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if request is completed", e);
        }
        return false;
    }
    
    private String formatTimeRemaining(long seconds) {
        if (seconds <= 0) {
            return "Ready";
        }
        
        long days = seconds / (24 * 3600);
        long hours = (seconds % (24 * 3600)) / 3600;
        long minutes = (seconds % 3600) / 60;
        
        if (days > 0) {
            return days + "d " + hours + "h";
        } else if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }
    
    private void showNoUnbondingMessage() {
        noUnbondingMessage.setVisibility(View.VISIBLE);
        unbondingRequestsTitle.setVisibility(View.GONE);
        unbondingRequestsContainer.setVisibility(View.GONE);
        completeUnbondButton.setVisibility(View.GONE);
    }
    
    private String getTokenContractAddress(String symbol) {
        Tokens.TokenInfo tokenInfo = Tokens.getToken(symbol);
        return tokenInfo != null ? tokenInfo.contract : null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}