package com.example.earthwallet.ui.pages.swap;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.activities.TransactionActivity;
import com.example.earthwallet.bridge.utils.ViewingKeyManager;

import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.constants.Tokens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * SwapTokensMainFragment
 * 
 * Implements token swapping functionality similar to the React web app:
 * - From/To token selection with balance display
 * - Swap simulation and execution
 * - Slippage tolerance settings
 * - Viewing key management integration
 */
public class SwapTokensMainFragment extends Fragment {
    
    private static final String TAG = "SwapTokensFragment";
    private static final String PREF_FILE = "viewing_keys_prefs";
    private static final int REQ_SIMULATE_SWAP = 3001;
    private static final int REQ_EXECUTE_SWAP = 3002;
    private static final int REQ_BALANCE_QUERY = 3003;
    private static final int REQUEST_SWAP_SIMULATION = 3004;
    private static final int REQUEST_TOKEN_BALANCE = 3005;
    private static final int REQUEST_SWAP_EXECUTION = 3006;
    private static final int REQ_SNIP_EXECUTE = 3008;
    
    
    // UI Components
    private Spinner fromTokenSpinner, toTokenSpinner;
    private EditText fromAmountInput, toAmountInput, slippageInput;
    private TextView fromBalanceText, toBalanceText, rateText, minReceivedText;
    private Button fromMaxButton, fromViewingKeyButton, toViewingKeyButton;
    private ImageButton toggleButton;
    private Button swapButton, detailsToggle;
    private LinearLayout detailsContainer;
    private ImageView fromTokenLogo, toTokenLogo;
    
    // State
    private SharedPreferences securePrefs;
    private String currentWalletAddress = "";
    private List<String> tokenSymbols;
    private String fromToken = "ANML";
    private String toToken = "ERTH";
    private double fromBalance = 0.0;
    private double toBalance = 0.0;
    private double slippage = 1.0;
    private boolean isSimulatingSwap = false;
    private boolean detailsVisible = false;
    private Handler inputHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;

    // Broadcast receiver for transaction success
    private BroadcastReceiver transactionSuccessReceiver;
    private ViewingKeyManager viewingKeyManager;
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Create separate secure preferences for viewing keys to avoid wallet corruption
        try {
            securePrefs = createSecurePrefs(requireContext());
            Log.d(TAG, "Successfully created viewing keys secure preferences");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create viewing keys secure preferences", e);
        }
        
        // Initialize token list
        tokenSymbols = new ArrayList<>(Tokens.ALL_TOKENS.keySet());
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_swap_tokens_main, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupSpinners();
        setupClickListeners();
        setupBroadcastReceiver();
        registerBroadcastReceiver();
        loadCurrentWalletAddress();

        // Initialize viewing key manager
        viewingKeyManager = ViewingKeyManager.getInstance(requireContext());

