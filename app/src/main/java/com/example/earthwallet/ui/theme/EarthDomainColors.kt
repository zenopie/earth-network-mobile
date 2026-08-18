package network.erth.wallet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours the vendored system has no concept of.
 *
 * Zodl's palette covers everything a wallet needs in general — surfaces, text,
 * buttons, inputs, utility ramps — and nothing specific to a chain with four
 * emission pillars. One hue per pillar, so a screen's subject is legible before
 * its label is read.
 *
 * Kept separate from the vendored files rather than edited into them, so
 * re-pulling their colour system stays a copy rather than a merge.
 */
data class EarthDomainColors(
    val anmlBg: Color,
    val anmlFg: Color,
    val stakingBg: Color,
    val stakingFg: Color,
    val dexBg: Color,
    val dexFg: Color,
    val governanceBg: Color,
    val governanceFg: Color,
    val gasWarningBg: Color,
    val gasWarningFg: Color,
)

/**
 * The pillar colours.
 *
 * One set, because the app has one ground. Pale tints for the pill behind a row
 * icon and a saturated foreground for the glyph inside it, so a pillar is
 * recognisable at 32dp without reading the label.
 *
 * ANML takes the coin's own yellow rather than a brown, so the pill matches the
 * mark that sits in it.
 */
internal val EarthDomainColorsOnGround = EarthDomainColors(
    anmlBg = Color(0xFFFBF3D0),
    anmlFg = Color(0xFFB08400),
    stakingBg = Color(0xFFE8F1E1),
    stakingFg = Color(0xFF4A8536),
    dexBg = Color(0xFFDEF6F1),
    dexFg = Color(0xFF128C7D),
    governanceBg = Color(0xFFEFE9FE),
    governanceFg = Color(0xFF6B3FD4),
    gasWarningBg = Color(0xFFFEF0C7),
    gasWarningFg = Color(0xFF93370D),
)

@Suppress("CompositionLocalAllowlist")
internal val LocalEarthDomainColors =
    staticCompositionLocalOf { EarthDomainColorsOnGround }

/** `EarthTheme.domain.stakingFg` alongside `EarthTheme.colors.Text.textPrimary`. */
val EarthThemeDomain: EarthDomainColors
    @Composable get() = LocalEarthDomainColors.current
