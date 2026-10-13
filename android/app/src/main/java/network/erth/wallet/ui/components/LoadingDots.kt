package network.erth.wallet.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors

private const val PERIOD_MS = 1_200
private const val STAGGER_MS = 180

/**
 * [LOADING], animated: three dots rising in a wave.
 *
 * Laid out as the "…" it replaces (drawn invisibly for its size), so the
 * figure that lands in its place moves nothing around it. Reduced motion
 * (animations off in developer options or accessibility) gets still dots.
 */
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = EarthColors.Text.textTertiary,
) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val phases: List<State<Float>>? = if (still) null else {
        val transition = rememberInfiniteTransition(label = "loading")
        List(3) { i ->
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = PERIOD_MS
                        // Up and back over the first half of the period from
                        // this dot's turn, then rest: a wave left to right.
                        0f at (i * STAGGER_MS) using LinearEasing
                        1f at (i * STAGGER_MS + PERIOD_MS / 4) using LinearEasing
                        0f at (i * STAGGER_MS + PERIOD_MS / 2) using LinearEasing
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$i",
            )
        }
    }
    Text(
        text = LOADING,
        style = style,
        color = Color.Transparent,
        maxLines = 1,
        modifier = modifier
            .semantics { contentDescription = "Loading" }
            .drawWithContent {
                // The ellipsis glyph's width, split three ways: each dot in its third.
                val slot = size.width / 3f
                val base = (slot * 0.36f).coerceAtMost(size.height * 0.11f)
                val y = size.height * 0.58f
                for (i in 0 until 3) {
                    val t = phases?.get(i)?.value
                    val scale = if (t == null) 1f else 0.75f + 0.25f * t
                    val alpha = if (t == null) 0.5f else 0.25f + 0.75f * t
                    drawCircle(
                        color = color.copy(alpha = color.alpha * alpha),
                        radius = base * scale,
                        center = Offset(slot * (i + 0.5f), y),
                    )
                }
            },
    )
}

/**
 * A figure that may still be loading: [LoadingDots] for [LOADING], a label
 * then the dots for "Balance …", and plain text otherwise. Only an exact
 * [LOADING] (or one at the end after a space) is a load; a "…" inside other
 * text (a shortened address) is just text.
 */
@Composable
fun FigureText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolved = style.merge(
        TextStyle(
            color = color,
            fontWeight = fontWeight,
            fontSize = fontSize,
            textAlign = textAlign ?: TextAlign.Unspecified,
        ),
    )
    // Unspecified: whatever the text would have been drawn in (a button's content colour).
    val dotColor = resolved.color.takeIf { it != Color.Unspecified } ?: androidx.compose.material3.LocalContentColor.current
    when {
        text == LOADING -> {
            if (textAlign == TextAlign.Center) {
                Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    LoadingDots(style = resolved, color = dotColor)
                }
            } else {
                LoadingDots(modifier, resolved, dotColor)
            }
        }
        text.endsWith(" $LOADING") -> Row(
            modifier,
            horizontalArrangement = if (textAlign == TextAlign.Center) androidx.compose.foundation.layout.Arrangement.Center
            else androidx.compose.foundation.layout.Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text.dropLast(LOADING.length), style = resolved, maxLines = 1)
            LoadingDots(style = resolved, color = dotColor)
        }
        else -> Text(text, modifier, style = resolved, maxLines = maxLines, overflow = overflow)
    }
}
