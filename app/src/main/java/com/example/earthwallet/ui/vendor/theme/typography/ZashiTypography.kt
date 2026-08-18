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

package network.erth.wallet.ui.vendor.theme.typography

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val EarthTypography: EarthTypographyInternal
    @Composable get() = LocalEarthTypography.current

@Suppress("CompositionLocalAllowlist")
internal val LocalEarthTypography =
    staticCompositionLocalOf<EarthTypographyInternal> { error("no typography specified") }
