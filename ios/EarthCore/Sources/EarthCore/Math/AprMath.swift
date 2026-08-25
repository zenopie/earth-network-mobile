import BigInt
import Foundation

/// What a pool pays its liquidity providers, as an annual rate.
///
/// Two sources, and they behave differently enough that the screen shows them
/// apart:
///
///  - **Fees.** A swap's ERTH fee is split in half — one half burned, one half
///    left in the pool — so providers earn 0.15% of ERTH volume at the chain's
///    0.3% fee. This scales with the pool: more liquidity means more volume can
///    pass at the same price impact.
///
///  - **Emissions.** The capital allocation stream emits 1 ERTH/sec, and
///    whatever share of it voters give the LP-rewards option is split across
///    pools *by volume*, not by size. This does not scale with the pool: a
///    deposit does not change the pool's share of the stream, it only splits
///    the same emission across more capital. So this half of the rate falls the
///    moment you add to it, which is the opposite of what an APR usually
///    implies.
///
/// Every figure here is an estimate from a snapshot, and the volume-weighted
/// half moves whenever any pool trades. Mirrors `ui/compose/PoolApr.kt`.
public struct PoolApr: Equatable {
    /// From swap fees, as a fraction — 0.05 is 5%.
    public let fee: Double
    /// From the LP-rewards allocation option.
    public let emission: Double
    /// This pool's share of the LP reward stream, as a fraction.
    public let volumeShare: Double

    public var total: Double { fee + emission }
}

public enum AprMath {

    /// One stream's emission, in uerth per second. Mirrors
    /// `types.EmissionPerSecond`.
    static let emissionUerthPerSecond = BigInt(1_000_000)
    static let secondsPerDay: Int64 = 86_400
    static let daysPerYear: Int64 = 365

    /// The pool's 14-day-weighted volume, in real uerth.
    ///
    /// A read, not a calculation. This used to reproduce the chain's decay
    /// client-side, and the chain stopped decaying: it scales new volume by a
    /// chain-wide index instead, so the stored figure carried a multiplier
    /// growing 7.7% a day forever. Nothing here divided it out and the fee APR
    /// inflated with it — right by accident on day one, out by 18x in a month.
    ///
    /// The chain de-scales it before returning it now, so there is nothing left
    /// to mirror. Reimplementing chain arithmetic in a client is what caused
    /// the drift; having none to reimplement is the fix.
    public static func volumeErth(_ pool: Dex.Pool) -> BigInt {
        BigInt(pool.volumeErth) ?? 0
    }

    /// The rate for one pool, given every pool (for the volume denominator) and
    /// the LP option's share of the capital stream.
    ///
    /// `lpOptionShare` is the option's weight over the stream's total weight —
    /// 1.0 when voters have given it everything. Returns nil when the pool has
    /// no liquidity, since a rate on nothing is not a number anyone can act on.
    public static func apr(
        for pool: Dex.Pool,
        allPools: [Dex.Pool],
        lpOptionShare: Double,
        swapFeePercent: Decimal,
    ) -> PoolApr? {
        guard let reserveErth = BigInt(pool.erthReserve), reserveErth > 0 else { return nil }

        // Both sides of a constant-product pool are worth the same, so the
        // whole pool is twice the hub side. Pricing the spoke side through the
        // pool's own ratio would just restate the same number.
        let tvl = Double(reserveErth) * 2

        let mine = volumeErth(pool)
        let total = allPools.reduce(BigInt(0)) { $0 + volumeErth($1) }

        // --- fees ---
        //
        // At a steady trading rate the decay settles the stored volume at
        // roughly window * daily, so daily volume is the stored figure over the
        // window. Right after a burst it overstates, and after a quiet spell it
        // understates; there is no per-day history on chain to do better.
        let dailyVolume = Double(mine) / Double(Dex.volumeWindowDays)

        // Half the fee is burned, half stays with the providers.
        let lpFeeFraction = (swapFeePercent as NSDecimalNumber).doubleValue / 100 / 2

        let feeApr = dailyVolume * lpFeeFraction * Double(daysPerYear) / tvl

        // --- emissions ---
        let share = total == 0 ? 0 : Double(mine) / Double(total)

        let yearlyEmission = Double(emissionUerthPerSecond)
            * Double(secondsPerDay * daysPerYear)
            * lpOptionShare
            * share

        return PoolApr(fee: feeApr, emission: yearlyEmission / tvl, volumeShare: share)
    }
}

/// What staking pays, as an annual rate.
///
/// The investor pillar mints 1 ERTH/sec into the fee collector and
/// x/distribution splits it by voting power. Three things this chain does make
/// the sum unusually simple:
///
///  - The community tax is zero, so nothing is skimmed before the split.
///  - Gas fees are burned in x/earth rather than left in the fee collector, so
///    they neither add to rewards nor dilute them. Emission is the whole of it.
///  - The rate is fixed per second rather than a target inflation percentage,
///    so the *numerator* is a constant and every change in the rate comes from
///    the denominator.
///
/// That last point is the one worth understanding: the rate is not a policy the
/// chain is trying to hit, it is arithmetic on how much stake is competing for
/// a fixed stream. It falls as the network stakes more, and on a young chain
/// with very little bonded it is enormous and means very little.
public enum StakingApr {

    private static let emissionUerthPerSecond: Double = 1_000_000
    private static let secondsPerYear: Double = 86_400 * 365

    /// The rate before any validator's commission, as a fraction.
    ///
    /// Nil when nothing is bonded — the rate is then unbounded rather than
    /// infinite-and-therefore-zero, and no honest number can be shown.
    public static func base(bondedUerth: Int64) -> Double? {
        guard bondedUerth > 0 else { return nil }
        return emissionUerthPerSecond * secondsPerYear / Double(bondedUerth)
    }

    /// What a delegator to this validator actually earns, after their cut.
    public static func forValidator(bondedUerth: Int64, commission: Double) -> Double? {
        base(bondedUerth: bondedUerth).map { $0 * (1 - min(max(commission, 0), 1)) }
    }
}
