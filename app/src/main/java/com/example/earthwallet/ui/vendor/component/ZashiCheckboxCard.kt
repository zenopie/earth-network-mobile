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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthCheckboxCard(state: CheckboxState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EarthDimensions.Radius.radiusXl),
        border = BorderStroke(1.dp, EarthColors.Surfaces.strokeSecondary),
    ) {
        EarthCheckbox(
            state = state,
            spacing = 16.dp,
            contentPadding = PaddingValues(16.dp),
            textStyles =
                EarthCheckboxDefaults.textStyles(
                    title =
                        EarthTypography.textSm.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = EarthColors.Text.textPrimary
                        ),
                    subtitle =
                        EarthTypography.textSm.copy(
                            color = EarthColors.Text.textTertiary
                        )
                )
        )
    }
}

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthCheckboxCard(
                state =
                    CheckboxState(
                        title = stringRes("title"),
                        subtitle = stringRes("subtitle"),
                        isChecked = false,
                        onClick = {}
                    )
            )
        }
    }
