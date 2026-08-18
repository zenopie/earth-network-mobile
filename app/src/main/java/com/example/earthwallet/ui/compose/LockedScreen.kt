package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.theme.EarthTheme

/**
 * No unlocked wallet session.
 *
 * A locked wallet is a normal state, not an error, so this says what to do
 * rather than what went wrong. Showing the wallet UI with zeroes instead would
 * be worse than useless — an empty balance and a locked one look identical, and
 * only one of them is alarming.
 */
@Composable
fun LockedScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaces.bgPrimary)
            .padding(dimens.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Wallet locked",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = "Open Earth Wallet and enter your PIN, then come back.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(dimens.space24))
        EarthButton("Try again", onUnlock, style = EarthButtonStyle.Secondary)
    }
}
