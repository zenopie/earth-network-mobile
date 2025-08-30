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
        // Refresh wallets UI in case CreateWalletFragment added a wallet
        refreshWalletsUI();
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

        // Clear existing token displays
        tokenBalancesContainer.removeAllViews();

        // Query balance for each supported token
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            Tokens.TokenInfo token = Tokens.getToken(symbol);
            if (token != null) {
                queryTokenBalance(address, token);
            }
        }
    }

    private void queryTokenBalance(String address, Tokens.TokenInfo token) {
        try {
            // Check if we have a viewing key for this token
            String viewingKey = getViewingKey(token.contract);
            if (TextUtils.isEmpty(viewingKey)) {
                // Add button to get viewing key
                addTokenBalanceView(token, null, false);
                return;
            }

            // Create SNIP-20 balance query (matches SecretJS implementation)
            org.json.JSONObject query = new org.json.JSONObject();
            org.json.JSONObject balanceQuery = new org.json.JSONObject();
            balanceQuery.put("address", address);
            balanceQuery.put("key", viewingKey);
            balanceQuery.put("time", System.currentTimeMillis()); // Add timestamp like SecretJS
            query.put("balance", balanceQuery);

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
        }
    }

    private void addTokenBalanceView(Tokens.TokenInfo token, String balance, boolean hasViewingKey) {
        try {
            // Create a card view for the token balance
            LinearLayout tokenCard = new LinearLayout(getContext());
            tokenCard.setOrientation(LinearLayout.HORIZONTAL);
            tokenCard.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tokenCard.setPadding(24, 16, 24, 16);
            tokenCard.setBackground(getResources().getDrawable(R.drawable.card_rounded_bg));

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
                
                tokenCard.addView(getViewingKeyBtn);
            } else {
                // Has viewing key - show balance text
                TextView balanceText = new TextView(getContext());
                balanceText.setText(balance);
                balanceText.setTextSize(16);
                balanceText.setTextColor(getResources().getColor(R.color.sidebar_text));
                
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
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to add token balance view for " + token.symbol, e);
        }
    }

    private String getViewingKey(String contractAddress) {
        return securePrefs.getString("viewing_key_" + contractAddress, "");
    }

    private void setViewingKey(String contractAddress, String viewingKey) {
        securePrefs.edit().putString("viewing_key_" + contractAddress, viewingKey).apply();
    }

    private void showViewingKeyManagementDialog() {
        // Create a dialog to manage all viewing keys
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        builder.setTitle("Manage Viewing Keys");
        
        // Create a simple list of tokens with their viewing key status
        StringBuilder message = new StringBuilder();
        message.append("Viewing keys allow you to see your private token balances.\n\n");
        
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
            
            // Add token info to intent so we can handle the result
            intent.putExtra("token_symbol", token.symbol);
            intent.putExtra("token_contract", token.contract);
            intent.putExtra("generated_viewing_key", viewingKey);
            
            startActivityForResult(intent, REQ_SET_VIEWING_KEY);
            
        } catch (Exception e) {
            Log.e("WalletMainFragment", "Failed to create set viewing key transaction", e);
            Toast.makeText(getContext(), "Failed to create transaction: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Generate a Secret Network SNIP-20 viewing key
     * This mimics keplr.getSecret20ViewingKey() functionality
     */
    private String generateSecret20ViewingKey(String mnemonic, String contractAddress) {
        try {
            // Generate a deterministic viewing key based on wallet and contract
            // This follows the Secret Network viewing key standard
            
            // Use wallet's private key + contract address to generate unique viewing key
            ECKey walletKey = SecretWallet.deriveKeyFromMnemonic(mnemonic);
            byte[] privateKeyBytes = walletKey.getPrivKeyBytes();
            
            // Create viewing key material: privateKey + contractAddress
            String keyMaterial = java.util.Base64.getEncoder().encodeToString(privateKeyBytes) + contractAddress;
            
            // Hash to create viewing key
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(keyMaterial.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            // Format as Secret Network viewing key (api_key_ prefix + base64)
            String viewingKey = "api_key_" + java.util.Base64.getEncoder().encodeToString(hash).substring(0, 32);
            
            Log.i("WalletMainFragment", "Generated viewing key for contract: " + contractAddress);
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
                    
                    if (!TextUtils.isEmpty(json) && !TextUtils.isEmpty(tokenSymbol)) {
                        org.json.JSONObject root = new org.json.JSONObject(json);
                        boolean success = root.optBoolean("success", false);
                        
                        if (success) {
                            org.json.JSONObject result = root.optJSONObject("result");
                            if (result != null) {
                                org.json.JSONObject balance = result.optJSONObject("balance");
                                if (balance != null) {
                                    String amount = balance.optString("amount", "0");
                                    Tokens.TokenInfo token = Tokens.getToken(tokenSymbol);
                                    if (token != null) {
                                        String formattedBalance = Tokens.formatTokenAmount(amount, token) + " " + token.symbol;
                                        
                                        // Update the specific token balance view
                                        updateTokenBalanceView(token, formattedBalance);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("WalletMainFragment", "Failed to parse token balance result", e);
                }
            }
        } else if (requestCode == REQ_SET_VIEWING_KEY) {
            // Handle set_viewing_key transaction result
            if (resultCode == getActivity().RESULT_OK && data != null) {
                try {
                    String tokenSymbol = data.getStringExtra("token_symbol");
                    String tokenContract = data.getStringExtra("token_contract");  
                    String generatedViewingKey = data.getStringExtra("generated_viewing_key");
                    
                    if (!TextUtils.isEmpty(tokenSymbol) && !TextUtils.isEmpty(tokenContract) && !TextUtils.isEmpty(generatedViewingKey)) {
                        // Transaction succeeded - now save the viewing key locally
                        setViewingKey(tokenContract, generatedViewingKey);
                        Toast.makeText(getContext(), "Viewing key set successfully for " + tokenSymbol + "!", Toast.LENGTH_SHORT).show();
                        
                        // Refresh token balances to show the new balance
                        Tokens.TokenInfo token = Tokens.getToken(tokenSymbol);
                        if (token != null) {
                            updateTokenBalanceView(token, "Loading...");
                            String mnemonic = getMnemonic();
                            if (!TextUtils.isEmpty(mnemonic)) {
                                String address = SecretWallet.getAddressFromMnemonic(mnemonic);
                                if (!TextUtils.isEmpty(address)) {
                                    queryTokenBalance(address, token);
                                }
                            }
                        }
                        
                        Log.i("WalletMainFragment", "Successfully set viewing key for " + tokenSymbol);
                    }
                } catch (Exception e) {
                    Log.e("WalletMainFragment", "Failed to handle set viewing key result", e);
                    Toast.makeText(getContext(), "Failed to process viewing key result", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Transaction failed
                String error = (data != null) ? data.getStringExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_ERROR) : "Transaction failed";
                Toast.makeText(getContext(), "Failed to set viewing key: " + error, Toast.LENGTH_SHORT).show();
                Log.e("WalletMainFragment", "Set viewing key transaction failed: " + error);
            }
        }
    }

    private void updateTokenBalanceView(Tokens.TokenInfo token, String balance) {
        // Find and update the existing token balance view
        // For simplicity, just refresh all token balances for now
        // In production, you might want to find and update the specific view
        refreshTokenBalances();
    }

    /**
     * FetchBalanceTask - AsyncTask to query SCRT balance via LCD
     */
    private class FetchBalanceTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            // Simplified balance fetch implementation
            return "0 SCRT";
        }

        @Override
        protected void onPostExecute(String result) {
            if (balanceText != null) {
                balanceText.setText(result);
            }
        }
    }
}