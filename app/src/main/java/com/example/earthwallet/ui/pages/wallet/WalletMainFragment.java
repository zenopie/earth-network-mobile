package com.example.earthwallet.ui.pages.wallet;

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
import com.example.earthwallet.ui.pages.wallet.CreateWalletFragment;
import com.example.earthwallet.ui.pages.wallet.WalletListFragment;
import com.example.earthwallet.ui.pages.wallet.ManageViewingKeysFragment;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.wallet.services.SecureWalletManager;

/**
 * WalletMainFragment
 * 
 * Coordinating fragment that manages child fragments:
 * - WalletDisplayFragment: Wallet info, address, SCRT balance
 * - TokenBalancesFragment: Token balance management
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
               ManageViewingKeysFragment.ManageViewingKeysListener {
    
    private static final String TAG = "WalletMainFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    
    // Child fragments
    private WalletDisplayFragment walletDisplayFragment;
    private TokenBalancesFragment tokenBalancesFragment;
    
    // UI components
    private TextView walletNameText;
    
    // State management
    private SharedPreferences securePrefs;
    private String currentWalletAddress = "";
    private String currentWalletName = "";
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Use centralized secure preferences from HostActivity
        securePrefs = ((com.example.earthwallet.ui.host.HostActivity) getActivity()).getSecurePrefs();
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

        // Add fragments to their containers
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.add(R.id.wallet_display_container, walletDisplayFragment);
        transaction.add(R.id.token_balances_container, tokenBalancesFragment);
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
            // Use SecureWalletManager instead of direct preferences access
            currentWalletName = SecureWalletManager.getCurrentWalletName(requireContext(), securePrefs);
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext(), securePrefs);

            // Ensure wallet has address (handles migration)
            SecureWalletManager.ensureCurrentWalletHasAddress(requireContext(), securePrefs);


            Log.d(TAG, "Loaded wallet: " + currentWalletName + " (" + currentWalletAddress + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load current wallet", e);
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
        
        // ViewingKeyManager is now handled automatically by individual fragments
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
        // Show manage viewing keys fragment where users can set viewing keys
        showManageViewingKeysFragment();
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
        if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
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
        if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
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
        if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
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