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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.theme.colors.EarthColors

@Composable
fun EarthCircularProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    colors: EarthCircularProgressIndicatorColors =
        LocalEarthCircularProgressIndicatorColors.current
            ?: EarthCircularProgressIndicatorDefaults.colors()
) {
    val animatedProgress by animateFloatAsState(
        progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
    )
    CircularProgressIndicator(
        modifier = modifier,
        color = colors.progressColor,
        trackColor = colors.trackColor,
        progress = { animatedProgress },
        gapSize = 0.dp,
        strokeWidth = 3.dp
    )
}

@Composable
fun EarthCircularProgressIndicatorByPercent(
    progressPercent: Float,
    modifier: Modifier = Modifier,
    colors: EarthCircularProgressIndicatorColors =
        LocalEarthCircularProgressIndicatorColors.current
            ?: EarthCircularProgressIndicatorDefaults.colors()
) {
    EarthCircularProgressIndicator(
        progress = progressPercent / 100f,
        modifier = modifier,
        colors = colors
    )
}

data class EarthCircularProgressIndicatorColors(
    val progressColor: Color,
    val trackColor: Color
)

@Suppress("CompositionLocalAllowlist")
val LocalEarthCircularProgressIndicatorColors = compositionLocalOf<EarthCircularProgressIndicatorColors?> { null }

object EarthCircularProgressIndicatorDefaults {
    @Composable
    fun colors(
        progressColor: Color = EarthColors.Utility.Purple.utilityPurple50,
        trackColor: Color = EarthColors.Utility.Purple.utilityPurple400
    ) = EarthCircularProgressIndicatorColors(
        progressColor = progressColor,
        trackColor = trackColor
    )
}
