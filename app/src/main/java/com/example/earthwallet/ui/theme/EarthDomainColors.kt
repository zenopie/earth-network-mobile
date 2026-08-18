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

internal val LightEarthDomainColors = EarthDomainColors(
    anmlBg = Color(0xFFE9D2BE),
    anmlFg = Color(0xFF70452A),
    stakingBg = Color(0xFFE8F1E1),
    stakingFg = Color(0xFF4A8536),
    dexBg = Color(0xFFDEF6F1),
    dexFg = Color(0xFF128C7D),
    governanceBg = Color(0xFFEFE9FE),
    governanceFg = Color(0xFF6B3FD4),
    gasWarningBg = Color(0xFFFEF0C7),
    gasWarningFg = Color(0xFF93370D),
)

internal val DarkEarthDomainColors = EarthDomainColors(
    anmlBg = Color(0xFF1A0F09),
    anmlFg = Color(0xFFD8B294),
    stakingBg = Color(0xFF0D1A09),
    stakingFg = Color(0xFF87B76F),
    dexBg = Color(0xFF031A17),
    dexFg = Color(0xFF4CC3B2),
    governanceBg = Color(0xFF150B29),
    governanceFg = Color(0xFFA182F1),
    gasWarningBg = Color(0xFF2E1105),
    gasWarningFg = Color(0xFFFEDF89),
)

@Suppress("CompositionLocalAllowlist")
internal val LocalEarthDomainColors =
    staticCompositionLocalOf { LightEarthDomainColors }

/** `EarthTheme.domain.stakingFg` alongside `EarthTheme.colors.Text.textPrimary`. */
val EarthThemeDomain: EarthDomainColors
    @Composable get() = LocalEarthDomainColors.current
