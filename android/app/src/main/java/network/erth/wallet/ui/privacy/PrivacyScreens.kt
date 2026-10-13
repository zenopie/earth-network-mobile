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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.components.HeroAmount
import network.erth.wallet.ui.components.HeroColumn
import network.erth.wallet.ui.components.HeroLine
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

internal fun date(unix: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(unix * 1000))

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
    val total = state?.mergeable?.values?.sum() ?: 0
    HeroColumn {
        network.erth.wallet.ui.components.FigureText(
            if (state == null) network.erth.wallet.ui.components.LOADING else "%,d".format(total),
            style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold),
            color = EarthColors.Text.textPrimary,
        )
        HeroLine(if (total > 0) "Notes to merge" else "Nothing to merge")
    }
    Spacer(Modifier.height(dimens.space24))
    shielded.filterValues { it > 0 }.toSortedMap().forEach { (denom, amount) ->
        val count = state?.mergeable?.get(denom)
        Row(Modifier.fillMaxWidth().padding(vertical = dimens.space12), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(denom.label(), style = EarthTypography.textMd, fontWeight = FontWeight.SemiBold, color = EarthColors.Text.textPrimary, maxLines = 1)
                Text(
                    formatUerth(amount) + (count?.let { " · $it notes" } ?: ""),
                    style = EarthTypography.textXs, color = EarthColors.Text.textTertiary, maxLines = 1,
                )
            }
            if (count != null) {
                network.erth.wallet.ui.earn.PillButton("Merge", primary = false, modifier = Modifier.width(110.dp)) { onMerge(denom) }
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
