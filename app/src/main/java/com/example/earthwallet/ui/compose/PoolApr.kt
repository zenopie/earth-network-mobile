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
     * The chain's daily volume decay, applied forward to [today].
     *
     * This has to be recomputed client-side rather than trusting the stored
     * figure: the chain decays lazily, only when a swap or a liquidity change
     * touches a pool. A pool nobody has traded in three days still reports the
     * volume it had three days ago, and summing those raw would inflate the
     * denominator and understate every active pool's share.
     *
     * Integer arithmetic in the same order as the chain's, so a pool's decayed
     * figure here matches what the chain will compute when it next touches it.
     */
    fun decayedVolume(pool: Dex.Pool, today: Long): BigInteger {
        val stored = pool.volume.toBigIntegerOrNull() ?: return BigInteger.ZERO
        if (pool.lastVolumeDay == 0L || today <= pool.lastVolumeDay) return stored

        val elapsed = today - pool.lastVolumeDay
        if (elapsed >= Dex.VOLUME_WINDOW_DAYS) return BigInteger.ZERO

        var v = stored
        val w = BigInteger.valueOf(Dex.VOLUME_WINDOW_DAYS.toLong())
        val wLess = BigInteger.valueOf(Dex.VOLUME_WINDOW_DAYS - 1L)
        repeat(elapsed.toInt()) { v = v * wLess / w }
        return v
    }

    /** Today's day index, the same way the chain computes it. */
    fun today(): Long = System.currentTimeMillis() / 1000 / SECONDS_PER_DAY

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
        today: Long = today(),
    ): PoolApr? {
        val reserveErth = pool.erthReserve.toBigIntegerOrNull() ?: return null
        if (reserveErth.signum() <= 0) return null

        // Both sides of a constant-product pool are worth the same, so the
        // whole pool is twice the hub side. Pricing the spoke side through the
        // pool's own ratio would just restate the same number.
        val tvl = BigDecimal(reserveErth).multiply(BigDecimal(2))

        val mine = decayedVolume(pool, today)
        val total = allPools.fold(BigInteger.ZERO) { acc, p -> acc + decayedVolume(p, today) }

        // --- fees ---
        //
        // At a steady trading rate the decay settles the stored volume at
        // roughly window * daily, so daily volume is the stored figure over the
        // window. Right after a burst it overstates, and after a quiet spell it
        // understates; there is no per-day history on chain to do better.
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

private fun String.toBigIntegerOrNull(): BigInteger? =
    runCatching { BigInteger(this) }.getOrNull()
