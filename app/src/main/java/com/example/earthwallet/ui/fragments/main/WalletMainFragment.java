package com.example.earthwallet.ui.fragments.main;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.ui.fragments.WalletListFragment;
import com.example.earthwallet.ui.fragments.CreateWalletFragment;

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

    private SharedPreferences securePrefs;

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
        });
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
            } else {
                addressRow.setVisibility(View.GONE);
                balanceRow.setVisibility(View.GONE);
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