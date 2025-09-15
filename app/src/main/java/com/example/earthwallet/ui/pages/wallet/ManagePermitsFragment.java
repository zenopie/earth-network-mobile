package com.example.earthwallet.ui.pages.wallet;

import android.content.Context;
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

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.constants.Tokens;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * ManagePermitsFragment
 *
 * Allows users to manage their stored SNIP-24 permits:
 * - View all tokens that have permits set
 * - Remove permits for specific tokens
 * - Create new permits for token access
 * - Navigate back to token balances
 */
public class ManagePermitsFragment extends Fragment {
    
    private static final String TAG = "ManagePermitsFragment";
    
    // UI Components
    private LinearLayout permitsContainer;
    private TextView emptyStateMessage;

    // State management
    private com.example.earthwallet.bridge.utils.PermitManager permitManager;
    private String walletAddress = "";
    
    // Interface for communication with parent
    public interface ManagePermitsListener {
        String getCurrentWalletAddress();
        void onPermitRemoved(Tokens.TokenInfo token);
        void onPermitRequested(Tokens.TokenInfo token);
    }
    
    private ManagePermitsListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof ManagePermitsListener) {
            listener = (ManagePermitsListener) context;
        }
        // Note: We don't require the listener since this fragment can work independently
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize PermitManager
        permitManager = com.example.earthwallet.bridge.utils.PermitManager.getInstance(requireContext());
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_manage_permits, container, false);

        permitsContainer = view.findViewById(R.id.permits_container);
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
        
        // If we still don't have a wallet address, we cannot proceed
        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available - cannot manage permits");
        }
        
        Log.d(TAG, "onViewCreated - walletAddress: " + walletAddress);
        
        // Load and display permits
        loadPermits();
    }
    
    /**
     * Load all tokens and display them with their permit status
     */
    private void loadPermits() {
        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available");
            showEmptyState();
            return;
        }

        permitsContainer.removeAllViews();

        List<TokenPermitInfo> allTokens = getAllTokensWithPermitStatus();

        if (allTokens.isEmpty()) {
            showEmptyState();
        } else {
            hideEmptyState();
            for (TokenPermitInfo tokenInfo : allTokens) {
                addTokenItem(tokenInfo);
            }
        }
    }
    

    /**
     * Get all tokens with their permit status
     */
    private List<TokenPermitInfo> getAllTokensWithPermitStatus() {
        List<TokenPermitInfo> allTokens = new ArrayList<>();

        try {
            Log.d(TAG, "Loading all tokens with permit status, wallet address: " + walletAddress);

            // Check each token to see if it has a permit
            for (String symbol : Tokens.ALL_TOKENS.keySet()) {
                Tokens.TokenInfo token = Tokens.getToken(symbol);
                if (token != null) {
                    com.example.earthwallet.bridge.models.Permit permit = permitManager.getPermit(walletAddress, token.contract);
                    Log.d(TAG, "Token " + symbol + " (" + token.contract + ") permit: " +
                        (permit == null ? "NONE" : "EXISTS"));

                    TokenPermitInfo tokenInfo = new TokenPermitInfo();
                    tokenInfo.token = token;
                    tokenInfo.permit = permit;
                    allTokens.add(tokenInfo);
                    Log.d(TAG, "Added token " + symbol + " (has permit: " + (permit != null) + ")");
                }
            }

            Log.d(TAG, "Loaded " + allTokens.size() + " tokens total");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load tokens", e);
        }

        return allTokens;
    }
    
    /**
     * Add a token item to the UI (with or without permit)
     */
    private void addTokenItem(TokenPermitInfo tokenInfo) {
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
            
            // Status text (permit status and permissions)
            TextView statusText = new TextView(getContext());
            if (tokenInfo.permit == null) {
                statusText.setText("No permit set");
                statusText.setTextColor(getResources().getColor(R.color.wallet_row_address));
            } else {
                String permissionsText = String.join(", ", tokenInfo.permit.getPermissions());
                if (permissionsText.length() > 25) {
                    permissionsText = permissionsText.substring(0, 25) + "...";
                }
                statusText.setText("Permissions: " + permissionsText);
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
            
            if (tokenInfo.permit == null) {
                // No permit - show "Add" button
                actionButton.setText("Add");
                android.graphics.drawable.GradientDrawable getBackground = new android.graphics.drawable.GradientDrawable();
                getBackground.setColor(android.graphics.Color.parseColor("#4caf50"));
                getBackground.setCornerRadius(8 * getResources().getDisplayMetrics().density);
                actionButton.setBackground(getBackground);
                actionButton.setTextColor(getResources().getColor(android.R.color.white));
                actionButton.setOnClickListener(v -> requestPermit(tokenInfo.token));
            } else {
                // Has permit - show "Remove" button
                actionButton.setText("Remove");
                android.graphics.drawable.GradientDrawable removeBackground = new android.graphics.drawable.GradientDrawable();
                removeBackground.setColor(android.graphics.Color.parseColor("#f44336"));
                removeBackground.setCornerRadius(8 * getResources().getDisplayMetrics().density);
                actionButton.setBackground(removeBackground);
                actionButton.setTextColor(getResources().getColor(android.R.color.white));
                actionButton.setOnClickListener(v -> removePermit(tokenInfo));
            }
            
            tokenRow.addView(actionButton);
            
            permitsContainer.addView(tokenRow);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to add token item for " + tokenInfo.token.symbol, e);
        }
    }
    
    
    /**
     * Remove the permit for a token
     */
    private void removePermit(TokenPermitInfo permitInfo) {
        try {
            // Remove permit using PermitManager
            permitManager.removePermit(walletAddress, permitInfo.token.contract);

            Toast.makeText(getContext(), "Permit removed for " + permitInfo.token.symbol, Toast.LENGTH_SHORT).show();

            // Notify parent if available
            if (listener != null) {
                listener.onPermitRemoved(permitInfo.token);
            }

            // Reload the permits list
            loadPermits();

            Log.i(TAG, "Successfully removed permit for " + permitInfo.token.symbol);

        } catch (Exception e) {
            Log.e(TAG, "Failed to remove permit for " + permitInfo.token.symbol, e);
            Toast.makeText(getContext(), "Failed to remove permit: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Show empty state when no permits are found
     */
    private void showEmptyState() {
        permitsContainer.setVisibility(View.GONE);
        emptyStateMessage.setVisibility(View.VISIBLE);
    }

    /**
     * Hide empty state when permits are available
     */
    private void hideEmptyState() {
        permitsContainer.setVisibility(View.VISIBLE);
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
     * Check if permit exists for a contract
     */
    private boolean hasPermit(String contractAddress) {
        if (TextUtils.isEmpty(walletAddress)) {
            return false;
        }
        return permitManager.hasPermit(walletAddress, contractAddress);
    }
    
    /**
     * Public method to update wallet address
     */
    public void updateWalletAddress(String newAddress) {
        if (!newAddress.equals(walletAddress)) {
            walletAddress = newAddress;
            loadPermits();
        }
    }
    
    /**
     * Request permit creation for a token
     */
    private void requestPermit(Tokens.TokenInfo token) {
        Log.i(TAG, "Requesting permit for " + token.symbol);

        // Create permit directly with default permissions
        createPermit(token);
    }
    
    
    /**
     * Create permit with default permissions (balance, history)
     */
    private void createPermit(Tokens.TokenInfo token) {
        java.util.List<String> defaultPermissions = java.util.Arrays.asList("balance", "history"); // lowercase for SNIP-24
        createPermitWithPermissions(token, defaultPermissions);
    }

    /**
     * Create permit with specific permissions - directly using PermitManager (no transaction flow)
     */
    private void createPermitWithPermissions(Tokens.TokenInfo token, java.util.List<String> permissions) {
        try {
            Log.d(TAG, "Creating permit directly for " + token.symbol + " with permissions: " + permissions);

            // Create permit directly using PermitManager in the background
            permitManager.createPermit(
                getContext(),
                walletAddress,
                java.util.Arrays.asList(token.contract),
                "EarthWallet", // permit name
                permissions
            );

            Toast.makeText(getContext(), "Permit created successfully for " + token.symbol + "!", Toast.LENGTH_SHORT).show();

            // Notify parent if available
            if (listener != null) {
                listener.onPermitRequested(token);
            }

            // Refresh the display
            loadPermits();

            Log.i(TAG, "Successfully created permit for " + token.symbol);

        } catch (Exception e) {
            Log.e(TAG, "Failed to create permit for " + token.symbol, e);
            Toast.makeText(getContext(), "Failed to create permit: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
    
    /**
     * Helper class to hold token and permit information
     */
    private static class TokenPermitInfo {
        Tokens.TokenInfo token;
        com.example.earthwallet.bridge.models.Permit permit;
    }
}