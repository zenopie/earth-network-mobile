package network.erth.wallet.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The accent, and there is only one.
 *
 * One Sprout tint behind every icon, one Sprout ink inside it: colour means
 * "this is Earth", not which pillar a row belongs to. A hue per pillar would
 * make a screen look like four products stacked together, and the
 * distinction is already there: a row says "Staked" or "Swapped" in words,
 * and the glyph inside the pill says it again.
 *
 * The two warning values are kept apart because they are not brand — an amber
 * that has been pulled toward green stops reading as a warning, which is the
 * one thing it has to do.
 */
object EarthAccent {
    /** Behind an icon, a pill, or a figures card. Sprout 50. */
    val tint = Color(0xFFE4FBEA)

    /** The glyph or figure on that tint. Sprout 700. */
    val ink = Color(0xFF00822D)

    val warnTint = Color(0xFFFEF0C7)
    val warnInk = Color(0xFF93370D)
}
