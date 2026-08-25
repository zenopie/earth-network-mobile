package network.erth.wallet.ui.compose

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import network.erth.wallet.chain.Dex

/**
 * What a pool pays its liquidity providers, as an annual rate.
 *
 * Two sources, and they behave differently enough that the screen shows them
 * apart:
 *
 *  - **Fees.** A swap's ERTH fee is split in half — one half burned, one half
 *    left in the pool — so providers earn 0.15% of ERTH volume at the chain's
 *    0.3% fee. This scales with the pool: more liquidity means more volume can
 *    pass at the same price impact.
 *
 *  - **Emissions.** The capital allocation stream emits 1 ERTH/sec, and
 *    whatever share of it voters give the LP-rewards option is split across
 *    pools *by volume*, not by size. This does not scale with the pool: a
 *    deposit does not change the pool's share of the stream, it only splits the
 *    same emission across more capital. So this half of the rate falls the
 *    moment you add to it, which is the opposite of what an APR usually
 *    implies.
 *
 * Every figure here is an estimate from a snapshot, and the volume-weighted
 * half moves whenever any pool trades.
 */
data class PoolApr(
    /** From swap fees, as a fraction — 0.05 is 5%. */
    val fee: Double,
    /** From the LP-rewards allocation option. */
    val emission: Double,
    /** This pool's share of the LP reward stream, as a fraction. */
    val volumeShare: Double,
) {
    val total: Double get() = fee + emission
}

object AprMath {

    /** One stream's emission, in uerth per second. Mirrors types.EmissionPerSecond. */
    private const val EMISSION_UERTH_PER_SEC = 1_000_000L

    private const val SECONDS_PER_DAY = 86_400L
    private const val DAYS_PER_YEAR = 365L

    /**
     * The pool's 14-day-weighted volume, in real uerth.
     *
     * A read, not a calculation. This used to reproduce the chain's decay
     * client-side, and the chain stopped decaying: it scales new volume by a
     * chain-wide index instead, so the stored figure carried a multiplier that
     * grew 7.7% a day forever. Nothing here divided it out, and the fee APR
     * inflated with it — right by accident on day one, out by 18x within a
     * month.
     *
     * The chain now de-scales it before returning it, so there is nothing left
     * to mirror. Reimplementing chain arithmetic in a client is what caused the
     * drift; not having any to reimplement is the fix.
     */
    fun volumeErth(pool: Dex.Pool): BigInteger =
        pool.volumeErth.toBigIntegerOrNull() ?: BigInteger.ZERO

    /**
     * The rate for one pool, given every pool (for the volume denominator) and
     * the LP option's share of the capital stream.
     *
     * [lpOptionShare] is the option's weight over the stream's total weight —
     * 1.0 when voters have given it everything. Returns null when the pool has
     * no liquidity, since a rate on nothing is not a number anyone can act on.
     */
    fun aprFor(
        pool: Dex.Pool,
        allPools: List<Dex.Pool>,
        lpOptionShare: Double,
        swapFeePercent: BigDecimal,
    ): PoolApr? {
        val reserveErth = pool.erthReserve.toBigIntegerOrNull() ?: return null
        if (reserveErth.signum() <= 0) return null

        // Both sides of a constant-product pool are worth the same, so the
        // whole pool is twice the hub side. Pricing the spoke side through the
        // pool's own ratio would just restate the same number.
        val tvl = BigDecimal(reserveErth).multiply(BigDecimal(2))

        val mine = volumeErth(pool)
        val total = allPools.fold(BigInteger.ZERO) { acc, p -> acc + volumeErth(p) }

        // --- fees ---
        //
        // At a steady trading rate the weighting settles at roughly window *
        // daily, so daily volume is the reported figure over the window. Right
        // after a burst it overstates, and after a quiet spell it understates;
        // there is no per-day history on chain to do better.
        val dailyVolume = BigDecimal(mine)
            .divide(BigDecimal(Dex.VOLUME_WINDOW_DAYS), 18, RoundingMode.HALF_UP)

        // Half the fee is burned, half stays with the providers.
        val lpFeeFraction = swapFeePercent
            .divide(BigDecimal(100))
            .divide(BigDecimal(2), 18, RoundingMode.HALF_UP)

        val feeApr = dailyVolume
            .multiply(lpFeeFraction)
            .multiply(BigDecimal(DAYS_PER_YEAR))
            .divide(tvl, 18, RoundingMode.HALF_UP)
            .toDouble()

        // --- emissions ---
        val share = if (total.signum() == 0) {
            0.0
        } else {
            BigDecimal(mine).divide(BigDecimal(total), 18, RoundingMode.HALF_UP).toDouble()
        }

        val yearlyEmission = BigDecimal(EMISSION_UERTH_PER_SEC)
            .multiply(BigDecimal(SECONDS_PER_DAY * DAYS_PER_YEAR))
            .multiply(BigDecimal(lpOptionShare))
            .multiply(BigDecimal(share))

        val emissionApr = yearlyEmission
            .divide(tvl, 18, RoundingMode.HALF_UP)
            .toDouble()

        return PoolApr(fee = feeApr, emission = emissionApr, volumeShare = share)
    }
}

/**
 * What staking pays, as an annual rate.
 *
 * The investor pillar mints 1 ERTH/sec into the fee collector and
 * x/distribution splits it by voting power. Three things this chain does make
 * the sum unusually simple:
 *
 *  - The community tax is zero, so nothing is skimmed before the split.
 *  - Gas fees are burned in x/earth rather than left in the fee collector, so
 *    they neither add to rewards nor dilute them. Emission is the whole of it.
 *  - The rate is fixed per second rather than a target inflation percentage,
 *    so the *numerator* is a constant and every change in the rate comes from
 *    the denominator.
 *
 * That last point is the one worth understanding: the rate is not a policy the
 * chain is trying to hit, it is arithmetic on how much stake is competing for a
 * fixed stream. It falls as the network stakes more, and on a young chain with
 * very little bonded it is enormous and means very little.
 */
object StakingApr {

    private const val EMISSION_UERTH_PER_SEC = 1_000_000L
    private const val SECONDS_PER_YEAR = 86_400L * 365L

    /**
     * The rate before any validator's commission, as a fraction.
     *
     * Null when nothing is bonded — the rate is then unbounded rather than
     * infinite-and-therefore-zero, and no honest number can be shown.
     */
    fun base(bondedUerth: Long): Double? {
        if (bondedUerth <= 0) return null
        val yearly = BigDecimal(EMISSION_UERTH_PER_SEC).multiply(BigDecimal(SECONDS_PER_YEAR))
        return yearly.divide(BigDecimal(bondedUerth), 18, RoundingMode.HALF_UP).toDouble()
    }

    /** What a delegator to this validator actually earns, after their cut. */
    fun forValidator(bondedUerth: Long, commission: Double): Double? =
        base(bondedUerth)?.times(1.0 - commission.coerceIn(0.0, 1.0))
}

private fun String.toBigIntegerOrNull(): BigInteger? =
    runCatching { BigInteger(this) }.getOrNull()
