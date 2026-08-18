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
import com.valentinilk.shimmer.shimmer
import network.erth.wallet.chain.Dex
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.ShimmerRectangle
import network.erth.wallet.ui.vendor.component.rememberEarthShimmer
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Markets: the pools, and the two things you can do to them.
 *
 * Every pool on this chain pairs against ERTH — it is a hub-and-spoke AMM, not
 * an arbitrary-pair one — so each row names only the spoke token and the price
 * is always quoted in ERTH. Saying "ERTH/ANML" on every row would repeat the
 * hub five times and hide the part that differs.
 *
 * The price shown is the pool's ratio, which is the marginal price before fee
 * and slippage, not what a trade of any size will actually clear at. The swap
 * screen quotes the real number against the amount entered; this is the
 * headline figure, and it is labelled as the pool price rather than as "price"
 * so the two cannot be confused.
 */
@Composable
fun MarketsScreen(
    pools: List<Dex.Pool>?,
    swapFeePercent: String?,
    onSwap: () -> Unit,
    onLiquidity: () -> Unit,
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
            EarthButton(
                text = "Swap",
                onClick = onSwap,
                modifier = Modifier.weight(1f),
                colors = brandButtonColors(),
            )
            Spacer(Modifier.height(dimens.space12))
            Spacer(Modifier.padding(horizontal = dimens.space4))
            EarthButton(
                text = "Liquidity",
                onClick = onLiquidity,
                modifier = Modifier.weight(1f),
                colors = EarthButtonDefaults.secondaryColors(),
            )
        }

        Spacer(Modifier.height(dimens.space24))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EarthLabel("Pools")
            Spacer(Modifier.weight(1f))
            if (swapFeePercent != null) {
                Text(
                    text = "${swapFeePercent.trimDecimal()}% swap fee",
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(dimens.space8))

        when {
            pools == null -> repeat(3) {
                Column(Modifier.padding(vertical = dimens.space12).shimmer(shimmer)) {
                    ShimmerRectangle(width = 96.dp(), height = 14.dp())
                    Spacer(Modifier.height(6.dp()))
                    ShimmerRectangle(width = 160.dp(), height = 12.dp())
                }
            }

            pools.isEmpty() -> Text(
                text = "No pools yet. A pool appears here once someone provides " +
                    "liquidity for a token against ERTH.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )

            else -> pools.forEach { pool -> PoolRow(pool, onSwap) }
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun PoolRow(pool: Dex.Pool, onClick: () -> Unit) {
    val dimens = EarthTheme.dimens
    val token = pool.tokenDenom.removePrefix("u").uppercase()
    val erth = pool.erthReserve.toLongOrNull() ?: 0L
    val tokens = pool.tokenReserve.toLongOrNull() ?: 0L

    // Marginal price only — a real quote needs the trade size, and the swap
    // screen computes it there.
    val price = if (tokens > 0) erth.toDouble() / tokens.toDouble() else 0.0

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                EarthColors.Surfaces.bgSecondary,
                RoundedCornerShape(EarthDimensions.Radius.radius3xl),
            )
            .clickable(onClick = onClick)
            .padding(dimens.space16),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = token,
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Text.textPrimary,
                )
                Text(
                    text = "Pool price",
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary,
                )
            }
            Text(
                text = "${price.readable()} ERTH",
                style = EarthTypography.textMd,
                color = EarthColors.Text.textPrimary,
            )
        }
        Spacer(Modifier.height(dimens.space8))
        // Reserves on their own line: at these magnitudes the pair is far too
        // long to sit opposite a label, and it wrapped the label instead.
        Text(
            text = "${formatUerth(erth)} ERTH · ${formatUerth(tokens)} $token",
            style = EarthTypography.textXs,
            color = EarthColors.Text.textTertiary,
        )
    }
    Spacer(Modifier.height(dimens.space8))
}

/**
 * A price at whatever magnitude it happens to be.
 *
 * The chain's pairs are not priced near 1: a hub token against a scarce one
 * lands in the hundreds of thousands, and the reverse pair would be a millionth.
 * Four fixed decimals is wrong at both ends, so the precision follows the
 * number.
 */
private fun Double.readable(): String = when {
    this == 0.0 -> "0"
    this >= 1000 -> "%,.0f".format(this)
    this >= 1 -> "%,.4f".format(this)
    else -> "%.8f".format(this).trimEnd('0').trimEnd('.')
}

/** The chain returns decimals at 18 places; nobody needs to read all of them. */
private fun String.trimDecimal(): String =
    if ('.' in this) trimEnd('0').trimEnd('.') else this

private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
