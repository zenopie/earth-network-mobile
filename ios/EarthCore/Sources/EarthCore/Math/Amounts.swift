import BigInt
import Foundation

/// Conversions between what a user types and what the chain moves.
public enum Amounts {

    /// Keep only what can be part of a decimal number, and only one point.
    ///
    /// A filter rather than validation-after-the-fact: the numeric keyboard
    /// still offers a comma on many locales and a paste can carry anything at
    /// all, so the field has to refuse the character rather than accept it and
    /// complain.
    public static func filterAmountInput(_ input: String, previous: String) -> String {
        guard !input.isEmpty else { return "" }
        var cleaned = ""
        var seenPoint = false
        for c in input {
            if c.isNumber, c.isASCII {
                cleaned.append(c)
            } else if c == "." || c == ",", !seenPoint {
                seenPoint = true
                cleaned.append(".")
            }
        }
        // Six decimals is the denomination's precision; more cannot be sent.
        let fraction = cleaned.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        if fraction.count == 2, fraction[1].count > 6 { return previous }
        return cleaned
    }

    /// Base units from a typed decimal, or nil when it is not a number.
    /// Truncates rather than rounds — the extra digits cannot be sent.
    public static func toBaseUnits(_ text: String, exponent: Int = Constants.denomExponent) -> BigInt? {
        let parts = text.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        guard !parts.isEmpty, parts.count <= 2 else { return nil }

        let wholeText = parts[0].isEmpty ? "0" : String(parts[0])
        guard wholeText.allSatisfy(\.isNumber), let whole = BigInt(wholeText) else { return nil }

        var fractionText = parts.count == 2 ? String(parts[1]) : ""
        guard fractionText.allSatisfy(\.isNumber) else { return nil }
        fractionText = String(fractionText.prefix(exponent))
        fractionText += String(repeating: "0", count: exponent - fractionText.count)

        let fraction = fractionText.isEmpty ? BigInt(0) : (BigInt(fractionText) ?? 0)
        return whole * BigInt(10).power(exponent) + fraction
    }

    /// Base units back to a plain decimal for display, without trailing zeros.
    public static func fromBaseUnits(_ amount: BigInt, exponent: Int = Constants.denomExponent) -> String {
        let scale = BigInt(10).power(exponent)
        let negative = amount < 0
        let magnitude = negative ? -amount : amount
        let whole = magnitude / scale
        var fraction = String(magnitude % scale)
        fraction = String(repeating: "0", count: exponent - fraction.count) + fraction
        while fraction.hasSuffix("0") { fraction.removeLast() }
        let sign = negative ? "-" : ""
        return fraction.isEmpty ? "\(sign)\(whole)" : "\(sign)\(whole).\(fraction)"
    }
}
