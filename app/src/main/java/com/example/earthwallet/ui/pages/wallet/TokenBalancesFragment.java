package com.example.earthwallet.ui.pages.wallet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

import com.example.earthwallet.R;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.bridge.utils.PermitManager;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * TokenBalancesFragment
 * 
 * Handles all token balance display and management functionality:
 * - Querying token balances with viewing keys
 * - Displaying token balance UI
 * - Managing token balance query queue
 * - Updating token balance views
 */
public class TokenBalancesFragment extends Fragment {
    
    private static final String TAG = "TokenBalancesFragment";
    private static final int REQ_TOKEN_BALANCE = 2001;
    
    // UI Components
    private LinearLayout tokenBalancesContainer;
    
    // State management
    private java.util.Queue<Tokens.TokenInfo> tokenQueryQueue = new java.util.LinkedList<>();
    private boolean isQueryingToken = false;
    private String walletAddress = "";
    private Tokens.TokenInfo currentlyQueryingToken = null;
    private PermitManager permitManager;
    
    // Interface for communication with parent
    public interface TokenBalancesListener {
        void onPermitRequested(Tokens.TokenInfo token);
        void onManageViewingKeysRequested();
        SharedPreferences getSecurePrefs();
    }
    
    private TokenBalancesListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof TokenBalancesListener) {
            listener = (TokenBalancesListener) getParentFragment();
        } else if (context instanceof TokenBalancesListener) {
            listener = (TokenBalancesListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement TokenBalancesListener");
        }
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize PermitManager
        permitManager = PermitManager.getInstance(requireContext());
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_token_balances, container, false);
        
        tokenBalancesContainer = view.findViewById(R.id.tokenBalancesContainer);
        
        // Set up manage viewing keys button
        Button manageViewingKeysButton = view.findViewById(R.id.manage_viewing_keys_button);
        if (manageViewingKeysButton != null) {
            manageViewingKeysButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onManageViewingKeysRequested();
                }
            });
        }
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize token balances if we have a wallet address
        loadCurrentWalletAddress();
        if (!TextUtils.isEmpty(walletAddress)) {
            refreshTokenBalances();
        }
    }
    
    /**
     * Public method to refresh token balances from parent fragment
     */
    public void refreshTokenBalances() {
        if (TextUtils.isEmpty(walletAddress)) {
            loadCurrentWalletAddress();
        }

        if (TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "No wallet address available for token balance refresh");
            return;
        }
        
        // Check if view is ready before proceeding
        if (tokenBalancesContainer == null) {
            Log.w(TAG, "TokenBalancesContainer is null, view not ready yet. Skipping refresh.");
            return;
        }
        
        Log.d(TAG, "refreshTokenBalances called for address: " + walletAddress);
        
        // Clear existing token displays
        tokenBalancesContainer.removeAllViews();
        
        // Clear any existing queue and reset state
        tokenQueryQueue.clear();
        isQueryingToken = false;
        
        // Add tokens with viewing keys to the queue and display them immediately with "..."
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            Tokens.TokenInfo token = Tokens.getToken(symbol);
            if (token != null) {
                // Remove old viewing key check - now using permits
                if (hasPermit(token.contract)) {
                    // Show token immediately with "..." while we fetch the actual balance
                    addTokenBalanceView(token, "...", true);
                    tokenQueryQueue.offer(token);
                }
            }
        }
        
        // Start processing the queue
        processNextTokenQuery();
    }
    
    /**
     * Public method to update wallet address and refresh if changed
     */
    public void updateWalletAddress(String newAddress) {
        if (!newAddress.equals(walletAddress)) {
            walletAddress = newAddress;
            // Only refresh if view is ready
            if (tokenBalancesContainer != null) {
                refreshTokenBalances();
            } else {
                Log.w(TAG, "Wallet address updated but view not ready, refresh deferred");
            }
        }
    }
    
    private void processNextTokenQuery() {
        if (isQueryingToken || tokenQueryQueue.isEmpty()) {
            return;
        }
        
        Tokens.TokenInfo token = tokenQueryQueue.poll();
        if (token != null) {
            isQueryingToken = true;
            currentlyQueryingToken = token;
            queryTokenBalance(token);
        }
    }
    
    private void queryTokenBalance(Tokens.TokenInfo token) {
        try {
            // Check if we have a permit for this token
            boolean hasPermit = permitManager.hasPermit(walletAddress, token.contract);
            if (!hasPermit) {
                // Add button to get permit
                addTokenBalanceView(token, null, false);

                // Mark query as complete and continue with next token
                isQueryingToken = false;
                currentlyQueryingToken = null;
                processNextTokenQuery();
                return;
            }
            
            // Token symbol is already stored with the permit, no need to store separately
            
            // Skip creating query here - permit-based query will be handled by SnipQueryService
            
            Log.d(TAG, "Querying token " + token.symbol + " balance");
            // Query will be built by SnipQueryService with permit structure
            Log.d(TAG, "Contract: " + token.contract);
            Log.d(TAG, "Hash: " + token.hash);
            
            // Use SnipQueryService for cleaner token balance queries
            new Thread(() -> {
                try {
                    // Check wallet availability without retrieving mnemonic
                    if (!com.example.earthwallet.wallet.services.SecureWalletManager.isWalletAvailable(getActivity())) {
                        getActivity().runOnUiThread(() -> {
                            Log.e(TAG, "No wallet found for token balance query");
                            addTokenBalanceView(token, "Error", false);
                            isQueryingToken = false;
                            currentlyQueryingToken = null;
                            processNextTokenQuery();
                        });
                        return;
                    }

                    // Use SnipQueryService for the permit-based balance query
                    JSONObject result = com.example.earthwallet.bridge.services.SnipQueryService.queryBalanceWithPermit(
                        getActivity(), // Use HostActivity context
                        token.symbol,
                        walletAddress
                    );

                    // Handle result on UI thread
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            handleTokenBalanceResult(result.toString());
                        });
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Token balance query failed for " + token.symbol, e);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            addTokenBalanceView(token, "Error", false);
                            isQueryingToken = false;
                            currentlyQueryingToken = null;
                            processNextTokenQuery();
                        });
                    }
                }
            }).start();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to query token balance for " + token.symbol, e);
            addTokenBalanceView(token, "Error", false);
            
            // Mark query as complete and continue with next token
            isQueryingToken = false;
            currentlyQueryingToken = null;
            processNextTokenQuery();
        }
    }
    
    private void handleTokenBalanceResult(String json) {
        try {
            // Use the currently querying token instead of trying to parse from result
            if (currentlyQueryingToken != null) {
                Log.d(TAG, "Token balance query result for " + currentlyQueryingToken.symbol + ": " + json);
                
                if (!TextUtils.isEmpty(json)) {
                    JSONObject root = new JSONObject(json);
                    boolean success = root.optBoolean("success", false);
                    
                    if (success) {
                        JSONObject result = root.optJSONObject("result");
                        if (result != null) {
                            JSONObject balance = result.optJSONObject("balance");
                            if (balance != null) {
                                String amount = balance.optString("amount", "0");
                                String formattedBalance = Tokens.formatTokenAmount(amount, currentlyQueryingToken);
                                updateTokenBalanceView(currentlyQueryingToken, formattedBalance);
                            } else {
                                updateTokenBalanceView(currentlyQueryingToken, "!");
                            }
                        } else {
                            updateTokenBalanceView(currentlyQueryingToken, "!");
                        }
                    } else {
                        updateTokenBalanceView(currentlyQueryingToken, "!");
                    }
                }
            } else {
                Log.w(TAG, "No currently querying token, cannot process result");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse token balance result", e);
            if (currentlyQueryingToken != null) {
                updateTokenBalanceView(currentlyQueryingToken, "!");
            }
        }
        
        // Mark current query as complete and process next token in queue
        isQueryingToken = false;
        currentlyQueryingToken = null;
        processNextTokenQuery();
    }

    
    private void addTokenBalanceView(Tokens.TokenInfo token, String balance, boolean hasViewingKey) {
        
        try {
            // Create a flat row for the token balance
            LinearLayout tokenRow = new LinearLayout(getContext());
            tokenRow.setOrientation(LinearLayout.HORIZONTAL);
            tokenRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tokenRow.setPadding(0, 12, 0, 12);
            tokenRow.setTag(token.symbol);
            
            // Add margin between rows
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.setMargins(0, 0, 0, 16);
            tokenRow.setLayoutParams(rowParams);
            
            // Token symbol
            TextView symbolText = new TextView(getContext());
            symbolText.setText(token.symbol);
            symbolText.setTextSize(16);
            symbolText.setTextColor(android.graphics.Color.parseColor("#1e3a8a"));
            symbolText.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams symbolParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
            );
            symbolText.setLayoutParams(symbolParams);
            tokenRow.addView(symbolText);
            
            if (balance == null) {
                // No viewing key - show "Get Viewing Key" button
                Button getPermitBtn = new Button(getContext());
                getPermitBtn.setText("Create Permit");
                getPermitBtn.setTextSize(11);
                
                // Create rounded green background programmatically
                android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
                background.setColor(android.graphics.Color.parseColor("#4caf50"));
                background.setCornerRadius(12 * getResources().getDisplayMetrics().density); // 12dp corner radius
                getPermitBtn.setBackground(background);

                // Remove button shadow/elevation
                getPermitBtn.setElevation(0f);
                getPermitBtn.setStateListAnimator(null);

                getPermitBtn.setTextColor(getResources().getColor(android.R.color.white));
                getPermitBtn.setPadding(16, 4, 16, 4);  // Reduced vertical padding
                getPermitBtn.setMinWidth(0);
                getPermitBtn.setMinHeight(0);
                
                // Set smaller height with top and bottom margins to prevent cutoff
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 
                    (int) (24 * getResources().getDisplayMetrics().density)  // 24dp height
                );
                layoutParams.setMargins(0, 
                    (int) (4 * getResources().getDisplayMetrics().density), // 4dp top margin
                    0, 
                    (int) (4 * getResources().getDisplayMetrics().density)  // 4dp bottom margin
                );
                getPermitBtn.setLayoutParams(layoutParams);

                getPermitBtn.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onPermitRequested(token);
                    }
                });
                getPermitBtn.setTag("get_permit_btn");
                tokenRow.addView(getPermitBtn);
            } else {
                // Has viewing key - show balance text
                TextView balanceText = new TextView(getContext());
                balanceText.setText(balance);
                balanceText.setTag("balance");
                
                // Style based on whether it's an error or normal balance
                if ("!".equals(balance)) {
                    balanceText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    balanceText.setTextSize(16);
                } else {
                    balanceText.setTextColor(android.graphics.Color.parseColor("#4caf50"));
                    balanceText.setTextSize(16);
                    balanceText.setTypeface(null, android.graphics.Typeface.BOLD);
                }
                
                tokenRow.addView(balanceText);
            }
            
            // Add token logo
            if (!TextUtils.isEmpty(token.logo)) {
                ImageView logoView = new ImageView(getContext());
                LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                    dpToPx(24), dpToPx(24)
                );
                logoParams.setMargins(dpToPx(8), 0, 0, 0);
                logoView.setLayoutParams(logoParams);
                logoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                
                // Load logo from assets
                try {
                    Bitmap bitmap = BitmapFactory.decodeStream(getContext().getAssets().open(token.logo));
                    logoView.setImageBitmap(bitmap);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load logo for " + token.symbol + ": " + token.logo, e);
                    // Hide the logo view if loading fails
                    logoView.setVisibility(View.GONE);
                }
                
                tokenRow.addView(logoView);
            }
            
            tokenBalancesContainer.addView(tokenRow);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add token balance view for " + token.symbol, e);
        }
    }
    
    /**
     * Convert dp to pixels
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
    
    private void updateTokenBalanceView(Tokens.TokenInfo token, String balance) {
        
        try {
            for (int i = 0; i < tokenBalancesContainer.getChildCount(); i++) {
                View child = tokenBalancesContainer.getChildAt(i);
                if (child.getTag() != null && child.getTag().equals(token.symbol)) {
                    LinearLayout tokenCard = (LinearLayout) child;
                    
                    // Find the balance text view or button in the card
                    for (int j = 0; j < tokenCard.getChildCount(); j++) {
                        View cardChild = tokenCard.getChildAt(j);
                        if (cardChild instanceof TextView && cardChild.getTag() != null && cardChild.getTag().equals("balance")) {
                            TextView balanceTextView = (TextView) cardChild;
                            balanceTextView.setText(balance);
                            
                            // Make error indicator red
                            if ("!".equals(balance)) {
                                balanceTextView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                balanceTextView.setTextSize(20);
                            } else {
                                balanceTextView.setTextColor(getResources().getColor(R.color.sidebar_text));
                                balanceTextView.setTextSize(16);
                            }
                            return;
                        } else if (cardChild instanceof Button && cardChild.getTag() != null && cardChild.getTag().equals("get_key_btn")) {
                            // Replace the "Get Viewing Key" button with balance text
                            tokenCard.removeView(cardChild);
                            TextView balanceText = new TextView(getContext());
                            balanceText.setText(balance);
                            balanceText.setTag("balance");
                            
                            if ("!".equals(balance)) {
                                balanceText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                balanceText.setTextSize(20);
                            } else {
                                balanceText.setTextColor(getResources().getColor(R.color.sidebar_text));
                                balanceText.setTextSize(16);
                            }
                            
                            tokenCard.addView(balanceText);
                            return;
                        }
                    }
                    return;
                }
            }

            // Token view not found, add a new one
            addTokenBalanceView(token, balance, hasPermit(token.contract));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to update token balance view for " + token.symbol, e);
            addTokenBalanceView(token, balance, hasPermit(token.contract));
        }
    }
    
    /**
     * Public method to update a specific token's balance (called from parent when viewing key is set)
     */
    public void updateTokenBalance(Tokens.TokenInfo token, String balance) {
        updateTokenBalanceView(token, balance);
    }
    
    /**
     * Public method to query a single token balance (called from parent after viewing key is set)
     */
    public void querySingleToken(Tokens.TokenInfo token) {
        if (!TextUtils.isEmpty(walletAddress)) {
            // Add to front of queue for priority processing
            java.util.Queue<Tokens.TokenInfo> tempQueue = new java.util.LinkedList<>();
            tempQueue.offer(token);
            while (!tokenQueryQueue.isEmpty()) {
                tempQueue.offer(tokenQueryQueue.poll());
            }
            tokenQueryQueue = tempQueue;
            
            // Start processing if not already processing
            if (!isQueryingToken) {
                processNextTokenQuery();
            }
        }
    }
    
    // Helper methods for viewing key management
    /**
     * Check if permit exists for contract
     */
    private boolean hasPermit(String contractAddress) {
        return permitManager.hasPermit(walletAddress, contractAddress);
    }

    private void loadCurrentWalletAddress() {
        // Use SecureWalletManager to get wallet address directly
        try {
            walletAddress = SecureWalletManager.getWalletAddress(requireContext());
            if (!TextUtils.isEmpty(walletAddress)) {
                Log.d(TAG, "Loaded wallet address: " + walletAddress.substring(0, Math.min(14, walletAddress.length())) + "...");
            } else {
                Log.w(TAG, "No wallet address available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallet address", e);
            walletAddress = "";
        }
    }
    
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}