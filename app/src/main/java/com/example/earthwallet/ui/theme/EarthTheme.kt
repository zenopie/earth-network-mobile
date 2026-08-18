package network.erth.wallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import network.erth.wallet.ui.theme.colors.DarkEarthColorsInternal
import network.erth.wallet.ui.theme.colors.EarthColorsInternal
import network.erth.wallet.ui.theme.colors.LightEarthColorsInternal

/**
 * The app's theme.
 *
 * `EarthTheme.colors` is the surface screens use. A MaterialTheme is still
 * installed underneath because Material 3 components read their own scheme —
 * but it is fed from the same tokens, so a stray Material default cannot
 * introduce a colour that is not in the system.
 */
object EarthTheme {
    val colors: EarthColorsInternal
        @Composable get() = LocalEarthColors.current

    val dimens: EarthDimens
        @Composable get() = LocalEarthDimens.current
}

@Suppress("CompositionLocalAllowlist")
internal val LocalEarthColors =
    staticCompositionLocalOf<EarthColorsInternal> { error("EarthTheme not applied") }

@Suppress("CompositionLocalAllowlist")
internal val LocalEarthDimens = staticCompositionLocalOf { EarthDimens() }

@Composable
fun EarthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkEarthColorsInternal else LightEarthColorsInternal

    val material =
        if (darkTheme) {
            darkColorScheme(
                primary = colors.btnPrimary.bg,
                onPrimary = colors.btnPrimary.fg,
                background = colors.surfaces.bgPrimary,
                onBackground = colors.text.textPrimary,
                surface = colors.surfaces.bgPrimary,
                onSurface = colors.text.textPrimary,
                error = colors.text.textError,
                outline = colors.surfaces.strokePrimary,
            )
        } else {
            lightColorScheme(
                primary = colors.btnPrimary.bg,
                onPrimary = colors.btnPrimary.fg,
                background = colors.surfaces.bgPrimary,
                onBackground = colors.text.textPrimary,
                surface = colors.surfaces.bgPrimary,
                onSurface = colors.text.textPrimary,
                error = colors.text.textError,
                outline = colors.surfaces.strokePrimary,
            )
        }

    CompositionLocalProvider(
        LocalEarthColors provides colors,
        LocalEarthDimens provides EarthDimens(),
    ) {
        MaterialTheme(
            colorScheme = material,
            typography = EarthTypography,
            content = content,
        )
    }
}
