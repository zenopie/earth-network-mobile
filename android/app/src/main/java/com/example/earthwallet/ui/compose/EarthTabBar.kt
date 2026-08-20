package network.erth.wallet.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.component.EarthHorizontalDivider
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * The tab bar.
 *
 * Deliberately not Material's NavigationBar: that arrives with its own
 * elevation, an indicator pill and a set of colour roles, and bending those
 * back onto the vendored tokens is more work than a Row. A hairline above it
 * instead of a shadow, matching how the rest of the app separates surfaces.
 *
 * Selection is carried by ink and weight together, not colour alone, so the
 * current tab survives a greyscale screenshot and a colour-blind reader.
 */
@Composable
fun EarthTabBar(
    current: EarthRoute.Tab,
    onSelect: (EarthRoute.Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(EarthColors.Surfaces.bgPrimary)) {
        EarthHorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            EARTH_TABS.forEach { tab ->
                val selected = tab == current
                val tint by animateColorAsState(
                    if (selected) EarthColors.Text.textPrimary else EarthColors.Text.textTertiary,
                    label = "tabTint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(
                            // No ripple: four of these side by side, and a
                            // rectangular ripple on each reads as a grid of
                            // boxes rather than a row of icons.
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(tab) },
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        modifier = Modifier.size(22.dp),
                        // Stroke weight carries the selection alongside ink and
                        // label weight, which is what the iOS bar does.
                        imageVector = tabGlyph(tab, selected),
                        colorFilter = ColorFilter.tint(tint),
                        contentDescription = null,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = EarthTypography.textXs,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                    )
                }
            }
        }
    }
}
