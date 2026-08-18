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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.StyledStringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes
import network.erth.wallet.ui.vendor.util.withStyle

@Composable
fun EarthMessage(state: EarthMessageState) {
    EarthCard(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (state.type) {
                        EarthMessageState.Type.INFO -> EarthColors.Utility.HyperBlue.utilityBlueDark50
                        EarthMessageState.Type.WARNING -> EarthColors.Utility.WarningYellow.utilityOrange50
                        EarthMessageState.Type.ERROR -> EarthColors.Utility.ErrorRed.utilityError50
                    },
            ),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row {
            Image(
                painter = painterResource(network.erth.wallet.R.drawable.ic_info),
                contentDescription = null,
                colorFilter =
                    ColorFilter.tint(
                        when (state.type) {
                            EarthMessageState.Type.INFO -> EarthColors.Utility.HyperBlue.utilityBlueDark500
                            EarthMessageState.Type.WARNING -> EarthColors.Utility.WarningYellow.utilityOrange500
                            EarthMessageState.Type.ERROR -> EarthColors.Utility.ErrorRed.utilityError500
                        }
                    )
            )
            Spacer(12.dp)
            Column {
                Spacer(2.dp)
                Text(
                    text = state.title.getValue(),
                    style = EarthTypography.textSm,
                    fontWeight = FontWeight.Medium,
                    color =
                        when (state.type) {
                            EarthMessageState.Type.INFO -> EarthColors.Utility.HyperBlue.utilityBlueDark700
                            EarthMessageState.Type.WARNING -> EarthColors.Utility.WarningYellow.utilityOrange700
                            EarthMessageState.Type.ERROR -> EarthColors.Utility.ErrorRed.utilityError700
                        }
                )
                Spacer(8.dp)
                Text(
                    text = state.text.getValue(),
                    style = EarthTypography.textXs,
                    color =
                        when (state.type) {
                            EarthMessageState.Type.INFO -> EarthColors.Utility.HyperBlue.utilityBlueDark800
                            EarthMessageState.Type.WARNING -> EarthColors.Utility.WarningYellow.utilityOrange800
                            EarthMessageState.Type.ERROR -> EarthColors.Utility.ErrorRed.utilityError800
                        }
                )
            }
        }
    }
}

data class EarthMessageState(
    val title: StringResource,
    val text: StyledStringResource,
    val type: Type
) {
    enum class Type {
        INFO,
        WARNING,
        ERROR
    }

    companion object {
        val preview =
            EarthMessageState(
                stringRes("Title"),
                stringRes("Text").withStyle(),
                Type.INFO
            )
    }
}

@PreviewScreens
@Composable
private fun EarthInfoMessagePreview() =
    ZcashTheme {
        EarthMessage(EarthMessageState.preview.copy(type = EarthMessageState.Type.INFO))
    }

@PreviewScreens
@Composable
private fun EarthWarningMessagePreview() =
    ZcashTheme {
        EarthMessage(EarthMessageState.preview.copy(type = EarthMessageState.Type.WARNING))
    }

@PreviewScreens
@Composable
private fun EarthErrorMessagePreview() =
    ZcashTheme {
        EarthMessage(EarthMessageState.preview.copy(type = EarthMessageState.Type.ERROR))
    }
