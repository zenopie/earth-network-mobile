package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/** Which split the chart is showing. */
private enum class Lens { Actual, Preferred }

/**
 * One allocation stream, as the chain has it and as you asked for it.
 *
 * Two views of the same options, which is the comparison worth making: Actual
 * is where the stream's emission is going once every voter is counted;
 * Preferred is where you asked it to go. Your vote is one of many, so the two
 * differ, and the gap between them is the only measure of whether a vote
 * changed anything.
 *
 * Restores the pair of pie charts the old CaretakerFund and DeflationFund
 * screens had, which the first Compose pass replaced with a single stacked bar
 * showing only your own split.
 */
@Composable
fun StreamDetailScreen(
    title: String,
    detail: String,
    stream: StreamUiState?,
    eligibility: String?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    var lens by remember { mutableStateOf(Lens.Actual) }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = detail,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        if (stream == null) {
            Spacer(Modifier.height(dimens.space24))
            Text(
                text = "Loading…",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
            return@Column
        }

        Spacer(Modifier.height(dimens.space16))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.space20))
                .background(EarthColors.Surfaces.bgSecondary)
                .padding(dimens.space4),
        ) {
            Lens.entries.forEach { option ->
                LensTab(
                    label = option.name,
                    selected = option == lens,
                    modifier = Modifier.weight(1f),
                ) { lens = option }
            }
        }

        val slices = if (lens == Lens.Actual) stream.actualSlices else stream.slices
        val allocated = slices.sumOf { it.percent }

        Spacer(Modifier.height(dimens.space24))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PieChart(
                slices = slices,
                // The hole states what the ring cannot: a stream nobody has
                // voted in draws as an empty circle, which is indistinguishable
                // from one that failed to load.
                centreLabel = if (slices.isEmpty()) "none" else "$allocated%",
            )
        }

        Spacer(Modifier.height(dimens.space16))
        PieLegend(slices)

        Spacer(Modifier.height(dimens.space16))
        Text(
            text = when {
                lens == Lens.Actual -> "Where this stream's emission goes once " +
                    "every voter is counted."
                eligibility != null -> eligibility
                slices.isEmpty() -> "You have not allocated your share of this stream."
                else -> "Where you asked your share to go. It is one vote among many, " +
                    "so the actual split will differ."
            },
            style = EarthTypography.textXs,
            color = EarthColors.Text.textTertiary,
        )

        // Only under Preferred. Actual is the whole stream's tally — a vote
        // button there would sit under a chart it cannot change, and read as
        // editing everyone's split rather than your own.
        if (lens == Lens.Preferred && eligibility == null) {
            Spacer(Modifier.height(dimens.space24))
            EarthButton(
                text = if (stream.slices.isEmpty()) "Allocate" else "Change allocation",
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
                colors = brandButtonColors(),
            )
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun LensTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    Text(
        text = label,
        style = EarthTypography.textSm,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        color = if (selected) {
            EarthColors.Btns.Secondary.btnSecondaryFg
        } else {
            EarthColors.Text.textTertiary
        },
        modifier = modifier
            .clip(RoundedCornerShape(dimens.space16))
            .background(
                if (selected) {
                    EarthColors.Btns.Secondary.btnSecondaryBg
                } else {
                    EarthColors.Surfaces.bgSecondary
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = dimens.space8),
    )
}
