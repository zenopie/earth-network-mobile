package com.example.earthwallet.ui.fragments.main;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;
import com.example.earthwallet.ui.fragments.CreateWalletFragment;
import com.example.earthwallet.ui.fragments.WalletListFragment;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.wallet.services.SecretWallet;

import org.bitcoinj.core.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * WalletMainFragment
 * 
 * Coordinating fragment that manages child fragments:
 * - WalletDisplayFragment: Wallet info, address, SCRT balance
 * - TokenBalancesFragment: Token balance management
 * - ViewingKeyManagerFragment: Viewing key operations (invisible helper)
 * 
 * This fragment handles:
 * - Child fragment lifecycle management
 * - Communication between child fragments
 * - Wallet selection and creation flow
 * - Overall wallet state management
 */
public class WalletMainFragment extends Fragment 
    implements WalletListFragment.WalletListListener, 
               CreateWalletFragment.CreateWalletListener,
               WalletDisplayFragment.WalletDisplayListener,
               TokenBalancesFragment.TokenBalancesListener,
               ViewingKeyManagerFragment.ViewingKeyManagerListener,
               ManageViewingKeysFragment.ManageViewingKeysListener {
    
    private static final String TAG = "WalletMainFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    
    // Child fragments
    private WalletDisplayFragment walletDisplayFragment;
    private TokenBalancesFragment tokenBalancesFragment;
    private ViewingKeyManagerFragment viewingKeyManagerFragment;
    
    // UI components
    private TextView walletNameText;
    
    // State management
    private SharedPreferences securePrefs;
    private String currentWalletAddress = "";
    private String currentWalletMnemonic = "";
    private String currentWalletName = "";
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            SecretWallet.initialize(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SecretWallet", e);
        }
        
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
            Log.e(TAG, "Failed to create secure preferences", e);
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wallet_main, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize UI components
        walletNameText = view.findViewById(R.id.wallet_name_text);
        
        // Set up wallet name click listener
        if (walletNameText != null) {
            walletNameText.setOnClickListener(v -> showWalletListFragment());
        }
        
        // Initialize child fragments
        initializeChildFragments();
        
        // Load current wallet info but don't update child fragments yet
        // (they need their views to be created first)
        loadCurrentWallet();
        
        // Delay updating child fragments until they are ready
        view.post(() -> {
            updateChildFragments();
        });
    }
    
    private void initializeChildFragments() {
        // Create child fragments
        walletDisplayFragment = new WalletDisplayFragment();
        tokenBalancesFragment = new TokenBalancesFragment();
        viewingKeyManagerFragment = new ViewingKeyManagerFragment();
        
        // Add fragments to their containers
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.add(R.id.wallet_display_container, walletDisplayFragment);
        transaction.add(R.id.token_balances_container, tokenBalancesFragment);
        transaction.add(viewingKeyManagerFragment, "viewing_key_manager"); // Invisible helper
        transaction.commit();
    }
    
    /**
     * Refresh wallet UI - loads current wallet and updates all child fragments
     */
    private void refreshWalletsUI() {
        loadCurrentWallet();
        updateChildFragments();
    }
    
    private void loadCurrentWallet() {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray walletsArray = new JSONArray(walletsJson);
            int selectedIndex = securePrefs.getInt("selected_wallet_index", -1);
            
            if (walletsArray.length() > 0) {
                JSONObject selectedWallet;
                if (selectedIndex >= 0 && selectedIndex < walletsArray.length()) {
                    selectedWallet = walletsArray.getJSONObject(selectedIndex);
                } else {
                    selectedWallet = walletsArray.getJSONObject(0);
                }
                
                currentWalletMnemonic = selectedWallet.optString("mnemonic", "");
                currentWalletName = selectedWallet.optString("name", "Wallet");
                currentWalletAddress = selectedWallet.optString("address", "");
                
                // If address is missing, derive it once and update storage
                if (TextUtils.isEmpty(currentWalletAddress) && !TextUtils.isEmpty(currentWalletMnemonic)) {
                    ECKey key = SecretWallet.deriveKeyFromMnemonic(currentWalletMnemonic);
                    currentWalletAddress = SecretWallet.getAddress(key);
                    
                    // Update the stored wallet with the address
                    selectedWallet.put("address", currentWalletAddress);
                    walletsArray.put(selectedIndex >= 0 ? selectedIndex : 0, selectedWallet);
                    securePrefs.edit().putString("wallets", walletsArray.toString()).apply();
                    
                    Log.d(TAG, "Migrated wallet address: " + currentWalletName + " (" + currentWalletAddress + ")");
                } else {
                    Log.d(TAG, "Loaded wallet: " + currentWalletName + " (" + currentWalletAddress + ")");
                }
            } else {
                // No wallets available
                currentWalletMnemonic = "";
                currentWalletName = "No wallet";
                currentWalletAddress = "";
                Log.d(TAG, "No wallets found");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load current wallet", e);
            currentWalletMnemonic = "";
            currentWalletName = "Error";
            currentWalletAddress = "";
        }
    }
    
    private void updateChildFragments() {
        // Update wallet name display
        if (walletNameText != null) {
            String displayName = currentWalletName.isEmpty() ? "My Wallet" : currentWalletName;
            walletNameText.setText(displayName);
        }
        
        // Update wallet display fragment
        if (walletDisplayFragment != null) {
            walletDisplayFragment.updateWalletInfo();
        }
        
        // Update token balances fragment
        if (tokenBalancesFragment != null) {
            tokenBalancesFragment.updateWalletAddress(currentWalletAddress);
        }
        
        // Update viewing key manager fragment
        if (viewingKeyManagerFragment != null) {
            viewingKeyManagerFragment.updateWalletAddress(currentWalletAddress);
        }
    }
    
    // =============================================================================
    // WalletDisplayFragment.WalletDisplayListener Implementation
    // =============================================================================
    
    @Override
    public String getCurrentWalletAddress() {
        return currentWalletAddress;
    }
    
    // =============================================================================
    // TokenBalancesFragment.TokenBalancesListener Implementation
    // =============================================================================
    
    @Override
    public void onViewingKeyRequested(Tokens.TokenInfo token) {
        // Delegate to viewing key manager fragment
        if (viewingKeyManagerFragment != null) {
            viewingKeyManagerFragment.requestViewingKey(token);
        }
    }
    
    @Override
    public void onManageViewingKeysRequested() {
        showManageViewingKeysFragment();
    }
    
    @Override
    public SharedPreferences getSecurePrefs() {
        return securePrefs;
    }
    
    // =============================================================================
    // ViewingKeyManagerFragment.ViewingKeyManagerListener Implementation
    // =============================================================================
    
    @Override
    public void onViewingKeySet(Tokens.TokenInfo token, String viewingKey) {
        Log.d(TAG, "Viewing key set for " + token.symbol + ", updating token balance");
        
        // Update token balance fragment to show "Loading..." and query balance
        if (tokenBalancesFragment != null) {
            tokenBalancesFragment.updateTokenBalance(token, "Loading...");
            tokenBalancesFragment.querySingleToken(token);
        }
    }
    
    // =============================================================================
    // ManageViewingKeysFragment.ManageViewingKeysListener Implementation
    // =============================================================================
    
    @Override
    public void onViewingKeyRemoved(Tokens.TokenInfo token) {
        Log.d(TAG, "Viewing key removed for " + token.symbol + ", updating token balance");
        
        // Update token balance fragment to refresh (will hide tokens without viewing keys)
        if (tokenBalancesFragment != null) {
            tokenBalancesFragment.refreshTokenBalances();
        }
    }
    
    // =============================================================================
    // WalletListFragment.WalletListListener Implementation
    // =============================================================================
    
    @Override
    public void onWalletSelected(int walletIndex) {
        Log.d(TAG, "Wallet selected at index: " + walletIndex);
        refreshWalletsUI(); // Reload and refresh all child fragments
        
        // Navigate back from wallet list fragment
        requireActivity().getSupportFragmentManager().popBackStack();
    }
    
    @Override
    public void onCreateWalletRequested() {
        showCreateWalletFragment();
    }
    
    // =============================================================================
    // CreateWalletFragment.CreateWalletListener Implementation
    // =============================================================================
    
    @Override
    public void onWalletCreated() {
        Log.d(TAG, "New wallet created");
        refreshWalletsUI(); // Reload and refresh all child fragments
        
        // Hide create wallet fragment
        getChildFragmentManager().popBackStack();
    }
    
    @Override
    public void onCreateWalletCancelled() {
        requireActivity().getSupportFragmentManager().popBackStack();
    }
    
    // =============================================================================
    // Navigation Methods
    // =============================================================================
    
    private void showWalletListFragment() {
        Log.d(TAG, "showWalletListFragment called");
        
        // Use the HostActivity's navigation system to show wallet list as a full-screen fragment
        if (getActivity() instanceof com.example.earthwallet.ui.activities.HostActivity) {
            WalletListFragment walletListFragment = new WalletListFragment();
            walletListFragment.setWalletListListener(this); // Set the listener so add wallet button works
            
            try {
                FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.host_content, walletListFragment, "wallet_list");
                transaction.addToBackStack("wallet_list");
                transaction.commit();
                Log.d(TAG, "WalletListFragment shown as full-screen using HostActivity fragment manager");
            } catch (Exception e) {
                Log.e(TAG, "Failed to show WalletListFragment via HostActivity", e);
            }
        }
    }
    
    private void showCreateWalletFragment() {
        Log.d(TAG, "showCreateWalletFragment called");
        
        // Use the same navigation pattern as showWalletListFragment (HostActivity level)
        if (getActivity() instanceof com.example.earthwallet.ui.activities.HostActivity) {
            CreateWalletFragment createWalletFragment = new CreateWalletFragment();
            
            try {
                FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.host_content, createWalletFragment, "create_wallet");
                transaction.addToBackStack("create_wallet");
                transaction.commit();
                Log.d(TAG, "CreateWalletFragment shown as full-screen using HostActivity fragment manager");
            } catch (Exception e) {
                Log.e(TAG, "Failed to show CreateWalletFragment via HostActivity", e);
            }
        }
    }
    
    private void showManageViewingKeysFragment() {
        Log.d(TAG, "showManageViewingKeysFragment called");
        
        // Use the HostActivity's navigation system to show manage viewing keys as a full-screen fragment
        if (getActivity() instanceof com.example.earthwallet.ui.activities.HostActivity) {
            ManageViewingKeysFragment manageViewingKeysFragment = new ManageViewingKeysFragment();
            
            // Create a bundle to pass the current wallet address
            Bundle args = new Bundle();
            args.putString("wallet_address", currentWalletAddress);
            manageViewingKeysFragment.setArguments(args);
            
            try {
                FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.host_content, manageViewingKeysFragment, "manage_viewing_keys");
                transaction.addToBackStack("manage_viewing_keys");
                transaction.commit();
                Log.d(TAG, "ManageViewingKeysFragment shown as full-screen using HostActivity fragment manager");
            } catch (Exception e) {
                Log.e(TAG, "Failed to show ManageViewingKeysFragment via HostActivity", e);
            }
        }
    }
    
    // =============================================================================
    // Lifecycle Methods
    // =============================================================================
    
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called - refreshing wallet UI");
        refreshWalletsUI();
    }
}