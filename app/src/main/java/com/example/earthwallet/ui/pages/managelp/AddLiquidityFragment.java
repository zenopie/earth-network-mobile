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
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.activities.TransactionActivity;
import com.example.earthwallet.bridge.utils.PermitManager;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AddLiquidityFragment extends Fragment {
    
    private static final String TAG = "AddLiquidityFragment";
    private static final int REQ_ADD_LIQUIDITY = 4001;
    
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
    private PermitManager permitManager;
    private ExecutorService executorService;

    // Broadcast receiver for transaction success
    private BroadcastReceiver transactionSuccessReceiver;
    
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
        
        // Initialize permit manager
        permitManager = PermitManager.getInstance(requireContext());
        
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
        setupBroadcastReceiver();
        registerBroadcastReceiver();
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
    
    private void setupBroadcastReceiver() {
        transactionSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "*** BROADCAST RECEIVED: TRANSACTION_SUCCESS - AddLiquidityFragment ***");

                // Clear input fields immediately
                tokenAmountInput.setText("");
                erthAmountInput.setText("");

                // Add small delay before refreshing balances to allow blockchain state to settle
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Refreshing balances after transaction success");
                    loadTokenBalances();
                    loadPoolReserves();
                }, 200); // 200ms delay
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
                Log.i(TAG, "*** SUCCESSFULLY REGISTERED TRANSACTION_SUCCESS BROADCAST RECEIVER ***");
                android.widget.Toast.makeText(getContext(), "AddLiquidity: Broadcast receiver registered", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to register broadcast receiver", e);
            }
        }
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
            handleAddLiquidity();
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
            // SNIP-20 token balance query using permits
            if (!hasPermitForToken(tokenSymbol)) {
                // No permit available
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
                    JSONObject result = com.example.earthwallet.bridge.services.SnipQueryService.queryBalanceWithPermit(
                        getActivity(),
                        tokenSymbol,
                        currentWalletAddress
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
            tokenBalanceText.setText("Balance: Create permit");
            tokenMaxButton.setVisibility(View.GONE);
        }
    }
    
    private void updateErthBalanceDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        
        if (erthBalance >= 0) {
            erthBalanceText.setText("Balance: " + df.format(erthBalance));
            erthMaxButton.setVisibility(erthBalance > 0 ? View.VISIBLE : View.VISIBLE);
        } else {
            erthBalanceText.setText("Balance: Create permit");
            erthMaxButton.setVisibility(View.GONE);
        }
    }
    
    private boolean hasPermitForToken(String tokenSymbol) {
        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
        if (tokenInfo == null) {
            return false;
        }
        return permitManager.hasPermit(currentWalletAddress, tokenInfo.contract);
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
    
    /**
     * Handle add liquidity button click
     * Based on the React provideLiquidity function from your web app
     */
    private void handleAddLiquidity() {
        String tokenAmountStr = tokenAmountInput.getText().toString().trim();
        String erthAmountStr = erthAmountInput.getText().toString().trim();
        
        // Validate inputs
        if (TextUtils.isEmpty(tokenAmountStr) || TextUtils.isEmpty(erthAmountStr)) {
            Toast.makeText(getContext(), "Please enter both token amounts", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (TextUtils.isEmpty(currentWalletAddress)) {
            Toast.makeText(getContext(), "No wallet connected", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            double tokenAmount = Double.parseDouble(tokenAmountStr);
            double erthAmount = Double.parseDouble(erthAmountStr);
            
            if (tokenAmount <= 0 || erthAmount <= 0) {
                Toast.makeText(getContext(), "Amounts must be greater than zero", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Check balances
            if (tokenBalance >= 0 && tokenAmount > tokenBalance) {
                Toast.makeText(getContext(), "Insufficient " + tokenKey + " balance", Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (erthBalance >= 0 && erthAmount > erthBalance) {
                Toast.makeText(getContext(), "Insufficient ERTH balance", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Execute add liquidity transaction
            executeAddLiquidity(tokenAmount, erthAmount);
            
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Execute the add liquidity transaction using MultiMessageExecuteActivity
     * This matches the React app's provideLiquidity function with multi-message transaction
     */
    private void executeAddLiquidity(double tokenAmount, double erthAmount) {
        try {
            Log.d(TAG, "Executing add liquidity: " + tokenAmount + " " + tokenKey + " + " + erthAmount + " ERTH");
            
            Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenKey);
            Tokens.TokenInfo erthInfo = Tokens.getToken("ERTH");
            
            if (tokenInfo == null || erthInfo == null) {
                Toast.makeText(getContext(), "Token information not found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Convert to microunits (multiply by 10^6)
            long tokenMicroAmount = Math.round(tokenAmount * Math.pow(10, tokenInfo.decimals));
            long erthMicroAmount = Math.round(erthAmount * Math.pow(10, erthInfo.decimals));
            
            String walletAddress = getCurrentWalletAddress();
            if (TextUtils.isEmpty(walletAddress)) {
                Toast.makeText(getContext(), "No wallet address available", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Create the multi-message transaction array (like React app)
            JSONArray messages = new JSONArray();
            
            // 1. ERTH allowance message
            JSONObject erthAllowanceMsg = createAllowanceMessage(
                walletAddress, erthInfo.contract, erthInfo.hash, 
                Constants.EXCHANGE_CONTRACT, String.valueOf(erthMicroAmount)
            );
            messages.put(erthAllowanceMsg);
            
            // 2. Token allowance message  
            JSONObject tokenAllowanceMsg = createAllowanceMessage(
                walletAddress, tokenInfo.contract, tokenInfo.hash,
                Constants.EXCHANGE_CONTRACT, String.valueOf(tokenMicroAmount)
            );
            messages.put(tokenAllowanceMsg);
            
            // 3. Add liquidity message
            JSONObject addLiquidityMsg = createAddLiquidityMessage(
                walletAddress, Constants.EXCHANGE_CONTRACT, Constants.EXCHANGE_HASH,
                tokenInfo.contract, String.valueOf(erthMicroAmount), String.valueOf(tokenMicroAmount)
            );
            messages.put(addLiquidityMsg);
            
            Log.d(TAG, "Multi-message array: " + messages.toString());
            
            // Launch MultiMessageExecuteActivity
            Intent intent = new Intent(getContext(), TransactionActivity.class);
            intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_MULTI_MESSAGE);
            intent.putExtra(TransactionActivity.EXTRA_MESSAGES, messages.toString());
            intent.putExtra(TransactionActivity.EXTRA_MEMO, "Add liquidity: " + tokenAmount + " " + tokenKey + " + " + erthAmount + " ERTH");
            
            startActivityForResult(intent, REQ_ADD_LIQUIDITY);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute add liquidity transaction", e);
            Toast.makeText(getContext(), "Failed to create transaction: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Create SNIP-20 increase_allowance message
     */
    private JSONObject createAllowanceMessage(String sender, String tokenContract, String tokenHash, 
                                            String spender, String amount) throws Exception {
        JSONObject message = new JSONObject();
        message.put("sender", sender);
        message.put("contract", tokenContract);
        message.put("code_hash", tokenHash);
        
        JSONObject msg = new JSONObject();
        JSONObject increaseAllowance = new JSONObject();
        increaseAllowance.put("spender", spender);
        increaseAllowance.put("amount", amount);
        msg.put("increase_allowance", increaseAllowance);
        
        message.put("msg", msg);
        message.put("sent_funds", new JSONArray()); // Empty array for no funds
        
        return message;
    }
    
    /**
     * Create add_liquidity message for exchange contract
     */
    private JSONObject createAddLiquidityMessage(String sender, String exchangeContract, String exchangeHash,
                                               String pool, String amountErth, String amountB) throws Exception {
        JSONObject message = new JSONObject();
        message.put("sender", sender);
        message.put("contract", exchangeContract);
        message.put("code_hash", exchangeHash);
        
        JSONObject msg = new JSONObject();
        JSONObject addLiquidity = new JSONObject();
        addLiquidity.put("amount_erth", amountErth);
        addLiquidity.put("amount_b", amountB);
        addLiquidity.put("pool", pool);
        msg.put("add_liquidity", addLiquidity);
        
        message.put("msg", msg);
        message.put("sent_funds", new JSONArray()); // Empty array for no funds
        
        return message;
    }
    
    /**
     * Get current wallet address directly from secure preferences
     */
    private String getCurrentWalletAddress() {
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
                    return selectedWallet.optString("address", "");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get wallet address", e);
        }
        return "";
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ADD_LIQUIDITY) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "Add liquidity transaction succeeded");
                // Clear input fields
                tokenAmountInput.setText("");
                erthAmountInput.setText("");
                // Refresh data (broadcast receiver will also handle this)
                loadTokenBalances();
                loadPoolReserves();
            } else {
                String error = (data != null) ? data.getStringExtra(TransactionActivity.EXTRA_ERROR) : "Transaction failed";
                Log.e(TAG, "Add liquidity transaction failed: " + error);
            }
        }
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