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

    /// A balance someone holds or is about to move, with its fraction.
    ///
    /// `whole` divides in integers, so it renders 2.5 ANML as "2" and anything
    /// below one whole unit as "0" — a held balance shown as nothing at all.
    /// That is fine for a pool reserve sharing a line with three other figures
    /// and wrong for every number a person is deciding against, which is why
    /// this exists separately rather than `whole` being changed.
    ///
    /// ANML is where it shows: it accrues one claim at a time, so a fractional
    /// balance is the normal case rather than the edge.
    static func balance(_ baseUnits: BigInt, _ token: Token) -> String {
        "\(plain(baseUnits, decimals: token.decimals)) \(token.symbol)"
    }

    /// A headline figure: enough precision to be honest, little enough to fit.
    ///
    /// `balance` keeps every decimal, which is right in a list row and too long
    /// at headline size — six places on a staked balance runs off the panel.
    /// `whole` fits but rounds small figures to nothing, which is the bug this
    /// pair exists to avoid.
    ///
    /// So: two decimals once there is a whole unit, and below that, enough
    /// places to reach two significant digits — 0.0034 rather than 0.00. All of
    /// it done on the decimal string rather than a Double, because these are
    /// exact base-unit integers and a large one does not survive the round trip.
    static func display(_ baseUnits: BigInt, decimals: Int = Constants.denomExponent) -> String {
        let text = Amounts.fromBaseUnits(baseUnits, exponent: decimals)
        let parts = text.split(separator: ".", maxSplits: 1)
        let wholePart = String(parts[0])
        let groupedWhole = grouped.string(from: NSDecimalNumber(string: wholePart)) ?? wholePart
        guard parts.count > 1 else { return groupedWhole }

        var fraction = String(parts[1])
        let hasWholeUnit = wholePart != "0" && wholePart != "-0"
        if hasWholeUnit {
            fraction = String(fraction.prefix(2))
        } else {
            // Count past the leading zeros, then take two more digits.
            let leadingZeros = fraction.prefix { $0 == "0" }.count
            fraction = String(fraction.prefix(leadingZeros + 2))
        }
        while fraction.hasSuffix("0") { fraction.removeLast() }
        return fraction.isEmpty ? groupedWhole : "\(groupedWhole).\(fraction)"
    }

    /// The same, when the symbol is already on the line.
    static func balance(_ baseUnits: BigInt, decimals: Int = Constants.denomExponent) -> String {
        plain(baseUnits, decimals: decimals)
    }

    static func balance(_ baseUnits: String, decimals: Int = Constants.denomExponent) -> String {
        BigInt(baseUnits).map { plain($0, decimals: decimals) } ?? baseUnits
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

    /// A plain count with thousands separators.
    static func grouped(_ value: Int64) -> String {
        grouped.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// A rate at whatever magnitude it lands.
    ///
    /// On a young chain with little bonded this runs to millions of percent,
    /// which is arithmetically right and worth showing rather than capping — a
    /// capped number invites the reader to believe the cap.
    static func rate(_ fraction: Double) -> String {
        let percent = fraction * 100
        switch percent {
        case 0: return "0%"
        case ..<0.01: return "<0.01%"
        case ..<1: return String(format: "%.2f%%", percent)
        case ..<1000: return String(format: "%.1f%%", percent)
        default:
            let whole = grouped.string(from: NSNumber(value: percent)) ?? String(Int(percent))
            return "\(whole)%"
        }
    }

    /// "1 validator", "3 validators".
    static func count(_ n: Int, _ singular: String, _ plural: String? = nil) -> String {
        "\(n) \(n == 1 ? singular : plural ?? singular + "s")"
    }
}
