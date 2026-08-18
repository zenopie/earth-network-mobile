package network.erth.wallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import network.erth.wallet.ui.vendor.theme.colors.DarkEarthColorsInternal
import network.erth.wallet.ui.vendor.theme.colors.EarthColorsInternal
import network.erth.wallet.ui.vendor.theme.colors.LightEarthColorsInternal
import network.erth.wallet.ui.vendor.theme.colors.LocalDarkEarthColors
import network.erth.wallet.ui.vendor.theme.colors.LocalEarthColors
import network.erth.wallet.ui.vendor.theme.colors.LocalLightEarthColors

/**
 * The app's theme.
 *
 * `EarthTheme.colors` is the vendored colour system — 420 semantic tokens in
 * Sprout's palette. Typography and dimens are ours: theirs pulled Google Fonts
 * through their own resources, and a type scale is small enough to be worth
 * owning outright.
 *
 * A MaterialTheme is installed underneath and fed from the same tokens, so a
 * Material component reaching for its own default cannot introduce a colour
 * from outside the system.
 */
object EarthTheme {
    val colors: EarthColorsInternal
        @Composable get() = LocalEarthColors.current

    val dimens: EarthDimens
        @Composable get() = LocalEarthDimens.current

    /** Earth's four pillars. Not part of the vendored system. */
    val domain: EarthDomainColors
        @Composable get() = LocalEarthDomainColors.current
}

@Suppress("CompositionLocalAllowlist")
internal val LocalEarthDimens = staticCompositionLocalOf { EarthDimens() }

@Composable
fun EarthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkEarthColorsInternal else LightEarthColorsInternal

    // Fed from the same tokens so a Material component reaching for its own
    // default cannot introduce a colour from outside the system.
    val material =
        if (darkTheme) {
            darkColorScheme(
                primary = colors.Btns.Brand.btnBrandBg,
                onPrimary = colors.Btns.Brand.btnBrandFg,
                background = colors.Surfaces.bgPrimary,
                onBackground = colors.Text.textPrimary,
                surface = colors.Surfaces.bgPrimary,
                onSurface = colors.Text.textPrimary,
                surfaceVariant = colors.Surfaces.bgSecondary,
                onSurfaceVariant = colors.Text.textSecondary,
                error = colors.Text.textError,
                outline = colors.Surfaces.strokePrimary,
                outlineVariant = colors.Surfaces.strokeSecondary,
            )
        } else {
            lightColorScheme(
                primary = colors.Btns.Brand.btnBrandBg,
                onPrimary = colors.Btns.Brand.btnBrandFg,
                background = colors.Surfaces.bgPrimary,
                onBackground = colors.Text.textPrimary,
                surface = colors.Surfaces.bgPrimary,
                onSurface = colors.Text.textPrimary,
                surfaceVariant = colors.Surfaces.bgSecondary,
                onSurfaceVariant = colors.Text.textSecondary,
                error = colors.Text.textError,
                outline = colors.Surfaces.strokePrimary,
                outlineVariant = colors.Surfaces.strokeSecondary,
            )
        }

    CompositionLocalProvider(
        LocalEarthColors provides colors,
        LocalLightEarthColors provides LightEarthColorsInternal,
        LocalDarkEarthColors provides DarkEarthColorsInternal,
        LocalEarthDimens provides EarthDimens(),
        LocalEarthDomainColors provides
            if (darkTheme) DarkEarthDomainColors else LightEarthDomainColors,
    ) {
        MaterialTheme(colorScheme = material, typography = EarthTypography, content = content)
    }
}
