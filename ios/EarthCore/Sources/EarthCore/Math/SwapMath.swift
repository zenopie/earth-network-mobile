import BigInt
import Foundation

/// What a swap returns, priced the way the chain prices it.
///
/// Mirrors `x/dex/keeper/amm.go` rather than approximating it — and mirrors
/// `chain/math/SwapQuote.kt`, which does the same on Android. The two
/// differences that matter, and that a generic constant-product quote gets
/// wrong:
///
///  - The fee is always taken in ERTH, whichever direction the swap goes.
///    Buying the token, it comes off the input before the curve; selling it,
///    off the ERTH output after. A quote that applies the fee to the input in
///    both directions is wrong in one of them.
///  - Every division truncates, because the chain works in integers. Rounding
///    would quote a fraction of a unit more than the chain will actually pay,
///    which is exactly the direction that makes a minimum-out check fail.
///
/// All amounts are base units (uerth / uanml).
public struct SwapQuote: Equatable {
    public let amountOut: BigInt
    public let feeErth: BigInt
    /// How far the trade moves the price against you, as a fraction.
    public let priceImpact: Double
}

public enum SwapMath {

    /// ERTH -> token. The fee comes off the input.
    public static func hubForToken(
        reserveErth: BigInt,
        reserveToken: BigInt,
        amountIn: BigInt,
        feePercent: Decimal
    ) -> SwapQuote? {
        guard amountIn > 0, reserveErth > 0 else { return nil }
        let fee = feeOf(amountIn, feePercent)
        let effectiveIn = amountIn - fee
        let out = reserveToken * effectiveIn / (reserveErth + effectiveIn)
        return SwapQuote(
            amountOut: out,
            feeErth: fee,
            priceImpact: impact(reserveIn: reserveErth, reserveOut: reserveToken,
                                amountIn: effectiveIn, amountOut: out)
        )
    }

    /// Token -> ERTH. The fee comes off the output.
    public static func tokenForHub(
        reserveErth: BigInt,
        reserveToken: BigInt,
        amountIn: BigInt,
        feePercent: Decimal
    ) -> SwapQuote? {
        guard amountIn > 0, reserveToken > 0 else { return nil }
        let gross = reserveErth * amountIn / (reserveToken + amountIn)
        let fee = feeOf(gross, feePercent)
        return SwapQuote(
            amountOut: gross - fee,
            feeErth: fee,
            priceImpact: impact(reserveIn: reserveToken, reserveOut: reserveErth,
                                amountIn: amountIn, amountOut: gross)
        )
    }

    /// One pool's reserves, ERTH and its token.
    public struct Reserves: Equatable, Sendable {
        public let erth: BigInt
        public let token: BigInt
        public init(erth: BigInt, token: BigInt) { self.erth = erth; self.token = token }
    }

    /// What swapping `amountIn` of `denomIn` for `denomOut` pays, routed the
    /// way x/dex swapExactIn routes it: one hop when either side is the hub
    /// (`hub`, ERTH), else token -> ERTH -> token through each token's pool.
    /// `pools` maps a token denom to its pool's reserves. Nil when a pool is
    /// missing or the output rounds to nothing (the chain refuses both).
    ///
    /// The node's pool query leaves out LP rewards not yet compounded into the
    /// ERTH reserve (settlePoolRewards runs at swap time), so the chain's own
    /// figure can differ by that much: callers prefer the chain's
    /// SimulateSwapExactIn (`withChain`) and use this when it is unavailable.
    public static func route(
        pools: [String: Reserves],
        hub: String,
        denomIn: String,
        amountIn: BigInt,
        denomOut: String,
        feePercent: Decimal
    ) -> SwapQuote? {
        guard denomIn != denomOut else { return nil }
        let q: SwapQuote?
        if denomIn == hub {
            q = pools[denomOut].flatMap { hubForToken(reserveErth: $0.erth, reserveToken: $0.token, amountIn: amountIn, feePercent: feePercent) }
        } else if denomOut == hub {
            q = pools[denomIn].flatMap { tokenForHub(reserveErth: $0.erth, reserveToken: $0.token, amountIn: amountIn, feePercent: feePercent) }
        } else {
            guard let a = pools[denomIn], let b = pools[denomOut],
                  let first = tokenForHub(reserveErth: a.erth, reserveToken: a.token, amountIn: amountIn, feePercent: feePercent),
                  first.amountOut > 0,
                  let second = hubForToken(reserveErth: b.erth, reserveToken: b.token, amountIn: first.amountOut, feePercent: feePercent)
            else { return nil }
            q = SwapQuote(amountOut: second.amountOut, feeErth: first.feeErth + second.feeErth,
                          priceImpact: 1 - (1 - first.priceImpact) * (1 - second.priceImpact))
        }
        guard let q, q.amountOut > 0 else { return nil }
        return q
    }

    /// `local` with the chain's own figures for amountOut and feeErth (x/dex
    /// SimulateSwapExactIn, which settles pending LP rewards first) when the
    /// node gave them; the price impact stays the local estimate. Without a
    /// chain figure, `local` as it is.
    public static func withChain(_ local: SwapQuote?, chainOut: BigInt?, chainFee: BigInt?) -> SwapQuote? {
        guard let chainOut, let chainFee, chainOut > 0 else { return local }
        return SwapQuote(amountOut: chainOut, feeErth: chainFee, priceImpact: local?.priceImpact ?? 0)
    }

