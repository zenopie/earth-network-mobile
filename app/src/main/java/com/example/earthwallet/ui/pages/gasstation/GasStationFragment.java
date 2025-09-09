package com.example.earthwallet.ui.pages.gasstation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.ImageView;
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
import com.example.earthwallet.bridge.activities.SnipExecuteActivity;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.constants.Tokens;

import org.json.JSONObject;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * GasStationFragment
 * 
 * Implements gas station functionality for swapping any token to SCRT for gas:
 * - From token selection with balance display
 * - Gas swap simulation and execution using swap_for_gas message
 * - Integration with registration and faucet systems
 * - Viewing key management integration
 */
public class GasStationFragment extends Fragment {
    
    private static final String TAG = "GasStationFragment";
    private static final String PREF_FILE = "viewing_keys_prefs";
    private static final int REQ_SWAP_FOR_GAS = 4001;
    private static final int REQUEST_REGISTRATION_CHECK = 4002;
    private static final int REQUEST_FAUCET_CLAIM = 4003;
    
    // UI Components
    private Spinner fromTokenSpinner;
    private EditText fromAmountInput, expectedScrtInput;
    private TextView fromBalanceText, scrtBalanceText, faucetStatusText;
    private Button fromMaxButton, fromViewingKeyButton;
    private ImageView fromTokenLogo;
    private Button swapForGasButton, faucetButton;
    
    // State
    private SharedPreferences securePrefs;
    private String currentWalletAddress = "";
    private List<String> tokenSymbols;
    private String fromToken = "sSCRT";
    private double fromBalance = 0.0;
    private double scrtBalance = 0.0;
    private boolean isSimulating = false;
    private boolean isRegistered = false;
    private boolean canClaimFaucet = false;
    private Handler inputHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Create secure preferences for viewing keys
        try {
            securePrefs = createSecurePrefs(requireContext());
            Log.d(TAG, "Successfully created viewing keys secure preferences");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create viewing keys secure preferences", e);
        }
        
