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

import androidx.compose.ui.graphics.Color
import network.erth.wallet.ui.vendor.theme.internal.ButtonColors
import network.erth.wallet.ui.vendor.theme.internal.TopAppBarColors

data class ExtendedColors(
    val primaryColor: Color,
    val secondaryColor: Color,
    val backgroundColor: Color,
    val gridColor: Color,
    val circularProgressBarSmall: Color,
    val circularProgressBarSmallDark: Color,
    val circularProgressBarScreen: Color,
    val linearProgressBarTrack: Color,
    val linearProgressBarBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDescription: Color,
    val textDisabled: Color,
    val textFieldHint: Color,
    val textFieldWarning: Color,
    val textFieldFrame: Color,
    val textDescriptionDark: Color,
    val layoutStroke: Color,
    val layoutStrokeSecondary: Color,
    val overlay: Color,
    val overlayProgressBar: Color,
    val reference: Color,
    val primaryButtonColors: ButtonColors,
    val secondaryButtonColors: ButtonColors,
    val tertiaryButtonColors: ButtonColors,
    val welcomeAnimationColor: Color,
    val complementaryColor: Color,
    val primaryDividerColor: Color,
    val secondaryDividerColor: Color,
    val tertiaryDividerColor: Color,
    val panelBackgroundColor: Color,
    val panelBackgroundColorActive: Color,
    val cameraDisabledBackgroundColor: Color,
    val cameraDisabledFrameColor: Color,
    val historyBackgroundColor: Color,
    val historyMessageBubbleColor: Color,
    val historyMessageBubbleStrokeColor: Color,
    val historyRedColor: Color,
    val historySyncingColor: Color,
    val topAppBarColors: TopAppBarColors,
    val transparentTopAppBarColors: TopAppBarColors,
)
