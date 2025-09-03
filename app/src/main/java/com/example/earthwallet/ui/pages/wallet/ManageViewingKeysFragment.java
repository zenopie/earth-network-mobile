package com.example.earthwallet.ui.pages.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;
import com.example.earthwallet.bridge.activities.SecretExecuteActivity;
import com.example.earthwallet.wallet.constants.Tokens;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * ManageViewingKeysFragment
 * 
 * Allows users to manage their stored viewing keys:
 * - View all tokens that have viewing keys set
 * - Remove viewing keys for specific tokens
 * - Navigate back to token balances
 */
public class ManageViewingKeysFragment extends Fragment {
    
    private static final String TAG = "ManageViewingKeysFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final int REQ_SET_VIEWING_KEY = 2003;
    
    // UI Components
    private LinearLayout viewingKeysContainer;
    private TextView emptyStateMessage;
    
    // State management
    private SharedPreferences securePrefs;
    private String walletAddress = "";
    private Tokens.TokenInfo pendingViewingKeyToken = null;
    private String pendingViewingKey = null;
    
    // Interface for communication with parent
    public interface ManageViewingKeysListener {
        String getCurrentWalletAddress();
        void onViewingKeyRemoved(Tokens.TokenInfo token);
        void onViewingKeyRequested(Tokens.TokenInfo token);
    }
    