        // Initialize token list (exclude SCRT since we're converting TO SCRT)
        tokenSymbols = new ArrayList<>(Tokens.ALL_TOKENS.keySet());
        tokenSymbols.remove("SCRT");
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gas_station, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupSpinner();
        setupClickListeners();
        loadCurrentWalletAddress();
        updateTokenLogo();
        fetchBalances();
        checkRegistrationStatus();
    }
    
    private void initializeViews(View view) {
        fromTokenSpinner = view.findViewById(R.id.from_token_spinner);
        fromAmountInput = view.findViewById(R.id.from_amount_input);
        expectedScrtInput = view.findViewById(R.id.expected_scrt_input);
        
        fromBalanceText = view.findViewById(R.id.from_balance_text);
        scrtBalanceText = view.findViewById(R.id.scrt_balance_text);
        faucetStatusText = view.findViewById(R.id.faucet_status_text);
        
        fromMaxButton = view.findViewById(R.id.from_max_button);
        fromViewingKeyButton = view.findViewById(R.id.from_viewing_key_button);
        fromTokenLogo = view.findViewById(R.id.from_token_logo);
        
        swapForGasButton = view.findViewById(R.id.swap_for_gas_button);
        faucetButton = view.findViewById(R.id.faucet_button);
    }
    
    private void setupSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), 
            R.layout.spinner_item, tokenSymbols);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        
        fromTokenSpinner.setAdapter(adapter);
        fromTokenSpinner.setSelection(tokenSymbols.indexOf(fromToken));
        
        fromTokenSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = tokenSymbols.get(position);
                if (!selected.equals(fromToken)) {
                    fromToken = selected;
                    onTokenSelectionChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    
    private void setupClickListeners() {
        // Add delayed simulation TextWatcher
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
                inputHandler.postDelayed(simulationRunnable, 500);
            }
        });
        
        fromMaxButton.setOnClickListener(v -> setMaxFromAmount());
        fromViewingKeyButton.setOnClickListener(v -> requestViewingKey(fromToken));
        
        swapForGasButton.setOnClickListener(v -> executeSwapForGas());
        faucetButton.setOnClickListener(v -> claimFaucet());
    }
    
    private void onTokenSelectionChanged() {
        updateTokenLogo();
        clearAmounts();
        fetchBalances();
    }
    
    private void onFromAmountChangedDelayed() {
        String amountStr = fromAmountInput.getText().toString();
        if (TextUtils.isEmpty(amountStr)) {
            expectedScrtInput.setText("");
            updateSwapButton();
            return;
        }
        
        try {
            double amount = Double.parseDouble(amountStr);
            if (amount > 0) {
                simulateSwapForGas(amount);
            } else {
                expectedScrtInput.setText("");
            }
        } catch (NumberFormatException e) {
            expectedScrtInput.setText("");
        }
        updateSwapButton();
    }
    
    private void simulateSwapForGas(double inputAmount) {
        if (isSimulating) return;
        isSimulating = true;
        
        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        Log.d(TAG, "Starting gas swap simulation: " + inputAmount + " " + fromTokenSymbol + " -> SCRT");
        
        Tokens.TokenInfo fromTokenInfo = Tokens.getToken(fromTokenSymbol);
        if (fromTokenInfo == null) {
            Log.e(TAG, "From token not supported: " + fromTokenSymbol);
            Toast.makeText(getContext(), "Token not supported", Toast.LENGTH_SHORT).show();
            isSimulating = false;
            updateSwapButton();
            return;
        }
        
        // Build swap simulation query - swap to sSCRT first (1:1 unwrap to SCRT)
        Tokens.TokenInfo sscrtTokenInfo = Tokens.getToken("sSCRT");
        if (sscrtTokenInfo == null) {
            Toast.makeText(getContext(), "sSCRT not available", Toast.LENGTH_SHORT).show();
            isSimulating = false;
            updateSwapButton();
            return;
        }
        
        String queryJson = String.format(
            "{\"simulate_swap\": {\"input_token\": \"%s\", \"amount\": \"%s\", \"output_token\": \"%s\"}}",
            fromTokenInfo.contract,
            String.valueOf((long)(inputAmount * Math.pow(10, fromTokenInfo.decimals))),
            sscrtTokenInfo.contract
        );
        
        Log.d(TAG, "Gas swap simulation query: " + queryJson);
        
        // Use SecretQueryService in background thread
        new Thread(() -> {
            try {
                if (!com.example.earthwallet.wallet.services.SecureWalletManager.isWalletAvailable(getContext())) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "No wallet found", Toast.LENGTH_SHORT).show();
                        isSimulating = false;
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
                    handleSimulationResult(response.toString());
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Gas swap simulation failed", e);
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Simulation failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isSimulating = false;
                    updateSwapButton();
                });
            }
        }).start();
    }
    
    private void handleSimulationResult(String json) {
        Log.d(TAG, "handleSimulationResult called with JSON: " + json);
        try {
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            
            if (success) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    String outputAmount = result.optString("output_amount", "0");
                    // The final SCRT amount will be the same as sSCRT amount (1:1 unwrap)
                    Tokens.TokenInfo sscrtTokenInfo = Tokens.getToken("sSCRT");
                    if (sscrtTokenInfo != null) {
                        double scrtOutput = Double.parseDouble(outputAmount) / Math.pow(10, sscrtTokenInfo.decimals);
                        DecimalFormat df = new DecimalFormat("#.######");
                        expectedScrtInput.setText(df.format(scrtOutput));
                        
                        Log.d(TAG, "Gas swap simulation successful - input: " + fromAmountInput.getText() + 
                            " " + tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition()) + 
                            ", output: " + df.format(scrtOutput) + " SCRT");
                    }
                }
            } else {
                Toast.makeText(getContext(), "Simulation failed", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse simulation result", e);
            Toast.makeText(getContext(), "Error simulating swap", Toast.LENGTH_SHORT).show();
        }
        
        isSimulating = false;
        updateSwapButton();
    }
    
    private void setMaxFromAmount() {
        if (fromBalance > 0) {
            fromAmountInput.setText(String.valueOf(fromBalance));
        }
    }
    
    private void clearAmounts() {
        fromAmountInput.setText("");
        expectedScrtInput.setText("");
        updateSwapButton();
    }
    
    private void updateTokenLogo() {
        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        loadTokenLogo(fromTokenLogo, fromTokenSymbol);
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
    
    private void updateSwapButton() {
        String fromAmountStr = fromAmountInput.getText().toString();
        String expectedScrtStr = expectedScrtInput.getText().toString();
        
        boolean enabled = !TextUtils.isEmpty(fromAmountStr) && 
                         !TextUtils.isEmpty(expectedScrtStr) &&
                         !fromAmountStr.equals("0") &&
                         !expectedScrtStr.equals("0");
                         
        swapForGasButton.setEnabled(enabled);
    }
    
    private void loadCurrentWalletAddress() {
        try {
            currentWalletAddress = com.example.earthwallet.wallet.services.SecureWalletManager.getWalletAddress(getContext());
            Log.d(TAG, "Loaded wallet address: " + currentWalletAddress);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallet address", e);
            currentWalletAddress = "";
        }
    }
    
    private void fetchBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            fromBalanceText.setText("Balance: Connect wallet");
            scrtBalanceText.setText("Balance: Connect wallet");
            return;
        }
        
        String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
        fetchTokenBalance(fromTokenSymbol, true);
        fetchNativeScrtBalance();
    }
    
    private void fetchTokenBalance(String tokenSymbol, boolean isFromToken) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            Log.w(TAG, "No wallet address available");
            return;
        }
        
        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
        if (tokenInfo == null) {
            Log.w(TAG, "Token not found: " + tokenSymbol);
            return;
        }
        
        String viewingKey = getViewingKeyForToken(tokenSymbol);
        if (TextUtils.isEmpty(viewingKey)) {
            fromBalance = -1;
            updateFromBalanceDisplay();
            return;
        }
        
        // Execute query in background thread
        new Thread(() -> {
            try {
                JSONObject result = com.example.earthwallet.bridge.services.SnipQueryService.queryBalance(
                    getActivity(),
                    tokenSymbol,
                    currentWalletAddress,
                    viewingKey
                );
                
                getActivity().runOnUiThread(() -> {
                    Log.d(TAG, "Token balance result: " + result.toString());
                    handleTokenBalanceResult(tokenSymbol, isFromToken, result.toString());
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Token balance query failed for " + tokenSymbol, e);
                getActivity().runOnUiThread(() -> {
                    fromBalance = -1;
                    updateFromBalanceDisplay();
                });
            }
        }).start();
    }
    
    private void fetchNativeScrtBalance() {
        // For now, set SCRT balance to placeholder - implement native balance query later
        scrtBalance = 0.0;
        updateScrtBalanceDisplay();
    }
    
    private void handleTokenBalanceResult(String tokenSymbol, boolean isFromToken, String json) {
        try {
            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "Balance query result JSON is empty");
                fromBalance = -1;
                updateFromBalanceDisplay();
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
                                    Log.w(TAG, "Invalid amount format: " + amount);
                                }
                            }
                            fromBalance = formattedBalance;
                            updateFromBalanceDisplay();
                        }
                    } else {
                        fromBalance = -1;
                        updateFromBalanceDisplay();
                    }
                }
            } else {
                String error = root.optString("error", "Unknown error");
                Log.e(TAG, "Balance query failed: " + error);
                fromBalance = -1;
                updateFromBalanceDisplay();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse balance query result", e);
            fromBalance = -1;
            updateFromBalanceDisplay();
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
    
    private void updateScrtBalanceDisplay() {
        DecimalFormat df = new DecimalFormat("#.##");
        scrtBalanceText.setText("Balance: " + df.format(scrtBalance));
    }
    
    private void checkRegistrationStatus() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            updateFaucetStatus(false, false);
            return;
        }
        
        // Check registration status using registration contract query
        new Thread(() -> {
            try {
                String queryJson = String.format(
                    "{\"query_registration_status\": {\"address\": \"%s\"}}",
                    currentWalletAddress
                );
                
                JSONObject queryObj = new JSONObject(queryJson);
                SecretQueryService queryService = new SecretQueryService(getContext());
                JSONObject result = queryService.queryContract(
                    Constants.REGISTRATION_CONTRACT,
                    Constants.REGISTRATION_HASH,
                    queryObj
                );
                
                getActivity().runOnUiThread(() -> {
                    handleRegistrationStatusResult(result.toString());
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Registration status check failed", e);
                getActivity().runOnUiThread(() -> {
                    updateFaucetStatus(false, false);
                });
            }
        }).start();
    }
    
    private void handleRegistrationStatusResult(String json) {
        try {
            JSONObject root = new JSONObject(json);
            boolean registrationStatus = root.optBoolean("registration_status", false);
            
            if (registrationStatus) {
                // Check if they can claim faucet (once per week)
                long lastClaim = root.optLong("last_claim", 0);
                long now = System.currentTimeMillis() * 1000000; // Convert to nanoseconds
                long oneWeekInNanos = 7L * 24 * 60 * 60 * 1000 * 1000000; // One week in nanoseconds
                boolean canClaim = (now - lastClaim) > oneWeekInNanos;
                
                updateFaucetStatus(true, canClaim);
            } else {
                updateFaucetStatus(false, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse registration status result", e);
            updateFaucetStatus(false, false);
        }
    }
    
    private void updateFaucetStatus(boolean registered, boolean canClaim) {
        isRegistered = registered;
        canClaimFaucet = canClaim;
        
        if (registered && canClaim) {
            faucetStatusText.setText("✓ Registered ✓ Available to use");
            faucetButton.setEnabled(true);
        } else if (registered && !canClaim) {
            faucetStatusText.setText("✓ Registered ✗ Already used this week");
            faucetButton.setEnabled(false);
        } else {
            faucetStatusText.setText("✗ Not registered");
            faucetButton.setEnabled(false);
        }
    }
    
    private String getViewingKeyForToken(String tokenSymbol) {
        try {
            Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
            if (tokenInfo == null || securePrefs == null) {
                return "";
            }
            
            String key = "viewing_key_" + currentWalletAddress + "_" + tokenInfo.contract;
            return securePrefs.getString(key, "");
        } catch (Exception e) {
            Log.e(TAG, "Failed to get viewing key for " + tokenSymbol, e);
            return "";
        }
    }
    
    private void requestViewingKey(String tokenSymbol) {
        Toast.makeText(getContext(), "Please set viewing key for " + tokenSymbol + " first", Toast.LENGTH_LONG).show();
    }
    
    private void executeSwapForGas() {
        String fromAmountStr = fromAmountInput.getText().toString();
        if (TextUtils.isEmpty(fromAmountStr)) return;
        
        try {
            double inputAmount = Double.parseDouble(fromAmountStr);
            if (inputAmount <= 0 || inputAmount > fromBalance) {
                Toast.makeText(getContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
                return;
            }
            
            swapForGasButton.setEnabled(false);
            swapForGasButton.setText("Swapping...");
            
            String fromTokenSymbol = tokenSymbols.get(fromTokenSpinner.getSelectedItemPosition());
            Tokens.TokenInfo fromTokenInfo = Tokens.getToken(fromTokenSymbol);
            if (fromTokenInfo == null) {
                Toast.makeText(getContext(), "Token not supported", Toast.LENGTH_SHORT).show();
                resetSwapButton();
                return;
            }
            
            // Build the swap_for_gas message
            String swapForGasMessage = String.format(
                "{\"swap_for_gas\": {\"from\": \"%s\", \"amount\": \"%s\"}}",
                currentWalletAddress,
                String.valueOf((long)(inputAmount * Math.pow(10, fromTokenInfo.decimals)))
            );
            
            long inputAmountMicro = (long)(inputAmount * Math.pow(10, fromTokenInfo.decimals));
            
            Log.d(TAG, "Starting swap for gas execution");
            Log.d(TAG, "From token: " + fromTokenSymbol);
            Log.d(TAG, "Amount: " + inputAmountMicro);
            Log.d(TAG, "Message: " + swapForGasMessage);
            
            Intent intent = new Intent(getContext(), SnipExecuteActivity.class);
            intent.putExtra(SnipExecuteActivity.EXTRA_TOKEN_CONTRACT, fromTokenInfo.contract);
            intent.putExtra(SnipExecuteActivity.EXTRA_TOKEN_HASH, fromTokenInfo.hash);
            intent.putExtra(SnipExecuteActivity.EXTRA_RECIPIENT, Constants.EXCHANGE_CONTRACT);
            intent.putExtra(SnipExecuteActivity.EXTRA_RECIPIENT_HASH, Constants.EXCHANGE_HASH);
            intent.putExtra(SnipExecuteActivity.EXTRA_AMOUNT, String.valueOf(inputAmountMicro));
            intent.putExtra(SnipExecuteActivity.EXTRA_MESSAGE_JSON, swapForGasMessage);
            
            startActivityForResult(intent, REQ_SWAP_FOR_GAS);
            
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
            resetSwapButton();
        }
    }
    
    private void claimFaucet() {
        if (!isRegistered || !canClaimFaucet) {
            Toast.makeText(getContext(), "Faucet not available", Toast.LENGTH_SHORT).show();
            return;
        }
        
        faucetButton.setEnabled(false);
        faucetButton.setText("Claiming...");
        
        // Implement faucet claiming logic - this would typically call a backend API
        Toast.makeText(getContext(), "Faucet functionality not implemented yet", Toast.LENGTH_SHORT).show();
        
        // Reset button
        faucetButton.setEnabled(true);
        faucetButton.setText("Faucet");
    }
    
    private void resetSwapButton() {
        swapForGasButton.setEnabled(true);
        swapForGasButton.setText("Swap for Gas");
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (requestCode == REQ_SWAP_FOR_GAS) {
                String json = data.getStringExtra(SnipExecuteActivity.EXTRA_RESULT_JSON);
                handleSwapForGasResult(json);
            }
        }
    }
    
    private void handleSwapForGasResult(String json) {
        Log.d(TAG, "handleSwapForGasResult called with JSON: " + json);
        
        resetSwapButton();
        
        try {
            JSONObject root = new JSONObject(json);
            
            boolean success = false;
            if (root.has("tx_response")) {
                JSONObject txResponse = root.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                success = (code == 0);
                
                if (success) {
                    String txHash = txResponse.optString("txhash", "");
                    Log.d(TAG, "Gas swap transaction hash: " + txHash);
                }
            } else {
                success = root.optBoolean("success", false);
            }
            
            if (success) {
                Toast.makeText(getContext(), "Gas swap successful!", Toast.LENGTH_SHORT).show();
                clearAmounts();
                fetchBalances(); // Refresh balances
            } else {
                Toast.makeText(getContext(), "Gas swap failed", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse gas swap result", e);
            Toast.makeText(getContext(), "Gas swap failed", Toast.LENGTH_SHORT).show();
        }
    }
    
    private static SharedPreferences createSecurePrefs(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create secure preferences", e);
            throw new RuntimeException("Secure preferences initialization failed", e);
        }
    }
}