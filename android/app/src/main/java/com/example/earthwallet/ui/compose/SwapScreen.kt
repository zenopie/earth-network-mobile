package network.erth.wallet.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
    /**
     * Base units, null while the wallet is still loading — a zero would be a
     * lie. Base units rather than display strings because the amount shortcuts
     * below have to do arithmetic on them, and parsing a formatted number back
     * is how a thousands separator ends up in a transaction.
     */
    erthUerth: Long?,
    anmlUnits: Long?,
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
    val amountKeys = doneKeyboard(keyboardType = KeyboardType.Decimal)
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)

    var erthIn by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf("") }
    var slippageBps by remember { mutableIntStateOf(DEFAULT_SLIPPAGE_BPS) }

    val fromDenom = if (erthIn) "ERTH" else "ANML"
    val toDenom = if (erthIn) "ANML" else "ERTH"
    val fromUnits = if (erthIn) erthUerth else anmlUnits
    val toUnits = if (erthIn) anmlUnits else erthUerth
    val fromBalance = fromUnits?.let { formatUerth(it) }
    val toBalance = toUnits?.let { formatUerth(it) }

    /**
     * What can actually be swapped, in base units.
     *
     * The fee is always paid in ERTH, so selling ERTH has to leave it behind —
     * "max" that spends the fee too produces a transaction the ante handler
     * rejects, which costs a round trip to discover. Selling ANML, the whole
     * balance is available and the ERTH for the fee has to already be there.
     */
    val spendable = when {
        fromUnits == null -> null
        erthIn -> (fromUnits - SWAP_FEE_UERTH).coerceAtLeast(0)
        else -> fromUnits
    }

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
            .dismissKeyboardOnTap()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))

        // The reverse button sits on the seam between the panels, so its
        // position is the paying panel's height — not the midpoint of the two.
        // Centring it only looked right while both panels happened to be the
        // same height, and the amount chips made the top one taller.
        var payHeightPx by remember { mutableIntStateOf(0) }

        Box {
            Column {
                SwapPanel(
                    modifier = Modifier.onSizeChanged { payHeightPx = it.height },
                    label = "You pay",
                    denom = fromDenom,
                    icon = if (erthIn) R.drawable.ic_erth_logo else R.drawable.anml,
                    balance = fromBalance,
                    shape = shape,
                    value = amount,
                    onValueChange = { amount = it.asAmountInput(amount) },
                    spendable = spendable,
                    onFraction = { numerator, denominator ->
                        val units = (spendable ?: 0) * numerator / denominator
                        amount = if (units > 0) units.asDecimalAmount() else ""
                    },
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

            // Straddles the seam: half its height in each panel, which is what
            // makes it read as reversing them rather than as an action on the
            // panel above. The ring is the page colour, so it punches a hole
            // through the seam instead of sitting on top of it.
            val seamOffset = with(LocalDensity.current) {
                payHeightPx.toDp() + dimens.space8 / 2 - REVERSE_BUTTON_SIZE / 2
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = seamOffset)
                    .size(REVERSE_BUTTON_SIZE)
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
            // The number that actually goes on chain. The quote above is what
            // the pool would pay right now; this is the floor the transaction
            // refuses to go below, and it is the only one of the two that is
            // enforced — so it is worth showing rather than leaving implied by
            // a tolerance setting.
            EarthDetailRow(
                "Minimum received",
                "${quote.amountOut.withSlippage(slippageBps).fromBaseUnits()} $toDenom",
            )

            Spacer(Modifier.height(dimens.space12))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Max slippage",
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                SLIPPAGE_CHOICES.forEach { bps ->
                    Spacer(Modifier.width(dimens.space8))
                    SlippageChip(
                        bps = bps,
                        selected = bps == slippageBps,
                        onClick = { slippageBps = bps },
                    )
                }
            }

            // Small pools move a long way on an ordinary trade, so a tolerance
            // under the impact the quote already shows will simply fail. Said
            // here rather than left to the chain, which reports it as a
            // rejected transaction after the fee is spent.
            if (quote.priceImpact * 10_000 > slippageBps) {
                Spacer(Modifier.height(dimens.space8))
                Text(
                    text = "This trade moves the price more than your tolerance " +
                        "allows, so it will be rejected. Raise the tolerance or " +
                        "trade a smaller amount.",
                    style = EarthTypography.textXs,
                    color = EarthColors.Utility.ErrorRed.utilityError700,
                )
            }
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
                        out.withSlippage(slippageBps),
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
    modifier: Modifier = Modifier,
    label: String,
    denom: String,
    /** The token's own mark, in its own colours — never tinted. */
    icon: Int,
    balance: String?,
    /** Non-null on the paying side, with the fee already set aside. */
    spendable: Long? = null,
    onFraction: (numerator: Long, denominator: Long) -> Unit = { _, _ -> },
    shape: androidx.compose.ui.graphics.Shape,
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean = false,
) {
    val dimens = EarthTheme.dimens
    val amountKeys = doneKeyboard(keyboardType = KeyboardType.Decimal)
    Column(
        modifier
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
                        keyboardOptions = amountKeys.first,
                        keyboardActions = amountKeys.second,
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

        // Only on the side being spent, and only when there is something to
        // spend. Shortcuts over a zero balance are two controls that do
        // nothing, on the screen where a new wallet spends most of its time.
        if (spendable != null && spendable > 0) {
            Spacer(Modifier.height(dimens.space12))
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
                AmountChip("50%") { onFraction(1, 2) }
                AmountChip("Max") { onFraction(1, 1) }
            }
        }
    }
}

