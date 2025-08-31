package com.example.earthwallet.ui.fragments.main;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.ui.fragments.WalletListFragment;
import com.example.earthwallet.ui.fragments.CreateWalletFragment;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.bridge.activities.SecretQueryActivity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.bitcoinj.core.ECKey;
import android.util.Log;

/**
 * Simple Secret Network wallet screen:
 * - Generate/import mnemonic
 * - Derive address (hrp "secret")
 * - Save mnemonic securely with EncryptedSharedPreferences
 * - Query SCRT balance via LCD REST
 */
public class WalletMainFragment extends Fragment implements WalletListFragment.WalletListListener, CreateWalletFragment.CreateWalletListener {

    private static final String TAG = "WalletMainFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";
    private static final String KEY_LCD_URL = "lcd_url";

    // UI
    private TextView currentWalletName;
    private Button btnAddWallet;
    private TextView addressText;
    private TextView balanceText;
    private View addressRow;
    private View balanceRow;
    
    // Token UI elements
    private View tokenBalancesSection;
    private LinearLayout tokenBalancesContainer;
    private Button btnManageViewingKeys;

    private SharedPreferences securePrefs;
    
    // Request codes for SNIP-20 queries
    private static final int REQ_TOKEN_BALANCE = 2001;
    private static final int REQ_VIEWING_KEY = 2002;
    private static final int REQ_SET_VIEWING_KEY = 2003;
    
    // Temporary storage for viewing key generation
    private Tokens.TokenInfo pendingViewingKeyToken = null;
    private String pendingViewingKey = null;
    
    // Token balance query queue to prevent simultaneous queries
    private java.util.Queue<Tokens.TokenInfo> tokenQueryQueue = new java.util.LinkedList<>();
    private boolean isQueryingToken = false;
    
    // Track current wallet address to avoid unnecessary token refreshes
    private String lastWalletAddress = null;

    public WalletMainFragment() {}
    
    public static WalletMainFragment newInstance() {
        return new WalletMainFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wallet_main, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize SecretWallet
        try {
            SecretWallet.initialize(getContext());
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to initialize SecretWallet", e);
            Toast.makeText(getContext(), "Wallet initialization failed", Toast.LENGTH_LONG).show();
        }

        // mnemonic_input and save button were removed from the layout;
        // read mnemonic from securePrefs when needed instead.
        addressText = view.findViewById(R.id.address_text);
        balanceText = view.findViewById(R.id.balance_text);
        addressRow = view.findViewById(R.id.address_row);
        balanceRow = view.findViewById(R.id.balance_row);
        
        // Token UI elements
        tokenBalancesSection = view.findViewById(R.id.token_balances_section);
        tokenBalancesContainer = view.findViewById(R.id.token_balances_container);
        btnManageViewingKeys = view.findViewById(R.id.btn_manage_viewing_keys);
 
        currentWalletName = view.findViewById(R.id.current_wallet_name);
        // Add button moved into the WalletListFragment; keep the reference null here to avoid missing-id crashes.
        btnAddWallet = null;
        Button btnCopy = view.findViewById(R.id.btn_copy);
        Button btnRefresh = view.findViewById(R.id.btn_refresh);
        // Show mnemonic moved into WalletListFragment; no local button reference needed.

        addressRow.setVisibility(View.GONE);
        balanceRow.setVisibility(View.GONE);

        // Secure preferences
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    getContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Toast.makeText(getContext(), "Secure storage init failed", Toast.LENGTH_LONG).show();
            // Fallback to normal SharedPreferences if necessary (not secure)
            securePrefs = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }

        // Restore saved wallets and selected index
        refreshWalletsUI();

        // Add wallet button -> open the wallet list which contains the Add flow
        if (btnAddWallet != null) {
            btnAddWallet.setOnClickListener(v -> {
                showWalletListFragment();
            });
        }
 
        // Tap wallet name or arrow -> open wallet list screen (hosted fragment if possible)
        View arrow = view.findViewById(R.id.current_wallet_arrow);
        View[] triggers = new View[] { currentWalletName, arrow };
        for (View t : triggers) {
            if (t != null) {
                t.setOnClickListener(v -> {
                    showWalletListFragment();
                });
            }
        }

        btnCopy.setOnClickListener(v -> {
            CharSequence addr = addressText.getText();
            if (TextUtils.isEmpty(addr)) {
                Toast.makeText(getContext(), "No address", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("address", addr));
                Toast.makeText(getContext(), "Address copied", Toast.LENGTH_SHORT).show();
            }
        });
 
        // Removed "Show mnemonic" button here — replaced by WalletListFragment which handles show/delete.
 
        btnRefresh.setOnClickListener(v -> {
            String address = addressText.getText() != null ? addressText.getText().toString().trim() : "";
            if (TextUtils.isEmpty(address)) {
                Toast.makeText(getContext(), "Derive address first", Toast.LENGTH_SHORT).show();
                return;
            }
            String lcd = getLcdUrl();
            new FetchBalanceTask().execute(lcd, address);
            
            // Also refresh token balances
            refreshTokenBalances();
        });
        
