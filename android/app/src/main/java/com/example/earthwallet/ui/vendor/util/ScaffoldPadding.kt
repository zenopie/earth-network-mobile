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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions

@Stable
fun Modifier.scaffoldPadding(
    paddingValues: PaddingValues,
    top: Dp = paddingValues.calculateTopPadding() + EarthDimensions.Spacing.spacingLg,
    bottom: Dp = paddingValues.calculateBottomPadding() + EarthDimensions.Spacing.spacing3xl,
    start: Dp = EarthDimensions.Spacing.spacing3xl,
    end: Dp = EarthDimensions.Spacing.spacing3xl
) = this.padding(
    top = top,
    bottom = bottom,
    start = start,
    end = end,
)

fun Modifier.scaffoldScrollPadding(
    paddingValues: PaddingValues,
    top: Dp = paddingValues.calculateTopPadding() + EarthDimensions.Spacing.spacingLg,
    bottom: Dp = paddingValues.calculateBottomPadding() + EarthDimensions.Spacing.spacing3xl,
    start: Dp = 0.dp,
    end: Dp = 0.dp
) = this.padding(
    top = top,
    bottom = bottom,
    start = start,
    end = end,
)

@Stable
fun PaddingValues.asScaffoldPaddingValues(
    top: Dp = calculateTopPadding() + EarthDimensions.Spacing.spacingLg,
    bottom: Dp = calculateBottomPadding() + EarthDimensions.Spacing.spacing3xl,
    start: Dp = EarthDimensions.Spacing.spacing3xl,
    end: Dp = EarthDimensions.Spacing.spacing3xl
) = PaddingValues(
    top = top,
    bottom = bottom,
    start = start,
    end = end,
)

@Stable
fun PaddingValues.asScaffoldScrollPaddingValues(
    top: Dp = calculateTopPadding() + EarthDimensions.Spacing.spacingLg,
    bottom: Dp = calculateBottomPadding() + EarthDimensions.Spacing.spacing3xl,
    start: Dp = 0.dp,
    end: Dp = 0.dp
) = PaddingValues(
    top = top,
    bottom = bottom,
    start = start,
    end = end,
)
