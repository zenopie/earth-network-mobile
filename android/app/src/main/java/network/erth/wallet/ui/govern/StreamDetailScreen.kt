package network.erth.wallet.ui.govern

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
import network.erth.wallet.ui.components.PieChart
import network.erth.wallet.ui.components.PieLegend
import network.erth.wallet.privacy.Reminders
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthTheme

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
 * Two pie charts, not one bar of your own split: the comparison is the
 * point.
 *
 * [groundworks]: the Groundworks vote's controls ([GroundworksVoteControls]),
 * shown under both lenses in place of the allocation button: the lease and
 * its renewal are not a chart's.
 */
@Composable
fun StreamDetailScreen(
    title: String,
    detail: String,
    stream: StreamUiState?,
    eligibility: String?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    groundworks: (@Composable () -> Unit)? = null,
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
        if (groundworks != null) {
            // Your stake votes: every stake tx carries the split onto the note it makes.
            if (eligibility == null) {
                Spacer(Modifier.height(dimens.space24))
                groundworks()
            }
        } else if (lens == Lens.Preferred && eligibility == null) {
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

/**
 * The Groundworks vote: Vote / Change vote, when it counts until, Renew when
 * due or lapsed, Stop voting. Each is one confirmation; nothing renews on its
 * own. [split]: the one this wallet votes with (empty: none); [lease]: its
 * lease (PrivacyWallet.groundworksLease; null when no split is chosen).
 */
@Composable
fun GroundworksVoteControls(
    split: Map<Long, Long>,
    lease: Reminders.GroundworksLease?,
    options: List<network.erth.wallet.chain.Allocation.OptionInfo>,
    now: Long,
    /** What this wallet's live votes weigh and its private stake, in uerth at the live rates; null when balances are hidden. */
    voting: Pair<Long, Long>?,
    onVote: () -> Unit,
    onRenew: (Map<Long, Long>) -> Unit,
    onStop: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EarthButton(
            text = if (split.isEmpty()) "Vote" else "Change vote",
            onClick = onVote,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        // The split names an option the fund no longer has, struck (still listed, removed) or pruned (gone): the chain refuses it as it is.
        fun removed(s: Map<Long, Long>) = options.isNotEmpty() && s.keys.any { id -> options.none { it.id == id && !it.removed } }
        if (split.isNotEmpty() && removed(split)) {
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = "An option you chose was removed. Vote again to keep counting.",
                style = EarthTypography.textSm,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = network.erth.wallet.ui.theme.EarthAccent.warnInk,
            )
        } else if (split.isNotEmpty() && voting != null) {
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = "Voting ${"%,d".format(voting.first / 1_000_000)} of ${"%,d".format(voting.second / 1_000_000)} ERTH",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
        }
        if (lease != null) {
            val lapsed = lease.lapsed(now)
            if (lapsed) {
                Spacer(Modifier.height(dimens.space12))
                Text(
                    text = "Your vote has lapsed",
                    style = EarthTypography.textSm,
                    fontWeight = FontWeight.SemiBold,
                    color = network.erth.wallet.ui.theme.EarthAccent.warnInk,
                )
            } else if (lease.expiresAt > 0) {
                Spacer(Modifier.height(dimens.space12))
                Text(
                    text = "Counts until ${countsUntil(lease.expiresAt)}",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textTertiary,
                )
            }
            // A split naming an option the fund has since removed cannot be cast again as it is.
            if ((lapsed || lease.renewalDue(now)) && !removed(lease.split)) {
                Spacer(Modifier.height(dimens.space12))
                EarthButton(
                    text = "Renew",
                    onClick = { onRenew(lease.split) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = EarthButtonDefaults.secondaryColors(),
                )
            }
        }
        if (split.isNotEmpty()) {
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = "Stop voting",
                style = EarthTypography.textSm,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(dimens.space8)).clickable(onClick = onStop).padding(dimens.space4),
            )
        }
    }
}

/** A lease end as a date: "28 Oct 2027". */
private fun countsUntil(unix: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(unix.coerceIn(0, 253_402_300_799) * 1000))
