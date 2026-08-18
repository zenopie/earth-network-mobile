package network.erth.wallet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColorsInternal
import network.erth.wallet.ui.vendor.theme.colors.LocalEarthColors

/**
 * The app's theme.
 *
 * A thin wrapper over the vendored one rather than a replacement. Their
 * components read a good deal more than colour — extended colours, typography,
 * ripple configuration, a keyboard manager — and their theme composable is what
 * provides all of it. Reimplementing that provider was how the first attempt at
 * using their components failed.
 *
 * What is added on top is Earth's own: the pillar colours, which their palette
 * has no concept of, and a dimension scale.
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

/**
 * One theme, regardless of the system setting.
 *
 * Earth's ground is the brand green rather than a neutral with the brand
 * applied to it, so there is no dark variant to switch to — inverting it would
 * produce a different brand, not the same one after dark. The system setting is
 * deliberately not read.
 */
@Composable
fun EarthTheme(content: @Composable () -> Unit) {
    ZcashTheme {
        CompositionLocalProvider(
            LocalEarthDimens provides EarthDimens(),
            LocalEarthDomainColors provides EarthDomainColorsOnGround,
            content = content,
        )
    }
}
