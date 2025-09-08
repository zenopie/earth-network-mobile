package com.example.earthwallet.ui.host;

import com.example.earthwallet.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

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
    private View bottomNavView;
    private View hostContent;
    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host);

        navWallet = findViewById(R.id.btn_nav_wallet);
        navActions = findViewById(R.id.btn_nav_actions);
        hostContent = findViewById(R.id.host_content);
        
        // Find bottom navigation view - it's included via <include> tag
        bottomNavView = findViewById(R.id.bottom_nav);

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
        showFragment(tag, null);
    }
    
    public void showFragment(String tag, Bundle arguments) {
        android.util.Log.d("HostActivity", "showFragment called with tag: " + tag);
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
    
        Fragment fragment;
        switch (tag) {
            case "wallet":
                fragment = new com.example.earthwallet.ui.pages.wallet.WalletMainFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "actions":
                fragment = new com.example.earthwallet.ui.nav.ActionsMainFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "scanner":
                fragment = new com.example.earthwallet.ui.pages.anml.ScannerFragment();
                // Hide navigation and status bar for scanner
                android.util.Log.d("HostActivity", "HIDING NAVIGATION AND STATUS BAR FOR SCANNER");
                hideBottomNavigation();
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "mrz_input":
                android.util.Log.d("HostActivity", "Creating MRZInputFragment");
                fragment = new com.example.earthwallet.ui.pages.anml.MRZInputFragment();
                break;
            case "camera_mrz_scanner":
                fragment = new com.example.earthwallet.ui.pages.anml.CameraMRZScannerFragment();
                break;
            case "scan_failure":
                fragment = new com.example.earthwallet.ui.pages.anml.ScanFailureFragment();
                if (arguments != null) {
                    fragment.setArguments(arguments);
                }
                break;
            case "create_wallet":
                CreateWalletFragment createWalletFragment = new com.example.earthwallet.ui.pages.wallet.CreateWalletFragment();
                createWalletFragment.setCreateWalletListener(this);
                fragment = createWalletFragment;
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "swap":
                fragment = new com.example.earthwallet.ui.pages.swap.SwapTokensMainFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "anml":
                fragment = new com.example.earthwallet.ui.pages.anml.ANMLClaimMainFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "managelp":
                fragment = new com.example.earthwallet.ui.pages.managelp.ManageLPFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "staking":
                fragment = new com.example.earthwallet.ui.pages.staking.StakeEarthFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "send":
                fragment = new com.example.earthwallet.ui.pages.wallet.SendTokensFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "receive":
                fragment = new com.example.earthwallet.ui.pages.wallet.ReceiveTokensFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "governance":
                fragment = new com.example.earthwallet.ui.pages.governance.GovernanceFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "caretaker_fund":
                fragment = new com.example.earthwallet.ui.pages.governance.CaretakerFundFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            case "deflation_fund":
                fragment = new com.example.earthwallet.ui.pages.governance.DeflationFundFragment();
                // Show navigation and status bar for normal fragments
                showBottomNavigation();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                break;
            default:
                // Default to scanner if an unknown tag is passed
                fragment = new com.example.earthwallet.ui.pages.anml.ScannerFragment();
                // Hide navigation and status bar for scanner
                hideBottomNavigation();
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
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
    
    /**
     * Hide bottom navigation and adjust content layout
     */
    public void hideBottomNavigation() {
        try {
            android.util.Log.d("HostActivity", "hideBottomNavigation called - bottomNavView: " + (bottomNavView != null));
            if (bottomNavView != null) {
                bottomNavView.setVisibility(View.GONE);
                android.util.Log.d("HostActivity", "Set bottomNavView visibility to GONE");
            }
            if (hostContent != null) {
                android.util.Log.d("HostActivity", "hostContent layoutParams type: " + hostContent.getLayoutParams().getClass().getSimpleName());
                ViewGroup.LayoutParams layoutParams = hostContent.getLayoutParams();
                if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                    // Remove bottom margin to make content full screen
                    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) layoutParams;
                    android.util.Log.d("HostActivity", "Original bottom margin: " + params.bottomMargin);
                    params.bottomMargin = 0;
                    hostContent.setLayoutParams(params);
                    android.util.Log.d("HostActivity", "Set bottom margin to 0");
                } else {
                    android.util.Log.d("HostActivity", "LayoutParams is not MarginLayoutParams, cannot set margin");
                }
            }
        } catch (Exception e) {
            android.util.Log.e("HostActivity", "Error hiding bottom navigation", e);
        }
    }
    
    /**
     * Show bottom navigation and restore content layout
     */
    public void showBottomNavigation() {
        try {
            android.util.Log.d("HostActivity", "showBottomNavigation called - STACK TRACE:");
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (int i = 0; i < Math.min(stackTrace.length, 8); i++) {
                android.util.Log.d("HostActivity", "  " + stackTrace[i].toString());
            }
            
            if (bottomNavView != null) {
                bottomNavView.setVisibility(View.VISIBLE);
                android.util.Log.d("HostActivity", "Set bottomNavView visibility to VISIBLE");
            }
            if (hostContent != null && hostContent.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                // Restore bottom margin for navigation
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) hostContent.getLayoutParams();
                params.bottomMargin = (int) (56 * getResources().getDisplayMetrics().density); // 56dp in pixels
                hostContent.setLayoutParams(params);
                android.util.Log.d("HostActivity", "Restored bottom margin to 56dp");
            }
        } catch (Exception e) {
            android.util.Log.e("HostActivity", "Error showing bottom navigation", e);
        }
    }
    
    /**
     * Set screen orientation to landscape
     */
    public void setLandscapeOrientation() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
    
    /**
     * Set screen orientation to portrait
     */
    public void setPortraitOrientation() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }
    
    @Override
    public void onBackPressed() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.host_content);
        if (currentFragment != null) {
            String currentTag = currentFragment.getTag();
            android.util.Log.d("HostActivity", "Back pressed, current fragment tag: " + currentTag);
            
            // Actions pages should navigate back to actions nav
            if (isActionsPage(currentTag)) {
                android.util.Log.d("HostActivity", "Current page is actions page, navigating to actions");
                showFragment("actions");
                setSelectedNav(navActions, navWallet);
                return;
            }
            
            // Wallet pages should navigate back to wallet page
            if (isWalletPage(currentTag)) {
                android.util.Log.d("HostActivity", "Current page is wallet page, navigating to wallet");
                showFragment("wallet");
                setSelectedNav(navWallet, navActions);
                return;
            }
        }
        
        // Default back behavior for main pages (wallet, actions) or unknown pages
        super.onBackPressed();
    }
    
    /**
     * Check if the current page is an actions page
     */
    private boolean isActionsPage(String fragmentTag) {
        if (fragmentTag == null) return false;
        return fragmentTag.equals("swap") || 
               fragmentTag.equals("anml") || 
               fragmentTag.equals("managelp") || 
               fragmentTag.equals("staking") ||
               fragmentTag.equals("governance") ||
               fragmentTag.equals("caretaker_fund") ||
               fragmentTag.equals("deflation_fund");
    }
    
    /**
     * Check if the current page is a wallet page
     */
    private boolean isWalletPage(String fragmentTag) {
        if (fragmentTag == null) return false;
        return fragmentTag.equals("send") || 
               fragmentTag.equals("receive");
    }
}