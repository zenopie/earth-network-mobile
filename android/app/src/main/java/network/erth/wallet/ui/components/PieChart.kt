package network.erth.wallet.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.govern.AllocationSlice
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/**
 * A share of a whole, as a ring.
 *
 * A ring rather than a filled pie: the hole gives the total somewhere to live,
 * and a reader comparing two of these compares arc lengths either way — the
 * centre of a pie carries no information and is the part hardest to judge by
 * eye.
 *
 * Slices are one hue at stepped opacity rather than a palette. The chart is
 * already labelled beside itself, so a second colour per slice would encode
 * nothing the legend does not, and the screen keeps one accent.
 */
@Composable
fun PieChart(
    slices: List<AllocationSlice>,
    modifier: Modifier = Modifier,
    /** Written in the hole. "100%" allocated, or "unallocated". */
    centreLabel: String? = null,
) {
    val total = slices.sumOf { it.percent }.coerceAtLeast(1)

    // Sweeps in rather than snapping, so switching between Actual and
    // Preferred reads as the same chart changing rather than two charts.
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(500),
        label = "pie",
    )

    Box(modifier.size(PIE_SIZE), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(PIE_SIZE)) {
            val stroke = PIE_STROKE.toPx()
            val inset = stroke / 2
            var start = -90f // twelve o'clock, where a reader starts
            slices.forEachIndexed { i, slice ->
                val sweep = 360f * slice.percent / total * progress
                drawArc(
                    color = EarthAccent.ink.stepped(i),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                start += sweep
            }
        }
        if (centreLabel != null) {
            Text(
                text = centreLabel,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
        }
    }
}

/**
 * The chart's key.
 *
 * Separate from the chart so a caller can put it beside or beneath depending on
 * width. Dots reuse the chart's stepped opacity, which is the only thing tying
 * a row to its arc.
 */
@Composable
fun PieLegend(
    slices: List<AllocationSlice>,
    modifier: Modifier = Modifier,
    /** Options with no votes: folded behind a toggle (anyone can list one, so there may be many). */
    unfunded: List<AllocationSlice> = emptyList(),
    /** Opens a row's option; null leaves the rows inert. */
    onOpen: ((AllocationSlice) -> Unit)? = null,
) {
    val dimens = EarthTheme.dimens
    var showUnfunded by remember { mutableStateOf(false) }
    Column(modifier) {
        slices.forEachIndexed { i, slice -> LegendRow(slice, EarthAccent.ink.stepped(i), onOpen) }
        if (unfunded.isNotEmpty()) {
            if (showUnfunded) unfunded.forEach { LegendRow(it, EarthColors.Text.textTertiary.copy(alpha = 0.4f), onOpen) }
            Text(
                if (showUnfunded) "Hide options with no votes" else "Show ${unfunded.size} with no votes",
                style = EarthTypography.textSm,
                fontWeight = FontWeight.SemiBold,
                color = EarthAccent.ink,
                modifier = Modifier.clickable { showUnfunded = !showUnfunded }.padding(vertical = dimens.space8),
            )
        }
        if (slices.isEmpty() && unfunded.isEmpty()) {
            Spacer(Modifier.height(dimens.space8))
            Text(
                text = "Nothing allocated.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
        }
    }
}

/** A legend row: dot, the option's name with what it is under it, its share. */
@Composable
private fun LegendRow(slice: AllocationSlice, dot: Color, onOpen: ((AllocationSlice) -> Unit)?) {
    val dimens = EarthTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable { onOpen(slice) } else Modifier)
            .padding(vertical = dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.padding(horizontal = dimens.space4))
        Column(Modifier.weight(1f)) {
            Text(
                text = slice.name,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (slice.source.isNotEmpty()) {
                Text(slice.source, style = EarthTypography.textXs, color = EarthColors.Text.textTertiary, maxLines = 1)
            }
        }
        Text(
            text = "${slice.percent}%",
            style = EarthTypography.textSm,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textSecondary,
        )
        if (onOpen != null) {
            Spacer(Modifier.padding(horizontal = dimens.space2))
            Text("›", style = EarthTypography.textLg, color = EarthColors.Text.textTertiary)
        }
    }
}

private val PIE_SIZE = 180.dp
private val PIE_STROKE = 34.dp

/** Steps down the accent so adjacent slices separate without a second hue. */
internal fun Color.stepped(index: Int): Color =
    copy(alpha = (1f - index * 0.16f).coerceAtLeast(0.28f))
