package com.example.earthwallet.ui.pages.wallet

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
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
    ManagePermitsFragment.ManagePermitsListener {

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
    private lateinit var securePrefs: SharedPreferences
    private var currentWalletAddress = ""
    private var currentWalletName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use centralized secure preferences from HostActivity
        securePrefs = (activity as com.example.earthwallet.ui.host.HostActivity).getSecurePrefs()
            ?: throw IllegalStateException("Failed to get secure preferences")
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
     */
    private fun refreshWalletsUI() {
        loadCurrentWallet()
        updateChildFragments()
    }

    private fun loadCurrentWallet() {
        try {
            // Use SecureWalletManager instead of direct preferences access
            currentWalletName = SecureWalletManager.getCurrentWalletName(requireContext(), securePrefs)
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext(), securePrefs) ?: ""

            // Ensure wallet has address (handles migration)
            SecureWalletManager.ensureCurrentWalletHasAddress(requireContext(), securePrefs)

            Log.d(TAG, "Loaded wallet: $currentWalletName ($currentWalletAddress)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load current wallet", e)
            currentWalletName = "Error"
            currentWalletAddress = ""
        }
    }

    private fun updateChildFragments() {
        // Update wallet name display
        val displayName = if (currentWalletName.isEmpty()) "My Wallet" else currentWalletName
        walletNameText.text = displayName

        // Update wallet display fragment
        walletDisplayFragment?.updateWalletInfo()

        // Update token balances fragment
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

    override fun getSecurePrefs(): SharedPreferences {
        return securePrefs
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

    // =============================================================================
    // Lifecycle Methods
    // =============================================================================

    override fun onResume() {
        super.onResume()
        // Refresh wallet info when returning to this fragment
        refreshWalletsUI()
    }
}