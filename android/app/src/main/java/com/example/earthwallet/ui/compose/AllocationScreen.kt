package network.erth.wallet.ui.compose

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.valentinilk.shimmer.shimmer
import network.erth.earth.proto.allocation.StreamId
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
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
 * Govern: the three places a vote can go.
 *
 * A menu, not a dashboard. Each entry is a whole screen's worth of detail —
 * two charts and a vote, or a list of proposals — and summarising all three
 * here left every one of them too small to act on while still being too much
 * to scan.
 *
 * The two streams are Earth's own governance and the third is the SDK's. They
 * are grouped together because both are voting and separated by a heading
 * because they are not the same vote: the streams steer an emission
 * continuously by personhood or stake, proposals change the chain itself for a
 * fixed period by bonded stake alone.
 */
@Composable
fun AllocationScreen(
    state: AllocationUiState?,
    registered: Boolean,
    stakedUerth: Long,
    onOpenStream: (StreamId) -> Unit,
    onOpenProposals: () -> Unit,
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
                "The Caretaker Fund counts people, Groundworks counts stake — " +
                "you can hold a say in both.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space16))
        GovernRow(
            title = "Caretaker Fund",
            detail = "One verified human, one vote.",
            status = state?.human.statusFor(
                eligible = registered,
                blocked = "Register to take part",
            ),
            loading = state == null,
            shimmer = shimmer,
            onClick = { onOpenStream(StreamId.STREAM_ID_CARETAKER) },
        )

        Spacer(Modifier.height(dimens.space8))
        GovernRow(
            title = "Groundworks Fund",
            detail = "Weighted by the ERTH you have staked.",
            status = state?.capital.statusFor(
                eligible = stakedUerth > 0,
                blocked = "Stake ERTH to take part",
            ),
            loading = state == null,
            shimmer = shimmer,
            onClick = { onOpenStream(StreamId.STREAM_ID_GROUNDWORKS) },
        )

        Spacer(Modifier.height(dimens.space24))
        EarthLabel("Chain governance")
        Spacer(Modifier.height(dimens.space8))
        GovernRow(
            title = "Proposals",
            detail = "Changes to the chain itself, voted on by staked ERTH.",
            status = state?.proposals?.let { proposals ->
                val live = proposals.count { it.status == "PROPOSAL_STATUS_VOTING_PERIOD" }
                when {
                    live > 0 -> "$live open for voting"
                    proposals.isEmpty() -> "None yet"
                    else -> "${proposals.size} closed"
                }
            },
            loading = state == null,
            shimmer = shimmer,
            onClick = onOpenProposals,
        )
        Spacer(Modifier.height(dimens.space32))
    }
}

/**
 * What this wallet's position in a stream is, in a few words.
 *
 * Ineligibility outranks the split: someone who cannot vote does not need to be
 * told they have allocated nothing, they need to be told why.
 */
private fun StreamUiState?.statusFor(eligible: Boolean, blocked: String): String? = when {
    this == null -> null
    !eligible -> blocked
    slices.isEmpty() -> "Not allocated"
    else -> slices.joinToString(" · ") { "${it.name} ${it.percent}%" }
}

@Composable
private fun GovernRow(
    title: String,
    detail: String,
    status: String?,
    loading: Boolean,
    shimmer: com.valentinilk.shimmer.Shimmer,
    onClick: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EarthDimensions.Radius.radius3xl))
            .background(EarthColors.Surfaces.bgSecondary)
            .clickable(onClick = onClick)
            .padding(dimens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Spacer(Modifier.height(dimens.space4))
            if (loading) {
                Box(Modifier.shimmer(shimmer)) {
                    ShimmerRectangle(width = 140.dp(), height = 12.dp())
                }
            } else if (status != null) {
                Text(
                    text = status,
                    style = EarthTypography.textSm,
                    color = EarthAccent.ink,
                )
            }
        }
        Text(
            text = "›",
            style = EarthTypography.textLg,
            color = EarthColors.Text.textTertiary,
        )
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
