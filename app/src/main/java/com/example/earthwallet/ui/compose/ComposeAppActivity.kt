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
 * Two states and one transition: locked until the PIN opens the session, then
 * the wallet. The old app split these across a launcher activity, an update
 * check, a host activity and a fragment; the transition between them is a
 * boolean, and a boolean does not need four activities.
 *
 * HostActivity still exists and still works — the screens not yet ported are
 * reachable from it — but it is no longer what starts.
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
                val wallet: WalletViewModel = viewModel()

                val unlocked by unlock.unlocked.collectAsStateWithLifecycle()
                val error by unlock.error.collectAsStateWithLifecycle()
                val lockout by unlock.lockout.collectAsStateWithLifecycle()

                // A session can already be open — the process may have been
                // restarted while the app was backgrounded, or the old
                // HostActivity may have opened one.
                val open = unlocked || SessionManager.isSessionActive()

                if (!open) {
                    UnlockScreen(
                        onSubmit = unlock::submit,
                        error = error,
                        lockoutMessage = lockout,
                    )
                } else {
                    val state by wallet.state.collectAsStateWithLifecycle()
                    val activity by wallet.activity.collectAsStateWithLifecycle()
                    LaunchedEffect(open) { wallet.refresh() }

                    EarthApp(
                        state = state,
                        activity = activity,
                        version = "Version $version",
                        onSendTx = { _, _ -> },
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
