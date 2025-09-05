package com.example.earthwallet.ui.host;

import com.example.earthwallet.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
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

import com.example.earthwallet.ui.pages.wallet.CreateWalletFragment;

public class HostActivity extends AppCompatActivity implements CreateWalletFragment.CreateWalletListener {

    private static final String PREF_FILE = "secret_wallet_prefs";
    private Button navWallet;
    private Button navActions;
    private SharedPreferences securePrefs;

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

        // Initialize secure preferences for app-wide use
        initializeSecurePreferences();
        
        // Choose default start fragment: open Actions if secure wallet exists, otherwise create wallet
        boolean hasWallet = false;
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            hasWallet = !TextUtils.isEmpty(walletsJson) && !"[]".equals(walletsJson.trim());
        } catch (Exception e) {
            SharedPreferences flatPrefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
            String walletsJson = flatPrefs.getString("wallets", "[]");
            hasWallet = !TextUtils.isEmpty(walletsJson) && !"[]".equals(walletsJson.trim());
        }

        if (hasWallet) {
            showFragment("actions");
            setSelectedNav(navActions, navWallet);
        } else {
            showFragment("create_wallet");
            setSelectedNav(navWallet, navActions);
        }

        // Handle intent to show a specific fragment (e.g., from other activities)
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
    
    
    // Make this public so fragments can request navigation without creating a second bottom nav.
    public void showFragment(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
    
        Fragment fragment;
        switch (tag) {
            case "wallet":
                fragment = new com.example.earthwallet.ui.pages.wallet.WalletMainFragment();
                break;
            case "actions":
                fragment = new com.example.earthwallet.ui.nav.ActionsMainFragment();
                break;
            case "scanner":
                fragment = new com.example.earthwallet.ui.pages.anml.ScannerFragment();
                break;
            case "create_wallet":
                CreateWalletFragment createWalletFragment = new com.example.earthwallet.ui.pages.wallet.CreateWalletFragment();
                createWalletFragment.setCreateWalletListener(this);
                fragment = createWalletFragment;
                break;
            case "swap":
                fragment = new com.example.earthwallet.ui.pages.swap.SwapTokensMainFragment();
                break;
            case "anml":
                fragment = new com.example.earthwallet.ui.pages.anml.ANMLClaimMainFragment();
                break;
            case "managelp":
                fragment = new com.example.earthwallet.ui.pages.managelp.ManageLPFragment();
                break;
            default:
                // Default to scanner if an unknown tag is passed
                fragment = new com.example.earthwallet.ui.pages.anml.ScannerFragment();
                break;
        }
    
        ft.replace(R.id.host_content, fragment, tag);
        ft.commitAllowingStateLoss();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        // Handle NFC intents and pass them to ScannerFragment if it's currently shown
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.host_content);
            if (currentFragment instanceof com.example.earthwallet.ui.pages.anml.ScannerFragment) {
                ((com.example.earthwallet.ui.pages.anml.ScannerFragment) currentFragment).handleNfcIntent(intent);
            }
        }
    }

    @Override
    public void onWalletCreated() {
        // Navigate to actions after wallet creation
        showFragment("actions");
        setSelectedNav(navActions, navWallet);
    }

    @Override
    public void onCreateWalletCancelled() {
        // Stay on create wallet or go to scanner
        showFragment("scanner");
        setSelectedNav(navWallet, navActions);
    }

    /**
     * Initialize encrypted shared preferences for app-wide secure wallet access
     */
    private void initializeSecurePreferences() {
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
            // Fallback to regular SharedPreferences if encryption fails
            securePrefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
        }
    }

    /**
     * Get the secure preferences instance for app-wide use
     * @return Secure SharedPreferences instance
     */
    public SharedPreferences getSecurePrefs() {
        return securePrefs;
    }
}