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

    private EditText mnemonicInput;
    private EditText lcdInput;
    private TextView addressText;
    private TextView balanceText;
    private View addressRow;
    private View balanceRow;

    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallet);

        mnemonicInput = findViewById(R.id.mnemonic_input);
        lcdInput = findViewById(R.id.lcd_input);
        addressText = findViewById(R.id.address_text);
        balanceText = findViewById(R.id.balance_text);
        addressRow = findViewById(R.id.address_row);
        balanceRow = findViewById(R.id.balance_row);

        Button btnGenerate = findViewById(R.id.btn_generate);
        Button btnDerive = findViewById(R.id.btn_derive);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnCopy = findViewById(R.id.btn_copy);
        Button btnRefresh = findViewById(R.id.btn_refresh);

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

        // Restore saved values
        String savedMnemonic = securePrefs.getString(KEY_MNEMONIC, "");
        String savedLcd = securePrefs.getString(KEY_LCD_URL, SecretWallet.DEFAULT_LCD_URL);
        if (!TextUtils.isEmpty(savedMnemonic)) {
            mnemonicInput.setText(savedMnemonic);
        }
        lcdInput.setText(TextUtils.isEmpty(savedLcd) ? SecretWallet.DEFAULT_LCD_URL : savedLcd);

        if (!TextUtils.isEmpty(savedMnemonic)) {
            deriveAndDisplayAddress(savedMnemonic);
        }

        btnGenerate.setOnClickListener(v -> {
            try {
                String mnemonic = SecretWallet.generateMnemonic();
                mnemonicInput.setText(mnemonic);
                Toast.makeText(this, "Mnemonic generated", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to generate mnemonic", Toast.LENGTH_LONG).show();
            }
        });

        btnDerive.setOnClickListener(v -> {
            String mnemonic = getMnemonic();
            if (TextUtils.isEmpty(mnemonic)) {
                Toast.makeText(this, "Enter mnemonic", Toast.LENGTH_SHORT).show();
                return;
            }
            deriveAndDisplayAddress(mnemonic);
        });

        btnSave.setOnClickListener(v -> {
            String mnemonic = getMnemonic();
            String lcd = getLcdUrl();
            if (TextUtils.isEmpty(mnemonic)) {
                Toast.makeText(this, "Enter mnemonic to save", Toast.LENGTH_SHORT).show();
                return;
            }
            securePrefs.edit()
                    .putString(KEY_MNEMONIC, mnemonic)
                    .putString(KEY_LCD_URL, lcd)
                    .apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });

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

        btnRefresh.setOnClickListener(v -> {
            String address = addressText.getText() != null ? addressText.getText().toString().trim() : "";
            if (TextUtils.isEmpty(address)) {
                Toast.makeText(this, "Derive address first", Toast.LENGTH_SHORT).show();
                return;
            }
            String lcd = getLcdUrl();
            new FetchBalanceTask().execute(lcd, address);
        });

        // Bottom navigation wiring
        View navWallet = findViewById(R.id.btn_nav_wallet);
        if (navWallet != null) {
            // Already on Wallet
            navWallet.setEnabled(false);
        }
        View navActions = findViewById(R.id.btn_nav_actions);
        if (navActions != null) {
            navActions.setOnClickListener(v -> {
                Intent a = new Intent(WalletActivity.this, ActionsActivity.class);
                startActivity(a);
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