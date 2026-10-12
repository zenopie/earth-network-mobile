package network.erth.wallet.ui.govern

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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.earn.PillButton
import network.erth.wallet.chain.Assembly
import network.erth.wallet.chain.Gov
import network.erth.wallet.ui.components.EarthDetailRow
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.destructiveButtonColors
import network.erth.wallet.ui.components.formatErth
import network.erth.wallet.ui.components.shortAddress
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

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
    /** This wallet's private stake in ERTH, for the weight line; null when none. */
    stakeWeight: String? = null,
    /** The human house's tally; null (not read) hides the section rather than showing it empty. */
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
    val red = EarthColors.Utility.ErrorRed.utilityError700
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${proposal.id}" + if (proposal.expedited) "  ·  Expedited" else "",
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            ProposalStatusPill(proposal.status)
        }
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = proposal.title,
            style = EarthTypography.header5.copy(fontWeight = FontWeight.Bold),
            color = EarthColors.Text.textPrimary,
        )
        if (proposal.summary.isNotBlank()) {
            Spacer(Modifier.height(dimens.space8))
            Text(
                text = proposal.summary,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (expanded) "Less" else "More",
                style = EarthTypography.textSm,
                fontWeight = FontWeight.SemiBold,
                color = EarthAccent.ink,
                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = dimens.space4),
            )
        }
        if (proposal.votingEndTime.isNotBlank()) {
            Spacer(Modifier.height(dimens.space8))
            Row(Modifier.fillMaxWidth().padding(vertical = dimens.space8)) {
                Text(if (proposal.isVoting) "Voting ends" else "Voting ended", style = EarthTypography.textMd,
                    color = EarthColors.Text.textSecondary, modifier = Modifier.weight(1f))
                Text(proposal.votingEndTime.take(10), style = EarthTypography.textMd, color = EarthColors.Text.textPrimary)
            }
        }
        // An upgrade's plan decides what happens and when: worth its row.
        proposal.planName?.let { EarthDetailRow("Upgrade", it + (proposal.planHeight?.let { h -> " · at $h" } ?: "")) }

        // --- stake house ---
        Spacer(Modifier.height(dimens.space24))
        // Named only when there are two houses.
        if (assembly != null) SectionLabel("Stake")
        if (proposal.total <= 0) {
            Text(if (proposal.isVoting) "Nothing cast yet" else "No votes cast", style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)
        } else {
            TallyBar {
                TallyBarPart(proposal.yes, proposal.total, EarthAccent.ink)
                TallyBarPart(proposal.no, proposal.total, red)
                TallyBarPart(proposal.veto, proposal.total, EarthColors.Utility.ErrorRed.utilityError400)
                TallyBarPart(proposal.abstain, proposal.total, EarthColors.Text.textTertiary)
            }
            Spacer(Modifier.height(dimens.space8))
            Line(
                "${proposal.yes.pct(proposal.total)} yes · ${proposal.no.pct(proposal.total)} no · " +
                    "${proposal.abstain.pct(proposal.total)} abstain · ${proposal.veto.pct(proposal.total)} veto",
            )
            // The figures as well: a stake-weighted vote is often one holder's, which shows only as a number.
            Line("${formatErth(proposal.yes)} · ${formatErth(proposal.no)} · ${formatErth(proposal.abstain)} · ${formatErth(proposal.veto)}")
        }
        if (proposal.isVoting) {
            Spacer(Modifier.height(dimens.space12))
            if (eligibility != null) {
                Line(eligibility)
            } else if (onVote != null) {
                stakeWeight?.let { Line("Your weight: $it"); Spacer(Modifier.height(dimens.space8)) }
                Row(horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                    PillButton(Gov.Vote.Yes.label, modifier = Modifier.weight(1f)) { onVote(proposal, Gov.Vote.Yes) }
                    PillButton(Gov.Vote.No.label, primary = false, modifier = Modifier.weight(1f)) { onVote(proposal, Gov.Vote.No) }
                }
                // A veto is not a louder no: it burns the deposit. Quieter, on its own row.
                Spacer(Modifier.height(dimens.space8))
                Row(horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                    PillButton(Gov.Vote.Abstain.label, primary = false, modifier = Modifier.weight(1f)) { onVote(proposal, Gov.Vote.Abstain) }
                    PillButton(Gov.Vote.Veto.label, primary = false, modifier = Modifier.weight(1f)) { onVote(proposal, Gov.Vote.Veto) }
                }
                Spacer(Modifier.height(dimens.space8))
                Line("Final · each stake note votes once")
            }
        }

        // --- human house ---
        if (assembly != null) {
            Spacer(Modifier.height(dimens.space24))
            SectionLabel("People")
            if (assembly.silent) {
                // Silence is a refusal here: a proposal with no human votes fails.
                Text(
                    if (proposal.isVoting) "No votes yet · fails without any" else "No votes cast",
                    style = EarthTypography.textSm,
                    color = if (proposal.isVoting) red else EarthColors.Text.textTertiary,
                )
            } else {
                TallyBar {
                    TallyBarPart(assembly.yes, assembly.total, EarthAccent.ink)
                    TallyBarPart(assembly.no, assembly.total, red)
                }
                Spacer(Modifier.height(dimens.space8))
                // Headcounts, not ERTH.
                Line("${assembly.yes} yes · ${assembly.no} no · " + if (assembly.approved) "clears two thirds" else "below two thirds")
            }
            if (proposal.isVoting) {
                Spacer(Modifier.height(dimens.space12))
                if (assemblyEligibility != null) {
                    Line(assemblyEligibility)
                } else if (onAssemblyVote != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                        PillButton(Assembly.Vote.Yes.label, modifier = Modifier.weight(1f)) { onAssemblyVote(proposal, Assembly.Vote.Yes) }
                        PillButton(Assembly.Vote.No.label, primary = false, modifier = Modifier.weight(1f)) { onAssemblyVote(proposal, Assembly.Vote.No) }
                    }
                }
            }
        }

        // The human tally is purged when a proposal resolves, so for a closed
        // one this string is the only surviving record of which house ended it.
        if (proposal.failedReason.isNotBlank()) {
            Spacer(Modifier.height(dimens.space16))
            Line(proposal.failedReason)
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textPrimary)
    Spacer(Modifier.height(EarthTheme.dimens.space8))
}

@Composable
private fun Line(text: String) = Text(text, style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)

@Composable
private fun TallyBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val dimens = EarthTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimens.space8)
            .background(EarthColors.Surfaces.bgSecondary, RoundedCornerShape(dimens.space8)),
        content = content,
    )
}

private fun Long.pct(total: Long): String = if (total <= 0) "0%" else "${this * 100 / total}%"

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
