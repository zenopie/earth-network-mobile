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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import network.erth.wallet.ui.vendor.util.createConfiguration

/**
 * @param isDarkTheme true to force dark theme, false to force light theme, null to not override
 */
@Composable
fun ConfigurationOverride(
    isDarkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val newConfiguration =
        remember(configuration) {
            configuration.createConfiguration(isDarkTheme)
        }
    val newContext by remember(context) {
        derivedStateOf {
            context.createConfigurationContext(newConfiguration)
        }
    }

    CompositionLocalProvider(
        LocalConfiguration provides newConfiguration,
        LocalContext provides newContext
    ) {
        content()
    }
}
