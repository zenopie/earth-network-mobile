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
import androidx.compose.ui.text.font.FontFamily
import network.erth.wallet.ui.vendor.theme.typography.InterFontFamily
import network.erth.wallet.ui.vendor.theme.typography.RobotoMonoFontFamily

enum class StyledStringFont {
    INTER,
    ROBOTO_MONO
}

@Composable
fun StyledStringFont.getFontFamily(): FontFamily =
    when (this) {
        StyledStringFont.INTER -> InterFontFamily
        StyledStringFont.ROBOTO_MONO -> RobotoMonoFontFamily
    }
