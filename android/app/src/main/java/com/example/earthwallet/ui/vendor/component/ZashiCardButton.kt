/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthCardButton(
    state: ButtonState,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier =
            modifier
                .clickable(enabled = state.isEnabled) {
                    if (state.hapticFeedbackType != null) {
                        runCatching { haptic.performHapticFeedback(state.hapticFeedbackType) }
                    }
                    state.onClick()
                },
        shape = RoundedCornerShape(EarthDimensions.Radius.radiusXl),
        color = EarthColors.Surfaces.bgPrimary,
        border = BorderStroke(1.dp, EarthColors.Surfaces.strokeSecondary)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.icon != null) {
                Image(
                    painter = painterResource(state.icon),
                    contentDescription = null,
                )
            }

            Text(
                text = state.text.getValue(),
                style = EarthTypography.textSm,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary,
                modifier = Modifier.weight(1f)
            )

            if (state.trailingIcon != null) {
                Image(
                    painter = painterResource(state.trailingIcon),
                    contentDescription = null,
                )
            }
        }
    }
}

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthCardButton(
                state =
                    ButtonState(
                        text = stringRes("Switch server"),
                        icon = android.R.drawable.ic_menu_info_details,
                        trailingIcon = network.erth.wallet.R.drawable.ic_chevron_right,
                        onClick = {}
                    )
            )
        }
    }
