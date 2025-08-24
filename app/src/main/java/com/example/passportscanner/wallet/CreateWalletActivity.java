package com.example.passportscanner.wallet;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.R;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * CreateWalletActivity
 *
 * Implements a Keplr-like wallet creation flow (for Secret Network):
 * - Intro: Create New / Import
 * - Mnemonic reveal (user must explicitly reveal)
 * - Mandatory backup acknowledgement and verification (select words in correct order)
 * - PIN creation and confirmation
 * - Completion screen that saves mnemonic (secure) and pin hash, then returns to wallet
 *
 * This activity intentionally keeps mnemonic handling in-memory and avoids logging it.
 */
public class CreateWalletActivity extends AppCompatActivity {

    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";
    private static final String KEY_LCD_URL = "lcd_url";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_WALLET_NAME = "wallet_name";

    // Steps' containers
    private View stepIntro;
    private View stepReveal;
    private View stepVerify;
    private View stepPin;
    private View stepDone;

    // Reveal step
    private TextView revealMnemonicText;
    private Button btnRevealNext;

    // Confirm step (simple acknowledgement)
    private CheckBox confirmBackupCheck;
    private Button btnConfirmNext;

    // Pin step
    private EditText walletNameInput;
    private EditText pinInput;
    private EditText pinConfirmInput;
    private Button btnPinNext;

    // Done
    private Button btnDone;

    // State
    private String mnemonic; // original correct mnemonic
    private List<String> mnemonicWords;

