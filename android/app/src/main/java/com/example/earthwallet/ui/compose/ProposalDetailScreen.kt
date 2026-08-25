package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.chain.Gov
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * One proposal, in full, and the place it is voted on.
 *
 * The list deliberately stops at a summary and a tally bar: four vote buttons
 * under every row turns a page of proposals into a page of buttons, and casting
 * a stake-weighted vote off a three-line preview is a decision made without
 * having read the thing. Opening it is the moment the details exist to read.
 */
@Composable
fun ProposalDetailScreen(
    proposal: Gov.Proposal?,
    modifier: Modifier = Modifier,
    /** Why this wallet cannot vote, or null when it can. */
    eligibility: String? = null,
    onVote: ((Gov.Proposal, Gov.Vote) -> Unit)? = null,
) {
    val dimens = EarthTheme.dimens

    if (proposal == null) {
        Box(
            modifier
                .fillMaxSize()
                .background(EarthColors.Surfaces.bgPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Loading…",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
        }
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space8))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${proposal.id}",
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
            if (proposal.expedited) {
                Spacer(Modifier.height(dimens.space4))
                Text(
                    text = "  ·  Expedited",
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            ProposalStatusPill(proposal.status)
        }

        Spacer(Modifier.height(dimens.space8))
        Text(
            text = proposal.title,
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )

        if (proposal.summary.isNotBlank()) {
            Spacer(Modifier.height(dimens.space8))
            // No maxLines here — the list truncates, this is where the whole
            // thing is meant to be readable.
            Text(
                text = proposal.summary,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textSecondary,
            )
        }

        // --- tally ---
        Spacer(Modifier.height(dimens.space24))
        Text(
            text = "Votes",
            style = EarthTypography.textMd,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space8))
        if (proposal.total <= 0) {
            Text(
                text = if (proposal.isVoting) {
                    "Nothing cast yet."
                } else {
                    "No votes were cast."
                },
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(dimens.space8)
                    .background(
                        EarthColors.Surfaces.bgSecondary,
                        RoundedCornerShape(dimens.space8),
                    ),
            ) {
                TallyBarPart(proposal.yes, proposal.total, EarthAccent.ink)
                TallyBarPart(
                    proposal.no,
                    proposal.total,
                    EarthColors.Utility.ErrorRed.utilityError700,
                )
                TallyBarPart(
                    proposal.veto,
                    proposal.total,
                    EarthColors.Utility.ErrorRed.utilityError400,
                )
                TallyBarPart(
                    proposal.abstain,
                    proposal.total,
                    EarthColors.Text.textTertiary,
                )
            }
            Spacer(Modifier.height(dimens.space12))
            // The numbers as well as the bar: the bar answers "is it passing",
            // the numbers answer "by how much", and a stake-weighted vote is
            // usually decided by one holder whose size only shows as a figure.
            VoteRow("Yes", proposal.yes, proposal.total, EarthAccent.ink)
            VoteRow("No", proposal.no, proposal.total, EarthColors.Utility.ErrorRed.utilityError700)
            VoteRow("Veto", proposal.veto, proposal.total, EarthColors.Utility.ErrorRed.utilityError400)
            VoteRow("Abstain", proposal.abstain, proposal.total, EarthColors.Text.textTertiary)
        }

        // --- details ---
        Spacer(Modifier.height(dimens.space24))
        Text(
            text = "Details",
            style = EarthTypography.textMd,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space8))
        if (proposal.messageType.isNotBlank()) {
            EarthDetailRow("Type", proposal.messageType.substringAfterLast('.'))
        }
        proposal.planName?.let { EarthDetailRow("Upgrade", it) }
        proposal.planHeight?.let { EarthDetailRow("At height", it) }
        if (proposal.totalDepositUerth > 0) {
            EarthDetailRow("Deposit", formatErth(proposal.totalDepositUerth))
        }
        if (proposal.votingEndTime.isNotBlank()) {
            EarthDetailRow(
                if (proposal.isVoting) "Voting ends" else "Voting ended",
                proposal.votingEndTime.take(10),
            )
        }
        if (proposal.proposer.isNotBlank()) {
            EarthDetailRow("Proposer", shortAddress(proposal.proposer))
        }

        // --- vote ---
        if (proposal.isVoting) {
            Spacer(Modifier.height(dimens.space24))
            if (eligibility != null) {
                Text(
                    text = eligibility,
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textTertiary,
                )
            } else if (onVote != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.space8),
                ) {
                    EarthButton(
                        text = Gov.Vote.Yes.label,
                        onClick = { onVote(proposal, Gov.Vote.Yes) },
                        modifier = Modifier.weight(1f),
                        colors = brandButtonColors(),
                    )
                    EarthButton(
                        text = Gov.Vote.No.label,
                        onClick = { onVote(proposal, Gov.Vote.No) },
                        modifier = Modifier.weight(1f),
                        colors = destructiveButtonColors(),
                    )
                }
                // Abstain and veto quieter and on their own row: they are the
                // rarer answers, and a veto is not a louder no — it burns the
                // deposit and deserves its own moment of thought.
                Spacer(Modifier.height(dimens.space8))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.space8),
                ) {
                    EarthButton(
                        text = Gov.Vote.Abstain.label,
                        onClick = { onVote(proposal, Gov.Vote.Abstain) },
                        modifier = Modifier.weight(1f),
                        colors = EarthButtonDefaults.secondaryColors(),
                    )
                    EarthButton(
                        text = Gov.Vote.Veto.label,
                        onClick = { onVote(proposal, Gov.Vote.Veto) },
                        modifier = Modifier.weight(1f),
                        colors = EarthButtonDefaults.secondaryColors(),
                    )
                }
                Spacer(Modifier.height(dimens.space8))
                Text(
                    text = "Voting again replaces your previous vote.",
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun VoteRow(label: String, amount: Long, total: Long, color: Color) {
    val dimens = EarthTheme.dimens
    if (amount <= 0) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(dimens.space8)
                .width(dimens.space8)
                .background(color, RoundedCornerShape(dimens.space8)),
        )
        Spacer(Modifier.width(dimens.space8))
        Text(
            text = label,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${formatErth(amount)}  ·  ${amount * 100 / total}%",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textPrimary,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TallyBarPart(
    amount: Long,
    total: Long,
    color: Color,
) {
    if (amount <= 0) return
    Box(
        Modifier
            .weight(amount.toFloat() / total)
            .fillMaxSize()
            .background(color),
    )
}

private fun shortAddress(a: String): String =
    if (a.length > 16) "${a.take(10)}…${a.takeLast(4)}" else a