    private ManageViewingKeysListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof ManageViewingKeysListener) {
            listener = (ManageViewingKeysListener) context;
        }
        // Note: We don't require the listener since this fragment can work independently
    }
    
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
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_manage_viewing_keys, container, false);
        
        viewingKeysContainer = view.findViewById(R.id.viewing_keys_container);
        emptyStateMessage = view.findViewById(R.id.empty_state_message);
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Get wallet address from arguments first
        Bundle args = getArguments();
        if (args != null) {
            walletAddress = args.getString("wallet_address", "");
        }
        
        // Fallback to listener if available
        if (TextUtils.isEmpty(walletAddress) && listener != null) {
            walletAddress = listener.getCurrentWalletAddress();
        }
        
        // If we still don't have a wallet address, try to get it directly
        if (TextUtils.isEmpty(walletAddress)) {
            walletAddress = getCurrentWalletAddressDirect();
        }
        
        Log.d(TAG, "onViewCreated - walletAddress: " + walletAddress);
        
        // Load and display viewing keys
        loadViewingKeys();
    }
    
    /**
     * Load all tokens and display them with their viewing key status
     */
    private void loadViewingKeys() {
        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available");
            showEmptyState();
            return;
        }
        
        viewingKeysContainer.removeAllViews();
        
        List<TokenViewingKeyInfo> allTokens = getAllTokensWithViewingKeyStatus();
        
        if (allTokens.isEmpty()) {
            showEmptyState();
        } else {
            hideEmptyState();
            for (TokenViewingKeyInfo tokenInfo : allTokens) {
                addTokenItem(tokenInfo);
            }
        }
    }
    
    /**
     * Get current wallet address directly from secure preferences
     */
    private String getCurrentWalletAddressDirect() {
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
                return selectedWallet.optString("address", "");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get wallet address directly", e);
        }
        return "";
    }

    /**
     * Get all tokens with their viewing key status
     */
    private List<TokenViewingKeyInfo> getAllTokensWithViewingKeyStatus() {
        List<TokenViewingKeyInfo> allTokens = new ArrayList<>();
        
        try {
            Log.d(TAG, "Loading all tokens with viewing key status, wallet address: " + walletAddress);
            
            // Check each token to see if it has a viewing key
            for (String symbol : Tokens.ALL_TOKENS.keySet()) {
                Tokens.TokenInfo token = Tokens.getToken(symbol);
                if (token != null) {
                    String viewingKey = getViewingKey(token.contract);
                    Log.d(TAG, "Token " + symbol + " (" + token.contract + ") viewing key: " + 
                        (TextUtils.isEmpty(viewingKey) ? "NONE" : viewingKey.length() + " chars"));
                    
                    TokenViewingKeyInfo tokenInfo = new TokenViewingKeyInfo();
                    tokenInfo.token = token;
                    tokenInfo.viewingKey = viewingKey; // Can be null/empty
                    allTokens.add(tokenInfo);
                    Log.d(TAG, "Added token " + symbol + " (has viewing key: " + !TextUtils.isEmpty(viewingKey) + ")");
                }
            }
            
            Log.d(TAG, "Loaded " + allTokens.size() + " tokens total");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load tokens", e);
        }
        
        return allTokens;
    }
    
    /**
     * Add a token item to the UI (with or without viewing key)
     */
    private void addTokenItem(TokenViewingKeyInfo tokenInfo) {
        try {
            // Create a row for each token
            LinearLayout tokenRow = new LinearLayout(getContext());
            tokenRow.setOrientation(LinearLayout.HORIZONTAL);
            tokenRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tokenRow.setPadding(16, 16, 16, 16);
            tokenRow.setBackground(getResources().getDrawable(R.drawable.card_rounded_bg));
            
            // Add margin between rows
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.setMargins(0, 0, 0, 16);
            tokenRow.setLayoutParams(rowParams);
            
            // Token logo
            if (!TextUtils.isEmpty(tokenInfo.token.logo)) {
                ImageView logoView = new ImageView(getContext());
                LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                    dpToPx(32), dpToPx(32)
                );
                logoParams.setMargins(0, 0, dpToPx(12), 0);
                logoView.setLayoutParams(logoParams);
                logoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                
                // Load logo from assets
                try {
                    Bitmap bitmap = BitmapFactory.decodeStream(getContext().getAssets().open(tokenInfo.token.logo));
                    logoView.setImageBitmap(bitmap);
                    tokenRow.addView(logoView);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load logo for " + tokenInfo.token.symbol + ": " + tokenInfo.token.logo, e);
                }
            }
            
            // Token info container
            LinearLayout tokenInfoContainer = new LinearLayout(getContext());
            tokenInfoContainer.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
            );
            tokenInfoContainer.setLayoutParams(infoParams);
            
            // Token symbol
            TextView symbolText = new TextView(getContext());
            symbolText.setText(tokenInfo.token.symbol);
            symbolText.setTextSize(16);
            symbolText.setTextColor(android.graphics.Color.parseColor("#1e3a8a"));
            symbolText.setTypeface(null, android.graphics.Typeface.BOLD);
            tokenInfoContainer.addView(symbolText);
            
            // Status text (viewing key status or truncated key)
            TextView statusText = new TextView(getContext());
            if (TextUtils.isEmpty(tokenInfo.viewingKey)) {
                statusText.setText("No viewing key set");
                statusText.setTextColor(getResources().getColor(R.color.wallet_row_address));
            } else {
                String truncatedKey = tokenInfo.viewingKey.length() > 20 ? 
                    tokenInfo.viewingKey.substring(0, 20) + "..." : tokenInfo.viewingKey;
                statusText.setText("Key: " + truncatedKey);
                statusText.setTextColor(getResources().getColor(R.color.wallet_row_address));
            }
            statusText.setTextSize(12);
            tokenInfoContainer.addView(statusText);
            
            tokenRow.addView(tokenInfoContainer);
            
            // Action button (Get or Remove)
            Button actionButton = new Button(getContext());
            actionButton.setTextSize(12);
            actionButton.setPadding(16, 8, 16, 8);
            actionButton.setMinWidth(0);
            actionButton.setMinHeight(0);
            actionButton.setElevation(0f);
            actionButton.setStateListAnimator(null);
            
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            actionButton.setLayoutParams(buttonParams);
            
            if (TextUtils.isEmpty(tokenInfo.viewingKey)) {
                // No viewing key - show "Get" button
                actionButton.setText("Get");
                android.graphics.drawable.GradientDrawable getBackground = new android.graphics.drawable.GradientDrawable();
                getBackground.setColor(android.graphics.Color.parseColor("#4caf50"));
                getBackground.setCornerRadius(8 * getResources().getDisplayMetrics().density);
                actionButton.setBackground(getBackground);
                actionButton.setTextColor(getResources().getColor(android.R.color.white));
                actionButton.setOnClickListener(v -> requestViewingKey(tokenInfo.token));
            } else {
                // Has viewing key - show "Remove" button
                actionButton.setText("Remove");
                android.graphics.drawable.GradientDrawable removeBackground = new android.graphics.drawable.GradientDrawable();
                removeBackground.setColor(android.graphics.Color.parseColor("#f44336"));
                removeBackground.setCornerRadius(8 * getResources().getDisplayMetrics().density);
                actionButton.setBackground(removeBackground);
                actionButton.setTextColor(getResources().getColor(android.R.color.white));
                actionButton.setOnClickListener(v -> showRemoveConfirmationDialog(tokenInfo));
            }
            
            tokenRow.addView(actionButton);
            
            viewingKeysContainer.addView(tokenRow);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to add token item for " + tokenInfo.token.symbol, e);
        }
    }
    
    /**
     * Show confirmation dialog before removing viewing key
     */
    private void showRemoveConfirmationDialog(TokenViewingKeyInfo keyInfo) {
        String message = "Are you sure you want to remove the viewing key for " + keyInfo.token.symbol + "?\n\n" +
                "This will hide the token balance until you set a viewing key again.";
        
        new AlertDialog.Builder(getContext())
            .setTitle("Remove Viewing Key")
            .setMessage(message)
            .setPositiveButton("Remove", (dialog, which) -> {
                removeViewingKey(keyInfo);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    /**
     * Remove the viewing key for a token
     */
    private void removeViewingKey(TokenViewingKeyInfo keyInfo) {
        try {
            // Remove viewing key from secure preferences
            String viewingKeyPref = "viewing_key_" + walletAddress + "_" + keyInfo.token.contract;
            String symbolKeyPref = "viewing_key_symbol_" + walletAddress + "_" + keyInfo.token.contract;
            
            securePrefs.edit()
                .remove(viewingKeyPref)
                .remove(symbolKeyPref)
                .apply();
            
            Toast.makeText(getContext(), "Viewing key removed for " + keyInfo.token.symbol, Toast.LENGTH_SHORT).show();
            
            // Notify parent if available
            if (listener != null) {
                listener.onViewingKeyRemoved(keyInfo.token);
            }
            
            // Reload the viewing keys list
            loadViewingKeys();
            
            Log.i(TAG, "Successfully removed viewing key for " + keyInfo.token.symbol);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove viewing key for " + keyInfo.token.symbol, e);
            Toast.makeText(getContext(), "Failed to remove viewing key: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Show empty state when no viewing keys are found
     */
    private void showEmptyState() {
        viewingKeysContainer.setVisibility(View.GONE);
        emptyStateMessage.setVisibility(View.VISIBLE);
    }
    
    /**
     * Hide empty state when viewing keys are available
     */
    private void hideEmptyState() {
        viewingKeysContainer.setVisibility(View.VISIBLE);
        emptyStateMessage.setVisibility(View.GONE);
    }
    
    /**
     * Convert dp to pixels
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
    
    /**
     * Get viewing key for a contract
     */
    private String getViewingKey(String contractAddress) {
        if (TextUtils.isEmpty(walletAddress)) {
            return "";
        }
        return securePrefs.getString("viewing_key_" + walletAddress + "_" + contractAddress, "");
    }
    
    /**
     * Public method to update wallet address
     */
    public void updateWalletAddress(String newAddress) {
        if (!newAddress.equals(walletAddress)) {
            walletAddress = newAddress;
            loadViewingKeys();
        }
    }
    
    /**
     * Request viewing key generation for a token
     */
    private void requestViewingKey(Tokens.TokenInfo token) {
        Log.i(TAG, "Requesting viewing key for " + token.symbol);
        
        // Check if viewing key already exists
        String existingKey = getViewingKey(token.contract);
        if (!TextUtils.isEmpty(existingKey)) {
            showViewingKeyManagementDialog(token);
        } else {
            showAutoViewingKeyDialog(token);
        }
    }
    
    private void showAutoViewingKeyDialog(Tokens.TokenInfo token) {
        new AlertDialog.Builder(getContext())
            .setTitle("Set Viewing Key for " + token.symbol)
            .setMessage("A viewing key is required to see your " + token.symbol + " balance. Would you like to:")
            .setPositiveButton("Generate Random Key", (dialog, which) -> {
                generateViewingKey(token);
            })
            .setNeutralButton("Enter Custom Key", (dialog, which) -> {
                showSetViewingKeyDialog(token);
            })
            .setNegativeButton("Learn More", (dialog, which) -> {
                showViewingKeyHelpDialog(token);
            })
            .setCancelable(true)
            .show();
    }
    
    private void showSetViewingKeyDialog(Tokens.TokenInfo token) {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_set_viewing_key, null);
        EditText keyInput = dialogView.findViewById(R.id.viewing_key_input);
        
        new AlertDialog.Builder(getContext())
            .setTitle("Set Viewing Key for " + token.symbol)
            .setView(dialogView)
            .setPositiveButton("Set Key", (dialog, which) -> {
                String customKey = keyInput.getText().toString().trim();
                if (!TextUtils.isEmpty(customKey)) {
                    executeSetViewingKeyTransaction(token, customKey);
                } else {
                    Toast.makeText(getContext(), "Please enter a viewing key", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void showViewingKeyHelpDialog(Tokens.TokenInfo token) {
        String helpText = "Viewing keys allow you to see your token balance while keeping transactions private.\n\n" +
                "• Generated keys are random and secure\n" +
                "• Custom keys let you use existing keys from other wallets\n" +
                "• Keys are stored securely on your device\n" +
                "• You can change or regenerate keys anytime";
        
        new AlertDialog.Builder(getContext())
            .setTitle("About Viewing Keys")
            .setMessage(helpText)
            .setPositiveButton("Generate Key", (dialog, which) -> {
                generateViewingKey(token);
            })
            .setNeutralButton("Enter Custom", (dialog, which) -> {
                showSetViewingKeyDialog(token);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void showViewingKeyManagementDialog(Tokens.TokenInfo token) {
        String currentKey = getViewingKey(token.contract);
        String message = "Current viewing key: " + 
            (currentKey.length() > 20 ? currentKey.substring(0, 20) + "..." : currentKey) +
            "\n\nWhat would you like to do?";
        
        new AlertDialog.Builder(getContext())
            .setTitle("Manage Viewing Key - " + token.symbol)
            .setMessage(message)
            .setPositiveButton("Regenerate", (dialog, which) -> {
                new AlertDialog.Builder(getContext())
                    .setTitle("Regenerate Viewing Key?")
                    .setMessage("This will generate a new viewing key and update it on the blockchain. Continue?")
                    .setPositiveButton("Yes", (d, w) -> generateViewingKey(token))
                    .setNegativeButton("No", null)
                    .show();
            })
            .setNeutralButton("Set Custom", (dialog, which) -> {
                showSetViewingKeyDialog(token);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void generateViewingKey(Tokens.TokenInfo token) {
        try {
            Log.d(TAG, "Starting viewing key generation for " + token.symbol);
            
            // Generate random viewing key (matches Secret Network standard)
            SecureRandom random = new SecureRandom();
            byte[] keyBytes = new byte[32];
            random.nextBytes(keyBytes);
            String viewingKey = "api_key_" + Base64.encodeToString(keyBytes, Base64.NO_WRAP);
            
            Log.i(TAG, "Generated random viewing key for contract: " + token.contract);
            executeSetViewingKeyTransaction(token, viewingKey);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate viewing key for " + token.symbol, e);
            Toast.makeText(getContext(), "Failed to generate viewing key: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void executeSetViewingKeyTransaction(Tokens.TokenInfo token, String viewingKey) {
        try {
            // Store pending state for result handling
            pendingViewingKeyToken = token;
            pendingViewingKey = viewingKey;
            
            // Create set_viewing_key message for SNIP-20 contract
            JSONObject setViewingKeyMsg = new JSONObject();
            JSONObject msgContent = new JSONObject();
            msgContent.put("key", viewingKey);
            setViewingKeyMsg.put("set_viewing_key", msgContent);
            
            // Launch SecretExecuteActivity to set viewing key on blockchain
            Intent intent = new Intent(getContext(), SecretExecuteActivity.class);
            intent.putExtra(SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, token.contract);
            intent.putExtra(SecretExecuteActivity.EXTRA_CODE_HASH, token.hash);
            intent.putExtra(SecretExecuteActivity.EXTRA_EXECUTE_JSON, setViewingKeyMsg.toString());
            intent.putExtra(SecretExecuteActivity.EXTRA_MEMO, "Set viewing key for " + token.symbol);
            startActivityForResult(intent, REQ_SET_VIEWING_KEY);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute set viewing key transaction", e);
            Toast.makeText(getContext(), "Failed to create transaction: " + e.getMessage(), Toast.LENGTH_LONG).show();
            
            // Clear pending state on error
            pendingViewingKeyToken = null;
            pendingViewingKey = null;
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_SET_VIEWING_KEY) {
            if (resultCode == Activity.RESULT_OK) {
                // Transaction succeeded - use the stored pending values
                if (pendingViewingKeyToken != null && !TextUtils.isEmpty(pendingViewingKey)) {
                    try {
                        Log.d(TAG, "Processing viewing key success for " + pendingViewingKeyToken.symbol);
                        
                        // Save the viewing key locally
                        setViewingKey(pendingViewingKeyToken.contract, pendingViewingKey);
                        Toast.makeText(getContext(), "Viewing key set successfully for " + pendingViewingKeyToken.symbol + "!", Toast.LENGTH_SHORT).show();
                        
                        // Refresh the display
                        loadViewingKeys();
                        
                        Log.i(TAG, "Successfully set viewing key for " + pendingViewingKeyToken.symbol);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to handle set viewing key result", e);
                        Toast.makeText(getContext(), "Failed to process viewing key result", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.w(TAG, "Transaction succeeded but no pending viewing key info");
                    Toast.makeText(getContext(), "Viewing key transaction completed", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Transaction failed
                String error = (data != null) ? data.getStringExtra(SecretExecuteActivity.EXTRA_ERROR) : "Transaction failed";
                Toast.makeText(getContext(), "Failed to set viewing key: " + error, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Set viewing key transaction failed: " + error);
            }
            
            // Clear pending state
            pendingViewingKeyToken = null;
            pendingViewingKey = null;
        }
    }
    
    private void setViewingKey(String contractAddress, String viewingKey) {
        if (TextUtils.isEmpty(walletAddress)) {
            Log.e(TAG, "Cannot set viewing key: no wallet address available");
            return;
        }
        
        // Store both the viewing key and the token symbol for later matching
        String tokenSymbol = null;
        if (pendingViewingKeyToken != null && contractAddress.equals(pendingViewingKeyToken.contract)) {
            tokenSymbol = pendingViewingKeyToken.symbol;
        }
        
        securePrefs.edit().putString("viewing_key_" + walletAddress + "_" + contractAddress, viewingKey).apply();
        
        // Also store the token symbol for this viewing key
        if (!TextUtils.isEmpty(tokenSymbol)) {
            securePrefs.edit().putString("viewing_key_symbol_" + walletAddress + "_" + contractAddress, tokenSymbol).apply();
        }
    }
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
    
    /**
     * Helper class to hold token and viewing key information
     */
    private static class TokenViewingKeyInfo {
        Tokens.TokenInfo token;
        String viewingKey;
    }
}