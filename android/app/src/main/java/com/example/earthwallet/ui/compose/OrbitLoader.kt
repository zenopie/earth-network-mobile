package network.erth.wallet.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.theme.colors.EarthColors

/**
 * ANML orbiting ERTH.
 *
 * Ports the web app's OrbitLoader (`src/components/Layout.jsx`, orbit rules in
 * `Layout.css`) so the three clients wait the same way. Geometry is the web's,
 * in dp rather than px:
 *
 *     container   160
 *     track       140, 2px dashed, the ink at 10%
 *     ERTH         80, centred
 *     ANML         40, on the track, 2.5s linear, counter-rotated
 *
 * The structure mirrors the web's DOM rather than reimplementing it: a
 * track-sized box rotates, and the coin inside counter-rotates by the same
 * angle. Skip the counter-rotation and the coin tumbles as it travels, which
 * reads as a second animation instead of one thing moving.
 */
@Composable
fun OrbitLoader(
    modifier: Modifier = Modifier,
    /** Scales the whole thing. The web draws it at 160 across a page. */
    diameter: Dp = 160.dp,
) {
    val scale = diameter / 160.dp
    val track = 140.dp * scale
    val erth = 80.dp * scale
    val anml = 40.dp * scale

    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing)),
        label = "angle",
    )

    val ink = EarthColors.Text.textPrimary.copy(alpha = 0.1f)

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(track)) {
            val dash = 4.dp.toPx()
            drawCircle(
                color = ink,
                radius = size.minDimension / 2,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                ),
            )
        }

        Image(
            painter = painterResource(R.drawable.ic_erth_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(erth).clip(CircleShape),
        )

        // The orbit. This box is the track, and it turns; the coin sits on its
        // right edge and is turned back by the same angle so it stays upright.
        Box(
            Modifier.size(track).rotate(angle),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Image(
                painter = painterResource(R.drawable.anml),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(anml)
                    // Half its own width outward, so its centre lands on the
                    // track rather than inside it — the web's `right: -20px`.
                    .offset(x = anml / 2)
                    .rotate(-angle)
                    .clip(CircleShape),
            )
        }
    }
}
