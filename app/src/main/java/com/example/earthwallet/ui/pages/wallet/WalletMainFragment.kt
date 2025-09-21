package com.example.earthwallet.ui.pages.wallet

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.earthwallet.R
import com.example.earthwallet.wallet.constants.Tokens
import com.example.earthwallet.wallet.services.SecureWalletManager

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
class WalletMainFragment : Fragment(),
    WalletListFragment.WalletListListener,
    CreateWalletFragment.CreateWalletListener,
    WalletDisplayFragment.WalletDisplayListener,
    TokenBalancesFragment.TokenBalancesListener,
    ManagePermitsFragment.ManagePermitsListener,
    WalletSettingsFragment.WalletSettingsListener {

    companion object {
        private const val TAG = "WalletMainFragment"
        private const val PREF_FILE = "secret_wallet_prefs"
    }

    // Child fragments
    private var walletDisplayFragment: WalletDisplayFragment? = null
    private var tokenBalancesFragment: TokenBalancesFragment? = null

    // UI components
    private lateinit var walletNameText: TextView

    // State management
    private var currentWalletAddress = ""
    private var currentWalletName = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SecureWalletManager handles secure preferences internally
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_wallet_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI components
        walletNameText = view.findViewById(R.id.wallet_name_text)

        // Set up wallet name click listener
        walletNameText.setOnClickListener { showWalletListFragment() }

        // Set up settings button click listener
        val settingsBtn = view.findViewById<ImageButton>(R.id.btn_wallet_settings)
        settingsBtn.setOnClickListener { showWalletSettingsFragment() }

        // Initialize child fragments
        initializeChildFragments()

        // Load current wallet info but don't update child fragments yet
        // (they need their views to be created first)
        loadCurrentWallet()

        // Delay updating child fragments until they are ready
        view.post {
            updateChildFragments()
        }
    }

    private fun initializeChildFragments() {
        // Create child fragments
        walletDisplayFragment = WalletDisplayFragment()
        tokenBalancesFragment = TokenBalancesFragment()

        // Add fragments to their containers
        val transaction: FragmentTransaction = childFragmentManager.beginTransaction()
        walletDisplayFragment?.let { transaction.add(R.id.wallet_display_container, it) }
        tokenBalancesFragment?.let { transaction.add(R.id.token_balances_container, it) }
        transaction.commit()
    }

    /**
     * Refresh wallet UI - loads current wallet and updates all child fragments
     * Forces refresh when wallet has actually changed
     */
    private fun refreshWalletsUI() {
        Log.d(TAG, "Refreshing wallet UI")
        loadCurrentWallet()
    }

    private fun loadCurrentWallet() {

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading wallet data in background...")

                // Heavy operations on background thread
                val walletName = SecureWalletManager.getCurrentWalletName(requireContext())
                val walletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""

                // No migration needed - app hasn't been released yet

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    if (isAdded) { // Check if fragment is still attached
                        currentWalletName = walletName
                        currentWalletAddress = walletAddress

                        Log.d(TAG, "Loaded wallet: $currentWalletName ($currentWalletAddress)")
                        updateChildFragments()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Log.e(TAG, "Failed to load current wallet", e)
                        currentWalletName = "Error"
                        currentWalletAddress = ""
                        updateChildFragments()
                    }
                }
            }
        }
    }

    private fun updateChildFragments() {
        // Update wallet name display
        val displayName = if (currentWalletName.isEmpty()) " " else currentWalletName
        walletNameText.text = displayName

        // Update wallet display fragment
        walletDisplayFragment?.updateWalletInfo()

        // Update token balances fragment - only if address actually changed
        tokenBalancesFragment?.updateWalletAddress(currentWalletAddress)

        // PermitManager is now handled automatically by individual fragments
    }

    // =============================================================================
    // WalletDisplayFragment.WalletDisplayListener Implementation
    // =============================================================================

    override fun getCurrentWalletAddress(): String {
        return currentWalletAddress
    }

    // =============================================================================
    // TokenBalancesFragment.TokenBalancesListener Implementation
    // =============================================================================

    override fun onPermitRequested(token: Tokens.TokenInfo) {
        // Show manage viewing keys fragment where users can set viewing keys
        showManagePermitsFragment()
    }

    override fun onManageViewingKeysRequested() {
        showManagePermitsFragment()
    }


    // =============================================================================
    // ManagePermitsFragment.ManagePermitsListener Implementation
    // =============================================================================

    override fun onPermitRemoved(token: Tokens.TokenInfo) {
        Log.d(TAG, "Viewing key removed for ${token.symbol}, updating token balance")

        // Update token balance fragment to refresh (will hide tokens without viewing keys)
        tokenBalancesFragment?.refreshTokenBalances()
    }

    // =============================================================================
    // WalletListFragment.WalletListListener Implementation
    // =============================================================================

    override fun onWalletSelected(walletIndex: Int) {
        Log.d(TAG, "Wallet selected at index: $walletIndex")
        refreshWalletsUI() // Reload and refresh all child fragments

        // Navigate back from wallet list fragment
        requireActivity().supportFragmentManager.popBackStack()
    }

    override fun onCreateWalletRequested() {
        showCreateWalletFragment()
    }

    // =============================================================================
    // CreateWalletFragment.CreateWalletListener Implementation
    // =============================================================================

    override fun onWalletCreated() {
        Log.d(TAG, "New wallet created")
        refreshWalletsUI() // Reload and refresh all child fragments

        // Hide create wallet fragment
        requireActivity().supportFragmentManager.popBackStack()
    }

    override fun onCreateWalletCancelled() {
        // Navigate back from create wallet fragment
        requireActivity().supportFragmentManager.popBackStack()
    }

    // =============================================================================
    // Helper Methods for Navigation
    // =============================================================================

    private fun showWalletListFragment() {
        val walletListFragment = WalletListFragment.newInstance()
        walletListFragment.setWalletListListener(this)

        requireActivity().supportFragmentManager
            .beginTransaction()
            .replace(R.id.host_content, walletListFragment)
            .addToBackStack("wallet_list")
            .commit()
    }

    private fun showCreateWalletFragment() {
        val createWalletFragment = CreateWalletFragment()
        createWalletFragment.setCreateWalletListener(this)

        requireActivity().supportFragmentManager
            .beginTransaction()
            .replace(R.id.host_content, createWalletFragment)
            .addToBackStack("create_wallet")
            .commit()
    }

    private fun showManagePermitsFragment() {
        val managePermitsFragment = ManagePermitsFragment()
        // Note: ManagePermitsFragment gets its listener through onAttach(context)
        // since this fragment implements ManagePermitsListener, it will be set automatically

        requireActivity().supportFragmentManager
            .beginTransaction()
            .replace(R.id.host_content, managePermitsFragment)
            .addToBackStack("manage_permits")
            .commit()
    }

    private fun showWalletSettingsFragment() {
        val walletSettingsFragment = WalletSettingsFragment.newInstance()
        walletSettingsFragment.setWalletSettingsListener(this)

        requireActivity().supportFragmentManager
            .beginTransaction()
            .replace(R.id.host_content, walletSettingsFragment)
            .addToBackStack("wallet_settings")
            .commit()
    }

    // =============================================================================
    // WalletSettingsFragment.WalletSettingsListener Implementation
    // =============================================================================

    override fun onBackPressed() {
        // Navigate back from wallet settings fragment
        requireActivity().supportFragmentManager.popBackStack()
    }

    // =============================================================================
    // Lifecycle Methods
    // =============================================================================

    override fun onResume() {
        super.onResume()
        // Always load fresh wallet data
        Log.d(TAG, "onResume: Loading wallet data")
        loadCurrentWallet()
    }
}