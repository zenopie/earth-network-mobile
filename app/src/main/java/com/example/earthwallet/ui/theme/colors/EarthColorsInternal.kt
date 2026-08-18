@file:Suppress("MagicNumber")

package network.erth.wallet.ui.theme.colors

import androidx.compose.ui.graphics.Color

/**
 * Semantic colour: what a thing is for, not what it looks like.
 *
 * Screens reference these and never the ramps. Two consequences worth stating,
 * because both are load-bearing:
 *
 * Dark mode is a second mapping of this same shape, so it is a remapping rather
 * than a redesign, and a screen cannot fall out of it by inventing a grey.
 *
 * And a token that does not exist cannot be misused. There is deliberately no
 * `textBrand`: Sprout 500 on white is about 1.9:1, which is fine behind bold
 * type and unreadable as type. Anything putting the accent near text pairs it
 * with [textOnBrand]; links use [textLink], which clears 4.5:1.
 */
data class EarthColorsInternal(
    val surfaces: Surfaces,
    val text: Text,
    val btnPrimary: BtnPrimary,
    val btnSecondary: BtnSecondary,
    val btnGhost: BtnGhost,
    val btnDestructive: BtnDestructive,
    val inputs: Inputs,
    val sheets: Sheets,
    val status: Status,
    val domain: Domain,
)

/** Grounds and edges. bgPrimary is pure white, so cards need a stroke to exist. */
data class Surfaces(
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgTertiary: Color,
    val bgBrand: Color,
    val bgInverse: Color,
    val strokePrimary: Color,
    val strokeSecondary: Color,
    val strokeBrand: Color,
    val scrim: Color,
    val divider: Color,
)

data class Text(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val textInverse: Color,
    /** For type sitting on the accent. Never the accent itself. */
    val textOnBrand: Color,
    val textLink: Color,
    val textError: Color,
    val textWarning: Color,
    val textSuccess: Color,
)

/**
 * The one loud control on a screen.
 *
 * Disabled drops to a tinted fill rather than grey, so it still reads as the
 * same control rather than a different one.
 */
data class BtnPrimary(
    val bg: Color,
    val bgPressed: Color,
    val fg: Color,
    val bgDisabled: Color,
    val fgDisabled: Color,
)

/** Outlined on white: Receive, Cancel, Copy. */
data class BtnSecondary(
    val bg: Color,
    val bgPressed: Color,
    val fg: Color,
    val border: Color,
    val bgDisabled: Color,
    val fgDisabled: Color,
)

/** No fill until pressed: nav, back, dismiss. */
data class BtnGhost(
    val bg: Color,
    val bgPressed: Color,
    val fg: Color,
    val fgDisabled: Color,
)

/** Remove liquidity, unstake, delete wallet. */
data class BtnDestructive(
    val bg: Color,
    val bgPressed: Color,
    val fg: Color,
    val bgDisabled: Color,
    val fgDisabled: Color,
)

/** Amount and address fields. Focus is a green stroke, not a glow. */
data class Inputs(
    val bg: Color,
    val bgFilled: Color,
    val bgDisabled: Color,
    val stroke: Color,
    val strokeFocused: Color,
    val strokeError: Color,
    val text: Color,
    val hint: Color,
    val label: Color,
    val icon: Color,
)

/** The confirmation and result sheets — the most-seen surface after the balance. */
data class Sheets(
    val bg: Color,
    val scrim: Color,
    val grabber: Color,
    val divider: Color,
    /** The monospace block a chain error is printed into. */
    val codeBg: Color,
    val codeFg: Color,
    val codeStroke: Color,
)

/**
 * Transaction and registration state.
 *
 * Success reuses the brand green deliberately. Zodl separates the two because
 * Zashi's brand is gold and a green tick cannot be mistaken for branding; here
 * it can, and two adjacent greens meaning different things is worse than one
 * green meaning both. Error and warning therefore carry the whole burden of
 * "something is different", which is why both sit far away in hue.
 */
data class Status(
    val successBg: Color,
    val successFg: Color,
    val pendingBg: Color,
    val pendingFg: Color,
    val failedBg: Color,
    val failedFg: Color,
    val neutralBg: Color,
    val neutralFg: Color,
)

/**
 * Where Earth stops looking like a generic wallet with a green button.
 *
 * One hue per pillar, so a screen's subject is legible before its label is
 * read. The chain has four; so does this.
 */
data class Domain(
    val anmlBadgeBg: Color,
    val anmlBadgeFg: Color,
    val stakingAccent: Color,
    val stakingBg: Color,
    val dexAccent: Color,
    val dexBg: Color,
    val governanceAccent: Color,
    val governanceBg: Color,
    val gasWarningBg: Color,
    val gasWarningFg: Color,
)
