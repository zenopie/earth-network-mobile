import BigInt
import Foundation

/// What a swap returns, priced the way the chain prices it.
///
/// Mirrors `x/dex/keeper/amm.go` rather than approximating it — and mirrors
/// `ui/compose/SwapQuote.kt`, which does the same on Android. The two
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

    /// The chain's `feeOf`: a percent of the amount, truncated.
    ///
    /// Done in integers via the percent's own scale rather than in `Decimal`,
    /// because `Decimal` carries 38 digits and a large reserve times a fee
    /// would round somewhere the chain does not.
    static func feeOf(_ amount: BigInt, _ feePercent: Decimal) -> BigInt {
        let (numerator, scale) = ratio(of: feePercent)
        guard numerator > 0 else { return 0 }
        return amount * numerator / (BigInt(100) * scale)
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
