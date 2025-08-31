package com.example.earthwallet.ui.fragments.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;
import com.example.earthwallet.bridge.activities.SecretQueryActivity;
import com.example.earthwallet.wallet.constants.Tokens;

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
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final int REQ_TOKEN_BALANCE = 2001;
    
    // UI Components
    private LinearLayout tokenBalancesContainer;
    
    // State management
    private SharedPreferences securePrefs;
    private java.util.Queue<Tokens.TokenInfo> tokenQueryQueue = new java.util.LinkedList<>();
    private boolean isQueryingToken = false;
    private String walletAddress = "";
    private Tokens.TokenInfo currentlyQueryingToken = null;
    
    // Interface for communication with parent
    public interface TokenBalancesListener {
        void onViewingKeyRequested(Tokens.TokenInfo token);
        String getCurrentWalletAddress();
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
        View view = inflater.inflate(R.layout.fragment_token_balances, container, false);
        
        tokenBalancesContainer = view.findViewById(R.id.tokenBalancesContainer);
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize token balances if we have a wallet address
        if (listener != null) {
            walletAddress = listener.getCurrentWalletAddress();
            if (!TextUtils.isEmpty(walletAddress)) {
                refreshTokenBalances();
            }
        }
    }
    
    /**
     * Public method to refresh token balances from parent fragment
     */
    public void refreshTokenBalances() {
        if (TextUtils.isEmpty(walletAddress)) {
            walletAddress = listener != null ? listener.getCurrentWalletAddress() : "";
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
        
        // Add all tokens to the queue
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            Tokens.TokenInfo token = Tokens.getToken(symbol);
            if (token != null) {
                tokenQueryQueue.offer(token);
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
            // Check if we have a viewing key for this token
            String viewingKey = getViewingKey(token.contract);
            if (TextUtils.isEmpty(viewingKey)) {
                // Add button to get viewing key
                addTokenBalanceView(token, null, false);
                
                // Mark query as complete and continue with next token
                isQueryingToken = false;
                currentlyQueryingToken = null;
                processNextTokenQuery();
                return;
            }
            
            // Store token symbol with viewing key for result matching
            if (!TextUtils.isEmpty(walletAddress)) {
                securePrefs.edit().putString("viewing_key_symbol_" + walletAddress + "_" + token.contract, token.symbol).apply();
            }
            
            // Create SNIP-20 balance query
            JSONObject query = new JSONObject();
            JSONObject balanceQuery = new JSONObject();
            balanceQuery.put("address", walletAddress);
            balanceQuery.put("key", viewingKey);
            balanceQuery.put("time", System.currentTimeMillis());
            query.put("balance", balanceQuery);
            
            Log.d(TAG, "Querying token " + token.symbol + " balance");
            Log.d(TAG, "Query JSON: " + query.toString());
            Log.d(TAG, "Contract: " + token.contract);
            Log.d(TAG, "Hash: " + token.hash);
            
            // Launch query using SecretQueryActivity
            Intent qi = new Intent(getContext(), SecretQueryActivity.class);
            qi.putExtra(SecretQueryActivity.EXTRA_CONTRACT_ADDRESS, token.contract);
            qi.putExtra(SecretQueryActivity.EXTRA_CODE_HASH, token.hash);
            qi.putExtra(SecretQueryActivity.EXTRA_QUERY_JSON, query.toString());
            startActivityForResult(qi, REQ_TOKEN_BALANCE);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to query token balance for " + token.symbol, e);
            addTokenBalanceView(token, "Error", false);
            
            // Mark query as complete and continue with next token
            isQueryingToken = false;
            currentlyQueryingToken = null;
            processNextTokenQuery();
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_TOKEN_BALANCE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    String json = data.getStringExtra(SecretQueryActivity.EXTRA_RESULT_JSON);
                    
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
                                        String formattedBalance = Tokens.formatTokenAmount(amount, currentlyQueryingToken) + " " + currentlyQueryingToken.symbol;
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
            } else {
                Log.w(TAG, "Token balance query failed or was cancelled");
                if (currentlyQueryingToken != null) {
                    updateTokenBalanceView(currentlyQueryingToken, "!");
                }
            }
            
            // Mark current query as complete and process next token in queue
            isQueryingToken = false;
            currentlyQueryingToken = null;
            processNextTokenQuery();
        }
    }
    
    private void addTokenBalanceView(Tokens.TokenInfo token, String balance, boolean hasViewingKey) {
        Log.d(TAG, "addTokenBalanceView called for " + token.symbol + " with balance: " + balance + " hasViewingKey: " + hasViewingKey);
        
        try {
            // Create a card view for the token balance
            LinearLayout tokenCard = new LinearLayout(getContext());
            tokenCard.setOrientation(LinearLayout.HORIZONTAL);
            tokenCard.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tokenCard.setPadding(24, 16, 24, 16);
            tokenCard.setBackground(getResources().getDrawable(R.drawable.card_rounded_bg));
            tokenCard.setTag(token.symbol);
            
            // Add margin between cards
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 0, 0, 12);
            tokenCard.setLayoutParams(cardParams);
            
            // Token symbol
            TextView symbolText = new TextView(getContext());
            symbolText.setText(token.symbol);
            symbolText.setTextSize(16);
            symbolText.setTextColor(getResources().getColor(R.color.brand_blue));
            symbolText.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams symbolParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
            );
            symbolText.setLayoutParams(symbolParams);
            tokenCard.addView(symbolText);
            
            if (balance == null) {
                // No viewing key - show "Get Viewing Key" button
                Button getViewingKeyBtn = new Button(getContext());
                getViewingKeyBtn.setText("Get Viewing Key");
                getViewingKeyBtn.setTextSize(12);
                getViewingKeyBtn.getBackground().setTint(getResources().getColor(R.color.brand_blue));
                getViewingKeyBtn.setTextColor(getResources().getColor(android.R.color.white));
                getViewingKeyBtn.setPadding(16, 8, 16, 8);
                getViewingKeyBtn.setMinWidth(0);
                getViewingKeyBtn.setMinHeight(0);
                
                getViewingKeyBtn.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onViewingKeyRequested(token);
                    }
                });
                getViewingKeyBtn.setTag("get_key_btn");
                tokenCard.addView(getViewingKeyBtn);
            } else {
                // Has viewing key - show balance text
                TextView balanceText = new TextView(getContext());
                balanceText.setText(balance);
                balanceText.setTag("balance");
                
                // Style based on whether it's an error or normal balance
                if ("!".equals(balance)) {
                    balanceText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    balanceText.setTextSize(20);
                } else {
                    balanceText.setTextColor(getResources().getColor(R.color.sidebar_text));
                    balanceText.setTextSize(16);
                }
                
                tokenCard.addView(balanceText);
            }
            
            tokenBalancesContainer.addView(tokenCard);
            Log.d(TAG, "Successfully added token view for " + token.symbol + " to container. Container now has " + tokenBalancesContainer.getChildCount() + " tokens");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add token balance view for " + token.symbol, e);
        }
    }
    
    private void updateTokenBalanceView(Tokens.TokenInfo token, String balance) {
        Log.d(TAG, "updateTokenBalanceView called for " + token.symbol + " with balance: " + balance);
        
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
            Log.d(TAG, "Token " + token.symbol + " not found in existing views, adding new one");
            addTokenBalanceView(token, balance, !TextUtils.isEmpty(getViewingKey(token.contract)));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to update token balance view for " + token.symbol, e);
            addTokenBalanceView(token, balance, !TextUtils.isEmpty(getViewingKey(token.contract)));
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
    private String getViewingKey(String contractAddress) {
        if (TextUtils.isEmpty(walletAddress)) {
            return "";
        }
        return securePrefs.getString("viewing_key_" + walletAddress + "_" + contractAddress, "");
    }
    
    private String getViewingKeyTokenSymbol(String contractAddress) {
        if (TextUtils.isEmpty(walletAddress)) {
            return "";
        }
        return securePrefs.getString("viewing_key_symbol_" + walletAddress + "_" + contractAddress, "");
    }
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}