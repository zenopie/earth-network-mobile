package network.erth.wallet.chain.math

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

    /**
     * The chain's feeOf: LegacyDec(amount).Mul(fee).Quo(100).TruncateInt().
     * Mul is exact for an 18-place fee; Quo rounds its 18th place half-even
     * (chopPrecisionAndRound) before the truncation, which only matters for a
     * fee whose quotient lands within 1e-18 of an integer.
     */
    fun feeOf(amount: BigInteger, feePercent: BigDecimal): BigInteger =
        BigDecimal(amount)
            .multiply(feePercent)
            .divide(BigDecimal(100))
            .setScale(18, RoundingMode.HALF_EVEN)
            .setScale(0, RoundingMode.DOWN)
            .toBigInteger()

    /** One pool's reserves, ERTH and its token. */
    data class Reserves(val erth: BigInteger, val token: BigInteger)

    /**
     * What swapping [amountIn] of [denomIn] for [denomOut] pays, routed the
     * way x/dex swapExactIn routes it: one hop when either side is the hub
     * ([hub], ERTH), else token -> ERTH -> token through each token's pool.
     * [pools] maps a token denom to its pool's reserves. Null when a pool is
     * missing or the output rounds to nothing (the chain refuses both).
     *
     * The node's pool query leaves out LP rewards not yet compounded into the
     * ERTH reserve (settlePoolRewards runs at swap time), so the chain's own
     * figure can differ by that much: the slippage floor absorbs it.
     */
    fun route(
        pools: Map<String, Reserves>,
        hub: String,
        denomIn: String,
        amountIn: BigInteger,
        denomOut: String,
        feePercent: BigDecimal,
    ): SwapQuote? {
        if (denomIn == denomOut) return null
        val q = when {
            denomIn == hub -> pools[denomOut]?.let { hubForToken(it.erth, it.token, amountIn, feePercent) }
            denomOut == hub -> pools[denomIn]?.let { tokenForHub(it.erth, it.token, amountIn, feePercent) }
            else -> {
                val a = pools[denomIn] ?: return null
                val b = pools[denomOut] ?: return null
                val first = tokenForHub(a.erth, a.token, amountIn, feePercent) ?: return null
                if (first.amountOut.signum() <= 0) return null
                val second = hubForToken(b.erth, b.token, first.amountOut, feePercent) ?: return null
                SwapQuote(
                    amountOut = second.amountOut,
                    feeErth = first.feeErth + second.feeErth,
                    priceImpact = 1 - (1 - first.priceImpact) * (1 - second.priceImpact),
                )
            }
        } ?: return null
        return q.takeIf { it.amountOut.signum() > 0 }
    }

    /**
     * The floor a swap accepts at a tolerance of [bps] basis points.
     * Truncating, so rounding only ever moves the floor down.
     */
    fun withSlippage(amountOut: BigInteger, bps: Int): BigInteger =
        amountOut * BigInteger.valueOf((10_000 - bps).toLong()) / BigInteger.valueOf(10_000)

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