        // Viewing key management button
        if (btnManageViewingKeys != null) {
            btnManageViewingKeys.setOnClickListener(v -> {
                showViewingKeyManagementDialog();
            });
        }
    }
 
    @Override
    public void onResume() {
        super.onResume();
        Log.d("WalletMainFragment", "onResume called - about to refresh wallets UI");
        Log.d("WalletMainFragment", "Token container has " + tokenBalancesContainer.getChildCount() + " tokens before onResume");
        
        // Refresh wallets UI in case CreateWalletFragment added a wallet
        refreshWalletsUI();
        
        Log.d("WalletMainFragment", "Token container has " + tokenBalancesContainer.getChildCount() + " tokens after onResume");
    }
 
    private String getMnemonic() {
        // Return the mnemonic for the selected wallet (wallets stored as JSON array)
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            if (arr.length() > 0) {
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "");
                } else if (arr.length() == 1) {
                    return arr.getJSONObject(0).optString("mnemonic", "");
                }
            }
        } catch (Exception ignored) {}
        return securePrefs.getString(KEY_MNEMONIC, "");
    }
 
    private void refreshWalletsUI() {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            if (arr.length() == 0) {
                currentWalletName.setText("No wallet");
                addressRow.setVisibility(View.GONE);
                balanceRow.setVisibility(View.GONE);
                return;
            }
            String walletName = "Wallet 1";
            String mnemonic = "";
            if (sel >= 0 && sel < arr.length()) {
                org.json.JSONObject wallet = arr.getJSONObject(sel);
                walletName = wallet.optString("name", "Wallet " + (sel + 1));
                mnemonic = wallet.optString("mnemonic", "");
            } else if (arr.length() == 1) {
                org.json.JSONObject wallet = arr.getJSONObject(0);
                walletName = wallet.optString("name", "Wallet 1");
                mnemonic = wallet.optString("mnemonic", "");
            }
            currentWalletName.setText(walletName);
            if (!TextUtils.isEmpty(mnemonic)) {
                String address = SecretWallet.getAddressFromMnemonic(mnemonic);
                addressText.setText(address);
                addressRow.setVisibility(View.VISIBLE);
                balanceRow.setVisibility(View.VISIBLE);
                
                // Show token balances section and refresh token balances
                tokenBalancesSection.setVisibility(View.VISIBLE);
                refreshTokenBalances();
                
                // Automatically query SCRT balance
                String lcd = getLcdUrl();
                new FetchBalanceTask().execute(lcd, address);
            } else {
                addressRow.setVisibility(View.GONE);
                balanceRow.setVisibility(View.GONE);
                tokenBalancesSection.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to refresh wallets UI", e);
            currentWalletName.setText("Error");
        }
    }

    private String getLcdUrl() {
        return "https://lcd.erth.network";
    }

    private void showWalletListFragment() {
        WalletListFragment fragment = WalletListFragment.newInstance();
        fragment.setWalletListListener(this);
        getParentFragmentManager().beginTransaction()
                .replace(R.id.host_content, fragment)
                .addToBackStack(null)
                .commit();
    }

    private void showCreateWalletFragment() {
        CreateWalletFragment fragment = CreateWalletFragment.newInstance();
        fragment.setCreateWalletListener(this);
        getParentFragmentManager().beginTransaction()
                .replace(R.id.host_content, fragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onWalletSelected(int index) {
        // Wallet selected, remove fragment and refresh main UI
        FragmentManager fm = getParentFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
        }
        refreshWalletsUI();
    }
    @Override
    public void onCreateWalletRequested() {
        showCreateWalletFragment();
    }
    @Override
    public void onWalletCreated() {
        // Wallet created, remove fragment and refresh main UI
        FragmentManager fm = getParentFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate();
        }
        refreshWalletsUI();
    }
    @Override
    public void onCreateWalletCancelled() {
        // User cancelled, just remove fragment
        FragmentManager fm = getParentFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
        }
    }

    // ===== SNIP-20 Token Methods =====

    private void refreshTokenBalances() {
        String address = addressText.getText() != null ? addressText.getText().toString().trim() : "";
        if (TextUtils.isEmpty(address)) {
            return;
        }

        Log.d("WalletMainFragment", "refreshTokenBalances called for address: " + address);
        Log.d("WalletMainFragment", "Last wallet address was: " + lastWalletAddress);
        Log.d("WalletMainFragment", "Current token container has " + tokenBalancesContainer.getChildCount() + " tokens");
        
        // Only clear and refresh if wallet address has changed
        boolean walletChanged = !address.equals(lastWalletAddress);
        
        if (walletChanged) {
            Log.d("WalletMainFragment", "Wallet changed - clearing existing tokens and refreshing");
            // Print stack trace to see WHO is calling refreshTokenBalances
            Log.d("WalletMainFragment", "refreshTokenBalances called from:", new Exception("Stack trace"));

            // Clear existing token displays only when wallet changes
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

            // Update the last known wallet address
            lastWalletAddress = address;

            // Start processing the queue
            processNextTokenQuery(address);
        } else {
            Log.d("WalletMainFragment", "Same wallet address - skipping token refresh to preserve existing tokens");
            
            // Still process any queued tokens if the queue is not empty but not currently processing
            if (!tokenQueryQueue.isEmpty() && !isQueryingToken) {
                Log.d("WalletMainFragment", "Resuming token queue processing");
                processNextTokenQuery(address);
            }
        }
    }

    private void queueSingleTokenQuery(String address, Tokens.TokenInfo token) {
        // Add a single token to the front of the queue for immediate processing
        Log.d("WalletMainFragment", "Queueing single token query for " + token.symbol);
        
        // Add the token to the front of the queue (priority processing)
        java.util.Queue<Tokens.TokenInfo> tempQueue = new java.util.LinkedList<>();
        tempQueue.offer(token);
        while (!tokenQueryQueue.isEmpty()) {
            tempQueue.offer(tokenQueryQueue.poll());
        }
        tokenQueryQueue = tempQueue;
        
        // Start processing if not already processing
        if (!isQueryingToken) {
            processNextTokenQuery(address);
        }
    }

    private void processNextTokenQuery(String address) {
        if (isQueryingToken || tokenQueryQueue.isEmpty()) {
            return;
        }

        Tokens.TokenInfo token = tokenQueryQueue.poll();
        if (token != null) {
            isQueryingToken = true;
            queryTokenBalance(address, token);
        }
    }

    private void queryTokenBalance(String address, Tokens.TokenInfo token) {
        try {
            // Check if we have a viewing key for this token
            String viewingKey = getViewingKey(token.contract);
            if (TextUtils.isEmpty(viewingKey)) {
                // Add button to get viewing key
                addTokenBalanceView(token, null, false);
                
                // Mark query as complete and continue with next token
                isQueryingToken = false;
                processNextTokenQuery(address);
                return;
            }

            // Store token symbol with viewing key for result matching
            String walletAddress = getCurrentWalletAddress();
            if (!TextUtils.isEmpty(walletAddress)) {
                securePrefs.edit().putString("viewing_key_symbol_" + walletAddress + "_" + token.contract, token.symbol).apply();
            }

            // Create SNIP-20 balance query (matches SecretJS implementation)
            org.json.JSONObject query = new org.json.JSONObject();
            org.json.JSONObject balanceQuery = new org.json.JSONObject();
            balanceQuery.put("address", address);
            balanceQuery.put("key", viewingKey);
            balanceQuery.put("time", System.currentTimeMillis()); // Add timestamp like SecretJS
            query.put("balance", balanceQuery);

            Log.d("WalletMainFragment", "Querying token " + token.symbol + " balance");
            Log.d("WalletMainFragment", "Query JSON: " + query.toString());
            Log.d("WalletMainFragment", "Contract: " + token.contract);
            Log.d("WalletMainFragment", "Hash: " + token.hash);
            Log.d("WalletMainFragment", "Viewing key starts with: " + (viewingKey.length() > 10 ? viewingKey.substring(0, 10) + "..." : viewingKey));

            // Launch query using general purpose SecretQueryActivity
            Intent qi = new Intent(getContext(), SecretQueryActivity.class);
            qi.putExtra(SecretQueryActivity.EXTRA_CONTRACT_ADDRESS, token.contract);
            qi.putExtra(SecretQueryActivity.EXTRA_CODE_HASH, token.hash);
            qi.putExtra(SecretQueryActivity.EXTRA_QUERY_JSON, query.toString());
            qi.putExtra("token_symbol", token.symbol); // Pass token symbol for result handling
            startActivityForResult(qi, REQ_TOKEN_BALANCE);

        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to query token balance for " + token.symbol, e);
            addTokenBalanceView(token, "Error", false);
            
            // Mark query as complete and continue with next token
            isQueryingToken = false;
            processNextTokenQuery(address);
        }
    }

    private void querySingleTokenBalance(String address, Tokens.TokenInfo token) {
        // Query a single token balance without using the queue system
        // This is used for individual token updates (e.g., after viewing key generation)
        try {
            String viewingKey = getViewingKey(token.contract);
            if (TextUtils.isEmpty(viewingKey)) {
                // No viewing key, just update to show the button
                updateTokenBalanceView(token, null);
                return;
            }

            // Create SNIP-20 balance query (same as queryTokenBalance)
            org.json.JSONObject query = new org.json.JSONObject();
            org.json.JSONObject balanceQuery = new org.json.JSONObject();
            balanceQuery.put("address", address);
            balanceQuery.put("key", viewingKey);
            balanceQuery.put("time", System.currentTimeMillis());
            query.put("balance", balanceQuery);

            // Launch query
            Intent qi = new Intent(getContext(), SecretQueryActivity.class);
            qi.putExtra(SecretQueryActivity.EXTRA_CONTRACT_ADDRESS, token.contract);
            qi.putExtra(SecretQueryActivity.EXTRA_CODE_HASH, token.hash);
            qi.putExtra(SecretQueryActivity.EXTRA_QUERY_JSON, query.toString());
            qi.putExtra("token_symbol", token.symbol);
            startActivityForResult(qi, REQ_TOKEN_BALANCE);

        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to query single token balance for " + token.symbol, e);
            updateTokenBalanceView(token, "Error");
        }
    }

    private void addTokenBalanceView(Tokens.TokenInfo token, String balance, boolean hasViewingKey) {
        Log.d("WalletMainFragment", "addTokenBalanceView called for " + token.symbol + " with balance: " + balance + " hasViewingKey: " + hasViewingKey);
        
        try {
            // Create a card view for the token balance
            LinearLayout tokenCard = new LinearLayout(getContext());
            tokenCard.setOrientation(LinearLayout.HORIZONTAL);
            tokenCard.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tokenCard.setPadding(24, 16, 24, 16);
            tokenCard.setBackground(getResources().getDrawable(R.drawable.card_rounded_bg));
            tokenCard.setTag(token.symbol); // Add tag to identify this token card

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
                    requestViewingKey(token);
                });
                getViewingKeyBtn.setTag("get_key_btn"); // Add tag to identify button
                
                tokenCard.addView(getViewingKeyBtn);
            } else {
                // Has viewing key - show balance text
                TextView balanceText = new TextView(getContext());
                balanceText.setText(balance);
                balanceText.setTag("balance"); // Add tag to identify balance text
                
                // Style based on whether it's an error or normal balance
                if ("!".equals(balance)) {
                    balanceText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    balanceText.setTextSize(20); // Make it slightly larger
                } else {
                    balanceText.setTextColor(getResources().getColor(R.color.sidebar_text));
                    balanceText.setTextSize(16); // Normal size
                }
                
                tokenCard.addView(balanceText);
                
                // Add click listener for viewing key management if needed
                if (!hasViewingKey) {
                    tokenCard.setOnClickListener(v -> {
                        showSetViewingKeyDialog(token);
                    });
                    tokenCard.setClickable(true);
                    tokenCard.setFocusable(true);
                }
            }

            tokenBalancesContainer.addView(tokenCard);
            Log.d("WalletMainFragment", "Successfully added token view for " + token.symbol + " to container. Container now has " + tokenBalancesContainer.getChildCount() + " tokens");
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to add token balance view for " + token.symbol, e);
        }
    }

    private String getViewingKey(String contractAddress) {
        String walletAddress = getCurrentWalletAddress();
        if (TextUtils.isEmpty(walletAddress)) {
            return ""; // No wallet address available
        }
        return securePrefs.getString("viewing_key_" + walletAddress + "_" + contractAddress, "");
    }

    private String getViewingKeyTokenSymbol(String contractAddress) {
        String walletAddress = getCurrentWalletAddress();
        if (TextUtils.isEmpty(walletAddress)) {
            return ""; // No wallet address available
        }
        return securePrefs.getString("viewing_key_symbol_" + walletAddress + "_" + contractAddress, "");
    }

    private void setViewingKey(String contractAddress, String viewingKey) {
        String walletAddress = getCurrentWalletAddress();
        if (TextUtils.isEmpty(walletAddress)) {
            Log.e("WalletMainFragment", "Cannot set viewing key: no wallet address available");
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

    private String getCurrentWalletAddress() {
        // Get the current wallet's address from the UI or derive it from mnemonic
        if (addressText != null && addressText.getText() != null) {
            String address = addressText.getText().toString().trim();
            if (!TextUtils.isEmpty(address)) {
                return address;
            }
        }
        
        // Fallback: derive from mnemonic if address not available in UI
        try {
            String mnemonic = getMnemonic();
            if (!TextUtils.isEmpty(mnemonic)) {
                return SecretWallet.getAddressFromMnemonic(mnemonic);
            }
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to get current wallet address", e);
        }
        
        return null;
    }

    private void showViewingKeyManagementDialog() {
        // Create a dialog to manage all viewing keys for the current wallet
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        String walletAddress = getCurrentWalletAddress();
        String walletDisplayAddress = walletAddress != null ? walletAddress.substring(0, Math.min(10, walletAddress.length())) + "..." : "Unknown";
        builder.setTitle("Manage Viewing Keys - " + walletDisplayAddress);
        
        // Create a simple list of tokens with their viewing key status for this wallet
        StringBuilder message = new StringBuilder();
        message.append("Viewing keys allow you to see your private token balances for this wallet.\n\n");
        
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            Tokens.TokenInfo token = Tokens.getToken(symbol);
            if (token != null) {
                String viewingKey = getViewingKey(token.contract);
                String status = TextUtils.isEmpty(viewingKey) ? "Not set" : "Set";
                message.append(symbol).append(": ").append(status).append("\n");
            }
        }
        
        builder.setMessage(message.toString());
        builder.setPositiveButton("Set Keys", (dialog, which) -> {
            // Show individual token key dialogs
            showSetViewingKeyDialog(Tokens.ERTH);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showSetViewingKeyDialog(Tokens.TokenInfo token) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        builder.setTitle("Set Viewing Key for " + token.symbol);
        builder.setMessage("Enter your viewing key for " + token.symbol + " to see your balance.\n\n" +
                          "You can get a viewing key from:\n" +
                          "• Secret Network wallets (Keplr, Fina, etc.)\n" +
                          "• Token contract interactions\n" +
                          "• Secret Network dApps");
        
        final EditText input = new EditText(getContext());
        input.setHint("Enter viewing key (starts with 'api_key_')");
        String currentKey = getViewingKey(token.contract);
        if (!TextUtils.isEmpty(currentKey)) {
            input.setText(currentKey);
        }
        builder.setView(input);
        
        builder.setPositiveButton("Set", (dialog, which) -> {
            String viewingKey = input.getText().toString().trim();
            if (!TextUtils.isEmpty(viewingKey)) {
                setViewingKey(token.contract, viewingKey);
                refreshTokenBalances();
                Toast.makeText(getContext(), "Viewing key set for " + token.symbol, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        
        // Add a "How to get" button for more information
        builder.setNeutralButton("How to get?", (dialog, which) -> {
            showViewingKeyHelpDialog(token);
        });
        
        builder.show();
    }
    
    private void showViewingKeyHelpDialog(Tokens.TokenInfo token) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        builder.setTitle("How to get a viewing key for " + token.symbol);
        builder.setMessage("Viewing keys are required to see your private token balances on Secret Network.\n\n" +
                          "To get a viewing key:\n\n" +
                          "1. Use a Secret Network wallet like Keplr\n" +
                          "2. Add the " + token.symbol + " token to your wallet\n" +
                          "3. The wallet will generate a viewing key\n" +
                          "4. Copy the viewing key and paste it here\n\n" +
                          "Contract: " + token.contract + "\n" +
                          "Code Hash: " + token.hash);
        
        builder.setPositiveButton("OK", null);
        builder.setNegativeButton("Set Key", (dialog, which) -> {
            showSetViewingKeyDialog(token);
        });
        builder.show();
    }

    /**
     * Request viewing key automatically (equivalent to SecretJS requestViewingKey function)
     * This attempts to get the viewing key from connected Secret Network wallets
     */
    private void requestViewingKey(Tokens.TokenInfo token) {
        try {
            Log.i("WalletMainFragment", "Requesting viewing key for " + token.symbol);
            
            // Show loading state
            Toast.makeText(getContext(), "Requesting viewing key for " + token.symbol + "...", Toast.LENGTH_SHORT).show();
            
            // For now, show a fallback dialog with auto-generation option
            // In a full implementation, this would integrate with Keplr/wallet extensions
            showAutoViewingKeyDialog(token);
            
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Error requesting viewing key for " + token.symbol, e);
            Toast.makeText(getContext(), "Failed to request viewing key", Toast.LENGTH_SHORT).show();
            
            // Fallback to manual entry
            showSetViewingKeyDialog(token);
        }
    }

    /**
     * Show dialog with automatic viewing key generation option
     * This simulates the keplr.suggestToken() and keplr.getSecret20ViewingKey() flow
     */
    private void showAutoViewingKeyDialog(Tokens.TokenInfo token) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        builder.setTitle("Get Viewing Key for " + token.symbol);
        builder.setMessage("This will automatically generate a viewing key for " + token.symbol + " token.\n\n" +
                          "The viewing key will be created using your wallet's private key and stored securely.\n\n" +
                          "Contract: " + token.contract);
        
        builder.setPositiveButton("Generate Key", (dialog, which) -> {
            generateViewingKey(token);
        });
        
        builder.setNeutralButton("Enter Manually", (dialog, which) -> {
            showSetViewingKeyDialog(token);
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Generate viewing key automatically using wallet's private key
     * This is the native Android equivalent of keplr.getSecret20ViewingKey()
     */
    private void generateViewingKey(Tokens.TokenInfo token) {
        try {
            Log.d("WalletMainFragment", "Starting viewing key generation for " + token.symbol);
            Toast.makeText(getContext(), "Generating viewing key for " + token.symbol + "...", Toast.LENGTH_SHORT).show();
            
            // Get the wallet's mnemonic
            String mnemonic = getMnemonic();
            if (TextUtils.isEmpty(mnemonic)) {
                Log.w("WalletMainFragment", "No mnemonic available for viewing key generation");
                Toast.makeText(getContext(), "No wallet available", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Generate a viewing key using the wallet's private key and token contract
            // This mimics the Keplr wallet's viewing key generation
            Log.d("WalletMainFragment", "Calling generateSecret20ViewingKey for contract: " + token.contract);
            String viewingKey = generateSecret20ViewingKey(mnemonic, token.contract);
            Log.d("WalletMainFragment", "Generated viewing key length: " + (viewingKey != null ? viewingKey.length() : "null"));
            
            if (!TextUtils.isEmpty(viewingKey)) {
                // Execute set_viewing_key transaction on the blockchain
                Log.d("WalletMainFragment", "Setting viewing key on blockchain for contract: " + token.contract);
                executeSetViewingKeyTransaction(token, viewingKey);
                
                Log.i("WalletMainFragment", "Initiated viewing key transaction for " + token.symbol);
            } else {
                Log.w("WalletMainFragment", "Failed to generate viewing key (null result)");
                Toast.makeText(getContext(), "Failed to generate viewing key", Toast.LENGTH_SHORT).show();
                showSetViewingKeyDialog(token); // Fallback to manual entry
            }
            
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to generate viewing key for " + token.symbol, e);
            Toast.makeText(getContext(), "Error generating viewing key: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            showSetViewingKeyDialog(token); // Fallback to manual entry
        }
    }

    /**
     * Execute set_viewing_key transaction on the blockchain
     */
    private void executeSetViewingKeyTransaction(Tokens.TokenInfo token, String viewingKey) {
        try {
            // Store the pending viewing key info for when the transaction completes
            pendingViewingKeyToken = token;
            pendingViewingKey = viewingKey;
            
            // Create the set_viewing_key message
            org.json.JSONObject setViewingKeyMsg = new org.json.JSONObject();
            org.json.JSONObject setViewingKeyInner = new org.json.JSONObject();
            setViewingKeyInner.put("key", viewingKey);
            setViewingKeyMsg.put("set_viewing_key", setViewingKeyInner);

            // Launch SecretExecuteActivity to set the viewing key on chain
            Intent intent = new Intent(getContext(), com.example.earthwallet.bridge.activities.SecretExecuteActivity.class);
            intent.putExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, token.contract);
            intent.putExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_CODE_HASH, token.hash);
            intent.putExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_EXECUTE_JSON, setViewingKeyMsg.toString());
            
            startActivityForResult(intent, REQ_SET_VIEWING_KEY);
            
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to create set viewing key transaction", e);
            Toast.makeText(getContext(), "Failed to create transaction: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            // Clear pending state on error
            pendingViewingKeyToken = null;
            pendingViewingKey = null;
        }
    }

    /**
     * Generate a Secret Network SNIP-20 viewing key
     * This mimics keplr.getSecret20ViewingKey() functionality
     */
    private String generateSecret20ViewingKey(String mnemonic, String contractAddress) {
        try {
            // Generate a random viewing key following Secret Network standard
            // This matches how Keplr and other wallets generate viewing keys
            
            // Generate 32 random bytes for the viewing key
            java.security.SecureRandom random = new java.security.SecureRandom();
            byte[] keyBytes = new byte[32];
            random.nextBytes(keyBytes);
            
            // Format as Secret Network viewing key (api_key_ prefix + base64)
            String viewingKey = "api_key_" + java.util.Base64.getEncoder().encodeToString(keyBytes);
            
            Log.i("WalletMainFragment", "Generated random viewing key for contract: " + contractAddress);
            return viewingKey;
            
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to generate viewing key", e);
            return null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_TOKEN_BALANCE) {
            if (resultCode == getActivity().RESULT_OK && data != null) {
                try {
                    String json = data.getStringExtra(SecretQueryActivity.EXTRA_RESULT_JSON);
                    String tokenSymbol = data.getStringExtra("token_symbol");
                    
                    // If token_symbol is null, use stored token symbol from viewing key storage
                    if (TextUtils.isEmpty(tokenSymbol)) {
                        Log.w("WalletMainFragment", "token_symbol is null, looking up from stored viewing key symbols");
                        // Since we process tokens sequentially, find the token that has a viewing key and was recently queried
                        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
                            Tokens.TokenInfo token = Tokens.getToken(symbol);
                            if (token != null) {
                                String storedSymbol = getViewingKeyTokenSymbol(token.contract);
                                String viewingKey = getViewingKey(token.contract);
                                if (!TextUtils.isEmpty(storedSymbol) && !TextUtils.isEmpty(viewingKey)) {
                                    tokenSymbol = storedSymbol;
                                    Log.d("WalletMainFragment", "Found stored token symbol: " + tokenSymbol + " for contract: " + token.contract);
                                    break; // Take the first match since we query sequentially
                                }
                            }
                        }
                        if (TextUtils.isEmpty(tokenSymbol)) {
                            Log.e("WalletMainFragment", "Still no token symbol found, cannot process result");
                        }
                    }
                    
                    Log.d("WalletMainFragment", "Token balance query result for " + tokenSymbol + ": " + json);
                    
                    if (!TextUtils.isEmpty(json) && !TextUtils.isEmpty(tokenSymbol)) {
                        org.json.JSONObject root = new org.json.JSONObject(json);
                        boolean success = root.optBoolean("success", false);
                        
                        Log.d("WalletMainFragment", "Query success: " + success + " for token " + tokenSymbol);
                        
                        Tokens.TokenInfo token = Tokens.getToken(tokenSymbol);
                        if (token != null) {
                            if (success) {
                                org.json.JSONObject result = root.optJSONObject("result");
                                Log.d("WalletMainFragment", "Result object: " + (result != null ? result.toString() : "null"));
                                
                                if (result != null) {
                                    org.json.JSONObject balance = result.optJSONObject("balance");
                                    Log.d("WalletMainFragment", "Balance object: " + (balance != null ? balance.toString() : "null"));
                                    
                                    if (balance != null) {
                                        String amount = balance.optString("amount", "0");
                                        Log.d("WalletMainFragment", "Balance amount: " + amount + " for token " + tokenSymbol);
                                        String formattedBalance = Tokens.formatTokenAmount(amount, token) + " " + token.symbol;
                                        
                                        // Update the specific token balance view
                                        updateTokenBalanceView(token, formattedBalance);
                                    } else {
                                        Log.w("WalletMainFragment", "No balance object in result for " + tokenSymbol);
                                        // Query succeeded but no balance data - show error
                                        updateTokenBalanceView(token, "!");
                                    }
                                } else {
                                    Log.w("WalletMainFragment", "No result object for " + tokenSymbol);
                                    // Query succeeded but no result data - show error
                                    updateTokenBalanceView(token, "!");
                                }
                            } else {
                                Log.w("WalletMainFragment", "Query failed for " + tokenSymbol);
                                // Query failed - show error
                                updateTokenBalanceView(token, "!");
                            }
                        }
                    } else {
                        Log.w("WalletMainFragment", "Missing json or tokenSymbol in result");
                    }
                } catch (Exception e) {
                    Log.e("WalletMainFragment", "Failed to parse token balance result", e);
                    // If we can't parse the result, still try to show error for the token if we can identify it
                    String tokenSymbol = data != null ? data.getStringExtra("token_symbol") : null;
                    if (!TextUtils.isEmpty(tokenSymbol)) {
                        Tokens.TokenInfo token = Tokens.getToken(tokenSymbol);
                        if (token != null) {
                            updateTokenBalanceView(token, "!");
                        }
                    }
                }
            } else {
                // Query returned error or was cancelled - try to show error for the token
                String tokenSymbol = (data != null) ? data.getStringExtra("token_symbol") : null;
                if (!TextUtils.isEmpty(tokenSymbol)) {
                    Tokens.TokenInfo token = Tokens.getToken(tokenSymbol);
                    if (token != null) {
                        updateTokenBalanceView(token, "!");
                    }
                }
            }
            
            // Mark current query as complete and process next token in queue
            isQueryingToken = false;
            String address = addressText.getText() != null ? addressText.getText().toString().trim() : "";
            if (!TextUtils.isEmpty(address)) {
                processNextTokenQuery(address);
            }
        } else if (requestCode == REQ_SET_VIEWING_KEY) {
            // Handle set_viewing_key transaction result
            if (resultCode == getActivity().RESULT_OK) {
                // Transaction succeeded - use the stored pending values
                if (pendingViewingKeyToken != null && !TextUtils.isEmpty(pendingViewingKey)) {
                    try {
                        Log.d("WalletMainFragment", "Processing viewing key success for " + pendingViewingKeyToken.symbol);
                        Log.d("WalletMainFragment", "Token container has " + tokenBalancesContainer.getChildCount() + " tokens before processing");
                        
                        // Save the viewing key locally
                        setViewingKey(pendingViewingKeyToken.contract, pendingViewingKey);
                        Toast.makeText(getContext(), "Viewing key set successfully for " + pendingViewingKeyToken.symbol + "!", Toast.LENGTH_SHORT).show();
                        
                        Log.d("WalletMainFragment", "Token container has " + tokenBalancesContainer.getChildCount() + " tokens after setViewingKey");
                        
                        // Update the UI to show the viewing key is set and balance is loading
                        // Use the address we already have instead of regenerating it
                        String currentAddress = getCurrentWalletAddress();
                        if (!TextUtils.isEmpty(currentAddress)) {
                            Log.d("WalletMainFragment", "About to update token balance view for " + pendingViewingKeyToken.symbol);
                            Log.d("WalletMainFragment", "Current address: " + currentAddress);
                            Log.d("WalletMainFragment", "Last wallet address: " + lastWalletAddress);
                            Log.d("WalletMainFragment", "Token container has " + tokenBalancesContainer.getChildCount() + " tokens before update");
                            
                            // Update the token to show "Loading..." (this handles both existing and new tokens)
                            updateTokenBalanceView(pendingViewingKeyToken, "Loading...");
                            
                            Log.d("WalletMainFragment", "Token container has " + tokenBalancesContainer.getChildCount() + " tokens after updateTokenBalanceView");
                            
                            // Queue a single token balance query without blocking the UI
                            Log.d("WalletMainFragment", "About to queue single token query for " + pendingViewingKeyToken.symbol);
                            queueSingleTokenQuery(currentAddress, pendingViewingKeyToken);
                            Log.d("WalletMainFragment", "Token container has " + tokenBalancesContainer.getChildCount() + " tokens after queueSingleTokenQuery");
                            
                            // Let's also log if anything calls refreshTokenBalances after this point
                            Log.d("WalletMainFragment", "Viewing key success handler completed. Any subsequent refreshTokenBalances calls will be logged with stack trace.");
                        } else {
                            Log.w("WalletMainFragment", "No wallet address available for token balance query");
                        }
                        
                        Log.i("WalletMainFragment", "Successfully set viewing key for " + pendingViewingKeyToken.symbol);
                    } catch (Exception e) {
                        Log.e("WalletMainFragment", "Failed to handle set viewing key result", e);
                        Toast.makeText(getContext(), "Failed to process viewing key result", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.w("WalletMainFragment", "Transaction succeeded but no pending viewing key info");
                    Toast.makeText(getContext(), "Viewing key transaction completed", Toast.LENGTH_SHORT).show();
                }
                
                // Clear pending state
                pendingViewingKeyToken = null;
                pendingViewingKey = null;
            } else {
                // Transaction failed
                String error = (data != null) ? data.getStringExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_ERROR) : "Transaction failed";
                Toast.makeText(getContext(), "Failed to set viewing key: " + error, Toast.LENGTH_SHORT).show();
                Log.e("WalletMainFragment", "Set viewing key transaction failed: " + error);
                
                // Clear pending state on failure too
                pendingViewingKeyToken = null;
                pendingViewingKey = null;
            }
        }
    }

    private void updateTokenBalanceView(Tokens.TokenInfo token, String balance) {
        Log.d("WalletMainFragment", "updateTokenBalanceView called for " + token.symbol + " with balance: " + balance);
        Log.d("WalletMainFragment", "Searching through " + tokenBalancesContainer.getChildCount() + " tokens in container");
        
        // Try to find and update the existing token balance view instead of refreshing all
        try {
            for (int i = 0; i < tokenBalancesContainer.getChildCount(); i++) {
                View child = tokenBalancesContainer.getChildAt(i);
                String childTag = child.getTag() != null ? child.getTag().toString() : "null";
                Log.d("WalletMainFragment", "Child " + i + " has tag: " + childTag);
                
                if (child.getTag() != null && child.getTag().equals(token.symbol)) {
                    Log.d("WalletMainFragment", "Found existing token view for " + token.symbol);
                    // Found the existing token view, update its balance
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
                                balanceTextView.setTextSize(20); // Make it slightly larger
                            } else {
                                balanceTextView.setTextColor(getResources().getColor(R.color.sidebar_text));
                                balanceTextView.setTextSize(16); // Normal size
                            }
                            return;
                        } else if (cardChild instanceof Button && cardChild.getTag() != null && cardChild.getTag().equals("get_key_btn")) {
                            // Replace the "Get Viewing Key" button with balance text
                            tokenCard.removeView(cardChild);
                            TextView balanceText = new TextView(getContext());
                            balanceText.setText(balance);
                            balanceText.setTag("balance");
                            
                            // Style based on whether it's an error or normal balance
                            if ("!".equals(balance)) {
                                balanceText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                balanceText.setTextSize(20); // Make it slightly larger
                            } else {
                                balanceText.setTextColor(getResources().getColor(R.color.sidebar_text));
                                balanceText.setTextSize(16); // Normal size
                            }
                            
                            tokenCard.addView(balanceText);
                            return;
                        }
                    }
                    return;
                }
            }
            
            // Token view not found, add a new one
            Log.d("WalletMainFragment", "Token " + token.symbol + " not found in existing views, adding new one");
            addTokenBalanceView(token, balance, !TextUtils.isEmpty(getViewingKey(token.contract)));
            
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to update token balance view for " + token.symbol, e);
            // Fallback to adding a new view
            Log.d("WalletMainFragment", "Exception occurred, falling back to adding new token view for " + token.symbol);
            addTokenBalanceView(token, balance, !TextUtils.isEmpty(getViewingKey(token.contract)));
        }
    }

    /**
     * FetchBalanceTask - AsyncTask to query SCRT balance via LCD using bank module
     */
    private class FetchBalanceTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            if (params.length < 2) return "Error: missing params";
            String lcdUrl = params[0];
            String address = params[1];
            
            try {
                // Use SecretWallet's bank query method (not contract query)
                long microScrt = SecretWallet.fetchUscrtBalanceMicro(lcdUrl, address);
                return SecretWallet.formatScrt(microScrt);
            } catch (Exception e) {
                Log.e(TAG, "SCRT balance query failed", e);
                return "Error: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (balanceText != null) {
                balanceText.setText(result);
            }
        }
    }
}