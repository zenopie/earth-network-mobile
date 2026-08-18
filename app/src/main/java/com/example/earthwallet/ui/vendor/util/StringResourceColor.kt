/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.util

import androidx.compose.runtime.Composable
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.util.StringResourceColor.HINT_ERROR
import network.erth.wallet.ui.vendor.util.StringResourceColor.NEGATIVE
import network.erth.wallet.ui.vendor.util.StringResourceColor.POSITIVE
import network.erth.wallet.ui.vendor.util.StringResourceColor.PRIMARY
import network.erth.wallet.ui.vendor.util.StringResourceColor.QUARTERNARY
import network.erth.wallet.ui.vendor.util.StringResourceColor.TERTIARY
import network.erth.wallet.ui.vendor.util.StringResourceColor.WARNING

enum class StringResourceColor {
    PRIMARY,
    TERTIARY,
    POSITIVE,
    NEGATIVE,
    HINT_ERROR,
    QUARTERNARY,
    WARNING
}

@Composable
fun StringResourceColor.getColor() =
    when (this) {
        PRIMARY -> EarthColors.Text.textPrimary
        TERTIARY -> EarthColors.Text.textTertiary
        POSITIVE -> EarthColors.Utility.SuccessGreen.utilitySuccess700
        NEGATIVE -> EarthColors.Text.textError
        HINT_ERROR -> EarthColors.Inputs.ErrorDefault.hint
        QUARTERNARY -> EarthColors.Text.textQuaternary
        WARNING -> EarthColors.Utility.WarningYellow.utilityOrange800
    }
