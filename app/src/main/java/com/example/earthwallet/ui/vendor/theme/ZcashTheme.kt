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

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import network.erth.wallet.ui.vendor.LocalKeyboardManager
import network.erth.wallet.ui.vendor.rememberKeyboardManager
import network.erth.wallet.ui.vendor.theme.balances.LocalBalancesAvailable
import network.erth.wallet.ui.vendor.theme.colors.DarkEarthColorsInternal
import network.erth.wallet.ui.vendor.theme.colors.LightEarthColorsInternal
import network.erth.wallet.ui.vendor.theme.colors.LocalEarthColors
import network.erth.wallet.ui.vendor.theme.internal.DarkColorPalette
import network.erth.wallet.ui.vendor.theme.internal.DarkExtendedColorPalette
import network.erth.wallet.ui.vendor.theme.internal.ExtendedTypography
import network.erth.wallet.ui.vendor.theme.internal.LightColorPalette
import network.erth.wallet.ui.vendor.theme.internal.LightExtendedColorPalette
import network.erth.wallet.ui.vendor.theme.internal.LocalExtendedColors
import network.erth.wallet.ui.vendor.theme.internal.LocalExtendedTypography
import network.erth.wallet.ui.vendor.theme.internal.LocalTypographies
import network.erth.wallet.ui.vendor.theme.internal.PrimaryTypography
import network.erth.wallet.ui.vendor.theme.internal.Typography
import network.erth.wallet.ui.vendor.theme.typography.LocalEarthTypography
import network.erth.wallet.ui.vendor.theme.typography.EarthTypographyInternal

/**
 * Commonly used top level app theme definition
 *
 * @param forceDarkMode Set this to true to force the app to use the dark mode theme, which is helpful, e.g.,
 * for the compose previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZcashTheme(
    forceDarkMode: Boolean = false,
    balancesAvailable: Boolean = true,
    content: @Composable () -> Unit
) {
    // Earth change: one mode, always light. Zashi ships light and dark and
    // lets the system pick; carrying two palettes means every colour decision
    // gets made twice and verified once, and the dark half drifts. Earth has
    // one ground. forceDarkMode stays in the signature because their previews
    // pass it, and is ignored.
    @Suppress("UNUSED_EXPRESSION") forceDarkMode
    val useDarkMode = false
    val baseColors = LightColorPalette
    val extendedColors = LightExtendedColorPalette
    val earthColors = LightEarthColorsInternal

    ZcashSystemBarTheme(useDarkMode)

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors,
        LocalEarthColors provides earthColors,
        LocalEarthTypography provides EarthTypographyInternal,
        LocalRippleConfiguration provides MaterialRippleConfig,
        LocalBalancesAvailable provides balancesAvailable,
        LocalKeyboardManager provides rememberKeyboardManager()
    ) {
        ProvideDimens {
            MaterialTheme(
                colorScheme = baseColors,
                typography = PrimaryTypography,
                content = content
            )
        }
    }
}

@Composable
private fun ZcashSystemBarTheme(useDarkMode: Boolean) {
    val activity = LocalActivity.current
    LaunchedEffect(useDarkMode) {
        if (activity is ComponentActivity) {
            if (useDarkMode) {
                activity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.dark(DefaultDarkScrim)
                )
            } else {
                activity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.light(DefaultLightScrim, DefaultDarkScrim)
                )
            }
        }
    }
}

// Use with eg. ZcashTheme.colors.tertiary
object ZcashTheme {
    val colors: ExtendedColors
        @Composable
        get() = LocalExtendedColors.current

    val typography: Typography
        @Composable
        get() = LocalTypographies.current

    val extendedTypography: ExtendedTypography
        @Composable
        get() = LocalExtendedTypography.current

    // TODO [#808]: [Design system] Use Dimens across the app
    // TODO [#808]: https://github.com/Electric-Coin-Company/earth-android/issues/808
    val dimens: Dimens
        @Composable
        get() = localDimens.current
}

@OptIn(ExperimentalMaterial3Api::class)
private val MaterialRippleConfig: RippleConfiguration
    @Composable
    get() = RippleConfiguration(color = LocalContentColor.current, rippleAlpha = RippleDefaults.RippleAlpha)

@Suppress("MagicNumber")
private val DefaultLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)

@Suppress("MagicNumber")
private val DefaultDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