    /// A deposit's other leg for `amount` of one side, against reserves
    /// `from` (that side's) and `to`: ceil(amount x to / from). x/dex prices a
    /// deposit at shares = min(floor(in_e x S / R_e), floor(in_t x S / R_t))
    /// and pulls each leg rounded up, ceil(shares x R / S); a
    /// leg rounded up here never makes the other side the binding one, so the
    /// shares are all the typed side buys and the pull never exceeds either
    /// leg (at most one unit comes back as a refund). 0 for an empty pool.
    public static func depositLeg(_ amount: BigInt, from: BigInt, to: BigInt) -> BigInt {
        guard amount > 0, from > 0, to >= 0 else { return 0 }
        let (q, r) = (amount * to).quotientAndRemainder(dividingBy: from)
        return r == 0 ? q : q + 1
    }

    /// What x/dex mints and pulls for a deposit of `erthIn` and `tokenIn`
    /// into reserves `re`, `rt` with `supply` shares out: (shares, erth
    /// pulled, token pulled), each leg ceil(shares x R / S). Nil when it
    /// mints nothing (ErrZeroShares) or the pool cannot price it.
    public static func deposit(erthIn: BigInt, tokenIn: BigInt, re: BigInt, rt: BigInt, supply: BigInt) -> (shares: BigInt, erth: BigInt, token: BigInt)? {
        guard supply > 0, re > 0, rt > 0 else { return nil }
        let shares = min(erthIn * supply / re, tokenIn * supply / rt)
        guard shares > 0 else { return nil }
        func up(_ r: BigInt) -> BigInt { let (q, m) = (shares * r).quotientAndRemainder(dividingBy: supply); return m == 0 ? q : q + 1 }
        let e = up(re), t = up(rt)
        guard e <= erthIn, t <= tokenIn, e > 0, t > 0 else { return nil }
        return (shares, e, t)
    }

    /// The floor a swap accepts at a tolerance of `bps` basis points.
    /// Truncating, so rounding only ever moves the floor down.
    public static func withSlippage(_ amountOut: BigInt, bps: Int) -> BigInt {
        amountOut * BigInt(10_000 - bps) / 10_000
    }

    /// The fewest LP shares a deposit of `erthIn` + `tokenIn` accepts
    /// (MsgAddLiquidity / MsgAddLiquidityShielded min_shares): what x/dex's
    /// deposit() mints over reserves `re`, `rt` and share supply `supply`,
    /// min(e*S/Re, t*S/Rt) floored, less `bps`. "" (no bound) for an empty
    /// pool, which seeds at sqrt(erth x token) instead.
    public static func minShares(erthIn: BigInt, tokenIn: BigInt, re: BigInt, rt: BigInt, supply: BigInt, bps: Int) -> String {
        guard supply > 0, re > 0, rt > 0 else { return "" }
        return withSlippage(min(erthIn * supply / re, tokenIn * supply / rt), bps: bps).description
    }

    /// The chain's `feeOf` (chain 203d3b2: rounded up, so a
    /// small swap cannot pay nothing):
    /// LegacyDec(amount).Mul(fee).Quo(100).Ceil().TruncateInt(). Mul is exact
    /// for an 18-place fee; Quo rounds its 18th place half-even
    /// (chopPrecisionAndRound) before the ceiling, which only matters for a
    /// fee whose quotient lands within 1e-18 of an integer. As Android's.
    ///
    /// Done in integers via the percent's own scale rather than in `Decimal`,
    /// because `Decimal` carries 38 digits and a large reserve times a fee
    /// would round somewhere the chain does not.
    static func feeOf(_ amount: BigInt, _ feePercent: Decimal) -> BigInt {
        let (numerator, scale) = ratio(of: feePercent)
        guard numerator > 0, amount > 0 else { return 0 }
        // The quotient in units of 1e-18, rounded half-even, then the ceiling of it.
        let unit = BigInt(10).power(18)
        let den = BigInt(100) * scale
        let (q, r) = (amount * numerator * unit).quotientAndRemainder(dividingBy: den)
        let twice = r * 2
        let atto = twice > den || (twice == den && q % 2 != 0) ? q + 1 : q
        let (whole, frac) = atto.quotientAndRemainder(dividingBy: unit)
        return frac > 0 ? whole + 1 : whole
    }

    /// A decimal percent as an exact integer fraction, so no precision is lost
    /// before the truncation the chain performs.
    private static func ratio(of value: Decimal) -> (numerator: BigInt, scale: BigInt) {
        var v = value
        var scale = BigInt(1)
        // Decimal's exponent is negative for fractional values; shifting the
        // point until it is whole is exact for anything the chain publishes.
        while v != v.rounded(0) {
            v *= 10
            scale *= 10
        }
        let digits = NSDecimalNumber(decimal: v.rounded(0)).stringValue
        return (BigInt(digits) ?? 0, scale)
    }

    /// How much worse the trade's average price is than the pool's marginal one.
    ///
    /// Worth showing because these pools are small: a trade that would be
    /// invisible on a deep market can move this one several percent, and the
    /// quote alone does not say whether the number is the market's or your own
    /// doing.
    private static func impact(
        reserveIn: BigInt,
        reserveOut: BigInt,
        amountIn: BigInt,
        amountOut: BigInt
    ) -> Double {
        guard amountIn != 0, reserveIn != 0 else { return 0 }
        let spot = Double(reserveOut) / Double(reserveIn)
        let effective = Double(amountOut) / Double(amountIn)
        guard spot != 0 else { return 0 }
        return min(max((spot - effective) / spot, 0), 1)
    }
}

private extension Decimal {
    func rounded(_ scale: Int) -> Decimal {
        var input = self
        var result = Decimal()
        NSDecimalRound(&result, &input, scale, .down)
        return result
    }
}
