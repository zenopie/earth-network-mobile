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
    private lateinit var portfolioTotalValue: TextView
    private lateinit var portfolioLabel: TextView
    private lateinit var gasBalanceRow: LinearLayout
    private lateinit var gasBalanceAmount: TextView
    private lateinit var gasBalanceUsd: TextView

    // State management
    private var currentWalletAddress = ""
    private var currentWalletName = ""
    private var gasUsdValue: Double = 0.0
    private var tokenUsdValue: Double = 0.0


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
        gasBalanceRow = view.findViewById(R.id.gas_balance_row)
        gasBalanceAmount = view.findViewById(R.id.gas_balance_amount)
        gasBalanceUsd = view.findViewById(R.id.gas_balance_usd)

        // Set up wallet name click listener
        walletNameText.setOnClickListener { showWalletListFragment() }

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

    override fun onGasUsdValueUpdated(usdValue: Double) {
        gasUsdValue = usdValue
        updatePortfolioTotal()
    }

    /**
     * Called by WalletDisplayFragment with gas balance info
     */
    override fun updateGasBalanceDisplay(balance: Double, usdValue: Double) {
        gasBalanceAmount.text = formatGasBalance(balance)
        if (usdValue > 0) {
            gasBalanceUsd.text = ErthPriceService.formatUSD(usdValue)
        } else {
            gasBalanceUsd.text = ""
        }
    }

    private fun formatGasBalance(balance: Double): String {
        return if (balance < 0.01 && balance > 0) {
            String.format("%.4f", balance)
        } else {
            String.format("%.2f", balance)
        }
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
        tokenUsdValue = totalUsdValue
        updatePortfolioTotal()
    }

    private fun updatePortfolioTotal() {
        val total = gasUsdValue + tokenUsdValue
        if (total > 0) {
            portfolioTotalValue.text = ErthPriceService.formatUSD(total)
            portfolioTotalValue.visibility = View.VISIBLE
            portfolioLabel.visibility = View.VISIBLE
        } else {
            portfolioTotalValue.visibility = View.GONE
            portfolioLabel.visibility = View.GONE
        }
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
        loadCurrentWallet()
    }
}