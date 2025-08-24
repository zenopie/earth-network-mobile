package com.example.passportscanner.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.R;
import com.example.passportscanner.ActionsActivity;

import org.bitcoinj.core.ECKey;
import android.util.Log;

/**
 * Simple Secret Network wallet screen:
 * - Generate/import mnemonic
 * - Derive address (hrp "secret")
 * - Save mnemonic securely with EncryptedSharedPreferences
 * - Query SCRT balance via LCD REST
 */
public class WalletActivity extends AppCompatActivity {

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

    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallet);

        // Initialize SecretWallet
        try {
            SecretWallet.initialize(this);
        } catch (Exception e) {
            Log.e("WalletActivity", "Failed to initialize SecretWallet", e);
            Toast.makeText(this, "Wallet initialization failed", Toast.LENGTH_LONG).show();
            // We might want to finish the activity here since core functionality is broken
            // finish();
            // return;
        }

        // mnemonic_input and save button were removed from the layout;
        // read mnemonic from securePrefs when needed instead.
        addressText = findViewById(R.id.address_text);
        balanceText = findViewById(R.id.balance_text);
        addressRow = findViewById(R.id.address_row);
        balanceRow = findViewById(R.id.balance_row);
 
        currentWalletName = findViewById(R.id.current_wallet_name);
        btnAddWallet = findViewById(R.id.btn_add_wallet);
        Button btnCopy = findViewById(R.id.btn_copy);
        Button btnRefresh = findViewById(R.id.btn_refresh);
        Button btnShowMnemonic = findViewById(R.id.btn_show_mnemonic);

        addressRow.setVisibility(View.GONE);
        balanceRow.setVisibility(View.GONE);

        // Secure preferences
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Toast.makeText(this, "Secure storage init failed", Toast.LENGTH_LONG).show();
            // Fallback to normal SharedPreferences if necessary (not secure)
            securePrefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
        }

        // Restore saved wallets and selected index
        refreshWalletsUI();

        // Add wallet button -> start create flow
        if (btnAddWallet != null) {
            btnAddWallet.setOnClickListener(v -> {
                Intent i = new Intent(WalletActivity.this, CreateWalletActivity.class);
                startActivity(i);
            });
        }
 
        // Tap wallet name -> pick from list
        if (currentWalletName != null) {
            currentWalletName.setOnClickListener(v -> showWalletPicker());
        }

        btnCopy.setOnClickListener(v -> {
            CharSequence addr = addressText.getText();
            if (TextUtils.isEmpty(addr)) {
                Toast.makeText(this, "No address", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("address", addr));
                Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
            }
        });
 
        // Show mnemonic button: requires entering the PIN
        if (btnShowMnemonic != null) {
            btnShowMnemonic.setOnClickListener(v -> {
                // PIN input
                android.widget.EditText pinField = new android.widget.EditText(this);
                pinField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                pinField.setHint("PIN");
 
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Enter PIN")
                        .setView(pinField)
                        .setPositiveButton("OK", (dlg, which) -> {
                            String pin = pinField.getText() != null ? pinField.getText().toString().trim() : "";
                            if (android.text.TextUtils.isEmpty(pin)) {
                                android.widget.Toast.makeText(this, "Enter PIN", android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                            try {
                                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                                byte[] digest = md.digest(pin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                StringBuilder sb = new StringBuilder();
                                for (byte b : digest) sb.append(String.format("%02x", b));
                                String pinHash = sb.toString();
                                String stored = securePrefs.getString("pin_hash", "");
                                if (stored != null && stored.equals(pinHash)) {
                                    String mnem = securePrefs.getString(KEY_MNEMONIC, "");
                                    if (android.text.TextUtils.isEmpty(mnem)) {
                                        android.widget.Toast.makeText(this, "No mnemonic stored", android.widget.Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    // Show mnemonic securely
                                    android.widget.TextView tv = new android.widget.TextView(this);
                                    tv.setText(mnem);
                                    int pad = (int)(12 * getResources().getDisplayMetrics().density);
                                    tv.setPadding(pad, pad, pad, pad);
                                    tv.setTextIsSelectable(true);
                                    androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this);
                                    b.setTitle("Recovery Phrase");
                                    b.setView(tv);
                                    b.setPositiveButton("Close", null);
                                    b.show();
                                } else {
                                    android.widget.Toast.makeText(this, "Invalid PIN", android.widget.Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception e) {
                                android.util.Log.e("WalletActivity", "PIN check failed", e);
                                android.widget.Toast.makeText(this, "Error checking PIN", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }
 
        btnRefresh.setOnClickListener(v -> {
            String address = addressText.getText() != null ? addressText.getText().toString().trim() : "";
            if (TextUtils.isEmpty(address)) {
                Toast.makeText(this, "Derive address first", Toast.LENGTH_SHORT).show();
                return;
            }
            String lcd = getLcdUrl();
            new FetchBalanceTask().execute(lcd, address);
        });

        // Bottom navigation wiring - use selected state instead of disabling to avoid default button borders
        View navWallet = findViewById(R.id.btn_nav_wallet);
        if (navWallet != null) {
            // Mark wallet as selected for styling and prevent redundant clicks
            navWallet.setSelected(true);
            navWallet.setOnClickListener(v -> {
                // no-op: already on Wallet screen
            });
        }
        View navActions = findViewById(R.id.btn_nav_actions);
        if (navActions != null) {
            navActions.setSelected(false);
            navActions.setOnClickListener(v -> {
                Intent a = new Intent(WalletActivity.this, ActionsActivity.class);
                startActivity(a);
            });
        }
    }
 
    @Override
    protected void onResume() {
        super.onResume();
        // Refresh wallets UI in case CreateWalletActivity added a wallet
        refreshWalletsUI();
    }
 
    private String getMnemonic() {
        // Return the mnemonic for the selected wallet (wallets stored as JSON array)
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            if (sel >= 0 && sel < arr.length()) {
                return arr.getJSONObject(sel).optString("mnemonic", "");
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
                if (currentWalletName != null) currentWalletName.setText("No wallet");
                addressRow.setVisibility(View.GONE);
                balanceRow.setVisibility(View.GONE);
                return;
            }
            if (sel < 0 || sel >= arr.length()) {
                sel = 0;
                securePrefs.edit().putInt("selected_wallet_index", sel).apply();
            }
            org.json.JSONObject obj = arr.getJSONObject(sel);
            String name = obj.optString("name", "Wallet");
            String mn = obj.optString("mnemonic", "");
            if (currentWalletName != null) currentWalletName.setText(name);
            if (!TextUtils.isEmpty(mn)) {
                deriveAndDisplayAddress(mn);
            } else {
                addressRow.setVisibility(View.GONE);
                balanceRow.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e("WalletActivity", "Failed to refresh wallets UI", e);
        }
    }
 
    private void showWalletPicker() {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
            if (arr.length() == 0) {
                Toast.makeText(this, "No wallets", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) names[i] = arr.getJSONObject(i).optString("name", "Wallet " + i);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Select wallet")
                    .setItems(names, (dlg, which) -> {
                        securePrefs.edit().putInt("selected_wallet_index", which).apply();
                        refreshWalletsUI();
                    })
                    .show();
        } catch (Exception e) {
            Log.e("WalletActivity", "Failed to show wallet picker", e);
        }
    }

    private String getLcdUrl() {
        // Endpoint fixed to project default
        return SecretWallet.DEFAULT_LCD_URL;
    }

    private void deriveAndDisplayAddress(String mnemonic) {
        try {
            ECKey key = SecretWallet.deriveKeyFromMnemonic(mnemonic);
            String address = SecretWallet.getAddress(key);
            addressText.setText(address);
            addressRow.setVisibility(View.VISIBLE);
            balanceRow.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Toast.makeText(this, "Derivation failed", Toast.LENGTH_LONG).show();
        }
    }

    private class FetchBalanceTask extends AsyncTask<String, Void, Long> {
        private Exception error;

        @Override
        protected Long doInBackground(String... params) {
            try {
                String lcd = params[0];
                String address = params[1];
                return SecretWallet.fetchUscrtBalanceMicro(lcd, address);
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(Long micro) {
            if (error != null) {
                Toast.makeText(WalletActivity.this, "Failed to fetch balance", Toast.LENGTH_LONG).show();
                return;
            }
            if (micro == null) micro = 0L;
            balanceText.setText(SecretWallet.formatScrt(micro));
        }
    }
}