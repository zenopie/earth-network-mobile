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

import android.content.res.Configuration

/**
 * @param isDarkTheme true to force dark theme, false to force light theme, null to not override
 */
fun Configuration.createConfiguration(
    isDarkTheme: Boolean?
): Configuration =
    Configuration(this).apply {
        when (isDarkTheme) {
            true -> {
                uiMode = Configuration.UI_MODE_NIGHT_YES
            }

            false -> {
                uiMode = Configuration.UI_MODE_NIGHT_NO
            }

            null -> {
                // do not override
            }
        }
    }
