package network.erth.wallet.ui.compose

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Set a stream's split.
 *
 * Steppers in fives, not free-typed numbers or sliders. The chain takes whole
 * percents that must total 100, and both alternatives fight that: a slider
 * cannot land on an exact figure reliably, and a text field lets you type a
 * total that will simply be rejected on chain after you have paid a fee to
 * find out.
 *
 * The remaining counter is the whole interface for the constraint — plus is
 * disabled at zero remaining rather than silently stealing from another
 * option, because an allocation that quietly rewrites a choice you already
 * made is worse than one that makes you take it back yourself.
 */
@Composable
fun AllocationEditSheet(
    title: String,
    stream: StreamUiState,
    onConfirm: (Map<Long, Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    val weights = remember {
        mutableStateMapOf<Long, Long>().apply {
            stream.options.forEach { put(it.id, stream.mine[it.id] ?: 0L) }
        }
    }

    val total = weights.values.sum()
    val remaining = 100L - total

    EarthSheet(onDismiss = onDismiss) {
        Text(
            text = title,
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = when {
                remaining > 0 -> "$remaining% left to allocate"
                remaining < 0 -> "${-remaining}% over — take some back"
                else -> "All 100% allocated"
            },
            style = EarthTypography.textSm,
            color = if (remaining == 0L) {
                EarthColors.Utility.SuccessGreen.utilitySuccess700
            } else {
                EarthColors.Text.textTertiary
            },
        )

        Spacer(Modifier.height(dimens.space16))

        stream.options.forEach { option ->
            val value = weights[option.id] ?: 0L
            Row(
                Modifier.fillMaxWidth().padding(vertical = dimens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = option.description,
                        style = EarthTypography.textMd,
                        color = EarthColors.Text.textPrimary,
                    )
                    Text(
                        text = option.kind.humanise(),
                        style = EarthTypography.textXs,
                        color = EarthColors.Text.textTertiary,
                    )
                }
                Stepper(
                    label = "−",
                    enabled = value > 0,
                    onClick = { weights[option.id] = (value - STEP).coerceAtLeast(0) },
                )
                Text(
                    text = "$value%",
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary,
                    modifier = Modifier.padding(horizontal = dimens.space12),
                )
                Stepper(
                    label = "+",
                    enabled = remaining >= STEP,
                    onClick = { weights[option.id] = value + STEP },
                )
            }
        }

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = "Set allocation",
            onClick = { onConfirm(weights.toMap()) },
            enabled = remaining == 0L,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space16))
    }
}

/**
 * "ALLOCATION_KIND_INTEGRATED" -> "Integrated".
 *
 * The chain names its enums for the wire; leaving that on screen shows the
 * reader the protobuf rather than the choice they are making.
 */
private fun String.humanise(): String =
    removePrefix("ALLOCATION_KIND_")
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

/** Five at a time: 100 divides evenly, and twenty taps is the worst case. */
private const val STEP = 5L

@Composable
private fun Stepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp())
            .background(EarthColors.Surfaces.bgSecondary, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = EarthTypography.textMd,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) {
                EarthColors.Text.textPrimary
            } else {
                EarthColors.Text.textTertiary
            },
        )
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
