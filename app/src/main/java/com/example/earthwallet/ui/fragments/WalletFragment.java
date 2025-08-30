package com.example.earthwallet.ui.fragments;

import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.ui.activities.CreateWalletActivity;
import com.example.earthwallet.ui.activities.WalletListActivity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;

import org.bitcoinj.core.ECKey;
import android.util.Log;

/**
 * WalletFragment reuses the wallet layout when used inside HostActivity.
 * It implements full wallet functionality including persistence, similar to WalletActivity.
 */
public class WalletFragment extends Fragment {

    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";
    private static final String KEY_LCD_URL = "lcd_url";

    private TextView addressText;
    private TextView balanceText;
    private View addressRow;
    private View balanceRow;

    private SharedPreferences securePrefs;

    public WalletFragment() {
        // Required empty public constructor
    }

    public static WalletFragment newInstance() {
        return new WalletFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the existing wallet layout. HostActivity provides overall nav.
        return inflater.inflate(R.layout.activity_wallet, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize SecretWallet with context
        try {
            SecretWallet.initialize(requireContext());
        } catch (Exception e) {
            Log.e("WalletFragment", "Failed to initialize SecretWallet", e);
            Toast.makeText(requireContext(), "Wallet initialization failed", Toast.LENGTH_LONG).show();
            return;
        }
        
        // Initialize UI elements
        addressText = view.findViewById(R.id.address_text);
        balanceText = view.findViewById(R.id.balance_text);
        addressRow = view.findViewById(R.id.address_row);
        balanceRow = view.findViewById(R.id.balance_row);
 
        // Initialize wallet selector + buttons
        final android.widget.TextView currentWalletName = view.findViewById(R.id.current_wallet_name);
        View btnAddWallet = null; // Add now handled in WalletListFragment
        Button btnCopy = view.findViewById(R.id.btn_copy);
        Button btnRefresh = view.findViewById(R.id.btn_refresh);
        View btnShowMnemonic = null; // Show moved to wallet list

        // Start hidden like WalletActivity
        if (addressRow != null) addressRow.setVisibility(View.GONE);
        if (balanceRow != null) balanceRow.setVisibility(View.GONE);

        // Secure preferences initialization with fallback
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
            Log.e("WalletFragment", "Secure storage init failed, using fallback", e);
            // Fallback to normal SharedPreferences if necessary (not secure)
            securePrefs = requireActivity().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }

        // Restore saved wallets array (preferred) and top-level wallet_name for compatibility.
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            String savedWalletName = securePrefs.getString("wallet_name", "");
            if (arr.length() == 0) {
                // fall back to top-level mnemonic if present (legacy)
                String savedMnemonic = securePrefs.getString(KEY_MNEMONIC, "");
                if (!TextUtils.isEmpty(savedWalletName) && currentWalletName != null) {
                    currentWalletName.setText(savedWalletName);
                }
                if (!TextUtils.isEmpty(savedMnemonic)) {
                    deriveAndDisplayAddress(savedMnemonic);
                }
            } else {
                if (sel < 0 || sel >= arr.length()) sel = 0;
                org.json.JSONObject obj = arr.getJSONObject(sel);
                String name = obj.optString("name", "Wallet");
                String mn = obj.optString("mnemonic", "");
                if (currentWalletName != null) currentWalletName.setText(name);
                if (!TextUtils.isEmpty(mn)) {
                    deriveAndDisplayAddress(mn);
                }
            }
        } catch (Exception e) {
            Log.e("WalletFragment", "Failed to restore wallets", e);
        }
  
        // Set up button click listeners
        // Add wallet removed from fragment UI - use WalletListFragment to add wallets
  
        // If hosted inside HostActivity, use its shared bottom nav by showing the wallet_list fragment.
        // Otherwise fall back to starting the standalone WalletListActivity.
        View arrow = view.findViewById(R.id.current_wallet_arrow);
        View[] triggers = new View[] { currentWalletName, arrow };
        for (View t : triggers) {
            if (t != null) {
                t.setOnClickListener(v -> {
                    if (getActivity() != null && getActivity() instanceof com.example.earthwallet.ui.activities.HostActivity) {
                        ((com.example.earthwallet.ui.activities.HostActivity) getActivity()).showFragment("wallet_list");
                    } else {
                        Intent i = new Intent(requireContext(), com.example.earthwallet.ui.activities.WalletListActivity.class);
                        startActivity(i);
                    }
                });
            }
        }
  
        // "Show mnemonic" removed from fragment UI. Use WalletListActivity to show/delete wallets.
 
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                CharSequence addr = addressText.getText();
                if (TextUtils.isEmpty(addr)) {
                    Toast.makeText(requireContext(), "No address", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("address", addr));
                    Toast.makeText(requireContext(), "Address copied", Toast.LENGTH_SHORT).show();
                }
            });
        }
 
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                String address = addressText.getText() != null ? addressText.getText().toString().trim() : "";
                if (TextUtils.isEmpty(address)) {
                    Toast.makeText(requireContext(), "Derive address first", Toast.LENGTH_SHORT).show();
                    return;
                }
                String lcd = getLcdUrl();
                new FetchBalanceTask().execute(lcd, address);
            });
        }
    }
 
    @Override
    public void onResume() {
        super.onResume();
        // Refresh UI from wallets array in secure prefs in case CreateWalletActivity or WalletListActivity changed data
        refreshWalletsUI();
    }

    private String getMnemonic() {
        String saved = securePrefs != null ? securePrefs.getString(KEY_MNEMONIC, "") : "";
        return TextUtils.isEmpty(saved) ? "" : saved;
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
            Toast.makeText(requireContext(), "Derivation failed", Toast.LENGTH_LONG).show();
        }
    }
 
    private void refreshWalletsUI() {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            if (arr.length() == 0) {
                if (getView() != null) {
                    TextView current = getView().findViewById(R.id.current_wallet_name);
                    if (current != null) current.setText("No wallet");
                }
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
            if (getView() != null) {
                TextView current = getView().findViewById(R.id.current_wallet_name);
                if (current != null) current.setText(name);
            }
            if (!TextUtils.isEmpty(mn)) {
                deriveAndDisplayAddress(mn);
            } else {
                addressRow.setVisibility(View.GONE);
                balanceRow.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e("WalletFragment", "Failed to refresh wallets UI", e);
        }
    }
 
    private void showWalletPicker() {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
            if (arr.length() == 0) {
                Toast.makeText(requireContext(), "No wallets", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) names[i] = arr.getJSONObject(i).optString("name", "Wallet " + i);
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Select wallet")
                    .setItems(names, (dlg, which) -> {
                        securePrefs.edit().putInt("selected_wallet_index", which).apply();
                        refreshWalletsUI();
                    })
                    .show();
        } catch (Exception e) {
            Log.e("WalletFragment", "Failed to show wallet picker", e);
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
                Toast.makeText(requireContext(), "Failed to fetch balance", Toast.LENGTH_LONG).show();
                return;
            }
            if (micro == null) micro = 0L;
            balanceText.setText(SecretWallet.formatScrt(micro));
        }
    }
}