package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme

/**
 * The app shell: a destination, its screen, and the bar that switches them.
 *
 * Navigation is a single piece of state rather than navigation-compose, for
 * now. The old app swapped fragments by tag through HostActivity and had no
 * back stack worth preserving, so there is nothing to port — and a NavHost
 * earns its complexity when there are arguments and deep links to carry, which
 * there are not yet.
 */
@Composable
fun EarthApp(
    state: WalletUiState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
) {
    val colors = EarthTheme.colors
    var current by remember { mutableStateOf(EarthDestination.Wallet) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.Surfaces.bgPrimary),
    ) {
        Box(Modifier.weight(1f)) {
            when (current) {
                EarthDestination.Wallet ->
                    WalletScreen(state = state, onSend = onSend, onReceive = onReceive)
                EarthDestination.Earn ->
                    Placeholder("Earn", "Staking, rewards and the allocation streams.")
                EarthDestination.Swap ->
                    Placeholder("Swap", "The ERTH/ANML pool and liquidity.")
                EarthDestination.Settings ->
                    Placeholder("Settings", "Wallets, keys and the node this app talks to.")
            }
        }
        EarthNavBar(current = current, onSelect = { current = it })
    }
}

/**
 * A destination that exists in the bar but not yet in Compose.
 *
 * Says what will be here rather than "coming soon", so the shell can ship
 * before every screen is ported and it is obvious which are outstanding.
 */
@Composable
private fun Placeholder(title: String, detail: String) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.Surfaces.bgPrimary)
            .padding(dimens.gutter),
    ) {
        androidx.compose.material3.Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = colors.Text.textPrimary,
        )
        androidx.compose.material3.Text(
            text = detail,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = colors.Text.textSecondary,
        )
    }
}
