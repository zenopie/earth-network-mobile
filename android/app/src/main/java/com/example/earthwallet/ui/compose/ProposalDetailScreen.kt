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
import network.erth.wallet.chain.Assembly
import network.erth.wallet.chain.Gov
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * One proposal, in full, and the place it is voted on — twice.
 *
 * The list deliberately stops at a summary and a tally bar: vote buttons under
 * every row turns a page of proposals into a page of buttons, and casting a
 * vote off a three-line preview is a decision made without having read the
 * thing. Opening it is the moment the details exist to read.
 *
 * A proposal has two houses and they are shown as two, not merged into one
 * score. Stake and people are different quantities — one is ERTH, the other is
 * a headcount — and a combined bar would have to invent an exchange rate
 * between them that the chain does not have. Both have to clear their own bar
 * for anything to happen, so both get their own tally, their own buttons and
 * their own reason when this wallet cannot vote in one of them.
 */
@Composable
fun ProposalDetailScreen(
    proposal: Gov.Proposal?,
    modifier: Modifier = Modifier,
    /** Why this wallet cannot vote with stake, or null when it can. */
    eligibility: String? = null,
    onVote: ((Gov.Proposal, Gov.Vote) -> Unit)? = null,
    /**
     * The human house's tally, or null when this chain has no assembly — which
     * is every node older than v0.9.0. Null hides the section rather than
     * showing an empty one: before the upgrade there is genuinely only one
     * house, and an explanation of a second would be describing something that
     * does not exist yet.
     */
    assembly: Assembly.Tally? = null,
    /** Why this wallet cannot vote as a human, or null when it can. */
    assemblyEligibility: String? = null,
    onAssemblyVote: ((Gov.Proposal, Assembly.Vote) -> Unit)? = null,
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

        // --- stake house ---
        Spacer(Modifier.height(dimens.space24))
        Text(
            text = if (assembly != null) "Stake" else "Votes",
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

        // --- human house ---
        if (assembly != null) {
            Spacer(Modifier.height(dimens.space24))
            Text(
                text = "People",
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary,
            )
            Spacer(Modifier.height(dimens.space8))
            if (assembly.silent) {
                // Not "no votes yet". Silence is a refusal here, and a voter
                // who reads it as "nothing has happened" is reading the one
                // state that will decide the proposal if it persists.
                Text(
                    text = if (proposal.isVoting) {
                        "Nobody has voted. A proposal with no human votes fails, " +
                            "however much stake is behind it."
                    } else {
                        "No human votes were cast."
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
                    TallyBarPart(assembly.yes, assembly.total, EarthAccent.ink)
                    TallyBarPart(
                        assembly.no,
                        assembly.total,
                        EarthColors.Utility.ErrorRed.utilityError700,
                    )
                }
                Spacer(Modifier.height(dimens.space12))
                // Counted as people rather than formatted as ERTH: these are
                // headcounts, and putting them through the token formatter
                // would render two voters as "0.000002".
                PeopleRow("Yes", assembly.yes, assembly.total, EarthAccent.ink)
                PeopleRow(
                    "No",
                    assembly.no,
                    assembly.total,
                    EarthColors.Utility.ErrorRed.utilityError700,
                )
                Spacer(Modifier.height(dimens.space8))
                Text(
                    text = if (assembly.approved) {
                        "Clearing two thirds."
                    } else {
                        "Below the two thirds needed."
                    },
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary,
                )
            }
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
        // The human tally is purged when a proposal resolves, so for a closed
        // one this string is the only surviving record of which house ended it.
        if (proposal.failedReason.isNotBlank()) {
            Spacer(Modifier.height(dimens.space8))
            Text(
                text = proposal.failedReason,
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
        }

        // --- vote ---
        if (proposal.isVoting) {
            Spacer(Modifier.height(dimens.space24))
            if (assembly != null) {
                // Named only when there are two. With one house a heading over
                // the buttons is noise; with two, "Vote" alone would leave the
                // buttons ambiguous about which tally they move.
                Text(
                    text = "Vote with stake",
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary,
                )
                Spacer(Modifier.height(dimens.space8))
            }
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

            // The human house. A separate vote on the same proposal, not a
            // confirmation of the one above: a wallet with stake and a
            // registration has two to cast, and casting one does nothing to
            // the other.
            if (assembly != null) {
                Spacer(Modifier.height(dimens.space24))
                Text(
                    text = "Vote as a person",
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary,
                )
                Spacer(Modifier.height(dimens.space8))
                if (assemblyEligibility != null) {
                    Text(
                        text = assemblyEligibility,
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textTertiary,
                    )
                } else if (onAssemblyVote != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dimens.space8),
                    ) {
                        EarthButton(
                            text = Assembly.Vote.Yes.label,
                            onClick = { onAssemblyVote(proposal, Assembly.Vote.Yes) },
                            modifier = Modifier.weight(1f),
                            colors = brandButtonColors(),
                        )
                        EarthButton(
                            text = Assembly.Vote.No.label,
                            onClick = { onAssemblyVote(proposal, Assembly.Vote.No) },
                            modifier = Modifier.weight(1f),
                            colors = destructiveButtonColors(),
                        )
                    }
                    Spacer(Modifier.height(dimens.space8))
                    // No abstain to explain away, and the reason is worth a
                    // line: two thirds is measured against the votes cast, so
                    // an abstention and a missing vote are the same arithmetic.
                    Text(
                        text = "Your vote counts once, whatever you hold. " +
                            "There is no abstain — not voting is the same as abstaining.",
                        style = EarthTypography.textXs,
                        color = EarthColors.Text.textTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(dimens.space32))
    }
}

/**
 * One line of the human tally.
 *
 * Deliberately not [VoteRow]: that one runs its figure through the ERTH
 * formatter, which is right for stake and renders two people as "0.000002".
 */
@Composable
private fun PeopleRow(label: String, count: Long, total: Long, color: Color) {
    val dimens = EarthTheme.dimens
    if (count <= 0) return
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
            text = "$count  ·  ${count * 100 / total}%",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textPrimary,
        )
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
