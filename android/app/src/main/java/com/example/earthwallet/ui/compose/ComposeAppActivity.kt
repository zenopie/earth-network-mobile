package network.erth.wallet.ui.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.wallet.services.SessionManager

/**
 * The app.
 *
 * Three states: set up, locked, or open. The old app split these across a
 * launcher activity, an update check, a host activity and a fragment, and the
 * transitions between them do not need four activities.
 *
 * The first state was missing until now, and its absence was fatal on a fresh
 * install. This screen only ever asked whether a session was open, so with no
 * wallet it showed the PIN screen — and since nothing in the Compose app had
 * ever called setPinHash, no PIN could be right. Three attempts, then a
 * lockout, and the wallet screens that would have fixed it sit behind the same
 * gate. HostActivity branched on hasPinSet and was deleted with the old app;
 * this is that branch, back.
 */
class ComposeAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "dev"

        setContent {
            EarthTheme {
                val unlock: UnlockViewModel = viewModel()
                val onboarding: OnboardingViewModel = viewModel()

                val unlocked by unlock.unlocked.collectAsStateWithLifecycle()
                val error by unlock.error.collectAsStateWithLifecycle()
                val lockout by unlock.lockout.collectAsStateWithLifecycle()

                val needsPin by onboarding.needsPin.collectAsStateWithLifecycle()
                val needsWallet by onboarding.needsWallet.collectAsStateWithLifecycle()
                val setupError by onboarding.error.collectAsStateWithLifecycle()

                // A session can already be open — the process may have been
                // restarted while the app was backgrounded.
                val open = unlocked || SessionManager.isSessionActive()

                // Asked once a session exists, because the count can only be
                // read through it. Catches a setup abandoned between choosing
                // a PIN and creating a wallet.
                LaunchedEffect(open) {
                    if (open) onboarding.checkWallets()
                }

                when {
                    needsPin -> PreAppScreen { inset ->
                        SetPinScreen(
                            error = setupError,
                            onChosen = onboarding::choosePin,
                            modifier = inset,
                        )
                    }

                    !open -> PreAppScreen { inset ->
                        UnlockScreen(
                            onSubmit = unlock::submit,
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

                    // EarthApp owns the view models now — each tab loads when
                    // it is first shown rather than all five on launch.
                    else -> EarthApp(
                        version = "Version $version",
                        onOpenUrl = ::openUrl,
                    )
                }
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
}