@Composable
private fun AmountChip(label: String, onClick: () -> Unit) {
    val dimens = EarthTheme.dimens
    Text(
        text = label,
        style = EarthTypography.textXs,
        fontWeight = FontWeight.SemiBold,
        color = EarthColors.Btns.Secondary.btnSecondaryFg,
        modifier = Modifier
            .clip(RoundedCornerShape(dimens.space20))
            .background(EarthColors.Btns.Secondary.btnSecondaryBg)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.space12, vertical = dimens.space4),
    )
}

/** The reverse button's diameter, needed to centre it on the seam. */
private val REVERSE_BUTTON_SIZE = 48.dp

/**
 * What a swap costs at the node's minimum gas price. Derived from the gas limit
 * the swap actually broadcasts with, rather than a copy of the number: this is
 * subtracted from the spendable balance, so a stale value lets the user spend
 * their way past what the fee needs.
 */
private val SWAP_FEE_UERTH: Long get() = TxController.DEFAULT_FEE_UERTH

/** Base units to a plain decimal, for putting a computed amount in the field. */
private fun Long.asDecimalAmount(): String =
    java.math.BigDecimal(this).movePointLeft(6).stripTrailingZeros().toPlainString()

/** The chain sends reserves as decimal strings; anything else is a broken response. */
private fun String.toBigIntegerOrNull(): java.math.BigInteger? =
    runCatching { java.math.BigInteger(this) }.getOrNull()

/**
 * The floor the swap will accept, given a tolerance in basis points.
 *
 * The quote is computed against reserves read a moment ago, and anything
 * landing in a block before this one moves them. Without a floor the chain
 * fills at whatever price results; with the floor set at the quote itself, an
 * unrelated transaction in the same block fails the swap.
 *
 * Truncating division, so rounding always moves the floor down. Rounding it up
 * would quote a minimum the chain might refuse by a single unit.
 */
private fun java.math.BigInteger.withSlippage(bps: Int): java.math.BigInteger {
    val remaining = java.math.BigInteger.valueOf((10_000 - bps).toLong())
    return this * remaining / java.math.BigInteger.valueOf(10_000)
}

/**
 * Tolerances offered, in basis points.
 *
 * A short list rather than a free-text field. The useful range is narrow, the
 * failure modes at each end are opposite — too tight and it never fills, too
 * loose and a reordering takes the difference — and neither is obvious from a
 * number typed into a box.
 */
private val SLIPPAGE_CHOICES = listOf(50, 100, 300)
private const val DEFAULT_SLIPPAGE_BPS = 100

@Composable
private fun SlippageChip(bps: Int, selected: Boolean, onClick: () -> Unit) {
    val dimens = EarthTheme.dimens
    Text(
        text = if (bps % 100 == 0) "${bps / 100}%" else "%.1f%%".format(bps / 100f),
        style = EarthTypography.textXs,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            EarthColors.Btns.Secondary.btnSecondaryFg
        } else {
            EarthColors.Text.textTertiary
        },
        modifier = Modifier
            .clip(RoundedCornerShape(dimens.space20))
            .background(
                if (selected) {
                    EarthColors.Btns.Secondary.btnSecondaryBg
                } else {
                    EarthColors.Surfaces.bgSecondary
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.space12, vertical = dimens.space4),
    )
}
