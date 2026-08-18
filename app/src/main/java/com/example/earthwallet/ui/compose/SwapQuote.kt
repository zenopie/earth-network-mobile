package network.erth.wallet.ui.compose

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * What a swap returns, priced the way the chain prices it.
 *
 * Mirrors x/dex/keeper/amm.go rather than approximating it. The two differences
 * that matter, and that a generic constant-product quote gets wrong:
 *
 *  - The fee is always taken in ERTH, whichever direction the swap goes. Buying
 *    the token, it comes off the input before the curve; selling it, off the
 *    ERTH output after. A quote that applies the fee to the input in both
 *    directions is wrong in one of them.
 *  - Every division truncates, because the chain works in integers. Rounding
 *    would quote a fraction of a unit more than the chain will actually pay,
 *    which is exactly the direction that makes a minimum-out check fail.
 *
 * All amounts are in base units (uerth / uanml).
 */
data class SwapQuote(
    val amountOut: BigInteger,
    val feeErth: BigInteger,
    /** How far the trade moves the price against you, as a fraction. */
    val priceImpact: Double,
)

object SwapMath {

    /** ERTH -> token. Fee comes off the input. */
    fun hubForToken(
        reserveErth: BigInteger,
        reserveToken: BigInteger,
        amountIn: BigInteger,
        feePercent: BigDecimal,
    ): SwapQuote? {
        if (amountIn <= BigInteger.ZERO || reserveErth <= BigInteger.ZERO) return null
        val fee = feeOf(amountIn, feePercent)
        val effectiveIn = amountIn - fee
        val out = reserveToken * effectiveIn / (reserveErth + effectiveIn)
        return SwapQuote(
            amountOut = out,
            feeErth = fee,
            priceImpact = impact(reserveErth, reserveToken, effectiveIn, out),
        )
    }

    /** Token -> ERTH. Fee comes off the output. */
    fun tokenForHub(
        reserveErth: BigInteger,
        reserveToken: BigInteger,
        amountIn: BigInteger,
        feePercent: BigDecimal,
    ): SwapQuote? {
        if (amountIn <= BigInteger.ZERO || reserveToken <= BigInteger.ZERO) return null
        val gross = reserveErth * amountIn / (reserveToken + amountIn)
        val fee = feeOf(gross, feePercent)
        return SwapQuote(
            amountOut = gross - fee,
            feeErth = fee,
            priceImpact = impact(reserveToken, reserveErth, amountIn, gross),
        )
    }

    /** The chain's feeOf: percent of amount, truncated. */
    private fun feeOf(amount: BigInteger, feePercent: BigDecimal): BigInteger =
        BigDecimal(amount)
            .multiply(feePercent)
            .divide(BigDecimal(100))
            .setScale(0, RoundingMode.DOWN)
            .toBigInteger()

    /**
     * How much worse the trade's average price is than the pool's marginal one.
     *
     * Worth showing because these pools are small: a trade that would be
     * invisible on a deep market can move this one several percent, and the
     * quote alone does not say whether the number is the market's or your own
     * doing.
     */
    private fun impact(
        reserveIn: BigInteger,
        reserveOut: BigInteger,
        amountIn: BigInteger,
        amountOut: BigInteger,
    ): Double {
        if (amountIn.signum() == 0 || reserveIn.signum() == 0) return 0.0
        val spot = reserveOut.toDouble() / reserveIn.toDouble()
        val effective = amountOut.toDouble() / amountIn.toDouble()
        if (spot == 0.0) return 0.0
        return ((spot - effective) / spot).coerceIn(0.0, 1.0)
    }
}

/**
 * Keep only what can be part of a decimal number, and only one point.
 *
 * A filter rather than validation-after-the-fact: the numeric keyboard still
 * offers a comma on many locales and a paste can carry anything at all, so the
 * field has to refuse the character rather than accept it and complain.
 */
internal fun String.asAmountInput(previous: String): String {
    if (isEmpty()) return ""
    val cleaned = buildString {
        var seenPoint = false
        for (c in this@asAmountInput) {
            when {
                c.isDigit() -> append(c)
                (c == '.' || c == ',') && !seenPoint -> {
                    seenPoint = true
                    append('.')
                }
                else -> Unit
            }
        }
    }
    // Six decimals is the denomination's precision; more cannot be sent.
    val frac = cleaned.substringAfter('.', "")
    return if (frac.length > 6) previous else cleaned
}

/** Base units from a typed decimal, or null when it is not a number. */
internal fun String.toBaseUnits(): BigInteger? =
    runCatching { BigDecimal(this).movePointRight(6).setScale(0, RoundingMode.DOWN).toBigInteger() }
        .getOrNull()

/** Base units back to a plain decimal for display. */
internal fun BigInteger.fromBaseUnits(): String =
    BigDecimal(this).movePointLeft(6).stripTrailingZeros().toPlainString()
