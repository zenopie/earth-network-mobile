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

import android.content.res.Configuration
import android.os.LocaleList

data class ConfigurationOverride(
    val uiMode: UiMode?,
    val locale: LocaleList?
) {
    fun newConfiguration(fromConfiguration: Configuration) =
        Configuration(fromConfiguration).apply {
            this@ConfigurationOverride.uiMode?.let {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or it.flag()
            }

            this@ConfigurationOverride.locale?.let {
                setLocales(it)
            }
        }
}

enum class UiMode {
    Light,
    Dark
}

private fun UiMode.flag() =
    when (this) {
        UiMode.Light -> Configuration.UI_MODE_NIGHT_NO
        UiMode.Dark -> Configuration.UI_MODE_NIGHT_YES
    }
