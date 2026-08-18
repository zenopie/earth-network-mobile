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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import java.util.Locale

@Composable
fun rememberDesiredFormatLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) { configuration.getPreferredLocale() }
}

fun Configuration.getPreferredLocale(): Locale {
    val locales = ConfigurationCompat.getLocales(this)
    return locales.getFirstMatch(arrayOf("en", "es")) ?: locales.get(0) ?: Locale.US
}
