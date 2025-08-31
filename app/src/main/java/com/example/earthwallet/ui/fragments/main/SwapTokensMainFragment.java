package com.example.earthwallet.ui.fragments.main;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
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
import com.example.earthwallet.bridge.activities.SecretQueryActivity;
import com.example.earthwallet.bridge.activities.SecretExecuteActivity;
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
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final int REQ_SIMULATE_SWAP = 3001;
    private static final int REQ_EXECUTE_SWAP = 3002;
    private static final int REQ_BALANCE_QUERY = 3003;
    private static final int REQUEST_SWAP_SIMULATION = 3004;
    private static final int REQUEST_TOKEN_BALANCE = 3005;
    private static final int REQUEST_SWAP_EXECUTION = 3006;
    
    // Exchange contract (placeholder - should be in constants)
    private static final String EXCHANGE_CONTRACT = "secret1exchangecontractaddress";
    private static final String EXCHANGE_HASH = "exchangehash";
    
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
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            securePrefs = EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                requireContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create secure preferences", e);
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
        loadCurrentWalletAddress();
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
        fromAmountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                onFromAmountChanged();
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
    
    private void onFromAmountChanged() {
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
        
        // Show/hide details toggle
        detailsToggle.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) {
            detailsContainer.setVisibility(View.GONE);
            detailsVisible = false;
            detailsToggle.setText("Show Details ▼");
        }
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
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallet address", e);
        }
    }
    
    private void fetchBalances() {
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
    
    private void fetchTokenBalance(String tokenSymbol, boolean isFromToken) {
        Tokens.TokenInfo token = Tokens.getToken(tokenSymbol);
        if (token == null) return;
        
        // Check if we have a viewing key for this token
        String viewingKey = getViewingKey(token.contract);
        if (TextUtils.isEmpty(viewingKey)) {
            // No viewing key available
            if (isFromToken) {
                fromBalance = -1; // Error state
                updateFromBalanceDisplay();
            } else {
                toBalance = -1; // Error state
                updateToBalanceDisplay();
            }
            return;
        }
        
        // Query token balance using existing pattern from TokenBalancesFragment
        try {
            JSONObject query = new JSONObject();
            JSONObject balanceQuery = new JSONObject();
            balanceQuery.put("address", currentWalletAddress);
            balanceQuery.put("key", viewingKey);
            balanceQuery.put("time", System.currentTimeMillis());
            query.put("balance", balanceQuery);
            
            Intent qi = new Intent(getContext(), SecretQueryActivity.class);
            qi.putExtra(SecretQueryActivity.EXTRA_CONTRACT_ADDRESS, token.contract);
            qi.putExtra(SecretQueryActivity.EXTRA_CODE_HASH, token.hash);
            qi.putExtra(SecretQueryActivity.EXTRA_QUERY_JSON, query.toString());
            qi.putExtra("is_from_token", isFromToken);
            qi.putExtra("token_symbol", tokenSymbol);
            startActivityForResult(qi, REQ_BALANCE_QUERY);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to query balance for " + tokenSymbol, e);
            if (isFromToken) {
                fromBalance = -1;
                updateFromBalanceDisplay();
            } else {
                toBalance = -1;
                updateToBalanceDisplay();
            }
        }
    }
    
    private String getViewingKey(String contractAddress) {
        if (TextUtils.isEmpty(currentWalletAddress)) return "";
        return securePrefs.getString("viewing_key_" + currentWalletAddress + "_" + contractAddress, "");
    }
    
    private void requestViewingKey(String tokenSymbol) {
        Toast.makeText(getContext(), "Requesting viewing key for " + tokenSymbol, Toast.LENGTH_SHORT).show();
        // Navigate to ViewingKeyManagerFragment or implement inline viewing key request
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
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            String json = data.getStringExtra(SecretQueryActivity.EXTRA_RESULT_JSON);
            
            if (requestCode == REQ_BALANCE_QUERY) {
                handleBalanceQueryResult(data, json);
            } else if (requestCode == REQ_SIMULATE_SWAP || requestCode == REQUEST_SWAP_SIMULATION) {
                handleSwapSimulationResult(json);
            } else if (requestCode == REQ_EXECUTE_SWAP || requestCode == REQUEST_SWAP_EXECUTION) {
                handleSwapExecutionResult(json);
            } else if (requestCode == REQUEST_TOKEN_BALANCE) {
                handleTokenBalanceResult(data, json);
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
        try {
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            
            if (success) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    String outputAmount = result.optString("amount_out", "0");
                    String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
                    Tokens.TokenInfo toTokenInfo = Tokens.getToken(toTokenSymbol);
                    if (toTokenInfo != null) {
                        double formattedOutput = Double.parseDouble(outputAmount) / Math.pow(10, toTokenInfo.decimals);
                        DecimalFormat df = new DecimalFormat("#.######");
                        toAmountInput.setText(df.format(formattedOutput));
                        updateDetailsDisplay();
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
        swapButton.setEnabled(true);
        swapButton.setText("Swap");
        
        try {
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            
            swapButton.setEnabled(true);
            swapButton.setText("Swap");
            
            if (success) {
                Toast.makeText(getContext(), "Swap completed successfully!", Toast.LENGTH_SHORT).show();
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
        
        if (fromBalance > 0) {
            fromBalanceText.setText("Balance: " + df.format(fromBalance));
            fromMaxButton.setVisibility(View.VISIBLE);
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
        
        if (toBalance > 0) {
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
        
        // Get token info for from token
        Tokens.TokenInfo fromTokenInfo = Tokens.getToken(fromTokenSymbol);
        if (fromTokenInfo == null) {
            Toast.makeText(getContext(), "Token not supported", Toast.LENGTH_SHORT).show();
            isSimulatingSwap = false;
            updateSwapButton();
            return;
        }
        
        // Build swap simulation query
        String queryJson = String.format(
            "{\"simulate_swap_exact_in\": {\"token_in\": \"%s\", \"token_out\": \"%s\", \"amount_in\": \"%s\"}}",
            fromTokenInfo.contract,
            toTokenSymbol.equals("SCRT") ? "uscrt" : Tokens.getToken(toTokenSymbol).contract,
            String.valueOf((long)(inputAmount * Math.pow(10, fromTokenInfo.decimals)))
        );
        
        Intent intent = new Intent(getContext(), SecretQueryActivity.class);
        intent.putExtra("contractAddress", "secret1ja0hcwvy76grqkpgv2s42h5swh0uu4xeupj3h8"); // DEX contract
        intent.putExtra("queryJson", queryJson);
        intent.putExtra("requestType", "SWAP_SIMULATION");
        
        startActivityForResult(intent, REQUEST_SWAP_SIMULATION);
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
        
        String queryJson;
        String contractAddress;
        
        if ("SCRT".equals(tokenSymbol)) {
            // Native SCRT balance query - use bank module
            queryJson = "{\"balance\":{}}";
            contractAddress = "bank";
        } else {
            // SNIP-20 token balance query
            queryJson = String.format(
                "{\"balance\": {\"address\": \"%s\", \"key\": \"%s\"}}",
                currentWalletAddress,
                getViewingKeyForToken(tokenSymbol)
            );
            contractAddress = tokenInfo.contract;
        }
        
        Intent intent = new Intent(getContext(), SecretQueryActivity.class);
        intent.putExtra("contractAddress", contractAddress);
        intent.putExtra("queryJson", queryJson);
        intent.putExtra("requestType", "TOKEN_BALANCE");
        intent.putExtra("tokenSymbol", tokenSymbol);
        intent.putExtra("isFromToken", isFromToken);
        
        startActivityForResult(intent, REQUEST_TOKEN_BALANCE);
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
    
    private void executeSwapWithContract() {
        swapButton.setEnabled(false);
        swapButton.setText("Swapping...");
        
        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        String toTokenSymbol = tokenSymbols.get(toTokenSpinner.getSelectedItemPosition());
        double inputAmount = Double.parseDouble(fromAmountInput.getText().toString());
        
        Tokens.TokenInfo fromTokenInfo = Tokens.getToken(fromTokenSymbol);
        if (fromTokenInfo == null) {
            Toast.makeText(getContext(), "Token not supported", Toast.LENGTH_SHORT).show();
            swapButton.setEnabled(true);
            swapButton.setText("Swap");
            return;
        }
        
        // Build swap execution message
        String executeMsg = String.format(
            "{\"swap_exact_in\": {\"token_in\": \"%s\", \"token_out\": \"%s\", \"amount_in\": \"%s\", \"min_amount_out\": \"%s\"}}",
            fromTokenInfo.contract,
            toTokenSymbol.equals("SCRT") ? "uscrt" : Tokens.getToken(toTokenSymbol).contract,
            String.valueOf((long)(inputAmount * Math.pow(10, fromTokenInfo.decimals))),
            calculateMinAmountOut(inputAmount)
        );
        
        Intent intent = new Intent(getContext(), SecretExecuteActivity.class);
        intent.putExtra("contractAddress", "secret1ja0hcwvy76grqkpgv2s42h5swh0uu4xeupj3h8"); // DEX contract
        intent.putExtra("executeMsg", executeMsg);
        intent.putExtra("requestType", "SWAP_EXECUTION");
        
        startActivityForResult(intent, REQUEST_SWAP_EXECUTION);
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
        // Get viewing key for the token from secure storage
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    "secret_wallet_prefs",
                    masterKeyAlias,
                    getContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return securePrefs.getString("vk_" + tokenSymbol, "");
        } catch (Exception e) {
            Log.e(TAG, "Failed to get viewing key for " + tokenSymbol, e);
            return "";
        }
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
}