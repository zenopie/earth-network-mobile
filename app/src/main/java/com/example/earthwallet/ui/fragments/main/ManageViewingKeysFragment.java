package com.example.earthwallet.ui.fragments.main;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import com.example.earthwallet.wallet.constants.Tokens;

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
    
    // UI Components
    private LinearLayout viewingKeysContainer;
    private TextView emptyStateMessage;
    
    // State management
    private SharedPreferences securePrefs;
    private String walletAddress = "";
    
    // Interface for communication with parent
    public interface ManageViewingKeysListener {
        String getCurrentWalletAddress();
        void onViewingKeyRemoved(Tokens.TokenInfo token);
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
        
        // Get wallet address
        if (listener != null) {
            walletAddress = listener.getCurrentWalletAddress();
        }
        
        // If we don't have a listener, try to get wallet address directly
        if (TextUtils.isEmpty(walletAddress)) {
            walletAddress = getCurrentWalletAddressDirect();
        }
        
        Log.d(TAG, "onViewCreated - walletAddress: " + walletAddress);
        
        // Load and display viewing keys
        loadViewingKeys();
    }
    
    /**
     * Load all viewing keys for the current wallet and display them
     */
    private void loadViewingKeys() {
        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available");
            showEmptyState();
            return;
        }
        
        viewingKeysContainer.removeAllViews();
        
        List<TokenViewingKeyInfo> viewingKeys = getStoredViewingKeys();
        
        if (viewingKeys.isEmpty()) {
            showEmptyState();
        } else {
            hideEmptyState();
            for (TokenViewingKeyInfo keyInfo : viewingKeys) {
                addViewingKeyItem(keyInfo);
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
     * Get all stored viewing keys for the current wallet
     */
    private List<TokenViewingKeyInfo> getStoredViewingKeys() {
        List<TokenViewingKeyInfo> viewingKeys = new ArrayList<>();
        
        try {
            Log.d(TAG, "Looking for viewing keys with wallet address: " + walletAddress);
            
            // Check each token to see if it has a viewing key
            for (String symbol : Tokens.ALL_TOKENS.keySet()) {
                Tokens.TokenInfo token = Tokens.getToken(symbol);
                if (token != null) {
                    String viewingKey = getViewingKey(token.contract);
                    Log.d(TAG, "Token " + symbol + " (" + token.contract + ") viewing key: " + 
                        (TextUtils.isEmpty(viewingKey) ? "NONE" : viewingKey.length() + " chars"));
                    
                    if (!TextUtils.isEmpty(viewingKey)) {
                        TokenViewingKeyInfo keyInfo = new TokenViewingKeyInfo();
                        keyInfo.token = token;
                        keyInfo.viewingKey = viewingKey;
                        viewingKeys.add(keyInfo);
                        Log.d(TAG, "Added viewing key for " + symbol);
                    }
                }
            }
            
            Log.d(TAG, "Found " + viewingKeys.size() + " viewing keys total");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load viewing keys", e);
        }
        
        return viewingKeys;
    }
    
    /**
     * Add a viewing key item to the UI
     */
    private void addViewingKeyItem(TokenViewingKeyInfo keyInfo) {
        try {
            // Create a row for each viewing key
            LinearLayout keyRow = new LinearLayout(getContext());
            keyRow.setOrientation(LinearLayout.HORIZONTAL);
            keyRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            keyRow.setPadding(16, 16, 16, 16);
            keyRow.setBackground(getResources().getDrawable(R.drawable.card_rounded_bg));
            
            // Add margin between rows
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.setMargins(0, 0, 0, 16);
            keyRow.setLayoutParams(rowParams);
            
            // Token logo
            if (!TextUtils.isEmpty(keyInfo.token.logo)) {
                ImageView logoView = new ImageView(getContext());
                LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                    dpToPx(32), dpToPx(32)
                );
                logoParams.setMargins(0, 0, dpToPx(12), 0);
                logoView.setLayoutParams(logoParams);
                logoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                
                // Load logo from assets
                try {
                    Bitmap bitmap = BitmapFactory.decodeStream(getContext().getAssets().open(keyInfo.token.logo));
                    logoView.setImageBitmap(bitmap);
                    keyRow.addView(logoView);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load logo for " + keyInfo.token.symbol + ": " + keyInfo.token.logo, e);
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
            symbolText.setText(keyInfo.token.symbol);
            symbolText.setTextSize(16);
            symbolText.setTextColor(android.graphics.Color.parseColor("#1e3a8a"));
            symbolText.setTypeface(null, android.graphics.Typeface.BOLD);
            tokenInfoContainer.addView(symbolText);
            
            // Viewing key (truncated)
            TextView keyText = new TextView(getContext());
            String truncatedKey = keyInfo.viewingKey.length() > 20 ? 
                keyInfo.viewingKey.substring(0, 20) + "..." : keyInfo.viewingKey;
            keyText.setText("Key: " + truncatedKey);
            keyText.setTextSize(12);
            keyText.setTextColor(getResources().getColor(R.color.wallet_row_address));
            tokenInfoContainer.addView(keyText);
            
            keyRow.addView(tokenInfoContainer);
            
            // Remove button
            Button removeButton = new Button(getContext());
            removeButton.setText("Remove");
            removeButton.setTextSize(12);
            
            // Create rounded red background
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(android.graphics.Color.parseColor("#f44336"));
            background.setCornerRadius(8 * getResources().getDisplayMetrics().density);
            removeButton.setBackground(background);
            
            removeButton.setTextColor(getResources().getColor(android.R.color.white));
            removeButton.setPadding(16, 8, 16, 8);
            removeButton.setMinWidth(0);
            removeButton.setMinHeight(0);
            removeButton.setElevation(0f);
            removeButton.setStateListAnimator(null);
            
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            removeButton.setLayoutParams(buttonParams);
            
            removeButton.setOnClickListener(v -> showRemoveConfirmationDialog(keyInfo));
            
            keyRow.addView(removeButton);
            
            viewingKeysContainer.addView(keyRow);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to add viewing key item for " + keyInfo.token.symbol, e);
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