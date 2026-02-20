package network.erth.wallet.ui.pages.wallet

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.services.SecureWalletManager

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
    private lateinit var portfolioTotalValue: TextView
    private lateinit var portfolioLabel: TextView

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
        portfolioTotalValue = view.findViewById(R.id.portfolio_total_value)
        portfolioLabel = view.findViewById(R.id.portfolio_label)

        // Set up wallet name container click listener (includes dropdown arrow)
        view.findViewById<LinearLayout>(R.id.wallet_name_container).setOnClickListener { showWalletListFragment() }

        // Initialize child fragments
        initializeChildFragments()

        // Don't load wallet here - onResume will handle it
        // This prevents duplicate calls when fragment is first created
    }

    private fun initializeChildFragments() {
        // Check if fragments already exist (e.g., after navigation back)
        walletDisplayFragment = childFragmentManager.findFragmentById(R.id.wallet_display_container) as? WalletDisplayFragment
        tokenBalancesFragment = childFragmentManager.findFragmentById(R.id.token_balances_container) as? TokenBalancesFragment

        // Only create and add new fragments if they don't already exist
        val transaction: FragmentTransaction = childFragmentManager.beginTransaction()

        if (walletDisplayFragment == null) {
            walletDisplayFragment = WalletDisplayFragment()
            transaction.add(R.id.wallet_display_container, walletDisplayFragment!!)
        }

        if (tokenBalancesFragment == null) {
            tokenBalancesFragment = TokenBalancesFragment()
            transaction.add(R.id.token_balances_container, tokenBalancesFragment!!)
        }

        transaction.commit()
    }

    /**
     * Refresh wallet UI - loads current wallet and updates all child fragments
     * Forces refresh when wallet has actually changed
     */
    private fun refreshWalletsUI(onComplete: (() -> Unit)? = null) {
        loadCurrentWallet(onComplete)
    }

    private fun loadCurrentWallet(onComplete: (() -> Unit)? = null) {
        Log.d(TAG, "loadCurrentWallet called")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Heavy operations on background thread
                val walletName = SecureWalletManager.getCurrentWalletName(requireContext())
                val walletAddress = SecureWalletManager.getWalletAddress(requireContext()) ?: ""
                Log.d(TAG, "loadCurrentWallet loaded: name=$walletName, address=$walletAddress")

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    if (isAdded) { // Check if fragment is still attached
                        currentWalletName = walletName
                        currentWalletAddress = walletAddress

                        updateChildFragments()

                        // Notify completion after UI is updated
                        onComplete?.invoke()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Log.e(TAG, "Failed to load current wallet", e)
                        currentWalletName = "Error"
                        currentWalletAddress = ""
                        updateChildFragments()

                        // Still notify completion even on error
                        onComplete?.invoke()
                    }
                }
            }
        }
    }

    private fun updateChildFragments() {
        Log.d(TAG, "updateChildFragments called")
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

    override fun onTokenUsdValueUpdated(totalUsdValue: Double) {
        portfolioTotalValue.text = ErthPriceService.formatUSD(totalUsdValue)
    }


    // =============================================================================
    // ManagePermitsFragment.ManagePermitsListener Implementation
    // =============================================================================

    override fun onPermitRemoved(token: Tokens.TokenInfo) {

        // Update token balance fragment to refresh (will hide tokens without viewing keys)
        tokenBalancesFragment?.refreshTokenBalances()
    }

    // =============================================================================
    // WalletListFragment.WalletListListener Implementation
    // =============================================================================

    override fun onWalletSelected(walletIndex: Int) {
        // Reload and refresh all child fragments, then navigate back
        refreshWalletsUI {
            // Navigate back only after wallet is fully loaded
            requireActivity().supportFragmentManager.popBackStack()
        }
    }

    override fun onCreateWalletRequested() {
        showCreateWalletFragment()
    }

    // =============================================================================
    // CreateWalletFragment.CreateWalletListener Implementation
    // =============================================================================

    override fun onWalletCreated() {
        // Reload and refresh all child fragments, then hide create wallet fragment
        refreshWalletsUI {
            // Navigate back only after wallet is fully loaded
            requireActivity().supportFragmentManager.popBackStack()
        }
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
        // Always load fresh wallet data
        loadCurrentWallet()
    }
}