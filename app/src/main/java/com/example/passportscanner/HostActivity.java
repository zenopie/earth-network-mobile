package com.example.passportscanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

public class HostActivity extends AppCompatActivity {

    private static final String PREF_FILE = "secret_wallet_prefs";
    private Button navWallet;
    private Button navActions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host);

        navWallet = findViewById(R.id.btn_nav_wallet);
        navActions = findViewById(R.id.btn_nav_actions);

        // Wire nav buttons to swap fragments
        navWallet.setOnClickListener(v -> {
            showFragment("wallet");
            setSelectedNav(navWallet, navActions);
        });
        navActions.setOnClickListener(v -> {
            showFragment("actions");
            setSelectedNav(navActions, navWallet);
        });

        // Choose default start fragment: open Actions if secure wallet exists, otherwise scanner
        boolean hasWallet = false;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            String savedMnemonic = securePrefs.getString("mnemonic", "");
            hasWallet = !TextUtils.isEmpty(savedMnemonic);
        } catch (Exception e) {
            SharedPreferences flatPrefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
            String savedMnemonic = flatPrefs.getString("mnemonic", "");
            hasWallet = !TextUtils.isEmpty(savedMnemonic);
        }

        if (hasWallet) {
            showFragment("actions");
            setSelectedNav(navActions, navWallet);
        } else {
            showFragment("scanner");
            setSelectedNav(navWallet, navActions);
        }

        // Handle intent to show a specific fragment (e.g., from MRZInputActivity)
        if (getIntent() != null && getIntent().hasExtra("fragment_to_show")) {
            String fragmentToShow = getIntent().getStringExtra("fragment_to_show");
            showFragment(fragmentToShow);
            if ("wallet".equals(fragmentToShow)) {
                setSelectedNav(navWallet, navActions);
            } else if ("actions".equals(fragmentToShow)) {
                setSelectedNav(navActions, navWallet);
            } else {
                setSelectedNav(navWallet, navActions); // Default to scanner if unknown
            }
        }
    }

    private void setSelectedNav(View selected, View other) {
        selected.setSelected(true);
        other.setSelected(false);
    }

    private void showFragment(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();

        Fragment fragment;
        switch (tag) {
            case "wallet":
                fragment = new com.example.passportscanner.wallet.WalletFragment();
                break;
            case "actions":
                fragment = new com.example.passportscanner.ActionsFragment();
                break;
            case "scanner":
                fragment = new com.example.passportscanner.ScannerFragment();
                break;
            default:
                // Default to scanner if an unknown tag is passed
                fragment = new com.example.passportscanner.ScannerFragment();
                break;
        }

        ft.replace(R.id.host_content, fragment, tag);
        ft.commitAllowingStateLoss();
    }
}