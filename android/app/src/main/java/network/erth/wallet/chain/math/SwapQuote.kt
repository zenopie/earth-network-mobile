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
     * The chain's feeOf (chain 203d3b2, audit 5 L-DX4: rounded up, so a small
     * swap cannot pay nothing):
     * LegacyDec(amount).Mul(fee).Quo(100).Ceil().TruncateInt(). Mul is exact
     * for an 18-place fee; Quo rounds its 18th place half-even
     * (chopPrecisionAndRound) before the ceiling, which only matters for a
     * fee whose quotient lands within 1e-18 of an integer.
     */
    fun feeOf(amount: BigInteger, feePercent: BigDecimal): BigInteger =
        BigDecimal(amount)
            .multiply(feePercent)
            .divide(BigDecimal(100))
            .setScale(18, RoundingMode.HALF_EVEN)
            .setScale(0, RoundingMode.CEILING)
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
     * figure can differ by that much: callers prefer the chain's
     * SimulateSwapExactIn ([withChain]) and use this when it is unavailable.
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
     * [local] with the chain's own figures for [amountOut] and [feeErth] (x/dex
     * SimulateSwapExactIn, which settles pending LP rewards first) when the
     * node gave them; the price impact stays the local estimate. Without a
     * chain figure, [local] as it is.
     */
    fun withChain(local: SwapQuote?, chainOut: BigInteger?, chainFee: BigInteger?): SwapQuote? {
        if (chainOut == null || chainFee == null || chainOut.signum() <= 0) return local
        return SwapQuote(amountOut = chainOut, feeErth = chainFee, priceImpact = local?.priceImpact ?: 0.0)
    }

    /**
     * A deposit's other leg for [amount] of one side, against reserves
     * [from] (that side's) and [to]: ceil(amount x to / from). x/dex prices a
     * deposit at shares = min(floor(in_e x S / R_e), floor(in_t x S / R_t))
     * and pulls each leg rounded up, ceil(shares x R / S) (audit 4, C2); a
     * leg rounded up here never makes the other side the binding one, so the
     * shares are all the typed side buys and the pull never exceeds either
     * leg (at most one unit comes back as a refund). 0 for an empty pool.
     */
    fun depositLeg(amount: BigInteger, from: BigInteger, to: BigInteger): BigInteger {
        if (amount.signum() <= 0 || from.signum() <= 0 || to.signum() < 0) return BigInteger.ZERO
        val (q, r) = (amount * to).divideAndRemainder(from)
        return if (r.signum() == 0) q else q + BigInteger.ONE
    }

    /**
     * What x/dex mints and pulls for a deposit of [erthIn] and [tokenIn]
     * into reserves [re], [rt] with [supply] shares out: (shares, erth
     * pulled, token pulled), each leg ceil(shares x R / S). Null when it
     * mints nothing (ErrZeroShares) or the pool cannot price it.
     */
    fun deposit(erthIn: BigInteger, tokenIn: BigInteger, re: BigInteger, rt: BigInteger, supply: BigInteger): Triple<BigInteger, BigInteger, BigInteger>? {
        if (supply.signum() <= 0 || re.signum() <= 0 || rt.signum() <= 0) return null
        val shares = minOf(erthIn * supply / re, tokenIn * supply / rt)
        if (shares.signum() <= 0) return null
        fun up(r: BigInteger): BigInteger { val (q, m) = (shares * r).divideAndRemainder(supply); return if (m.signum() == 0) q else q + BigInteger.ONE }
        val e = up(re); val t = up(rt)
        if (e > erthIn || t > tokenIn || e.signum() <= 0 || t.signum() <= 0) return null
        return Triple(shares, e, t)
    }

    /** x/dex ErrPoolCap: a reserve, share supply or input past 2^120 (code 1120, codespace dex). */
    const val ERR_POOL_CAP = 1120
    val POOL_CAP: BigInteger = BigInteger.ONE.shiftLeft(120)

    /**
     * The floor a swap accepts at a tolerance of [bps] basis points.
     * Truncating, so rounding only ever moves the floor down.
     */
    fun withSlippage(amountOut: BigInteger, bps: Int): BigInteger =
        amountOut * BigInteger.valueOf((10_000 - bps).toLong()) / BigInteger.valueOf(10_000)

    /**
     * The fewest LP shares a deposit of [erthIn] + [tokenIn] accepts
     * (MsgAddLiquidity / MsgAddLiquidityShielded min_shares): what x/dex's
     * deposit() mints over reserves [re], [rt] and share supply [supply],
     * min(e*S/Re, t*S/Rt) floored, less [bps]. "" (no bound) for an empty
     * pool, which seeds at sqrt(erth x token) instead.
     */
    fun minShares(erthIn: BigInteger, tokenIn: BigInteger, re: BigInteger, rt: BigInteger, supply: BigInteger, bps: Int): String {
        if (supply.signum() == 0 || re.signum() == 0 || rt.signum() == 0) return ""
        return withSlippage(minOf(erthIn * supply / re, tokenIn * supply / rt), bps).toString()
    }

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
