package network.erth.wallet.ui.compose

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.valentinilk.shimmer.shimmer
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.ShimmerRectangle
import network.erth.wallet.ui.vendor.component.rememberEarthShimmer
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/** One option in an allocation stream, and the share this wallet gives it. */
data class AllocationSlice(
    val name: String,
    val percent: Int,
)

/**
 * Govern: where this wallet's two votes send their share of the emission.
 *
 * Earth's governance is these two streams; there is no separate proposal
 * system, so this is the whole of it. Two streams, shown as two stacked bars
 * rather than two pie charts — a stacked bar reads left to right at a glance
 * and stays readable at three options or ten, while a pie needs a legend to say
 * which wedge is which, and a legend is a second thing to read.
 *
 * The eligibility line under each bar says why a stream is inactive, because
 * "0%" and "you have no say here yet" look identical otherwise.
 */
@Composable
fun AllocationScreen(
    state: AllocationUiState?,
    registered: Boolean,
    stakedUerth: Long,
    onEdit: (StreamId) -> Unit,
    onOpenStream: (StreamId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    val shimmer = rememberEarthShimmer()

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))
        Text(
            text = "Two of Earth's four emission streams are directed by vote. " +
                "One counts people, one counts stake — you can hold a say in both.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space24))
        StreamSection(
            title = "Human stream",
            detail = "One verified human, one vote.",
            eligibility = if (registered) null else "Register your identity to take part.",
            stream = state?.human,
            shimmer = shimmer,
            accent = EarthAccent.ink,
            onEdit = { onEdit(StreamId.STREAM_ID_HUMAN) },
            onOpen = { onOpenStream(StreamId.STREAM_ID_HUMAN) },
        )

        Spacer(Modifier.height(dimens.space16))
        StreamSection(
            title = "Capital stream",
            detail = "Weighted by the ERTH you have staked.",
            eligibility = if (stakedUerth > 0) null else "Stake ERTH to take part.",
            stream = state?.capital,
            shimmer = shimmer,
            accent = EarthAccent.ink,
            onEdit = { onEdit(StreamId.STREAM_ID_CAPITAL) },
            onOpen = { onOpenStream(StreamId.STREAM_ID_CAPITAL) },
        )
        Spacer(Modifier.height(dimens.space32))
        EarthLabel("Chain proposals")
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = "Changes to the chain itself, voted on by staked ERTH. " +
                "Separate from the streams above, which direct emissions.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )
        Spacer(Modifier.height(dimens.space12))
        ProposalList(proposals = state?.proposals)
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun StreamSection(
    title: String,
    detail: String,
    eligibility: String?,
    stream: StreamUiState?,
    shimmer: com.valentinilk.shimmer.Shimmer,
    accent: Color,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EarthDimensions.Radius.radius3xl))
            .background(EarthColors.Surfaces.bgSecondary)
            // The card opens the stream's charts; Edit inside it changes the
            // vote. Two different things, so the whole card is not the edit.
            .clickable(onClick = onOpen)
            .padding(dimens.space16),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary,
                )
                Text(
                    text = detail,
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textTertiary,
                )
            }
            if (eligibility == null && stream != null) {
                Text(
                    text = "Edit",
                    style = EarthTypography.textSm,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.clickable(onClick = onEdit).padding(dimens.space4),
                )
            }
        }

        Spacer(Modifier.height(dimens.space12))

        if (stream == null) {
            Column(Modifier.shimmer(shimmer)) {
                ShimmerRectangle(width = 240.dp(), height = 12.dp())
            }
            return@Column
        }

        if (eligibility != null) {
            Text(
                text = eligibility,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
            return@Column
        }

        val slices = stream.slices
        if (slices.isEmpty()) {
            Text(
                text = "Nothing allocated yet — your share sits unassigned.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
            Spacer(Modifier.height(dimens.space12))
            EarthButton(
                text = "Allocate",
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
                colors = EarthButtonDefaults.secondaryColors(),
            )
            return@Column
        }

        AllocationBar(slices, accent)
        Spacer(Modifier.height(dimens.space12))
        slices.forEachIndexed { i, slice ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp()), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp())
                        .background(accent.stepped(i), CircleShape),
                )
                Spacer(Modifier.padding(horizontal = dimens.space4))
                Text(
                    text = slice.name,
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${slice.percent}%",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textSecondary,
                )
            }
        }
    }
}

/**
 * The stacked bar.
 *
 * Alpha steps the slices apart rather than a second hue, so each section keeps
 * one colour: the two streams stay distinguishable from each other rather than
 * competing internally, and the dots in the legend below reuse the same steps.
 */
@Composable
private fun AllocationBar(slices: List<AllocationSlice>, accent: Color) {
    val dimens = EarthTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimens.space12)
            .background(EarthColors.Surfaces.bgPrimary, RoundedCornerShape(dimens.space8)),
    ) {
        slices.forEachIndexed { i, slice ->
            Box(
                Modifier
                    .weight(slice.percent.toFloat().coerceAtLeast(0.01f))
                    .fillMaxSize()
                    .background(accent.stepped(i)),
            )
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
