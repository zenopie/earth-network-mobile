/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class Dimens(
    // Default spacings:
    val spacingNone: Dp,
    val spacingMini: Dp,
    val spacingXtiny: Dp,
    val spacingMin: Dp,
    val spacingTiny: Dp,
    val spacingSmall: Dp,
    val spacingMid: Dp,
    val spacingDefault: Dp,
    val spacingLarge: Dp,
    val spacingUpLarge: Dp,
    val spacingBig: Dp,
    val spacingHuge: Dp,
    // List of custom spacings:
    // Button:
    val buttonShadowOffsetX: Dp,
    val buttonShadowOffsetY: Dp,
    val buttonShadowSpread: Dp,
    val buttonWidth: Dp,
    val buttonHeight: Dp,
    val buttonHeightSmall: Dp,
    // Screen background grid
    val gridCellSize: Dp,
    val gridLineWidth: Dp,
    // Chip
    val chipShadowElevation: Dp,
    val chipStroke: Dp,
    // Progress
    val circularScreenProgressWidth: Dp,
    val circularMidProgressWidth: Dp,
    val circularSmallProgressWidth: Dp,
    val linearProgressHeight: Dp,
    // TopAppBar:
    val topAppBarZcashLogoHeight: Dp,
    // TextField:
    val textFieldDefaultHeight: Dp,
    val textFieldSeedPanelDefaultHeight: Dp,
    val textFieldMemoPanelDefaultHeight: Dp,
    // Any Layout:
    val divider: Dp,
    val layoutStroke: Dp,
    val regularRippleEffectCorner: Dp,
    val smallRippleEffectCorner: Dp,
    val tinyRippleEffectCorner: Dp,
    // Screen custom spacings:
    val inScreenZcashLogoHeight: Dp,
    val inScreenZcashLogoWidth: Dp,
    val inScreenZcashTextLogoHeight: Dp,
    val screenHorizontalSpacingBig: Dp,
    val screenHorizontalSpacingRegular: Dp,
    val cameraTorchButton: Dp,
)

private val defaultDimens =
    Dimens(
        spacingNone = 0.dp,
        spacingMini = 1.dp,
        spacingXtiny = 2.dp,
        spacingTiny = 4.dp,
        spacingMin = 6.dp,
        spacingSmall = 8.dp,
        spacingMid = 12.dp,
        spacingDefault = 16.dp,
        spacingLarge = 24.dp,
        spacingUpLarge = 32.dp,
        spacingBig = 48.dp,
        spacingHuge = 64.dp,
        buttonShadowOffsetX = 20.dp,
        buttonShadowOffsetY = 20.dp,
        buttonShadowSpread = 10.dp,
        buttonWidth = 244.dp,
        buttonHeight = 56.dp,
        buttonHeightSmall = 38.dp,
        gridCellSize = 14.dp,
        gridLineWidth = 1.dp,
        chipShadowElevation = 2.dp,
        chipStroke = 0.5.dp,
        circularScreenProgressWidth = 48.dp,
        circularMidProgressWidth = 22.dp,
        circularSmallProgressWidth = 14.dp,
        linearProgressHeight = 14.dp,
        topAppBarZcashLogoHeight = 24.dp,
        textFieldDefaultHeight = 40.dp,
        textFieldSeedPanelDefaultHeight = 215.dp,
        textFieldMemoPanelDefaultHeight = 140.dp,
        layoutStroke = 1.dp,
        divider = 1.dp,
        regularRippleEffectCorner = 28.dp,
        smallRippleEffectCorner = 10.dp,
        tinyRippleEffectCorner = 8.dp,
        inScreenZcashLogoHeight = 100.dp,
        inScreenZcashLogoWidth = 60.dp,
        inScreenZcashTextLogoHeight = 30.dp,
        screenHorizontalSpacingBig = 64.dp,
        screenHorizontalSpacingRegular = 32.dp,
        cameraTorchButton = 20.dp,
    )

private val normalDimens = defaultDimens

internal var localDimens = staticCompositionLocalOf { defaultDimens }

/**
 * This is a convenience way on how to provide device specification based spacings. We use Configuration from Compose
 * package for this purpose.
 *
 * For now we use just "normal" sized spacings for phone devices. Here is an example how we could split spacings to
 * more groups based on screen size:
 *
 *     val screenLayoutBitMask = LocalConfiguration.current.screenLayout
 *     val resultDimens = when (screenLayoutBitMask and SCREENLAYOUT_SIZE_MASK) {
 *         SCREENLAYOUT_SIZE_SMALL -> {
 *             Log.d("Earth", ("Current device screen size: SMALL. Screen is approximately 320x426 dp at least.").toString())
 *             smallDimens
 *         }
 *         SCREENLAYOUT_SIZE_NORMAL -> {
 *             Log.d("Earth", ("Current device screen size: NORMAL. Screen is approximately 320x470 dp at least.").toString())
 *             normalDimens
 *         }
 *         SCREENLAYOUT_SIZE_LARGE -> {
 *             Log.d("Earth", ("Current device screen size: LARGE. Screen is approximately 480x640 dp at least.").toString())
 *             largeDimens
 *         }
 *         SCREENLAYOUT_SIZE_XLARGE -> {
 *             Log.d("Earth", ("Current device screen size: XLARGE. Screen is approximately 720x960 dp at least.").toString())
 *             xlargeDimens
 *         }
 *         else -> {
 *             Log.d("Earth", ("Current device screen size: UNDEFINED - using NORMAL size values.").toString())
 *             normalDimens
 *         }
 *     }
 *     LocalDimens = staticCompositionLocalOf { resultDimens }
 *
 *
 * Alternatively we could also use different values based on these metrics:
 *  - landscape/portrait orientation modes
 *  - long/normal screen aspect ratio (screen is wider/taller than normal)
 *  - LRT/RTL screen set up
 *  - rounded/normal screen shape
 */
@Composable
internal fun ProvideDimens(content: @Composable () -> Unit) {
    val resultDimens = normalDimens
    CompositionLocalProvider(localDimens provides resultDimens, content = content)
}
