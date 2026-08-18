package network.erth.wallet.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import network.erth.wallet.ui.theme.EarthTheme

/**
 * The Compose app, against the real chain.
 *
 * Runs alongside the existing HostActivity rather than replacing it: the
 * rewrite ports screens one at a time, and both can be installed at once while
 * that happens. Started directly:
 *
 *     adb shell am start -n network.erth.wallet/.ui.compose.ComposeAppActivity
 */
class ComposeAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EarthTheme {
                val vm: WalletViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val locked by vm.locked.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.refresh() }
                if (locked) {
                    // The wallet lives behind a PIN session. Until the PIN
                    // screen is ported, say so plainly rather than showing a
                    // convincing wallet full of zeroes.
                    LockedScreen(onUnlock = { vm.refresh() })
                } else {
                    EarthApp(state = state, onSend = {}, onReceive = {})
                }
            }
        }
    }
}
