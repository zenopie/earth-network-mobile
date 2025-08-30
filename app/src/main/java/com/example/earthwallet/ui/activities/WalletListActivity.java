package com.example.earthwallet.ui.activities;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.ui.fragments.WalletListFragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * WalletListActivity
 *
 * Lists saved wallets (from "wallets" JSON array in secure prefs) and shows address,
 * with Show (requires PIN) and Delete (with irreversible confirmation) actions.
 */
public class WalletListActivity extends AppCompatActivity {

    private static final String PREF_FILE = "secret_wallet_prefs";

    private SharedPreferences securePrefs;
    private LinearLayout container;
    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallet_list);

        container = findViewById(R.id.wallet_list_container);
        inflater = LayoutInflater.from(this);

        try {
            SecretWallet.initialize(this);
        } catch (Exception e) {
            Toast.makeText(this, "Wallet initialization failed", Toast.LENGTH_LONG).show();
            // continue; address derivation may fail later
        }

        initSecurePrefs();
        loadAndRenderWallets();
    }

    private void initSecurePrefs() {
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
            securePrefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
    }

    private void loadAndRenderWallets() {
        container.removeViews(1, Math.max(0, container.getChildCount() - 1)); // keep title, clear rows if any
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            if (arr.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("No wallets found");
                empty.setPadding(0, 16, 0, 0);
                container.addView(empty);
                return;
            }

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                final int index = i;
                final String walletName = obj.optString("name", "Wallet " + i);
                String mnemonic = obj.optString("mnemonic", "");
                String address = "";
                if (!TextUtils.isEmpty(mnemonic)) {
                    try {
                        address = SecretWallet.getAddressFromMnemonic(mnemonic);
                    } catch (Exception ignored) { address = ""; }
                }
                final String finalAddress = address;
 
                View row = inflater.inflate(R.layout.item_wallet_row, container, false);
                TextView tvName = row.findViewById(R.id.wallet_row_name);
                TextView tvAddr = row.findViewById(R.id.wallet_row_address);
                Button btnShow = row.findViewById(R.id.wallet_row_show);
                Button btnDelete = row.findViewById(R.id.wallet_row_delete);
 
                tvName.setText(walletName);
                tvAddr.setText(TextUtils.isEmpty(finalAddress) ? "No address" : finalAddress);
 
                // Show mnemonic -> prompt for PIN and reveal mnemonic if PIN valid
                btnShow.setOnClickListener(v -> {
                    askPinAndShowMnemonic(index);
                });
 
                // Delete -> confirm irreversible, then delete
                btnDelete.setOnClickListener(v -> {
                    new AlertDialog.Builder(WalletListActivity.this)
                            .setTitle("Delete wallet")
                            .setMessage("This action is irreversible. Delete wallet '" + walletName + "'?")
                            .setPositiveButton("Delete", (dlg, which) -> {
                                deleteWalletAtIndex(index);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
 
                // Long-press copy address
                tvAddr.setOnLongClickListener(v -> {
                    if (!TextUtils.isEmpty(finalAddress)) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("address", finalAddress));
                            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
                        }
                    }
                    return true;
                });
 
                // Allow tapping the row to select it as the active wallet
                row.setOnClickListener(v -> {
                    try {
                        securePrefs.edit().putInt("selected_wallet_index", index).apply();
                        Toast.makeText(WalletListActivity.this, "Selected wallet: " + walletName, Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                    // Finish the activity so callers return to the previous screen showing the selected wallet
                    finish();
                });
 
                container.addView(row);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load wallets", Toast.LENGTH_SHORT).show();
        }
    }

    private void askPinAndShowMnemonic(int walletIndex) {
        final EditText pinField = new EditText(this);
        pinField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setHint("PIN");

        new AlertDialog.Builder(this)
                .setTitle("Enter PIN")
                .setView(pinField)
                .setPositiveButton("OK", (dlg, which) -> {
                    String pin = pinField.getText() != null ? pinField.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(pin)) {
                        Toast.makeText(this, "Enter PIN", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        byte[] digest = md.digest(pin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        for (byte b : digest) sb.append(String.format("%02x", b));
                        String pinHash = sb.toString();
                        String stored = securePrefs.getString("pin_hash", "");
                        if (stored != null && stored.equals(pinHash)) {
                            // reveal mnemonic for the selected wallet
                            String walletsJson = securePrefs.getString("wallets", "[]");
                            JSONArray arr = new JSONArray(walletsJson);
                            if (walletIndex >= 0 && walletIndex < arr.length()) {
                                String mnem = arr.getJSONObject(walletIndex).optString("mnemonic", "");
                                if (TextUtils.isEmpty(mnem)) {
                                    Toast.makeText(this, "No mnemonic stored", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                TextView tv = new TextView(this);
                                tv.setText(mnem);
                                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                                tv.setPadding(pad, pad, pad, pad);
                                tv.setTextIsSelectable(true);
                                new AlertDialog.Builder(this)
                                        .setTitle("Recovery Phrase")
                                        .setView(tv)
                                        .setPositiveButton("Close", null)
                                        .show();
                            } else {
                                Toast.makeText(this, "Wallet not found", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Error checking PIN", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteWalletAtIndex(int index) {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            if (index < 0 || index >= arr.length()) {
                Toast.makeText(this, "Invalid wallet index", Toast.LENGTH_SHORT).show();
                return;
            }
            // Remove element
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (i == index) continue;
                newArr.put(arr.getJSONObject(i));
            }

            // Update selected index
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            int newSel = sel;
            if (arr.length() == 1) {
                // deleting the only wallet
                newSel = -1;
            } else {
                if (sel == index) {
                    // choose previous wallet if available, otherwise 0
                    newSel = Math.max(0, index - 1);
                } else if (sel > index) {
                    newSel = sel - 1;
                }
            }

            SharedPreferences.Editor ed = securePrefs.edit();
            ed.putString("wallets", newArr.toString());
            ed.putInt("selected_wallet_index", newSel);
            if (newArr.length() > 0) {
                // update top-level wallet_name for compatibility
                ed.putString("wallet_name", newArr.getJSONObject(Math.max(0, newSel)).optString("name", ""));
            } else {
                ed.remove("wallet_name");
            }
            ed.apply();

            Toast.makeText(this, "Wallet deleted", Toast.LENGTH_SHORT).show();
            // Refresh UI
            loadAndRenderWallets();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to delete wallet", Toast.LENGTH_SHORT).show();
        }
    }
}