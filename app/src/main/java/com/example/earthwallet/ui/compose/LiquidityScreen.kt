package network.erth.wallet.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import network.erth.wallet.R
import network.erth.wallet.ui.theme.EarthAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * Liquidity: what each pool holds, and the two sides of providing to it.
 *
 * A pool row states its reserves rather than an APR. This chain pays liquidity
 * providers from swap fees and nothing else, so an APR here would be a
 * backward-looking guess dressed as a rate — and a headline yield is exactly
 * the number people act on without reading the qualifier under it.
 *
 * Impermanent loss is named on the screen rather than in a help page for the
 * same reason: it is the thing that surprises providers, and it surprises them
 * after they have provided.
 */
@Composable
fun LiquidityScreen(
    pools: List<Dex.Pool>?,
    swapFeePercent: String?,
    lpOptionShare: Double,
    modifier: Modifier = Modifier,
    onAdd: (Dex.Pool) -> Unit = {},
    onRemove: (Dex.Pool) -> Unit = {},
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
        Text(
            text = if (swapFeePercent != null) {
                "Providing liquidity earns a share of that pool's " +
                    "${swapFeePercent.trimDecimal()}% swap fee. It also means holding " +
                    "both sides: if the price moves, you end up with more of " +
                    "whichever token fell."
            } else {
                "Providing liquidity earns a share of that pool's swap fees. " +
                    "It also means holding both sides: if the price moves, you " +
                    "end up with more of whichever token fell."
            },
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space24))

        if (pools == null) {
            repeat(2) {
                Column(Modifier.padding(vertical = dimens.space12).shimmer(shimmer)) {
                    ShimmerRectangle(width = 120.dp(), height = 16.dp())
                    Spacer(Modifier.height(8.dp()))
                    ShimmerRectangle(width = 200.dp(), height = 12.dp())
                }
            }
            return@Column
        }

        if (pools.isEmpty()) {
            Text(
                text = "No pools yet.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
            return@Column
        }

        val fee = swapFeePercent?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }

        pools.forEach { pool ->
            val token = pool.tokenDenom.removePrefix("u").uppercase()
            val apr = fee?.let { AprMath.aprFor(pool, pools, lpOptionShare, it) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        EarthColors.Surfaces.bgSecondary,
                        RoundedCornerShape(EarthDimensions.Radius.radius3xl),
                    )
                    .padding(dimens.space16),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Both marks, overlapped slightly, because a pool is the
                    // pair rather than either side of it.
                    Image(
                        modifier = Modifier.size(dimens.space24),
                        painter = painterResource(R.drawable.ic_erth_logo),
                        contentDescription = null,
                    )
                    Image(
                        modifier = Modifier
                            .offset(x = (-6).dp())
                            .size(dimens.space24),
                        painter = painterResource(
                            if (pool.tokenDenom == "uanml") {
                                R.drawable.anml
                            } else {
                                R.drawable.ic_token_default
                            },
                        ),
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(dimens.space8))
                    Text(
                        text = "ERTH · $token",
                        style = EarthTypography.textMd,
                        fontWeight = FontWeight.SemiBold,
                        color = EarthColors.Text.textPrimary,
                    )
                }
                Spacer(Modifier.height(dimens.space8))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Pool price",
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    val erth = pool.erthReserve.toLongOrNull() ?: 0L
                    val tokens = pool.tokenReserve.toLongOrNull() ?: 0L
                    Text(
                        text = if (tokens > 0) {
                            "${(erth.toDouble() / tokens).readable()} ERTH"
                        } else {
                            "—"
                        },
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textPrimary,
                    )
                }
                Spacer(Modifier.height(dimens.space4))
                // Reserves stack rather than sit opposite a label: at pool
                // sizes this large the value is wider than the row, and
                // weighting it against a label squeezed "Reserves" down to one
                // character per line.
                Text(
                    text = "Reserves",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textTertiary,
                )
                Text(
                    text = "${formatUerth(pool.erthReserve.toLongOrNull() ?: 0)} ERTH",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textPrimary,
                )
                Text(
                    text = "${formatUerth(pool.tokenReserve.toLongOrNull() ?: 0)} $token",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textPrimary,
                )
                if (apr != null) {
                    Spacer(Modifier.height(dimens.space12))
                    AprBlock(apr)
                }

                Spacer(Modifier.height(dimens.space12))
                Row(Modifier.fillMaxWidth()) {
                    EarthButton(
                        text = "Add",
                        onClick = { onAdd(pool) },
                        modifier = Modifier.weight(1f),
                        colors = brandButtonColors(),
                    )
                    Spacer(Modifier.padding(horizontal = dimens.space4))
                    EarthButton(
                        text = "Remove",
                        onClick = { onRemove(pool) },
                        modifier = Modifier.weight(1f),
                        colors = EarthButtonDefaults.secondaryColors(),
                    )
                }
            }
            Spacer(Modifier.height(dimens.space12))
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

/**
 * The rate, split by where it comes from.
 *
 * Split rather than summed to one headline because the two halves behave in
 * opposite directions when you deposit. Fees scale with the pool — more
 * liquidity carries more volume at the same price impact. Emissions do not: the
 * stream is shared out by *volume*, so a deposit does not raise this pool's cut
 * of it, it only divides the same cut across more capital. The emission line
 * therefore falls the moment you add to the pool, and someone reading a single
 * blended number would expect the opposite.
 *
 * The volume share is shown alongside because it is the whole basis of the
 * emission line and it moves whenever any pool trades.
 */
@Composable
private fun AprBlock(apr: PoolApr) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(EarthAccent.tint, RoundedCornerShape(dimens.space12))
            .padding(dimens.space12),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Estimated APR",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = apr.total.asPercent(),
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthAccent.ink,
            )
        }
        Spacer(Modifier.height(dimens.space8))
        AprLine("Swap fees", apr.fee.asPercent())
        AprLine("Emissions", apr.emission.asPercent())
        AprLine("Share of LP stream", apr.volumeShare.asPercent())
    }
}

@Composable
private fun AprLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = EarthTypography.textXs,
            color = EarthColors.Text.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = EarthTypography.textXs,
            color = EarthColors.Text.textSecondary,
        )
    }
}

/**
 * A rate at whatever magnitude it lands.
 *
 * These span from a rounding error to triple digits depending on how much
 * liquidity is in a pool, so a fixed two decimals is wrong at both ends. Below
 * a hundredth of a percent it says so rather than rounding to a flat 0.00%,
 * which reads as "none" when it means "very small".
 */
private fun Double.asPercent(): String {
    val pct = this * 100
    return when {
        pct == 0.0 -> "0%"
        pct < 0.01 -> "<0.01%"
        pct < 1 -> "%.2f%%".format(pct)
        pct < 100 -> "%.1f%%".format(pct)
        else -> "%,.0f%%".format(pct)
    }
}

/** Prices here span several orders of magnitude, so precision follows the number. */
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
