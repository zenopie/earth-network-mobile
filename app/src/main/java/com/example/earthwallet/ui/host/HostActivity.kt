package com.example.earthwallet.ui.host

import com.example.earthwallet.R
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.nfc.NfcAdapter
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.earthwallet.wallet.services.SessionManager
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.example.earthwallet.wallet.services.UpdateManager
import com.example.earthwallet.ui.utils.WindowInsetsUtil
import com.example.earthwallet.ui.pages.wallet.CreateWalletFragment
import com.example.earthwallet.ui.pages.auth.PinEntryFragment
import com.example.earthwallet.ui.components.ForceUpdateDialog
import androidx.lifecycle.Observer
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.initialization.InitializationStatus
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class HostActivity : AppCompatActivity(), CreateWalletFragment.CreateWalletListener, PinEntryFragment.PinEntryListener {

    companion object {
        private const val PREF_FILE = "secret_wallet_prefs"
        private const val TAG = "HostActivity"

        // Test interstitial ad unit ID (use your real ad unit ID in production)
        private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    }

    private var navWallet: Button? = null
    private var navActions: Button? = null
    private var bottomNavView: View? = null
    private var hostContent: View? = null
    private var securePrefs: SharedPreferences? = null

    // AdMob interstitial ad
    private var mInterstitialAd: InterstitialAd? = null
    private var isAdLoaded = false
    private var adCompletionCallback: Runnable? = null

    // Update management
    private lateinit var updateManager: UpdateManager
    private var forceUpdateDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        navWallet = findViewById(R.id.btn_nav_wallet)
        navActions = findViewById(R.id.btn_nav_actions)
        hostContent = findViewById(R.id.host_content)

        // Find bottom navigation view - it's included via <include> tag
        bottomNavView = findViewById(R.id.bottom_nav)

        // Wire nav buttons to swap fragments
        navWallet?.setOnClickListener {
            showFragment("wallet")
            setSelectedNav(navWallet, navActions)
        }
        navActions?.setOnClickListener {
            showFragment("actions")
            setSelectedNav(navActions, navWallet)
        }

        // Initialize AdMob
        initializeAds()

        // Initialize update manager
        initializeUpdateManager()

        // Setup modern back pressed handling
        setupBackPressedCallback()

        // Check if PIN is set and handle startup flow
        handleStartupFlow()

        // Handle intent to show a specific fragment (e.g., from other activities)
        intent?.let {
            if (it.hasExtra("fragment_to_show")) {
                val fragmentToShow = it.getStringExtra("fragment_to_show")
                fragmentToShow?.let { fragment ->
                    showFragment(fragment)
                    when (fragment) {
                        "wallet" -> setSelectedNav(navWallet, navActions)
                        "actions" -> setSelectedNav(navActions, navWallet)
                        else -> setSelectedNav(navWallet, navActions) // Default to scanner if unknown
                    }
                }
            }
        }
    }

    private fun setSelectedNav(selected: View?, other: View?) {
        selected?.isSelected = true
        other?.isSelected = false
    }

    // Make this public so fragments can request navigation without creating a second bottom nav.
    fun showFragment(tag: String) {
        showFragment(tag, null)
    }

    fun showFragment(tag: String, arguments: Bundle?) {
        val fm = supportFragmentManager
        val ft = fm.beginTransaction()

        val fragment: Fragment = when (tag) {
            "wallet" -> {
                com.example.earthwallet.ui.pages.wallet.WalletMainFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "actions" -> {
                com.example.earthwallet.ui.nav.ActionsMainFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "scanner" -> {
                com.example.earthwallet.ui.pages.anml.ScannerFragment().also {
                    // Hide navigation and status bar for scanner
                    hideBottomNavigation()
                    WindowInsetsUtil.hideSystemBars(window)
                }
            }
            "mrz_input" -> {
                com.example.earthwallet.ui.pages.anml.MRZInputFragment()
            }
            "camera_mrz_scanner" -> {
                com.example.earthwallet.ui.pages.anml.CameraMRZScannerFragment()
            }
            "scan_failure" -> {
                com.example.earthwallet.ui.pages.anml.ScanFailureFragment().apply {
                    arguments?.let { setArguments(it) }
                }
            }
            "pin_entry" -> {
                PinEntryFragment.newInstance().also {
                    // Hide navigation and status bar for focused PIN entry
                    hideBottomNavigation()
                    WindowInsetsUtil.hideSystemBars(window)
                }
            }
            "create_wallet" -> {
                CreateWalletFragment().apply {
                    setCreateWalletListener(this@HostActivity)
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "swap" -> {
                com.example.earthwallet.ui.pages.swap.SwapTokensMainFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "anml" -> {
                com.example.earthwallet.ui.pages.anml.ANMLClaimMainFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "managelp" -> {
                com.example.earthwallet.ui.pages.managelp.ManageLPFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "staking" -> {
                com.example.earthwallet.ui.pages.staking.StakeEarthFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "send" -> {
                com.example.earthwallet.ui.pages.wallet.SendTokensFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "receive" -> {
                com.example.earthwallet.ui.pages.wallet.ReceiveTokensFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "governance" -> {
                com.example.earthwallet.ui.pages.governance.GovernanceFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "gas_station" -> {
                com.example.earthwallet.ui.pages.gasstation.GasStationFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "caretaker_fund" -> {
                com.example.earthwallet.ui.pages.governance.CaretakerFundFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            "deflation_fund" -> {
                com.example.earthwallet.ui.pages.governance.DeflationFundFragment().also {
                    // Show navigation and status bar for normal fragments
                    showBottomNavigation()
                    WindowInsetsUtil.showSystemBars(window)
                }
            }
            else -> {
                // Default to scanner if an unknown tag is passed
                com.example.earthwallet.ui.pages.anml.ScannerFragment().also {
                    // Hide navigation and status bar for scanner
                    hideBottomNavigation()
                    WindowInsetsUtil.hideSystemBars(window)
                }
            }
        }

        // Set arguments on the fragment if provided
        arguments?.let {
            fragment.arguments = it
        }

        ft.replace(R.id.host_content, fragment, tag)
        ft.commitAllowingStateLoss()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Handle NFC intents and pass them to ScannerFragment if it's currently shown
        if (intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.host_content)
            if (currentFragment is com.example.earthwallet.ui.pages.anml.ScannerFragment) {
                currentFragment.handleNfcIntent(intent)
            }
        }

        // Handle fragment navigation from other activities
        intent?.let {
            if (it.hasExtra("fragment_to_show")) {
                val fragmentToShow = it.getStringExtra("fragment_to_show")
                fragmentToShow?.let { fragment ->
                    showFragment(fragment)
                    when (fragment) {
                        "wallet" -> setSelectedNav(navWallet, navActions)
                        "actions" -> setSelectedNav(navActions, navWallet)
                        else -> setSelectedNav(navWallet, navActions) // Default to wallet nav for other fragments
                    }
                }
            }
        }
    }

    override fun onWalletCreated() {
        // Navigate to actions after wallet creation
        showFragment("actions")
        setSelectedNav(navActions, navWallet)
    }

    override fun onCreateWalletCancelled() {
        // Stay on create wallet or go to scanner
        showFragment("scanner")
        setSelectedNav(navWallet, navActions)
    }

    /**
     * Handle startup flow based on PIN and wallet state
     */
    private fun handleStartupFlow() {
        try {
            // Check if PIN is set (can be done without session)
            val hasPinSet = SecureWalletManager.hasPinSet(this)

            if (hasPinSet) {
                // PIN exists - show PIN entry screen
                showFragment("pin_entry")
                setSelectedNav(navWallet, navActions)
            } else {
                // No PIN set - show wallet creation to set up first wallet and PIN
                showFragment("create_wallet")
                setSelectedNav(navWallet, navActions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine startup flow", e)
            // Default to wallet creation on error
            showFragment("create_wallet")
            setSelectedNav(navWallet, navActions)
        }
    }

    /**
     * Initialize session after PIN is entered
     * Call this after successful PIN entry
     */
    fun initializeSessionAndNavigate(pin: String) {
        try {
            // Start session with PIN
            SessionManager.startSession(this, pin)

            // Check if wallets exist
            val walletCount = SecureWalletManager.getWalletCount(this)

            if (walletCount > 0) {
                showFragment("actions")
                setSelectedNav(navActions, navWallet)
            } else {
                showFragment("create_wallet")
                setSelectedNav(navWallet, navActions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize session", e)
            // Stay on current screen and show error
            // TODO: Show proper error message to user
        }
    }


    /**
     * Hide bottom navigation and adjust content layout
     */
    fun hideBottomNavigation() {
        try {
            bottomNavView?.let {
                it.visibility = View.GONE
            }
            hostContent?.let { content ->
                val layoutParams = content.layoutParams
                if (layoutParams is ViewGroup.MarginLayoutParams) {
                    // Remove bottom margin to make content full screen
                    layoutParams.bottomMargin = 0
                    content.layoutParams = layoutParams
                } else {
                }
            }
        } catch (e: Exception) {
            Log.e("HostActivity", "Error hiding bottom navigation", e)
        }
    }

    /**
     * Show bottom navigation and restore content layout
     */
    fun showBottomNavigation() {
        try {
            val stackTrace = Thread.currentThread().stackTrace
            for (i in 0 until minOf(stackTrace.size, 8)) {
            }

            bottomNavView?.let {
                it.visibility = View.VISIBLE
            }
            hostContent?.let { content ->
                val layoutParams = content.layoutParams
                if (layoutParams is ViewGroup.MarginLayoutParams) {
                    // Restore bottom margin for navigation
                    layoutParams.bottomMargin = (56 * resources.displayMetrics.density).toInt() // 56dp in pixels
                    content.layoutParams = layoutParams
                }
            }
        } catch (e: Exception) {
            Log.e("HostActivity", "Error showing bottom navigation", e)
        }
    }

    /**
     * Set screen orientation to landscape
     */
    fun setLandscapeOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    /**
     * Set screen orientation to portrait
     */
    fun setPortraitOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun setupBackPressedCallback() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.host_content)
                currentFragment?.let { fragment ->
                    val currentTag = fragment.tag

                    // Actions pages should navigate back to actions nav
                    if (isActionsPage(currentTag)) {
                        showFragment("actions")
                        setSelectedNav(navActions, navWallet)
                        return
                    }

                    // Wallet pages should navigate back to wallet page
                    if (isWalletPage(currentTag)) {
                        showFragment("wallet")
                        setSelectedNav(navWallet, navActions)
                        return
                    }
                }

                // Default back behavior for main pages (wallet, actions) or unknown pages
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
    }

    /**
     * Check if the current page is an actions page
     */
    private fun isActionsPage(fragmentTag: String?): Boolean {
        if (fragmentTag == null) return false
        return fragmentTag in listOf(
            "swap", "anml", "managelp", "staking", "governance",
            "gas_station", "caretaker_fund", "deflation_fund"
        )
    }

    /**
     * Check if the current page is a wallet page
     */
    private fun isWalletPage(fragmentTag: String?): Boolean {
        if (fragmentTag == null) return false
        return fragmentTag in listOf("send", "receive")
    }

    /**
     * Initialize AdMob and load interstitial ad
     */
    private fun initializeAds() {

        MobileAds.initialize(this) { _ ->
            loadInterstitialAd()
        }
    }

    /**
     * Load an interstitial ad
     */
    private fun loadInterstitialAd() {

        val adRequest = AdRequest.Builder().build()

        InterstitialAd.load(this, INTERSTITIAL_AD_UNIT_ID, adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    mInterstitialAd = interstitialAd
                    isAdLoaded = true

                    // Set up full screen content callback
                    mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdClicked() {
                        }

                        override fun onAdDismissedFullScreenContent() {
                            mInterstitialAd = null
                            isAdLoaded = false

                            // Execute the completion callback if set
                            adCompletionCallback?.run()
                            adCompletionCallback = null

                            // Load a new ad for next time
                            loadInterstitialAd()
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                            Log.e(TAG, "Interstitial ad failed to show: ${adError.message}")
                            mInterstitialAd = null
                            isAdLoaded = false

                            // Execute the completion callback anyway
                            adCompletionCallback?.run()
                            adCompletionCallback = null

                            // Load a new ad for next time
                            loadInterstitialAd()
                        }

                        override fun onAdImpression() {
                        }

                        override fun onAdShowedFullScreenContent() {
                        }
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.e(TAG, "Failed to load interstitial ad: ${loadAdError.message}")
                    mInterstitialAd = null
                    isAdLoaded = false
                }
            })
    }

    /**
     * Show interstitial ad before executing a callback
     * @param callback The callback to execute after the ad is dismissed (or if ad fails to show)
     */
    fun showInterstitialAdThen(callback: Runnable?) {

        if (mInterstitialAd != null && isAdLoaded) {
            adCompletionCallback = callback
            mInterstitialAd?.show(this)
        } else {
            // No ad loaded, execute callback immediately
            callback?.run()
        }
    }

    // =============================================================================
    // PinEntryFragment.PinEntryListener Implementation
    // =============================================================================

    override fun onPinEntered(pin: String) {
        // PIN was successfully entered and verified in PinEntryFragment
        // Session has already been started, just navigate to appropriate screen
        initializeSessionAndNavigate(pin)
    }

    /**
     * Initialize update manager and check for updates
     */
    private fun initializeUpdateManager() {
        updateManager = UpdateManager.getInstance(this)

        // Observe update info
        updateManager.updateInfo.observe(this, Observer { updateInfo ->
            updateInfo?.let {
                if (it.isUpdateAvailable) {
                    showUpdateDialog(it)
                }
            }
        })

        // Check for updates on app start
        updateManager.checkForUpdates()
    }

    /**
     * Show update dialog based on update info
     */
    private fun showUpdateDialog(updateInfo: UpdateManager.UpdateInfo) {
        // Dismiss any existing dialog
        forceUpdateDialog?.dismiss()

        if (updateInfo.isForceUpdate) {
            // Show blocking force update dialog
            forceUpdateDialog = ForceUpdateDialog.showBlockingForceUpdateDialog(
                context = this,
                updateInfo = updateInfo
            )
        } else {
            // Show regular update dialog
            forceUpdateDialog = ForceUpdateDialog.showUpdateDialog(
                context = this,
                updateInfo = updateInfo,
                onUpdateClicked = {
                    updateManager.navigateToUpdate(updateInfo)
                },
                onLaterClicked = {
                    // User chose to update later
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateManager.cleanup()
        forceUpdateDialog?.dismiss()
    }

    override fun onResume() {
        super.onResume()
        // Check for updates when returning to app (in case user updated from Play Store)
        if (::updateManager.isInitialized) {
            updateManager.checkForUpdates()
        }
    }

}