        updateTokenLogos();
        fetchBalances();
    }
    
    private void initializeViews(View view) {
        fromTokenSpinner = view.findViewById(R.id.from_token_spinner);
        toTokenSpinner = view.findViewById(R.id.to_token_spinner);
        fromAmountInput = view.findViewById(R.id.from_amount_input);
        toAmountInput = view.findViewById(R.id.to_amount_input);
        slippageInput = view.findViewById(R.id.slippage_input);
        
        fromBalanceText = view.findViewById(R.id.from_balance_text);
        toBalanceText = view.findViewById(R.id.to_balance_text);
        rateText = view.findViewById(R.id.rate_text);
        minReceivedText = view.findViewById(R.id.min_received_text);
        
        fromMaxButton = view.findViewById(R.id.from_max_button);
        fromViewingKeyButton = view.findViewById(R.id.from_viewing_key_button);
        toViewingKeyButton = view.findViewById(R.id.to_viewing_key_button);
        
        toggleButton = view.findViewById(R.id.toggle_button);
        swapButton = view.findViewById(R.id.swap_button);
        detailsToggle = view.findViewById(R.id.details_toggle);
        detailsContainer = view.findViewById(R.id.details_container);
        
        fromTokenLogo = view.findViewById(R.id.from_token_logo);
        toTokenLogo = view.findViewById(R.id.to_token_logo);
    }
    
    private void setupSpinners() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), 
            R.layout.spinner_item, tokenSymbols);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        
        fromTokenSpinner.setAdapter(adapter);
        toTokenSpinner.setAdapter(adapter);
        
        // Set initial selections
        fromTokenSpinner.setSelection(tokenSymbols.indexOf(fromToken));
        toTokenSpinner.setSelection(tokenSymbols.indexOf(toToken));
        
        fromTokenSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = tokenSymbols.get(position);
                if (!selected.equals(fromToken)) {
                    if (selected.equals(toToken)) {
                        // Swap tokens if selecting the same as 'to'
                        toToken = fromToken;
                        toTokenSpinner.setSelection(tokenSymbols.indexOf(toToken));
                    }
                    fromToken = selected;
                    onTokenSelectionChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        toTokenSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = tokenSymbols.get(position);
                if (!selected.equals(toToken)) {
                    if (selected.equals(fromToken)) {
                        // Swap tokens if selecting the same as 'from'
                        fromToken = toToken;
                        fromTokenSpinner.setSelection(tokenSymbols.indexOf(fromToken));
                    }
                    toToken = selected;
                    onTokenSelectionChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    
    private void setupClickListeners() {
        // Add delayed simulation TextWatcher to prevent clearing and keyboard dismissal
        fromAmountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                // Cancel previous simulation if still pending
                if (simulationRunnable != null) {
                    inputHandler.removeCallbacks(simulationRunnable);
                }
                
                // Schedule new simulation with delay
                simulationRunnable = () -> onFromAmountChangedDelayed();
                inputHandler.postDelayed(simulationRunnable, 500); // 500ms delay - no keyboard dismissal with direct service
            }
        });
        
        fromMaxButton.setOnClickListener(v -> setMaxFromAmount());
        fromViewingKeyButton.setOnClickListener(v -> requestViewingKey(fromToken));
        toViewingKeyButton.setOnClickListener(v -> requestViewingKey(toToken));
        
        toggleButton.setOnClickListener(v -> toggleTokenPair());
        swapButton.setOnClickListener(v -> executeSwap());
        
        detailsToggle.setOnClickListener(v -> toggleDetails());
        
        slippageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateSlippage();
            }
        });
    }
    
    private void onTokenSelectionChanged() {
        updateTokenLogos();
        clearAmounts();
        fetchBalances();
    }
    
    private void onFromAmountChangedDelayed() {
        String amountStr = fromAmountInput.getText().toString();
        if (TextUtils.isEmpty(amountStr)) {
            toAmountInput.setText("");
            updateSwapButton();
            return;
        }
        
        try {
            double amount = Double.parseDouble(amountStr);
            if (amount > 0) {
                simulateSwap(amount);
            } else {
                toAmountInput.setText("");
            }
        } catch (NumberFormatException e) {
            toAmountInput.setText("");
        }
        updateSwapButton();
    }
    
    private void simulateSwap(double inputAmount) {
        if (isSimulatingSwap) return;
        isSimulatingSwap = true;
        simulateSwapWithContract(inputAmount);
    }
    
    private void setMaxFromAmount() {
        if (fromBalance > 0) {
            fromAmountInput.setText(String.valueOf(fromBalance));
        }
    }
    
    private void toggleTokenPair() {
        String tempToken = fromToken;
        fromToken = toToken;
        toToken = tempToken;
        
        fromTokenSpinner.setSelection(tokenSymbols.indexOf(fromToken));
        toTokenSpinner.setSelection(tokenSymbols.indexOf(toToken));
        
        updateTokenLogos();
        clearAmounts();
        fetchBalances();
    }
    
    private void toggleDetails() {
        detailsVisible = !detailsVisible;
        detailsContainer.setVisibility(detailsVisible ? View.VISIBLE : View.GONE);
        detailsToggle.setText(detailsVisible ? "Hide Details ▲" : "Show Details ▼");
    }
    
    private void clearAmounts() {
        fromAmountInput.setText("");
        toAmountInput.setText("");
        updateSwapButton();
    }
    
    private void updateTokenLogos() {
        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
        
        loadTokenLogo(fromTokenLogo, fromTokenSymbol);
        loadTokenLogo(toTokenLogo, toTokenSymbol);
    }
    
    private void loadTokenLogo(ImageView imageView, String tokenSymbol) {
        try {
            // Get token info and logo path
            Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
            if (tokenInfo != null && tokenInfo.logo != null) {
                InputStream inputStream = getContext().getAssets().open(tokenInfo.logo);
                android.graphics.drawable.Drawable drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null);
                imageView.setImageDrawable(drawable);
                inputStream.close();
            } else {
                // No token info or logo path, use default
                imageView.setImageResource(R.drawable.ic_wallet);
            }
        } catch (Exception e) {
            // If logo not found, use default wallet icon
            Log.d(TAG, "Logo not found for " + tokenSymbol + ", using default icon");
            imageView.setImageResource(R.drawable.ic_wallet);
        }
    }
    
    private void updateSwapButton() {
        String fromAmountStr = fromAmountInput.getText().toString();
        String toAmountStr = toAmountInput.getText().toString();
        
        boolean enabled = !TextUtils.isEmpty(fromAmountStr) && 
                         !TextUtils.isEmpty(toAmountStr) &&
                         !fromAmountStr.equals("0") &&
                         !toAmountStr.equals("0");
                         
        swapButton.setEnabled(enabled);
        
        // Details toggle is always visible now - no layout shift
    }
    
    private void updateSlippage() {
        String slippageStr = slippageInput.getText().toString();
        try {
            slippage = Double.parseDouble(slippageStr);
            updateDetailsDisplay();
        } catch (NumberFormatException e) {
            slippage = 1.0;
        }
    }
    
    private void updateDetailsDisplay() {
        String fromAmountStr = fromAmountInput.getText().toString();
        String toAmountStr = toAmountInput.getText().toString();
        
        if (!TextUtils.isEmpty(fromAmountStr) && !TextUtils.isEmpty(toAmountStr)) {
            try {
                double fromAmount = Double.parseDouble(fromAmountStr);
                double toAmount = Double.parseDouble(toAmountStr);
                
                // Update rate
                double rate = toAmount / fromAmount;
                DecimalFormat df = new DecimalFormat("#.######");
                rateText.setText("1 " + fromToken + " = " + df.format(rate) + " " + toToken);
                
                // Update minimum received
                double minReceived = toAmount * (1 - slippage / 100);
                Tokens.TokenInfo toTokenInfo = Tokens.getToken(toToken);
                int decimals = toTokenInfo != null ? toTokenInfo.decimals : 6;
                DecimalFormat minDf = new DecimalFormat("#." + "0".repeat(decimals));
                minReceivedText.setText(minDf.format(minReceived) + " " + toToken);
                
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error updating details display", e);
            }
        }
    }
    
    private void loadCurrentWalletAddress() {
        try {
            // Use SecureWalletManager to get current wallet address
            currentWalletAddress = com.example.earthwallet.wallet.services.SecureWalletManager.getWalletAddress(getContext());
            Log.d(TAG, "Loaded wallet address from SecureWalletManager: " + currentWalletAddress);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallet address", e);
            currentWalletAddress = "";
        }
    }
    
    private void fetchBalances() {
        Log.i(TAG, "*** FETCHBALANCES CALLED - refreshing " + fromToken + " and " + toToken + " balances ***");

        if (TextUtils.isEmpty(currentWalletAddress)) {
            fromBalanceText.setText("Balance: Connect wallet");
            toBalanceText.setText("Balance: Connect wallet");
            return;
        }

        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
        
        fetchTokenBalanceWithContract(fromTokenSymbol, true);
        fetchTokenBalanceWithContract(toTokenSymbol, false);
    }
    
    
    private String getViewingKey(String contractAddress) {
        if (TextUtils.isEmpty(currentWalletAddress)) return "";
        return securePrefs.getString("viewing_key_" + currentWalletAddress + "_" + contractAddress, "");
    }
    
    private void requestViewingKey(String tokenSymbol) {
        Toast.makeText(getContext(), "Requesting viewing key for " + tokenSymbol, Toast.LENGTH_SHORT).show();
        // Use ViewingKeyService or implement inline viewing key request
        Toast.makeText(getContext(), "Please set viewing key for " + tokenSymbol + " first", Toast.LENGTH_LONG).show();
    }
    
    private void executeSwap() {
        String fromAmountStr = fromAmountInput.getText().toString();
        if (TextUtils.isEmpty(fromAmountStr)) return;
        
        try {
            double inputAmount = Double.parseDouble(fromAmountStr);
            if (inputAmount <= 0 || inputAmount > fromBalance) {
                Toast.makeText(getContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
                return;
            }
            
            executeSwapWithContract();
            
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SNIP_EXECUTE) {
            if (resultCode == Activity.RESULT_OK) {
                clearAmounts();
                fetchBalances(); // Refresh balances
            } else {
                String error = data != null ? data.getStringExtra(TransactionActivity.EXTRA_ERROR) : "Unknown error";
                Toast.makeText(getContext(), "Swap failed: " + error, Toast.LENGTH_LONG).show();
            }
        } else if (resultCode == Activity.RESULT_OK && data != null) {
            String json;

            if (requestCode == REQ_EXECUTE_SWAP || requestCode == REQUEST_SWAP_EXECUTION) {
                // Legacy handling for old flow - can be removed later
                json = data.getStringExtra(TransactionActivity.EXTRA_RESULT_JSON);
                handleSwapExecutionResult(json);
            } else {
                // Use generic result key for other requests
                json = data.getStringExtra("EXTRA_RESULT_JSON");

                if (requestCode == REQ_BALANCE_QUERY) {
                    handleBalanceQueryResult(data, json);
                } else if (requestCode == REQ_SIMULATE_SWAP || requestCode == REQUEST_SWAP_SIMULATION) {
                    handleSwapSimulationResult(json);
                } else if (requestCode == REQUEST_TOKEN_BALANCE) {
                    handleTokenBalanceResult(data, json);
                }
            }
        }
    }
    
    private void handleBalanceQueryResult(Intent data, String json) {
        try {
            boolean isFromToken = data.getBooleanExtra("is_from_token", false);
            String tokenSymbol = data.getStringExtra("token_symbol");
            
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            
            if (success) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    JSONObject balance = result.optJSONObject("balance");
                    if (balance != null) {
                        String amount = balance.optString("amount", "0");
                        Tokens.TokenInfo token = Tokens.getToken(tokenSymbol);
                        if (token != null) {
                            double balanceValue = Double.parseDouble(amount) / Math.pow(10, token.decimals);
                            
                            if (isFromToken) {
                                fromBalance = balanceValue;
                                updateFromBalanceDisplay();
                            } else {
                                toBalance = balanceValue;
                                updateToBalanceDisplay();
                            }
                        }
                    }
                }
            } else {
                // Balance query failed
                if (isFromToken) {
                    fromBalance = -1;
                    updateFromBalanceDisplay();
                } else {
                    toBalance = -1;
                    updateToBalanceDisplay();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse balance query result", e);
        }
    }
    
    private void handleSwapSimulationResult(String json) {
        Log.d(TAG, "handleSwapSimulationResult called with JSON: " + json);
        try {
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            Log.d(TAG, "Swap simulation success: " + success);
            
            if (success) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    // Parse output_amount to match React web app response format
                    String outputAmount = result.optString("output_amount", "0");
                    String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
                    Tokens.TokenInfo toTokenInfo = Tokens.getToken(toTokenSymbol);
                    if (toTokenInfo != null) {
                        double formattedOutput = Double.parseDouble(outputAmount) / Math.pow(10, toTokenInfo.decimals);
                        DecimalFormat df = new DecimalFormat("#.######");
                        
                        // Update output amount without requesting focus to avoid dismissing keyboard
                        toAmountInput.setText(df.format(formattedOutput));
                        updateDetailsDisplay();
                        
                        Log.d(TAG, "Swap simulation successful - input: " + fromAmountInput.getText() + " " + 
                            tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition()) + ", output: " + 
                            df.format(formattedOutput) + " " + toTokenSymbol);
                    }
                }
            } else {
                Toast.makeText(getContext(), "Swap simulation failed", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse swap simulation result", e);
            Toast.makeText(getContext(), "Error simulating swap", Toast.LENGTH_SHORT).show();
        }
        
        isSimulatingSwap = false;
        updateSwapButton();
    }
    
    private void handleSwapExecutionResult(String json) {
        Log.d(TAG, "handleSwapExecutionResult called with JSON: " + json);
        
        try {
            JSONObject root = new JSONObject(json);
            
            // Check for success based on transaction code (0 = success)
            boolean success = false;
            if (root.has("tx_response")) {
                JSONObject txResponse = root.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                success = (code == 0);
                Log.d(TAG, "Transaction code: " + code + ", success: " + success);
                
                if (success) {
                    String txHash = txResponse.optString("txhash", "");
                    Log.d(TAG, "Transaction hash: " + txHash);
                }
            } else {
                // Fallback to old success field
                success = root.optBoolean("success", false);
                Log.d(TAG, "Using fallback success field: " + success);
            }
            
            if (success) {
                clearAmounts();
                fetchBalances(); // Refresh balances
            } else {
                Toast.makeText(getContext(), "Swap failed", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse swap execution result", e);
            Toast.makeText(getContext(), "Swap failed", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateFromBalanceDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        
        if (fromBalance >= 0) {
            fromBalanceText.setText("Balance: " + df.format(fromBalance));
            fromMaxButton.setVisibility(fromBalance > 0 ? View.VISIBLE : View.GONE);
            fromViewingKeyButton.setVisibility(View.GONE);
        } else if (fromBalance == -1) {
            fromBalanceText.setText("Balance: Error");
            fromMaxButton.setVisibility(View.GONE);
            fromViewingKeyButton.setVisibility(View.VISIBLE);
        } else {
            fromBalanceText.setText("Balance: ...");
            fromMaxButton.setVisibility(View.GONE);
            fromViewingKeyButton.setVisibility(View.GONE);
        }
    }
    
    private void updateToBalanceDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        
        if (toBalance >= 0) {
            toBalanceText.setText("Balance: " + df.format(toBalance));
            toViewingKeyButton.setVisibility(View.GONE);
        } else if (toBalance == -1) {
            toBalanceText.setText("Balance: Error");
            toViewingKeyButton.setVisibility(View.VISIBLE);
        } else {
            toBalanceText.setText("Balance: ...");
            toViewingKeyButton.setVisibility(View.GONE);
        }
    }
    
    private void simulateSwapWithContract(double inputAmount) {
        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
        
        Log.d(TAG, "Starting swap simulation: " + inputAmount + " " + fromTokenSymbol + " -> " + toTokenSymbol);
        
        // Get token info for from token
        Tokens.TokenInfo fromTokenInfo = Tokens.getToken(fromTokenSymbol);
        if (fromTokenInfo == null) {
            Log.e(TAG, "From token not supported: " + fromTokenSymbol);
            Toast.makeText(getContext(), "Token not supported", Toast.LENGTH_SHORT).show();
            isSimulatingSwap = false;
            updateSwapButton();
            return;
        }
        
        // Build swap simulation query to match React web app format
        Tokens.TokenInfo toTokenInfo = Tokens.getToken(toTokenSymbol);
        if (toTokenInfo == null) {
            Toast.makeText(getContext(), "To token not supported", Toast.LENGTH_SHORT).show();
            isSimulatingSwap = false;
            updateSwapButton();
            return;
        }
        
        String queryJson = String.format(
            "{\"simulate_swap\": {\"input_token\": \"%s\", \"amount\": \"%s\", \"output_token\": \"%s\"}}",
            fromTokenInfo.contract,
            String.valueOf((long)(inputAmount * Math.pow(10, fromTokenInfo.decimals))),
            toTokenInfo.contract
        );
        
        Log.d(TAG, "Swap simulation query: " + queryJson);
        Log.d(TAG, "Exchange contract: " + Constants.EXCHANGE_CONTRACT);
        
        // Use SecretQueryService directly in background thread to avoid Activity transition
        new Thread(() -> {
            try {
                // Check wallet availability using the correct wallet preferences
                if (!com.example.earthwallet.wallet.services.SecureWalletManager.isWalletAvailable(getContext())) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "No wallet found", Toast.LENGTH_SHORT).show();
                        isSimulatingSwap = false;
                        updateSwapButton();
                    });
                    return;
                }
                
                JSONObject queryObj = new JSONObject(queryJson);
                SecretQueryService queryService = new SecretQueryService(getContext());
                JSONObject result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryObj
                );
                
                // Format result to match expected format
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("result", result);
                
                // Handle result on UI thread
                getActivity().runOnUiThread(() -> {
                    handleSwapSimulationResult(response.toString());
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Swap simulation failed", e);
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Swap simulation failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isSimulatingSwap = false;
                    updateSwapButton();
                });
            }
        }).start();
    }
    
    private void fetchTokenBalanceWithContract(String tokenSymbol, boolean isFromToken) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            Log.w("SwapTokensMainFragment", "No wallet address available");
            return;
        }
        
        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
        if (tokenInfo == null) {
            Log.w("SwapTokensMainFragment", "Token not found: " + tokenSymbol);
            return;
        }
        
        if ("SCRT".equals(tokenSymbol)) {
            // Native SCRT balance query - handle directly since it doesn't use contract queries
            // For now, set to 0 balance as SCRT balance queries need different handling
            if (isFromToken) {
                fromBalance = 0.0;
                updateFromBalanceDisplay();
            } else {
                toBalance = 0.0;
                updateToBalanceDisplay();
            }
        } else {
            // SNIP-20 token balance query using SnipQueryService directly
            String viewingKey = getViewingKeyForToken(tokenSymbol);
            if (TextUtils.isEmpty(viewingKey)) {
                // No viewing key available - set balance to error state
                if (isFromToken) {
                    fromBalance = -1;
                    updateFromBalanceDisplay();
                } else {
                    toBalance = -1;
                    updateToBalanceDisplay();
                }
                return;
            }
            
            // Execute query in background thread
            new Thread(() -> {
                try {
                    JSONObject result = com.example.earthwallet.bridge.services.SnipQueryService.queryBalance(
                        getActivity(), // Use HostActivity context instead of Fragment context
                        tokenSymbol,
                        currentWalletAddress,
                        viewingKey
                    );
                    
                    // Handle result on UI thread
                    getActivity().runOnUiThread(() -> {
                        Log.d(TAG, "Raw SNIP query result: " + result.toString());
                        handleSnipBalanceResult(tokenSymbol, isFromToken, result.toString());
                    });
                    
                } catch (Exception e) {
                    Log.e(TAG, "Token balance query failed for " + tokenSymbol + ": " + e.getMessage(), e);
                    getActivity().runOnUiThread(() -> {
                        if (isFromToken) {
                            fromBalance = -1;
                            updateFromBalanceDisplay();
                        } else {
                            toBalance = -1;
                            updateToBalanceDisplay();
                        }
                    });
                }
            }).start();
        }
    }
    
    private void handleSnipBalanceResult(String tokenSymbol, boolean isFromToken, String json) {
        try {
            Log.d(TAG, "handleSnipBalanceResult - tokenSymbol: " + tokenSymbol + ", isFromToken: " + isFromToken);
            
            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "SNIP balance query result JSON is empty");
                if (isFromToken) {
                    fromBalance = -1;
                    updateFromBalanceDisplay();
                } else {
                    toBalance = -1;
                    updateToBalanceDisplay();
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
                            // Always process amount, even if it's "0" or empty (like wallet display does)
                            double formattedBalance = 0;
                            if (!TextUtils.isEmpty(amount)) {
                                try {
                                    formattedBalance = Double.parseDouble(amount) / Math.pow(10, tokenInfo.decimals);
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "Invalid amount format: " + amount + ", using 0");
                                }
                            }
                            if (isFromToken) {
                                fromBalance = formattedBalance;
                                updateFromBalanceDisplay();
                            } else {
                                toBalance = formattedBalance;
                                updateToBalanceDisplay();
                            }
                        } else {
                            if (isFromToken) {
                                fromBalance = -1;
                                updateFromBalanceDisplay();
                            } else {
                                toBalance = -1;
                                updateToBalanceDisplay();
                            }
                        }
                    } else {
                        if (isFromToken) {
                            fromBalance = -1;
                            updateFromBalanceDisplay();
                        } else {
                            toBalance = -1;
                            updateToBalanceDisplay();
                        }
                    }
                } else {
                    if (isFromToken) {
                        fromBalance = -1;
                        updateFromBalanceDisplay();
                    } else {
                        toBalance = -1;
                        updateToBalanceDisplay();
                    }
                }
            } else {
                String error = root.optString("error", "Unknown error");
                Log.e(TAG, "SNIP balance query failed: " + error);
                if (isFromToken) {
                    fromBalance = -1;
                    updateFromBalanceDisplay();
                } else {
                    toBalance = -1;
                    updateToBalanceDisplay();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse SNIP balance query result", e);
            if (isFromToken) {
                fromBalance = -1;
                updateFromBalanceDisplay();
            } else {
                toBalance = -1;
                updateToBalanceDisplay();
            }
        }
    }

    private void updateBalanceDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        
        if (fromBalance > 0) {
            fromBalanceText.setText("Balance: " + df.format(fromBalance));
            fromMaxButton.setVisibility(View.VISIBLE);
            fromViewingKeyButton.setVisibility(View.GONE);
        } else {
            fromBalanceText.setText("Balance: Error");
            fromMaxButton.setVisibility(View.GONE);
            fromViewingKeyButton.setVisibility(View.VISIBLE);
        }
        
        if (toBalance > 0) {
            toBalanceText.setText("Balance: " + df.format(toBalance));
            toViewingKeyButton.setVisibility(View.GONE);
        } else {
            toBalanceText.setText("Balance: Error");
            toViewingKeyButton.setVisibility(View.VISIBLE);
        }
    }
    
    private void setupBroadcastReceiver() {
        transactionSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "*** SWAP PAGE: Received TRANSACTION_SUCCESS broadcast - refreshing balances ***");

                // Clear amounts and start multiple refresh attempts to ensure UI updates during animation
                clearAmounts();
                fetchBalances(); // First immediate refresh

                // Stagger additional refreshes to catch the UI during animation
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Secondary refresh during animation");
                    fetchBalances();
                }, 100); // 100ms delay

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Third refresh during animation");
                    fetchBalances();
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
    }

    private void executeSwapWithContract() {
        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
        double inputAmount = Double.parseDouble(fromAmountInput.getText().toString());
        
        Tokens.TokenInfo fromTokenInfo = Tokens.getToken(fromTokenSymbol);
        if (fromTokenInfo == null) {
            Toast.makeText(getContext(), "Token not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Build swap execution message to match React web app format
        // Use SNIP execution with "send" message like the React app's snip() function
        Tokens.TokenInfo toTokenInfo = Tokens.getToken(toTokenSymbol);
        if (toTokenInfo == null) {
            Toast.makeText(getContext(), "To token not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Build the message that will be base64 encoded (like snipmsg in React app)
        String swapMessage = String.format(
            "{\"swap\": {\"output_token\": \"%s\", \"min_received\": \"%s\"}}",
            toTokenInfo.contract,
            calculateMinAmountOut(inputAmount)
        );
        
        long inputAmountMicro = (long)(inputAmount * Math.pow(10, fromTokenInfo.decimals));
        
        Log.d(TAG, "Starting SNIP swap execution");
        Log.d(TAG, "From token: " + fromTokenSymbol + " (" + fromTokenInfo.contract + ")");
        Log.d(TAG, "To exchange: " + Constants.EXCHANGE_CONTRACT);
        Log.d(TAG, "Amount: " + inputAmountMicro);
        Log.d(TAG, "Swap message: " + swapMessage);
        
        Intent intent = new Intent(getContext(), TransactionActivity.class);
        intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SNIP_EXECUTE);
        intent.putExtra(TransactionActivity.EXTRA_TOKEN_CONTRACT, fromTokenInfo.contract);
        intent.putExtra(TransactionActivity.EXTRA_TOKEN_HASH, fromTokenInfo.hash);
        intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, Constants.EXCHANGE_CONTRACT);
        intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_HASH, Constants.EXCHANGE_HASH);
        intent.putExtra(TransactionActivity.EXTRA_AMOUNT, String.valueOf(inputAmountMicro));
        intent.putExtra(TransactionActivity.EXTRA_MESSAGE_JSON, swapMessage);

        startActivityForResult(intent, REQ_SNIP_EXECUTE);
    }
    
    private String calculateMinAmountOut(double inputAmount) {
        // Calculate minimum output based on slippage tolerance
        try {
            double slippage = Double.parseDouble(slippageInput.getText().toString()) / 100.0;
            double expectedOutput = Double.parseDouble(toAmountInput.getText().toString());
            double minOutput = expectedOutput * (1.0 - slippage);
            
            String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
            Tokens.TokenInfo toTokenInfo = Tokens.getToken(toTokenSymbol);
            int decimals = toTokenInfo != null ? toTokenInfo.decimals : 6;
            
            return String.valueOf((long)(minOutput * Math.pow(10, decimals)));
        } catch (Exception e) {
            return "0";
        }
    }
    
    private String getViewingKeyForToken(String tokenSymbol) {
        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
        if (tokenInfo == null) {
            return "";
        }
        return viewingKeyManager.getViewingKey(currentWalletAddress, tokenInfo.contract);
    }
    
    private void handleTokenBalanceResult(Intent data, String json) {
        try {
            boolean isFromToken = data.getBooleanExtra("isFromToken", false);
            String tokenSymbol = data.getStringExtra("tokenSymbol");
            
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            
            if (success) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    String balanceStr = "0";
                    if ("SCRT".equals(tokenSymbol)) {
                        // Native SCRT balance
                        JSONArray balances = result.optJSONArray("balances");
                        if (balances != null && balances.length() > 0) {
                            JSONObject balance = balances.getJSONObject(0);
                            balanceStr = balance.optString("amount", "0");
                        }
                    } else {
                        // SNIP-20 token balance
                        JSONObject balance = result.optJSONObject("balance");
                        if (balance != null) {
                            balanceStr = balance.optString("amount", "0");
                        }
                    }
                    
                    Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
                    if (tokenInfo != null) {
                        double balanceValue = Double.parseDouble(balanceStr) / Math.pow(10, tokenInfo.decimals);
                        
                        if (isFromToken) {
                            fromBalance = balanceValue;
                        } else {
                            toBalance = balanceValue;
                        }
                        updateBalanceDisplay();
                    }
                }
            } else {
                // Balance query failed - set to -1 to show "Get Viewing Key" button
                if (isFromToken) {
                    fromBalance = -1;
                } else {
                    toBalance = -1;
                }
                updateBalanceDisplay();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse token balance result", e);
            if (data.getBooleanExtra("isFromToken", false)) {
                fromBalance = -1;
            } else {
                toBalance = -1;
            }
            updateBalanceDisplay();
        }
    }
    
    private static SharedPreferences createSecurePrefs(Context context) {
        try {
            String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
            return androidx.security.crypto.EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                context,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e("SwapTokensMainFragment", "Failed to create secure preferences", e);
            throw new RuntimeException("Secure preferences initialization failed", e);
        }
    }
    
}