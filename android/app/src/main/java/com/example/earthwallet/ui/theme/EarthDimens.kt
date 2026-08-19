package network.erth.wallet.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and radii, on a 4dp grid.
 *
 * Named by role rather than size so a screen asks for "the gap between a label
 * and its value", not "8dp". The old XML layouts hand-picked padding per file,
 * which is why no two screens agreed on their margins.
 */
data class EarthDimens(
    val space2: Dp = 2.dp,
    val space4: Dp = 4.dp,
    val space8: Dp = 8.dp,
    val space12: Dp = 12.dp,
    val space16: Dp = 16.dp,
    val space20: Dp = 20.dp,
    val space24: Dp = 24.dp,
    val space32: Dp = 32.dp,
    val space48: Dp = 48.dp,
    /** Screen gutter. Everything full-bleed stops here. */
    val gutter: Dp = 20.dp,
    val radiusSm: Dp = 8.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 16.dp,
    val radiusSheet: Dp = 20.dp,
    val radiusPill: Dp = 999.dp,
    /** Minimum touch target; buttons never go under this. */
    val touchTarget: Dp = 48.dp,
    val buttonHeight: Dp = 52.dp,
    val strokeWidth: Dp = 1.dp,
)
