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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors

@Composable
fun EarthLinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    colors: EarthLinearProgressIndicatorColors = EarthLinearProgressIndicatorDefaults.defaultColors(),
    size: EarthLinearProgressIndicatorSize = EarthLinearProgressIndicatorDefaults.defaultSize(),
) {
    LinearProgressIndicator(
        drawStopIndicator = {},
        progress = { progress },
        color = colors.progressColor,
        trackColor = colors.trackColor,
        strokeCap = StrokeCap.Round,
        gapSize = size.gap,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(size.height)
                .then(modifier)
    )
}

data class EarthLinearProgressIndicatorColors(
    val progressColor: Color,
    val trackColor: Color,
)

data class EarthLinearProgressIndicatorSize(
    val height: Dp,
    val gap: Dp = -height
)

object EarthLinearProgressIndicatorDefaults {
    @Composable
    fun keystoneColors(
        progressColor: Color = EarthColors.Surfaces.brandBg,
        trackColor: Color = EarthColors.Surfaces.bgTertiary,
    ) = EarthLinearProgressIndicatorColors(
        progressColor = progressColor,
        trackColor = trackColor,
    )

    @Composable
    fun defaultColors(
        progressColor: Color = EarthColors.Text.textPrimary,
        trackColor: Color = EarthColors.Surfaces.bgQuaternary,
    ) = EarthLinearProgressIndicatorColors(
        progressColor = progressColor,
        trackColor = trackColor,
    )

    fun keystoneSize(height: Dp = 4.dp) = EarthLinearProgressIndicatorSize(height = height)

    fun defaultSize(height: Dp = 8.dp) = EarthLinearProgressIndicatorSize(height = height)
}

@Preview
@Composable
private fun EarthLinearProgressIndicatorPreview() {
    ZcashTheme(forceDarkMode = false) {
        @Suppress("MagicNumber")
        EarthLinearProgressIndicator(0.75f)
    }
}
