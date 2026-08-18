package network.erth.wallet.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.R
import network.erth.wallet.ui.theme.EarthTheme
import com.valentinilk.shimmer.shimmer
import network.erth.wallet.ui.vendor.component.ShimmerRectangle
import network.erth.wallet.ui.vendor.component.rememberEarthShimmer
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthIconButton
import network.erth.wallet.ui.vendor.component.EarthTextField
import network.erth.wallet.ui.vendor.component.IconButtonState
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.stringRes

/**
 * Swap, against the chain's pools.
 *
 * Their swap screen quotes across chains through a provider, with a route, a
 * slippage setting and a quote that expires. Earth's pool is on the same chain
 * and prices itself, so what survives is the composition: two stacked panels
 * for what goes in and what comes out, with a circular flip button straddling
 * the seam. The flip button is the reason the panels are stacked rather than
 * side by side — it has to sit on the boundary to read as reversing it.
 */
@Composable
fun SwapScreen(
    /** Null while the wallet is still loading; a zero here would be a lie. */
    erthBalance: String?,
    anmlBalance: String?,
    /** The pool being traded against, for the quote. Null while loading. */
    pool: network.erth.wallet.chain.Dex.Pool?,
    swapFeePercent: String?,
    /**
     * Denoms and amounts in base units, plus the minimum to accept.
     *
     * The screen passes the quote's floor rather than letting the caller
     * recompute it: the quote and the slippage guard have to come from the same
     * numbers, or the guard protects against the wrong price.
     */
    onSwap: (denomIn: String, amountIn: java.math.BigInteger,
             denomOut: String, minOut: java.math.BigInteger) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)

    var erthIn by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf("") }

    val fromDenom = if (erthIn) "ERTH" else "ANML"
    val toDenom = if (erthIn) "ANML" else "ERTH"
    val fromBalance = if (erthIn) erthBalance else anmlBalance
    val toBalance = if (erthIn) anmlBalance else erthBalance

    val fee = swapFeePercent?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }

    val quote = remember(amount, erthIn, pool, fee) {
        val input = amount.toBaseUnits() ?: return@remember null
        val p = pool ?: return@remember null
        val f = fee ?: return@remember null
        val erth = p.erthReserve.toBigIntegerOrNull() ?: return@remember null
        val token = p.tokenReserve.toBigIntegerOrNull() ?: return@remember null
        if (erthIn) {
            SwapMath.hubForToken(erth, token, input, f)
        } else {
            SwapMath.tokenForHub(erth, token, input, f)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))

        Box {
            Column {
                SwapPanel(
                    label = "You pay",
                    denom = fromDenom,
                    icon = if (erthIn) R.drawable.ic_erth_logo else R.drawable.anml,
                    balance = fromBalance,
                    shape = shape,
                    value = amount,
                    onValueChange = { amount = it.asAmountInput(amount) },
                )
                Spacer(Modifier.height(dimens.space8))
                SwapPanel(
                    label = "You receive",
                    denom = toDenom,
                    icon = if (erthIn) R.drawable.anml else R.drawable.ic_erth_logo,
                    balance = toBalance,
                    shape = shape,
                    value = quote?.amountOut?.fromBaseUnits().orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                )
            }

            // Straddles the seam between the two panels: half its height sits
            // in each, which is what makes it read as reversing them rather
            // than as an action on the panel above. The ring is the page
            // colour, so it punches a hole through the seam instead of sitting
            // on top of it.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(dimens.space48)
                    .background(EarthColors.Surfaces.bgPrimary, CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(EarthColors.Surfaces.bgSecondary)
                    .border(1.dp, EarthColors.Surfaces.strokeSecondary, CircleShape)
                    .clickable { erthIn = !erthIn },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    modifier = Modifier.size(18.dp),
                    painter = painterResource(network.erth.wallet.R.drawable.ic_swap_vertical),
                    colorFilter = ColorFilter.tint(EarthColors.Text.textPrimary),
                    contentDescription = "Reverse the swap",
                )
            }
        }

        if (quote != null) {
            Spacer(Modifier.height(dimens.space16))
            EarthDetailRow("Fee", "${quote.feeErth.fromBaseUnits()} ERTH")
            EarthDetailRow("Price impact", "%.2f%%".format(quote.priceImpact * 100))
        }

        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "Review swap",
            onClick = {
                val input = amount.toBaseUnits()
                val out = quote?.amountOut
                if (input != null && out != null) {
                    onSwap(
                        if (erthIn) "uerth" else "uanml",
                        input,
                        if (erthIn) "uanml" else "uerth",
                        out.withSlippage(),
                    )
                }
            },
            enabled = quote != null && quote.amountOut.signum() > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )

        // No pool list here. Reserves and LP shares are what a liquidity
        // provider needs; someone swapping needs the rate, the fee and what
        // they get, all of which are above. Pools moved to Liquidity, one tap
        // away in the bar.
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun SwapPanel(
    label: String,
    denom: String,
    /** The token's own mark, in its own colours — never tinted. */
    icon: Int,
    balance: String?,
    shape: androidx.compose.ui.graphics.Shape,
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean = false,
) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(EarthColors.Surfaces.bgSecondary, shape)
            .padding(dimens.space16),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EarthLabel(label)
            Spacer(Modifier.weight(1f))
            if (balance == null) {
                Box(Modifier.shimmer(rememberEarthShimmer())) {
                    ShimmerRectangle(width = 64.dp, height = 12.dp)
                }
            } else {
                Text(
                    text = "Balance $balance",
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(dimens.space8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (readOnly) {
                    Text(
                        text = value.ifEmpty { "0" },
                        style = EarthTypography.header5,
                        color = EarthColors.Text.textTertiary,
                    )
                } else {
                    EarthTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text("0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
            Spacer(Modifier.width(dimens.space12))
            Image(
                modifier = Modifier.size(dimens.space24),
                painter = painterResource(icon),
                contentDescription = null,
            )
            Spacer(Modifier.width(dimens.space8))
            Text(
                text = denom,
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary,
            )
        }
    }
}

/** The chain sends reserves as decimal strings; anything else is a broken response. */
private fun String.toBigIntegerOrNull(): java.math.BigInteger? =
    runCatching { java.math.BigInteger(this) }.getOrNull()

/**
 * The floor the swap will accept.
 *
 * One percent under the quote. The quote is computed against reserves that were
 * read a moment ago, and anything landing in a block before this one moves
 * them; without a floor the chain fills at whatever price results, and with a
 * floor set at the quote itself an unrelated transaction in the same block
 * fails the swap. One percent is loose enough to survive ordinary traffic on a
 * chain this quiet and tight enough that a real reordering is refused.
 */
private fun java.math.BigInteger.withSlippage(): java.math.BigInteger =
    this * java.math.BigInteger.valueOf(99) / java.math.BigInteger.valueOf(100)
