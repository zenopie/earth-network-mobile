/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
@file:Suppress("MagicNumber")

package network.erth.wallet.ui.vendor.theme.internal

import androidx.compose.ui.graphics.Color

// TODO [#1555]: replace by new design system
// TODO [#1555]: https://github.com/Electric-Coin-Company/earth-android/issues/1555
data class ExchangeRateColors(
    val btnSecondaryBg: Color,
    val btnSecondaryBorder: Color,
    val btnSecondaryFg: Color,
    val btnSpinnerDisabled: Color
)

internal val LightExchangeRateColorPalette =
    ExchangeRateColors(
        btnSecondaryBg = Color(0xFFFFFFFF),
        btnSecondaryBorder = Color(0xFFD9D8CF),
        btnSecondaryFg = Color(0xFF4D4941),
        btnSpinnerDisabled = Color(0x97989980)
    )

internal val DarkExchangeRateColorPalette =
    ExchangeRateColors(
        btnSecondaryBg = Color(0xFF4B4144),
        btnSecondaryBorder = Color(0xFF4B4144),
        btnSecondaryFg = Color(0xFFFFFFFF),
        btnSpinnerDisabled = Color(0xFF3D3A3B)
    )
