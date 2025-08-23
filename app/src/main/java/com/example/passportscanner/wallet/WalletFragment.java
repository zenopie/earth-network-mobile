package com.example.passportscanner.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

import com.example.passportscanner.R;

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

    private EditText mnemonicInput;
    private EditText lcdInput;
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
        mnemonicInput = view.findViewById(R.id.mnemonic_input);
        lcdInput = view.findViewById(R.id.lcd_input);
        addressText = view.findViewById(R.id.address_text);
        balanceText = view.findViewById(R.id.balance_text);
        addressRow = view.findViewById(R.id.address_row);
        balanceRow = view.findViewById(R.id.balance_row);

        // Initialize buttons
        Button btnGenerate = view.findViewById(R.id.btn_generate);
        Button btnSave = view.findViewById(R.id.btn_save);
        Button btnCopy = view.findViewById(R.id.btn_copy);
        Button btnRefresh = view.findViewById(R.id.btn_refresh);

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

        // Restore saved values
        String savedMnemonic = securePrefs.getString(KEY_MNEMONIC, "");
        String savedLcd = securePrefs.getString(KEY_LCD_URL, SecretWallet.DEFAULT_LCD_URL);
        if (!TextUtils.isEmpty(savedMnemonic)) {
            mnemonicInput.setText(savedMnemonic);
        }
        lcdInput.setText(TextUtils.isEmpty(savedLcd) ? SecretWallet.DEFAULT_LCD_URL : savedLcd);

        // Derive and display address if mnemonic exists
        if (!TextUtils.isEmpty(savedMnemonic)) {
            deriveAndDisplayAddress(savedMnemonic);
        }

        // Set up button click listeners
        if (btnGenerate != null) {
            btnGenerate.setOnClickListener(v -> {
                try {
                    String mnemonic = SecretWallet.generateMnemonic();
                    if (mnemonicInput != null) mnemonicInput.setText(mnemonic);

                    // Do NOT log or persist the mnemonic here
                    Toast.makeText(requireContext(), "Mnemonic generated", Toast.LENGTH_SHORT).show();

                    // Derive and display address
                    deriveAndDisplayAddress(mnemonic);
                } catch (Exception e) {
                    // Keep logs free of mnemonic contents
                    Log.e("WalletFragment", "Failed to generate mnemonic", e);
                    Toast.makeText(requireContext(), "Failed to generate mnemonic", Toast.LENGTH_LONG).show();
                }
            });
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String mnemonic = getMnemonic();
                String lcd = getLcdUrl();
                if (TextUtils.isEmpty(mnemonic)) {
                    Toast.makeText(requireContext(), "Enter mnemonic to save", Toast.LENGTH_SHORT).show();
                    return;
                }
                securePrefs.edit()
                        .putString(KEY_MNEMONIC, mnemonic)
                        .putString(KEY_LCD_URL, lcd)
                        .apply();
                Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show();
            });
        }

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

    private String getMnemonic() {
        return mnemonicInput.getText() != null ? mnemonicInput.getText().toString().trim() : "";
    }

    private String getLcdUrl() {
        String lcd = lcdInput.getText() != null ? lcdInput.getText().toString().trim() : "";
        return TextUtils.isEmpty(lcd) ? SecretWallet.DEFAULT_LCD_URL : lcd;
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