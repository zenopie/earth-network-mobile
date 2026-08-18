package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme

/** The app's four destinations — one per pillar the chain actually has. */
enum class EarthDestination(val route: String, val label: String, val glyph: String) {
    Wallet("wallet", "Wallet", "◎"),
    Earn("earn", "Earn", "▲"),
    Swap("swap", "Swap", "⇄"),
    Settings("settings", "Settings", "⚙"),
}

/**
 * Bottom navigation.
 *
 * Deliberately not Material's NavigationBar: that comes with its own elevation,
 * indicator pill and colour roles, and bending those back to the tokens is more
 * work than a Row. The selected state is carried by colour *and* weight, so it
 * survives being screenshotted in greyscale.
 */
@Composable
fun EarthNavBar(
    current: EarthDestination,
    onSelect: (EarthDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaces.bgPrimary)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = dimens.space8),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        EarthDestination.entries.forEach { dest ->
            val selected = dest == current
            Column(
                modifier = Modifier
                    .clickable { onSelect(dest) }
                    .padding(vertical = dimens.space4, horizontal = dimens.space12),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = dest.glyph,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) colors.text.textPrimary else colors.text.textTertiary,
                )
                Text(
                    text = dest.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.text.textPrimary else colors.text.textTertiary,
                )
            }
        }
    }
}
