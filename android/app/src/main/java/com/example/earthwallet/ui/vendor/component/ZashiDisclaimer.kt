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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.StringResourceColor
import network.erth.wallet.ui.vendor.util.StyledStringResource
import network.erth.wallet.ui.vendor.util.StyledStringStyle
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes
import network.erth.wallet.ui.vendor.util.styledStringResource
import network.erth.wallet.ui.vendor.util.withStyle

@Composable
fun EarthDisclaimer(
    state: EarthDisclaimerState,
    modifier: Modifier = Modifier,
) {
    EarthCard(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = EarthColors.Utility.WarningYellow.utilityOrange50,
            ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = state.value.getValue(),
            style = EarthTypography.textXs,
            color = EarthColors.Utility.WarningYellow.utilityOrange900,
        )
    }
}

data class EarthDisclaimerState(
    val value: StyledStringResource
) {
    companion object {
        fun warning(text: StyledStringResource) =
            EarthDisclaimerState(
                styledStringResource(
                    R.string.general_warning,
                    StyledStringStyle(color = StringResourceColor.WARNING, fontWeight = FontWeight.Bold),
                ) + text
            )

        fun warning(text: StringResource) =
            EarthDisclaimerState(
                styledStringResource(
                    R.string.general_warning,
                    StyledStringStyle(color = StringResourceColor.WARNING, fontWeight = FontWeight.Bold),
                ) +
                    text.withStyle(
                        StyledStringStyle(
                            color = StringResourceColor.WARNING,
                            fontWeight = FontWeight.Normal
                        )
                    )
            )
    }
}

@PreviewScreens
@Composable
private fun EarthDisclaimerPreview() =
    ZcashTheme {
        EarthDisclaimer(EarthDisclaimerState.warning(stringRes("Test")))
    }
