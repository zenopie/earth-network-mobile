package network.erth.wallet.ui.explore

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import network.erth.wallet.chain.Explorer
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.components.EarthListRow
import network.erth.wallet.ui.designsystem.component.EarthHorizontalDivider
import network.erth.wallet.ui.designsystem.component.ShimmerRectangle
import network.erth.wallet.ui.designsystem.component.rememberEarthShimmer
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.earn.DelegationRow
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/** What the explore tab shows, loaded together. */
data class ExploreUiState(
    val chainId: String,
    val height: Long,
    val registrations: Long,
    val blocks: List<Explorer.Block>,
    val validators: List<DelegationRow>,
)

/**
 * Explore: the chain itself.
 *
 * Verified humans lead: the thing the chain exists to count, with the
 * height (the chain running) under it. Blocks and validators follow as lists.
 *
 * Registered humans is here rather than on the identity screen because it is a
 * fact about the network, not about you; the identity screen answers whether
 * *this wallet* is verified, which is a different question.
 */
@Composable
fun ExploreScreen(
    state: ExploreUiState?,
    onTx: (String) -> Unit,
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
        // The count the chain exists to keep leads; the chain running is the line under it.
        network.erth.wallet.ui.components.HeroColumn {
            if (state == null) {
                Column(Modifier.shimmer(shimmer)) { ShimmerRectangle(width = 120.dp, height = 48.dp) }
            } else {
                Text(
                    "%,d".format(state.registrations),
                    style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold),
                    color = EarthColors.Text.textPrimary,
                )
            }
            Text("verified humans", style = EarthTypography.textMd, color = EarthColors.Text.textTertiary)
            if (state != null) network.erth.wallet.ui.components.HeroLine("Block %,d · ${state.chainId}".format(state.height))
        }
        Spacer(Modifier.height(dimens.space24))
        EarthLabel("Latest blocks")
        Spacer(Modifier.height(dimens.space8))

        if (state == null) {
            repeat(4) {
                Column(Modifier.padding(vertical = dimens.space8).shimmer(shimmer)) {
                    ShimmerRectangle(width = 200.dp, height = 12.dp)
                }
            }
        } else {
            state.blocks.forEachIndexed { i, b ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = dimens.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "%,d".format(b.height),
                        style = EarthTypography.textMd,
                        fontWeight = FontWeight.SemiBold,
                        color = EarthColors.Text.textPrimary,
                    )
                    Spacer(Modifier.padding(horizontal = dimens.space8))
                    Text(
                        text = b.time.substringAfter('T').take(8),
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        // "no transactions" rather than "0 txs": a block with
                        // nothing in it is the normal case on a quiet chain,
                        // and a column of zeroes reads as an error.
                        text = if (b.txCount == 0) {
                            "empty"
                        } else {
                            "${b.txCount} tx"
                        },
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textTertiary,
                    )
                }
                if (i != state.blocks.lastIndex) EarthHorizontalDivider()
            }
        }

        if (!state?.validators.isNullOrEmpty()) {
            Spacer(Modifier.height(dimens.space24))
            EarthLabel("Validators")
            Spacer(Modifier.height(dimens.space8))
            state.validators.forEach { v ->
                EarthListRow(
                    initial = v.moniker.take(1).uppercase(),
                    name = v.moniker,
                    subtitle = "${"%.0f".format(v.commission * 100)}% commission",
                    value = formatUerth(v.amountUerth),
                    iconBg = EarthAccent.tint,
                    iconFg = EarthAccent.ink,
                )
            }
        }
        Spacer(Modifier.height(dimens.space32))
    }
}
