package network.erth.wallet.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.chain.Allocation
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.ui.components.EarthDetailRow
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.destructiveButtonColors
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun Page(modifier: Modifier, content: @Composable () -> Unit) {
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))
        content()
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
internal fun Note(text: String) {
    Text(text = text, style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.space4)
            .background(EarthColors.Surfaces.bgSecondary, RoundedCornerShape(EarthDimensions.Radius.radius3xl))
            .padding(dimens.space16),
    ) { content() }
}

private fun optionName(options: List<Allocation.OptionInfo>, id: Long) =
    options.firstOrNull { it.id == id }?.description?.ifBlank { null } ?: "Option $id"

internal fun date(unix: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(unix * 1000))

/**
 * Groundworks positions: the private way to direct the Groundworks Fund.
 *
 * A position locks staked ERTH (derth) at a validator under an owner tag
 * (a commitment to this wallet the stake proof opens again to update,
 * vote or unlock it); its split is public and weighted by the stake, its
 * owner is not. The stake keeps earning while locked.
 */
@Composable
fun PositionsScreen(
    state: PrivacyActionsState?,
    /** derth/<valoper> balances this wallet can lock. */
    lockable: Map<String, Long>,
    onLock: () -> Unit,
    onEditSplit: (PositionRow) -> Unit,
    onUnlock: (PositionRow) -> Unit,
    modifier: Modifier = Modifier,
    /** derth at a validator, in uerth at its live rate. */
    valueOf: (derth: Long, validator: String) -> Long = { d, _ -> d },
) = Page(modifier) {
    val dimens = EarthTheme.dimens
    Note(
        "Lock staked ERTH in a position to direct the Groundworks Fund. The split and the " +
            "amount are public; nothing links the position to you. Locked stake keeps earning, " +
            "and unlocking returns it as staked ERTH.",
    )
    Spacer(Modifier.height(dimens.space16))
    if (state == null) {
        Note("Loading…")
        return@Page
    }
    EarthLabel("Your positions")
    Spacer(Modifier.height(dimens.space8))
    if (state.positions.isEmpty()) Note("No positions yet.")
    state.positions.forEach { row ->
        val p = row.position
        Card {
            Text(
                text = "${formatUerth(valueOf(p.derth, p.validator))} ERTH",
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary,
            )
            Text(text = "${formatUerth(p.derth)} derth · ${p.validator}", style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
            Spacer(Modifier.height(dimens.space8))
            p.splits.entries.sortedByDescending { it.value }.forEach { (id, pct) ->
                EarthDetailRow(optionName(state.groundworksOptions, id), "$pct%")
            }
            Spacer(Modifier.height(dimens.space8))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                EarthButton(
                    text = "Change split",
                    onClick = { onEditSplit(row) },
                    modifier = Modifier.weight(1f),
                    colors = EarthButtonDefaults.secondaryColors(),
                )
                EarthButton(
                    text = "Unlock",
                    onClick = { onUnlock(row) },
                    modifier = Modifier.weight(1f),
                    colors = destructiveButtonColors(),
                )
            }
        }
    }
    Spacer(Modifier.height(dimens.space16))
    if (lockable.values.sum() > 0) {
        EarthButton(text = "Lock stake in a position", onClick = onLock, modifier = Modifier.fillMaxWidth(), colors = brandButtonColors())
    } else {
        Note("Stake ERTH privately (Earn) to lock it in a position.")
    }
}

/**
 * Removal ballots: the human chamber's say over Groundworks options. Any
 * registered person may open one against an option (one open ballot per
 * option, no deposit) and vote in it; who opened or voted is never known,
 * and a second vote replaces the first.
 */
@Composable
fun RemovalBallotsScreen(
    state: PrivacyActionsState?,
    registered: Boolean,
    onPropose: (optionId: Long) -> Unit,
    onVote: (optionId: Long, yes: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = Page(modifier) {
    val dimens = EarthTheme.dimens
    Note(
        "People can remove a Groundworks option by ballot. Opening one and voting both " +
            "prove you are a registered person without saying which one.",
    )
    Spacer(Modifier.height(dimens.space16))
    if (state == null) {
        Note("Loading…")
        return@Page
    }
    if (!registered) {
        Note("Register your identity to open or vote in a removal ballot.")
        Spacer(Modifier.height(dimens.space16))
    }
    EarthLabel("Open ballots")
    Spacer(Modifier.height(dimens.space8))
    if (state.ballots.isEmpty()) Note("No ballot is open.")
    state.ballots.forEach { b: PrivacyQueries.RemovalBallot ->
        Card {
            Text(
                text = "Remove ${optionName(state.groundworksOptions, b.optionId)}?",
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary,
            )
            EarthDetailRow("Yes / No", "${b.yes} / ${b.no}")
            EarthDetailRow("Closes", date(b.closesAt))
            if (registered) {
                Spacer(Modifier.height(dimens.space8))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                    EarthButton(text = "Yes", onClick = { onVote(b.optionId, true) }, modifier = Modifier.weight(1f), colors = brandButtonColors())
                    EarthButton(text = "No", onClick = { onVote(b.optionId, false) }, modifier = Modifier.weight(1f), colors = destructiveButtonColors())
                }
            }
        }
    }
    if (registered) {
        Spacer(Modifier.height(dimens.space24))
        EarthLabel("Open a ballot")
        Spacer(Modifier.height(dimens.space8))
        val open = state.ballots.map { it.optionId }.toSet()
        state.groundworksOptions.filter { it.id !in open }.forEach { o ->
            Row(Modifier.fillMaxWidth().padding(vertical = dimens.space4)) {
                Text(
                    text = o.description,
                    style = EarthTypography.textMd,
                    color = EarthColors.Text.textPrimary,
                    modifier = Modifier.weight(1f).padding(top = dimens.space8),
                )
                EarthButton(text = "Propose removal", onClick = { onPropose(o.id) }, colors = EarthButtonDefaults.secondaryColors())
            }
        }
    }
}

/**
 * Shielded notes, per asset. A private payment spends any number of notes,
 * up to max_actions_per_bundle in one transaction, so merging only matters
 * for a balance spread over more notes than that: it joins the smallest
 * notes one transaction carries (ERTH pays its fee from them). Stake notes
 * move two to a proof, so a stake balance spread over many merges by a
 * restake.
 */
@Composable
fun NotesScreen(
    state: PrivacyActionsState?,
    shielded: Map<String, Long>,
    onMerge: (denom: String) -> Unit,
    modifier: Modifier = Modifier,
) = Page(modifier) {
    val dimens = EarthTheme.dimens
    Note(
        "A private payment can spend many notes at once, of any assets. Merge only when a balance is " +
            "spread over more notes than one transaction carries, or to tidy staked ERTH into fewer notes.",
    )
    Spacer(Modifier.height(dimens.space16))
    shielded.filterValues { it > 0 }.toSortedMap().forEach { (denom, amount) ->
        val count = state?.mergeable?.get(denom)
        Card {
            Text(text = denom.label(), style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textPrimary)
            EarthDetailRow("Balance", formatUerth(amount))
            if (count != null) {
                EarthDetailRow("Notes", "$count")
                Spacer(Modifier.height(dimens.space8))
                EarthButton(
                    text = "Merge smallest notes",
                    onClick = { onMerge(denom) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = EarthButtonDefaults.secondaryColors(),
                )
            }
        }
    }
}

private fun String.label(): String = when {
    this == "uerth" -> "ERTH"
    this == "uanml" -> "ANML"
    startsWith("derth/") -> "Staked ERTH · ${removePrefix("derth/").take(20)}…"
    else -> this
}
