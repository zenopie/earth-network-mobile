package network.erth.wallet.ui.compose

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.valentinilk.shimmer.shimmer
import network.erth.wallet.chain.Explorer
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthHorizontalDivider
import network.erth.wallet.ui.vendor.component.ShimmerRectangle
import network.erth.wallet.ui.vendor.component.rememberEarthShimmer
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

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
 * Height and registered humans lead because they are the two counters that say
 * what this chain is — one measures the chain running, the other measures the
 * thing it exists to count. Blocks and validators follow as lists.
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
        Spacer(Modifier.height(dimens.space16))

        Row(Modifier.fillMaxWidth()) {
            StatCard(
                label = "Block height",
                value = state?.height?.let { "%,d".format(it) },
                shimmer = shimmer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(horizontal = dimens.space4))
            StatCard(
                label = "Verified humans",
                value = state?.registrations?.let { "%,d".format(it) },
                shimmer = shimmer,
                modifier = Modifier.weight(1f),
            )
        }

        if (state != null) {
            Spacer(Modifier.height(dimens.space8))
            Text(
                text = "Chain ${state.chainId}",
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
        }

        Spacer(Modifier.height(dimens.space24))
        EarthLabel("Latest blocks")
        Spacer(Modifier.height(dimens.space8))

        if (state == null) {
            repeat(4) {
                Column(Modifier.padding(vertical = dimens.space8).shimmer(shimmer)) {
                    ShimmerRectangle(width = 200.dp(), height = 12.dp())
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

@Composable
private fun StatCard(
    label: String,
    value: String?,
    shimmer: com.valentinilk.shimmer.Shimmer,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    Column(
        modifier
            .background(
                EarthColors.Surfaces.bgSecondary,
                RoundedCornerShape(EarthDimensions.Radius.radius3xl),
            )
            .padding(dimens.space16),
    ) {
        Text(
            text = label,
            style = EarthTypography.textXs,
            color = EarthColors.Text.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(dimens.space4))
        if (value == null) {
            Column(Modifier.shimmer(shimmer)) {
                ShimmerRectangle(width = 72.dp(), height = 22.dp())
            }
        } else {
            Text(
                text = value,
                style = EarthTypography.header5,
                color = EarthColors.Text.textPrimary,
            )
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
