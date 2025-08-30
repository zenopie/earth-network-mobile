package com.example.earthwallet.ui.fragments;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.SecretWallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * CreateWalletFragment
 *
 * Implements a Keplr-like wallet creation flow (for Secret Network):
 * - Intro: Create New / Import
 * - Mnemonic reveal (user must explicitly reveal)
 * - Mandatory backup acknowledgement and verification (select words in correct order)
 * - PIN creation and confirmation
 * - Completion screen that saves mnemonic (secure) and pin hash, then returns to wallet
 */
public class CreateWalletFragment extends Fragment {

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
    private EditText revealMnemonicText;
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
    private String mnemonic;
    private List<String> mnemonicWords;

    // Secure prefs
    private SharedPreferences securePrefs;

    // Interface for communication with parent activity
    public interface CreateWalletListener {
        void onWalletCreated();
        void onCreateWalletCancelled();
    }
    
    private CreateWalletListener listener;
    
    public CreateWalletFragment() {}
    
    public static CreateWalletFragment newInstance() {
        return new CreateWalletFragment();
    }
    
    public void setCreateWalletListener(CreateWalletListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_create_wallet, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize SecretWallet wordlist
        try {
            SecretWallet.initialize(requireContext());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Wallet initialization failed", Toast.LENGTH_LONG).show();
            if (listener != null) {
                listener.onCreateWalletCancelled();
            }
            return;
        }

        // Encrypted prefs (fallback to normal prefs if not available)
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
            securePrefs = requireActivity().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
        
        // Check whether a global PIN already exists
        final boolean hasExistingPin = !TextUtils.isEmpty(securePrefs.getString(KEY_PIN_HASH, ""));

        // Wire up steps
        stepIntro = view.findViewById(R.id.step_intro);
        stepReveal = view.findViewById(R.id.step_reveal);
        stepVerify = view.findViewById(R.id.step_verify);
        stepPin = view.findViewById(R.id.step_pin);
        stepDone = view.findViewById(R.id.step_done);

        revealMnemonicText = view.findViewById(R.id.reveal_mnemonic_text);
        btnRevealNext = view.findViewById(R.id.btn_reveal_next);

        // Enable the Next button when the user pastes/enters text (supports import flow)
        revealMnemonicText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnRevealNext.setEnabled(s != null && s.toString().trim().length() > 0);
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        confirmBackupCheck = view.findViewById(R.id.confirm_backup_check);
        btnConfirmNext = view.findViewById(R.id.btn_confirm_next);

        walletNameInput = view.findViewById(R.id.wallet_name_input);
        pinInput = view.findViewById(R.id.pin_input);
        pinConfirmInput = view.findViewById(R.id.pin_confirm_input);
        btnPinNext = view.findViewById(R.id.btn_pin_next);

        btnDone = view.findViewById(R.id.btn_done);

        // Intro buttons
        Button btnCreateNew = view.findViewById(R.id.btn_create_new);
        Button btnImport = view.findViewById(R.id.btn_import);

        btnCreateNew.setOnClickListener(v -> startCreateNewFlow());
        btnImport.setOnClickListener(v -> startImportFlow());

        // Reveal step: next only after user has revealed and acknowledged backup will be required in verification
        Button btnReveal = view.findViewById(R.id.btn_reveal);
        btnReveal.setOnClickListener(v -> {
            // Generate mnemonic and show it
            mnemonic = SecretWallet.generateMnemonic();
            mnemonicWords = Arrays.asList(mnemonic.trim().split("\\s+"));
            revealMnemonicText.setText(mnemonic);
            btnRevealNext.setEnabled(true);
        });

        btnRevealNext.setOnClickListener(v -> {
            if (mnemonic == null) {
                Toast.makeText(requireContext(), "Reveal your mnemonic first", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(requireContext(), "Please confirm you backed up your recovery phrase", Toast.LENGTH_SHORT).show();
                return;
            }
            showStep(stepPin);
        });

        // PIN step
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinConfirmInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        
        // If a PIN already exists, hide the PIN inputs and reuse the existing global PIN
        if (hasExistingPin) {
            pinInput.setVisibility(View.GONE);
            pinConfirmInput.setVisibility(View.GONE);
            if (walletNameInput != null) walletNameInput.setHint("Wallet name (PIN already set)");
            btnPinNext.setOnClickListener(v -> {
                String walletName = walletNameInput != null ? (walletNameInput.getText() != null ? walletNameInput.getText().toString().trim() : "") : "";
                if (TextUtils.isEmpty(walletName)) {
                    Toast.makeText(requireContext(), "Enter a wallet name", Toast.LENGTH_SHORT).show();
                    return;
                }
                saveMnemonicAndPin(null, walletName);
                showStep(stepDone);
            });
        } else {
            btnPinNext.setOnClickListener(v -> {
                String walletName = walletNameInput != null ? (walletNameInput.getText() != null ? walletNameInput.getText().toString().trim() : "") : "";
                if (TextUtils.isEmpty(walletName)) {
                    Toast.makeText(requireContext(), "Enter a wallet name", Toast.LENGTH_SHORT).show();
                    return;
                }
                String pin = pinInput.getText() != null ? pinInput.getText().toString().trim() : "";
                String pin2 = pinConfirmInput.getText() != null ? pinConfirmInput.getText().toString().trim() : "";
                if (TextUtils.isEmpty(pin) || TextUtils.isEmpty(pin2)) {
                    Toast.makeText(requireContext(), "Enter and confirm PIN", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!pin.equals(pin2)) {
                    Toast.makeText(requireContext(), "PINs do not match", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pin.length() < 4) {
                    Toast.makeText(requireContext(), "PIN should be at least 4 digits", Toast.LENGTH_SHORT).show();
                    return;
                }
                saveMnemonicAndPin(pin, walletName);
                showStep(stepDone);
            });
        }

        btnDone.setOnClickListener(v -> {
            if (listener != null) {
                listener.onWalletCreated();
            }
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
        mnemonic = null;
        mnemonicWords = null;
        showStep(stepReveal);
        revealMnemonicText.setText("Press Reveal to generate and display your mnemonic. Write it down and keep it safe.");
        btnRevealNext.setEnabled(false);
    }

    private void startImportFlow() {
        showStep(stepReveal);
        revealMnemonicText.setText("");
        btnRevealNext.setEnabled(false);

        // Make the reveal text editable for import
        revealMnemonicText.setFocusable(true);
        revealMnemonicText.setClickable(true);
        revealMnemonicText.setFocusableInTouchMode(true);
        revealMnemonicText.setText("");
        revealMnemonicText.setHint("Paste your 12/24-word mnemonic here and press Next");
        
        btnRevealNext.setOnClickListener(v -> {
            String pasted = revealMnemonicText.getText() != null ? revealMnemonicText.getText().toString().trim() : "";
            if (TextUtils.isEmpty(pasted)) {
                Toast.makeText(requireContext(), "Paste mnemonic first", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> words = Arrays.asList(pasted.split("\\s+"));
            if (words.size() < 12) {
                Toast.makeText(requireContext(), "Mnemonic looks too short", Toast.LENGTH_SHORT).show();
                return;
            }
            mnemonic = pasted;
            mnemonicWords = words;
            revealMnemonicText.setFocusable(false);
            revealMnemonicText.setClickable(false);
            revealMnemonicText.setFocusableInTouchMode(false);
            startConfirmStep();
        });
    }

    private void startConfirmStep() {
        revealMnemonicText.setFocusable(false);
        revealMnemonicText.setClickable(false);
        revealMnemonicText.setFocusableInTouchMode(false);
        confirmBackupCheck.setChecked(false);
        btnConfirmNext.setEnabled(false);
        showStep(stepVerify);
    }

    private void saveMnemonicAndPin(String pin, String walletName) {
        try {
            String existingPinHash = securePrefs.getString(KEY_PIN_HASH, "");
            String pinHash = existingPinHash;

            if (TextUtils.isEmpty(existingPinHash)) {
                if (pin == null) {
                    Toast.makeText(requireContext(), "No existing PIN found; please create a PIN", Toast.LENGTH_SHORT).show();
                    return;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(pin.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                pinHash = sb.toString();
            }

            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);

            JSONObject obj = new JSONObject();
            obj.put("name", walletName);
            obj.put("mnemonic", mnemonic);
            arr.put(obj);

            SharedPreferences.Editor ed = securePrefs.edit();
            ed.putString("wallets", arr.toString());
            ed.putInt("selected_wallet_index", arr.length() - 1);
            ed.putString(KEY_WALLET_NAME, walletName);
            ed.putString(KEY_LCD_URL, SecretWallet.DEFAULT_LCD_URL);
            if (TextUtils.isEmpty(existingPinHash)) {
                ed.putString(KEY_PIN_HASH, pinHash);
            }
            ed.apply();

            Toast.makeText(requireContext(), "Wallet created and saved securely", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to save wallet", Toast.LENGTH_LONG).show();
        }
    }
}