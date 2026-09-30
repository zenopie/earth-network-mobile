/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.designsystem.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import network.erth.wallet.ui.designsystem.LocalKeyboardManager
import network.erth.wallet.ui.designsystem.rememberKeyboardManager
import network.erth.wallet.ui.designsystem.theme.balances.LocalBalancesAvailable
import network.erth.wallet.ui.designsystem.theme.colors.LightEarthColorsInternal
import network.erth.wallet.ui.designsystem.theme.colors.LocalEarthColors
import network.erth.wallet.ui.designsystem.theme.internal.ExtendedTypography
import network.erth.wallet.ui.designsystem.theme.internal.LightColorPalette
import network.erth.wallet.ui.designsystem.theme.internal.LightExtendedColorPalette
import network.erth.wallet.ui.designsystem.theme.internal.LocalExtendedColors
import network.erth.wallet.ui.designsystem.theme.internal.LocalExtendedTypography
import network.erth.wallet.ui.designsystem.theme.internal.LocalTypographies
import network.erth.wallet.ui.designsystem.theme.internal.PrimaryTypography
import network.erth.wallet.ui.designsystem.theme.internal.Typography
import network.erth.wallet.ui.designsystem.theme.typography.LocalEarthTypography
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypographyInternal

/**
 * The vendored library's theme provider: extended colours, typography, ripple,
 * keyboard manager and dimensions, all of which its components read.
 *
 * Earth change: one mode, always light. Zashi ships light and dark and lets the
 * system pick; carrying two palettes means every colour decision gets made
 * twice and verified once, and the dark half drifts. Earth has one ground.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZcashTheme(
    balancesAvailable: Boolean = true,
    content: @Composable () -> Unit
) {
    val baseColors = LightColorPalette
    val extendedColors = LightExtendedColorPalette
    val earthColors = LightEarthColorsInternal

    ZcashSystemBarTheme()

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
private fun ZcashSystemBarTheme() {
    val activity = LocalActivity.current
    LaunchedEffect(Unit) {
        if (activity is ComponentActivity) {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(DefaultLightScrim, DefaultDarkScrim)
            )
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
