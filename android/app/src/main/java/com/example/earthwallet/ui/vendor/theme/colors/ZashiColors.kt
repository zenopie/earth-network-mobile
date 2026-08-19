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

package network.erth.wallet.ui.vendor.theme.colors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val EarthColors: EarthColorsInternal
    @Composable get() = LocalEarthColors.current

val EarthLightColors: EarthColorsInternal
    @Composable get() = LocalLightEarthColors.current

val EarthDarkColors: EarthColorsInternal
    @Composable get() = LocalDarkEarthColors.current

@Suppress("CompositionLocalAllowlist")
internal val LocalEarthColors = staticCompositionLocalOf<EarthColorsInternal> { error("no colors specified") }

@Suppress("CompositionLocalAllowlist")
internal val LocalLightEarthColors = staticCompositionLocalOf { LightEarthColorsInternal }

@Suppress("CompositionLocalAllowlist")
internal val LocalDarkEarthColors = staticCompositionLocalOf { DarkEarthColorsInternal }
