package network.erth.wallet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The tab bar's glyphs, drawn to match the iOS bar.
 *
 * iOS uses SF Symbols — `wallet.bifold`, `chart.line.uptrend.xyaxis`,
 * `arrow.left.arrow.right`, `person.3` — which are *stroked* outlines, not
 * filled shapes, and which get heavier when their tab is selected because the
 * symbol is asked for at a different weight. The drawables these replace were
 * solid fills at one weight, so the two bars read as different apps even though
 * every other measurement already matched.
 *
 * Built in code rather than as vector XML for one reason: stroke width is a
 * parameter. A VectorDrawable's is baked in, so matching the selected/unselected
 * weight change would mean eight files that have to be kept identical apart from
 * one number.
 *
 * The geometry is a 24-unit viewport, round caps and round joins throughout,
 * which is what makes a hand-drawn path sit next to SF Symbols without looking
 * sharper than them.
 */
@Composable
fun tabGlyph(tab: EarthRoute.Tab, selected: Boolean): ImageVector {
    // SF Symbols' regular and semibold at 20pt, converted to this viewport.
    val weight = if (selected) 2.0f else 1.6f
    return remember(tab, weight) {
        when (tab) {
            EarthRoute.Wallet -> walletGlyph(weight)
            EarthRoute.Earn -> earnGlyph(weight)
            EarthRoute.Swap -> swapGlyph(weight)
            EarthRoute.Govern -> governGlyph(weight)
        }
    }
}

/** `wallet.bifold` — the card body, the fold, and the clasp. */
private fun walletGlyph(weight: Float) = glyph(
    name = "wallet",
    weight = weight,
    // The clasp is solid at every weight, the way the symbol draws it: a ring
    // this small closes up into a blob as soon as the stroke thickens.
    fill = { circle(18.5f, 12f, 1.0f) },
) {
    roundedRect(2.6f, 5.4f, 21.4f, 18.6f, 3.2f)
    moveTo(15.6f, 5.4f)
    lineTo(15.6f, 18.6f)
}

/** `chart.line.uptrend.xyaxis` — axes, a rising line, and its arrow head. */
private fun earnGlyph(weight: Float) = glyph("earn", weight) {
    // The axes. Without them this is a bare arrow, which is the icon the
    // Android bar had and the thing that made the two bars look unrelated.
    moveTo(3.4f, 3.2f)
    lineTo(3.4f, 20.6f)
    lineTo(21.0f, 20.6f)

    moveTo(6.6f, 16.6f)
    lineTo(10.4f, 12.4f)
    lineTo(13.4f, 15.2f)
    lineTo(19.6f, 7.8f)

    // The head is a corner bracket, which is what an arrow travelling up and
    // to the right at 45 degrees needs: one leg flat, one leg upright.
    moveTo(15.4f, 7.8f)
    lineTo(19.6f, 7.8f)
    lineTo(19.6f, 12.0f)
}

/** `arrow.left.arrow.right` — two straight arrows, not one crossing pair. */
private fun swapGlyph(weight: Float) = glyph("swap", weight) {
    moveTo(20.0f, 8.2f)
    lineTo(4.4f, 8.2f)
    moveTo(7.6f, 5.0f)
    lineTo(4.4f, 8.2f)
    lineTo(7.6f, 11.4f)

    moveTo(4.0f, 15.8f)
    lineTo(19.6f, 15.8f)
    moveTo(16.4f, 12.6f)
    lineTo(19.6f, 15.8f)
    lineTo(16.4f, 19.0f)
}

/**
 * `person.3` — three people, the middle one in front.
 *
 * The side figures' shoulders stop short rather than running behind the centre
 * one. That gap is the occlusion: closing it would draw a torso through a body
 * that is meant to be in front of it.
 */
private fun governGlyph(weight: Float) = glyph("govern", weight) {
    circle(12f, 8.4f, 2.7f)
    moveTo(6.9f, 19.4f)
    curveTo(7.1f, 16.1f, 9.3f, 14.6f, 12f, 14.6f)
    curveTo(14.7f, 14.6f, 16.9f, 16.1f, 17.1f, 19.4f)

    circle(4.7f, 10.0f, 1.9f)
    moveTo(1.2f, 18.6f)
    curveTo(1.4f, 16.0f, 2.6f, 14.8f, 4.4f, 14.7f)

    circle(19.3f, 10.0f, 1.9f)
    moveTo(22.8f, 18.6f)
    curveTo(22.6f, 16.0f, 21.4f, 14.8f, 19.6f, 14.7f)
}

private fun glyph(
    name: String,
    weight: Float,
    fill: (PathBuilder.() -> Unit)? = null,
    body: PathBuilder.() -> Unit,
): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        // Black, then tinted by the caller — the bar animates the colour and
        // this only supplies the shape.
        stroke = SolidColor(Color.Black),
        strokeLineWidth = weight,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = body,
    )
    fill?.let { builder.path(fill = SolidColor(Color.Black), pathBuilder = it) }
    return builder.build()
}

/** A circle as four cubics. The constant is the usual circular-arc kappa. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    val k = r * 0.5523f
    moveTo(cx, cy - r)
    curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    close()
}

private fun PathBuilder.roundedRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    val k = rad * 0.5523f
    moveTo(l + rad, t)
    lineTo(r - rad, t)
    curveTo(r - rad + k, t, r, t + rad - k, r, t + rad)
    lineTo(r, b - rad)
    curveTo(r, b - rad + k, r - rad + k, b, r - rad, b)
    lineTo(l + rad, b)
    curveTo(l + rad - k, b, l, b - rad + k, l, b - rad)
    lineTo(l, t + rad)
    curveTo(l, t + rad - k, l + rad - k, t, l + rad, t)
    close()
}
