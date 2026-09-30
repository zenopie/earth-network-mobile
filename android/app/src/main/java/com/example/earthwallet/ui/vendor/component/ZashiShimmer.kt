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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import com.valentinilk.shimmer.LocalShimmerTheme
import com.valentinilk.shimmer.ShimmerBounds
import com.valentinilk.shimmer.rememberShimmer

@Composable
fun rememberEarthShimmer() =
    rememberShimmer(
        ShimmerBounds.View,
        LocalShimmerTheme.current.copy(
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = 750,
                            easing = LinearEasing,
                            delayMillis = 450,
                        ),
                    repeatMode = RepeatMode.Restart,
                )
        )
    )

@Composable
fun ShimmerCircle(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = EarthColors.Surfaces.bgSecondary
) {
    Box(
        modifier =
            modifier
                .size(size)
                .background(color, CircleShape)
    )
}

@Composable
fun ShimmerRectangle(
    width: Dp = 40.dp,
    height: Dp = 20.dp,
    color: Color = EarthColors.Surfaces.bgSecondary,
    shape: Shape = RoundedCornerShape(EarthDimensions.Radius.radiusSm)
) {
    Box(
        modifier =
            Modifier
                .width(width)
                .height(height)
                .background(color, shape)
    )
}

@Composable
fun ShimmerRectangle(
    modifier: Modifier = Modifier,
    color: Color = EarthColors.Surfaces.bgSecondary,
    shape: Shape = RoundedCornerShape(EarthDimensions.Radius.radiusSm)
) {
    Box(
        modifier =
            modifier
                .background(color, shape)
    )
}
