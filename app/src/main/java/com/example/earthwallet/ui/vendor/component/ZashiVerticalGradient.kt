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

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.util.orDark

@Composable
fun earthVerticalGradient(
    startColor: Color = EarthColors.Utility.WarningYellow.utilityOrange100,
    endColor: Color = EarthColors.Surfaces.bgPrimary,
    startStop: Float = VERTICAL_GRADIENT_START_STOP,
    endStop: Float = VERTICAL_GRADIENT_END_STOP_LIGHT orDark VERTICAL_GRADIENT_END_STOP_DARK
) = Brush.verticalGradient(
    startStop to startColor,
    endStop to endColor,
)

const val VERTICAL_GRADIENT_START_STOP = .0f
const val VERTICAL_GRADIENT_END_STOP_DARK = .35f
const val VERTICAL_GRADIENT_END_STOP_LIGHT = .4f
