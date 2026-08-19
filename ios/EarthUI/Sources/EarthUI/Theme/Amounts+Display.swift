import BigInt
import EarthCore
import Foundation

/// How figures are written on screen.
///
/// Android shows whole ERTH with thousands separators and lets the fractions
/// live in the detail rows. That is not a rounding shortcut — a pool reserve
/// printed as `840938527.632374` is six digits of noise on the end of a number
/// nobody can read at a glance, and the six that matter are at the front.
enum Figures {

    private static let grouped: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        return formatter
    }()

    private static let short: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 2
        return formatter
    }()

    /// Whole units, grouped. For anything that shares a line with other
    /// figures.
    static func whole(_ baseUnits: BigInt, decimals: Int = Constants.denomExponent) -> String {
        let units = baseUnits / BigInt(10).power(decimals)
        return grouped.string(from: NSDecimalNumber(string: units.description)) ?? units.description
    }

    static func whole(_ baseUnits: String, decimals: Int = Constants.denomExponent) -> String {
        BigInt(baseUnits).map { whole($0, decimals: decimals) } ?? baseUnits
    }

    /// Whole units with the symbol.
    static func amount(_ baseUnits: BigInt, _ token: Token) -> String {
        "\(whole(baseUnits, decimals: token.decimals)) \(token.symbol)"
    }

    static func amount(_ baseUnits: String, _ token: Token) -> String {
        "\(whole(baseUnits, decimals: token.decimals)) \(token.symbol)"
    }

    /// Two decimal places, for a figure small enough that they matter — a
    /// balance being sent, not a pool reserve.
    static func precise(_ baseUnits: BigInt, decimals: Int = Constants.denomExponent) -> String {
        let scale = BigInt(10).power(decimals)
        let value = Double(baseUnits) / Double(scale)
        return short.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// The full amount, grouped, fractions kept. What the balance widget
    /// splits into a large whole and a small fraction.
    static func plain(_ baseUnits: BigInt, decimals: Int = Constants.denomExponent) -> String {
        let text = Amounts.fromBaseUnits(baseUnits, exponent: decimals)
        let parts = text.split(separator: ".", maxSplits: 1)
        let whole = grouped.string(from: NSDecimalNumber(string: String(parts[0])))
            ?? String(parts[0])
        return parts.count > 1 ? "\(whole).\(parts[1])" : whole
    }

    /// A plain decimal, trimmed. For amounts that already carry their scale.
    static func decimal(_ value: Double) -> String {
        short.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// "1 validator", "3 validators".
    static func count(_ n: Int, _ singular: String, _ plural: String? = nil) -> String {
        "\(n) \(n == 1 ? singular : plural ?? singular + "s")"
    }
}
