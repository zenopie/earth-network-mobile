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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

@Composable
@ReadOnlyComposable
// Earth change: the app has one mode, so the dark branch is never taken. Kept
// as a function rather than deleted because ~30 vendored components call it,
// and stripping it from all of them is churn that makes the next re-vendor
// harder for no gain.
@Suppress("UnusedParameter")
infix fun <T> T.orDark(dark: T): T = this
