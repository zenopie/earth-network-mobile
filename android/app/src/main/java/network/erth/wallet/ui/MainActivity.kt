package network.erth.wallet.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import network.erth.wallet.notify.Notifications
import network.erth.wallet.ui.onboarding.FirstWalletFlow
import network.erth.wallet.ui.onboarding.OnboardingViewModel
import network.erth.wallet.ui.onboarding.PreAppScreen
import network.erth.wallet.ui.onboarding.UnlockSetupFlow
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.unlock.UnlockGate
import network.erth.wallet.ui.unlock.UnlockViewModel
import network.erth.wallet.wallet.AutoLock
import network.erth.wallet.wallet.SessionManager
import network.erth.wallet.wallet.UnlockMethod

/**
 * The app.
 *
 * Three states: set up, locked, or open, in one activity.
 *
 * Set up comes first, before any unlock: with no wallet sealed, no PIN can be
 * right, so showing the PIN screen on a fresh install would end in a lockout
 * with the wallet screens that could fix it behind the same gate.
 *
 * Open follows the session, not the unlock that started it: [AutoLock] can
 * end the session underneath this screen, and the gate has to come back when
 * it does.
 */
// FragmentActivity rather than ComponentActivity: BiometricPrompt attaches
// to a fragment manager, and there is no way to raise it without one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeOpen(intent)


        setContent {
            EarthTheme {
                val unlock: UnlockViewModel = viewModel()
                val onboarding: OnboardingViewModel = viewModel()

                val error by unlock.error.collectAsStateWithLifecycle()
                val lockout by unlock.lockout.collectAsStateWithLifecycle()

                val needsPin by onboarding.needsPin.collectAsStateWithLifecycle()
                val needsWallet by onboarding.needsWallet.collectAsStateWithLifecycle()
                val setupError by onboarding.error.collectAsStateWithLifecycle()

                val open by SessionManager.active.collectAsStateWithLifecycle()

                // Asked once a session exists, because the count can only be
                // read through it. Catches a setup abandoned between choosing
                // a PIN and creating a wallet.
                LaunchedEffect(open) {
                    if (open) onboarding.checkWallets() else onboarding.recheckSetup()
                }

                when {
                    needsPin -> PreAppScreen { inset ->
                        UnlockSetupFlow(
                            error = setupError,
                            onDone = onboarding::finishSetup,
                            onFailed = onboarding::reportError,
                            modifier = inset,
                        )
                    }

                    !open -> PreAppScreen { inset ->
                        UnlockGate(
                            // Read here rather than held in the view model:
                            // changing it in settings has to take effect on the
                            // next lock, not the next process.
                            method = UnlockMethod.current(this@MainActivity),
                            onSecret = unlock::submitSecret,
                            onFailure = unlock::reportFailure,
                            error = error,
                            lockoutMessage = lockout,
                            modifier = inset,
                        )
                    }

                    needsWallet -> PreAppScreen { inset ->
                        FirstWalletFlow(
                            onReady = onboarding::walletReady,
                            modifier = inset,
                        )
                    }

                    // EarthApp owns the view models: each tab loads when it is
                    // first shown rather than all of them on launch.
                    else -> EarthApp(
                        version = "Version ${network.erth.wallet.BuildConfig.VERSION_NAME} (${network.erth.wallet.BuildConfig.VERSION_CODE})",
                        onOpenUrl = ::openUrl,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpen(intent)
    }

    // Only this app's own notifications can carry it: the activity is not exported.
    private fun takeOpen(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_OPEN)?.let { Notifications.pendingOpen.value = it }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
}