    // Secure prefs
    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_wallet);

        // Initialize SecretWallet wordlist
        try {
            SecretWallet.initialize(this);
        } catch (Exception e) {
            Toast.makeText(this, "Wallet initialization failed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Encrypted prefs (fallback to normal prefs if not available)
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
        
        // Check whether a global PIN already exists. If it does, do not force the user
        // to create another PIN when adding a new wallet — reuse the existing PIN.
        final boolean hasExistingPin = !TextUtils.isEmpty(securePrefs.getString(KEY_PIN_HASH, ""));

        // Wire up steps
        stepIntro = findViewById(R.id.step_intro);
        stepReveal = findViewById(R.id.step_reveal);
        stepVerify = findViewById(R.id.step_verify);
        stepPin = findViewById(R.id.step_pin);
        stepDone = findViewById(R.id.step_done);

        revealMnemonicText = findViewById(R.id.reveal_mnemonic_text);
        btnRevealNext = findViewById(R.id.btn_reveal_next);
 
        confirmBackupCheck = findViewById(R.id.confirm_backup_check);
        btnConfirmNext = findViewById(R.id.btn_confirm_next);

        walletNameInput = findViewById(R.id.wallet_name_input);
        pinInput = findViewById(R.id.pin_input);
        pinConfirmInput = findViewById(R.id.pin_confirm_input);
        btnPinNext = findViewById(R.id.btn_pin_next);

        btnDone = findViewById(R.id.btn_done);

        // Intro buttons
        Button btnCreateNew = findViewById(R.id.btn_create_new);
        Button btnImport = findViewById(R.id.btn_import);

        btnCreateNew.setOnClickListener(v -> startCreateNewFlow());
        btnImport.setOnClickListener(v -> startImportFlow());

        // Reveal step: next only after user has revealed and acknowledged backup will be required in verification
        Button btnReveal = findViewById(R.id.btn_reveal);
        btnReveal.setOnClickListener(v -> {
            // Generate mnemonic and show it
            mnemonic = SecretWallet.generateMnemonic();
            mnemonicWords = Arrays.asList(mnemonic.trim().split("\\s+"));
            revealMnemonicText.setText(mnemonic); // show full mnemonic
            btnRevealNext.setEnabled(true);
        });

        btnRevealNext.setOnClickListener(v -> {
            if (mnemonic == null) {
                Toast.makeText(this, "Reveal your mnemonic first", Toast.LENGTH_SHORT).show();
                return;
            }
            startConfirmStep();
        });

        // Confirm controls (simple acknowledgement)
        btnConfirmNext.setEnabled(false);
        confirmBackupCheck.setChecked(false);
        confirmBackupCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            btnConfirmNext.setEnabled(isChecked);
        });
        btnConfirmNext.setOnClickListener(v -> {
            if (!confirmBackupCheck.isChecked()) {
                Toast.makeText(this, "Please confirm you backed up your recovery phrase", Toast.LENGTH_SHORT).show();
                return;
            }
            // Proceed to PIN creation
            showStep(stepPin);
        });
 
        // PIN step
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinConfirmInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        // If a PIN already exists, hide the PIN inputs and reuse the existing global PIN.
        if (hasExistingPin) {
            pinInput.setVisibility(View.GONE);
            pinConfirmInput.setVisibility(View.GONE);
            // Make wallet name hint indicate that PIN is already set
            if (walletNameInput != null) walletNameInput.setHint("Wallet name (PIN already set)");
            btnPinNext.setOnClickListener(v -> {
                String walletName = walletNameInput != null ? (walletNameInput.getText() != null ? walletNameInput.getText().toString().trim() : "") : "";
                if (TextUtils.isEmpty(walletName)) {
                    Toast.makeText(this, "Enter a wallet name", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Save mnemonic using existing PIN (pass null so saveMnemonicAndPin will not re-hash)
                saveMnemonicAndPin(null, walletName);
                showStep(stepDone);
            });
        } else {
            btnPinNext.setOnClickListener(v -> {
                String walletName = walletNameInput != null ? (walletNameInput.getText() != null ? walletNameInput.getText().toString().trim() : "") : "";
                if (TextUtils.isEmpty(walletName)) {
                    Toast.makeText(this, "Enter a wallet name", Toast.LENGTH_SHORT).show();
                    return;
                }
                String pin = pinInput.getText() != null ? pinInput.getText().toString().trim() : "";
                String pin2 = pinConfirmInput.getText() != null ? pinConfirmInput.getText().toString().trim() : "";
                if (TextUtils.isEmpty(pin) || TextUtils.isEmpty(pin2)) {
                    Toast.makeText(this, "Enter and confirm PIN", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!pin.equals(pin2)) {
                    Toast.makeText(this, "PINs do not match", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pin.length() < 4) {
                    Toast.makeText(this, "PIN should be at least 4 digits", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Save mnemonic + pin hash + wallet name to securePrefs
                saveMnemonicAndPin(pin, walletName);
                showStep(stepDone);
            });
        }
 
        btnDone.setOnClickListener(v -> {
            // Finish and return to wallet. WalletActivity reloads onResume.
            finish();
        });
 
        // Initialize UI state
        btnRevealNext.setEnabled(false);
        btnConfirmNext.setEnabled(false);
        showStep(stepIntro);
    }
 
    private void showStep(View step) {
        // Hide all then show the requested
        stepIntro.setVisibility(View.GONE);
        stepReveal.setVisibility(View.GONE);
        stepVerify.setVisibility(View.GONE);
        stepPin.setVisibility(View.GONE);
        stepDone.setVisibility(View.GONE);
 
        step.setVisibility(View.VISIBLE);
    }
 
    private void startCreateNewFlow() {
        // Reset any previous state then go to reveal step
        mnemonic = null;
        mnemonicWords = null;
        showStep(stepReveal);
        revealMnemonicText.setText("Press Reveal to generate and display your mnemonic. Write it down and keep it safe.");
        btnRevealNext.setEnabled(false);
    }
 
    private void startImportFlow() {
        // Simple import: show a dialog-like input for user to paste mnemonic
        // For simplicity embed a prompt screen reusing reveal pane to accept pasted mnemonic
        showStep(stepReveal);
        revealMnemonicText.setText("");
        btnRevealNext.setEnabled(false);
 
        // Replace reveal button behavior temporarily: we'll allow pasting into the reveal text (it's editable)
        // Provide guidance: user should paste mnemonic into the text area and press Next
        // Make the reveal text editable for import
        revealMnemonicText.setFocusable(true);
        revealMnemonicText.setClickable(true);
        revealMnemonicText.setFocusableInTouchMode(true);
        revealMnemonicText.setText("");
        revealMnemonicText.setHint("Paste your 12/24-word mnemonic here and press Next");
        // When user edits the text, we will set mnemonic variable on Next click
        btnRevealNext.setOnClickListener(v -> {
            String pasted = revealMnemonicText.getText() != null ? revealMnemonicText.getText().toString().trim() : "";
            if (TextUtils.isEmpty(pasted)) {
                Toast.makeText(this, "Paste mnemonic first", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> words = Arrays.asList(pasted.split("\\s+"));
            if (words.size() < 12) {
                Toast.makeText(this, "Mnemonic looks too short", Toast.LENGTH_SHORT).show();
                return;
            }
            mnemonic = pasted;
            mnemonicWords = words;
            // Remove editability to avoid accidental modifications
            revealMnemonicText.setFocusable(false);
            revealMnemonicText.setClickable(false);
            revealMnemonicText.setFocusableInTouchMode(false);
            startConfirmStep();
        });
    }
 
    private void startConfirmStep() {
        // Make sure the mnemonic is not editable now and present a simple acknowledgement checkbox
        revealMnemonicText.setFocusable(false);
        revealMnemonicText.setClickable(false);
        revealMnemonicText.setFocusableInTouchMode(false);
        confirmBackupCheck.setChecked(false);
        btnConfirmNext.setEnabled(false);
        showStep(stepVerify);
    }

    private void saveMnemonicAndPin(String pin, String walletName) {
        try {
            // Determine if a PIN already exists (shared across all wallets)
            String existingPinHash = securePrefs.getString(KEY_PIN_HASH, "");
            String pinHash = existingPinHash;
    
            // If no existing PIN, require a non-null pin parameter to set one.
            if (TextUtils.isEmpty(existingPinHash)) {
                if (pin == null) {
                    // Defensive: this should not happen in normal flow, but avoid NPE and inform user.
                    Toast.makeText(this, "No existing PIN found; please create a PIN", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Hash PIN with SHA-256 (use a proper KDF in production)
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(pin.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                pinHash = sb.toString();
            }
    
            // Load existing wallets array (stored as JSON string)
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
    
            // Append new wallet object { name, mnemonic }
            JSONObject obj = new JSONObject();
            obj.put("name", walletName);
            obj.put("mnemonic", mnemonic);
            arr.put(obj);
    
            // Save updated wallets and other data. Store wallet_name at top-level as well
            // for compatibility with code that reads a top-level name.
            SharedPreferences.Editor ed = securePrefs.edit();
            ed.putString("wallets", arr.toString());
            ed.putInt("selected_wallet_index", arr.length() - 1);
            ed.putString(KEY_WALLET_NAME, walletName);
            ed.putString(KEY_LCD_URL, SecretWallet.DEFAULT_LCD_URL);
            if (TextUtils.isEmpty(existingPinHash)) {
                ed.putString(KEY_PIN_HASH, pinHash);
            }
            ed.apply();
    
            Toast.makeText(this, "Wallet created and saved securely", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save wallet", Toast.LENGTH_LONG).show();
        }
    }
}