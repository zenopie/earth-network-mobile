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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.chain.Gov
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Chain proposals — the SDK's governance, not Earth's allocation streams.
 *
 * The two are both called governance and are genuinely different: allocation
 * votes steer an emission continuously and are weighted by personhood or stake,
 * while these change the chain itself, run for a fixed period and are weighted
 * by bonded stake alone. Keeping them on one screen but visibly apart is what
 * stops "I voted" meaning two things.
 */
@Composable
fun ProposalList(
    proposals: List<Gov.Proposal>?,
    modifier: Modifier = Modifier,
    /** Opens the proposal in full. Null leaves the rows inert.  */
    onOpen: ((Gov.Proposal) -> Unit)? = null,
) {
    val dimens = EarthTheme.dimens

    Column(modifier.fillMaxWidth()) {
        when {
            proposals == null -> Text(
                text = "Loading…",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )

            proposals.isEmpty() -> Text(
                text = "No proposals yet. Anything that changes the chain itself " +
                    "— parameters, upgrades, spending from the community pool — " +
                    "is proposed here and voted on by staked ERTH.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )

            else -> proposals.forEach { p ->
                ProposalRow(p, onOpen)
                Spacer(Modifier.height(dimens.space8))
            }
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: Gov.Proposal,
    onOpen: ((Gov.Proposal) -> Unit)?,
) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EarthDimensions.Radius.radius3xl))
            .background(EarthColors.Surfaces.bgSecondary)
            // Clickable before the padding so the whole card is the target,
            // not just the text inside it.
            .let { m -> if (onOpen != null) m.clickable { onOpen(proposal) } else m }
            .padding(dimens.space16),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${proposal.id}",
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            ProposalStatusPill(proposal.status)
        }
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = proposal.title,
            style = EarthTypography.textMd,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (proposal.summary.isNotBlank()) {
            Text(
                text = proposal.summary,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // The tally as one bar. A proposal passes on the ratio between these,
        // so the ratio is the thing to show; four numbers would need adding up
        // before they said anything.
        if (proposal.total > 0) {
            Spacer(Modifier.height(dimens.space12))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(dimens.space8)
                    .background(
                        EarthColors.Surfaces.bgPrimary,
                        RoundedCornerShape(dimens.space8),
                    ),
            ) {
                TallyPart(proposal.yes, proposal.total, EarthAccent.ink)
                TallyPart(proposal.no, proposal.total, EarthColors.Utility.ErrorRed.utilityError700)
                TallyPart(proposal.veto, proposal.total, EarthColors.Utility.ErrorRed.utilityError400)
                TallyPart(proposal.abstain, proposal.total, EarthColors.Text.textTertiary)
            }
            Spacer(Modifier.height(dimens.space8))
            Text(
                text = "${proposal.yes.percentOf(proposal.total)} yes · " +
                    "${proposal.no.percentOf(proposal.total)} no · " +
                    "${proposal.veto.percentOf(proposal.total)} veto · " +
                    "${proposal.abstain.percentOf(proposal.total)} abstain",
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
        }

    }
}

@Composable
private fun RowScopeTallyPlaceholder() = Unit

@Composable
private fun androidx.compose.foundation.layout.RowScope.TallyPart(
    amount: Long,
    total: Long,
    color: androidx.compose.ui.graphics.Color,
) {
    if (amount <= 0) return
    Box(
        Modifier
            .weight(amount.toFloat() / total)
            .fillMaxSize()
            .background(color),
    )
}

@Composable
internal fun ProposalStatusPill(status: String) {
    val dimens = EarthTheme.dimens
    // The chain's enum names are for the wire; these are for reading.
    val (label, tint) = when (status) {
        "PROPOSAL_STATUS_VOTING_PERIOD" -> "Voting" to EarthAccent.ink
        "PROPOSAL_STATUS_PASSED" -> "Passed" to EarthAccent.ink
        "PROPOSAL_STATUS_REJECTED" -> "Rejected" to EarthColors.Utility.ErrorRed.utilityError700
        "PROPOSAL_STATUS_FAILED" -> "Failed" to EarthColors.Utility.ErrorRed.utilityError700
        "PROPOSAL_STATUS_DEPOSIT_PERIOD" -> "Deposit" to EarthColors.Text.textTertiary
        else -> status.removePrefix("PROPOSAL_STATUS_").lowercase()
            .replaceFirstChar { it.uppercase() } to EarthColors.Text.textTertiary
    }
    Text(
        text = label,
        style = EarthTypography.textXs,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier = Modifier
            .background(EarthAccent.tint, RoundedCornerShape(dimens.space20))
            .padding(horizontal = dimens.space8, vertical = dimens.space2),
    )
}

private fun Long.percentOf(total: Long): String =
    if (total <= 0) "0%" else "${this * 100 / total}%"
