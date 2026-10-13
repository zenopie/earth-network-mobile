import Foundation

/// Stake vintages (chain v1.3.0, ORCHARD_DESIGN 8.9): a stake note's asset
/// names its validator and the UTC month (YYYYMM) its stake was last checked
/// in (delegated, credited by a move, or moved by MsgCheckIn):
///
///     derth/<valoper>/<YYYYMM>
///
/// except the base vintage (the v1.3.0 upgrade month on earth-1), whose denom
/// is the unsuffixed derth/<valoper> every note made before v1.3.0 carries.
/// One denom per (validator, vintage). A vintage is live in its own month and
/// the 12 after it and retires at 00:00 UTC on the 1st of its month + 13: from
/// then on it earns nothing and can only be withdrawn. Mirrors
/// x/shieldedstaking/types/vintage.go; VintageTests pins it to the chain's
/// output.
public enum Vintage {
    /// How many vintages are live at once: the current month and the 12 before it.
    public static let liveMonths: Int64 = 13
    /// The check-in reminder opens this long before a vintage holding stake retires.
    public static let checkInLeadSeconds: Int64 = 30 * 86_400

    /// Whether `m` is a YYYYMM month the chain takes (year 2000-9999).
    public static func valid(_ m: UInt32) -> Bool {
        let y = m / 100, mo = m % 100
        return (2000 ... 9999).contains(y) && (1 ... 12).contains(mo)
    }

    /// `m` as months since year 0 (comparable, subtractable).
    public static func index(_ m: UInt32) -> Int64 { Int64(m / 100) * 12 + Int64(m % 100) - 1 }

    public static func fromIndex(_ i: Int64) -> UInt32 {
        guard i >= 0, i / 12 <= 9999 else { return 0 }
        return UInt32(i / 12) * 100 + UInt32(i % 12) + 1
    }

    public static func addMonths(_ m: UInt32, _ n: Int64) -> UInt32 { fromIndex(index(m) + n) }

    /// The UTC month of unix time `t` (seconds).
    public static func monthOf(_ t: Int64) -> UInt32 {
        // Days from the epoch to the civil date (Hinnant), no calendar API.
        let z = Int64(floor(Double(t) / 86_400)) + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let month = mp < 10 ? mp + 3 : mp - 9
        let year = yoe + era * 400 + (month <= 2 ? 1 : 0)
        guard year >= 0, year <= 9999 else { return 0 }
        return UInt32(year) * 100 + UInt32(month)
    }

    /// 00:00 UTC on the 1st of `m` (unix seconds).
    public static func start(_ m: UInt32) -> Int64 {
        let y = Int64(m / 100), mo = Int64(m % 100)
        let yy = mo <= 2 ? y - 1 : y
        let era = yy / 400, yoe = yy - era * 400
        let doy = (153 * (mo > 2 ? mo - 3 : mo + 9) + 2) / 5
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146_097 + doe - 719_468) * 86_400
    }

    /// When `m` retires: 00:00 UTC on the 1st of m + 13.
    public static func retiresAt(_ m: UInt32) -> Int64 { start(addMonths(m, liveMonths)) }

    /// Whether `m` is live in month `cur`: cur - 12 <= m <= cur.
    public static func live(_ m: UInt32, current cur: UInt32) -> Bool {
        let i = index(m), c = index(cur)
        return i <= c && i > c - liveMonths
    }

    /// Whether `m` has retired by month `cur`.
    public static func retired(_ m: UInt32, current cur: UInt32) -> Bool { index(m) <= index(cur) - liveMonths }

    /// validator's stake denom for vintage `m`: unsuffixed for the base vintage.
    public static func denom(_ valoper: String, _ m: UInt32, base: UInt32) -> String {
        m == base ? PrivacyWallet.derthPrefix + valoper : PrivacyWallet.derthPrefix + valoper + "/" + String(format: "%06u", m)
    }

    /// A derth denom's validator and vintage (0: the unsuffixed form, the
    /// base vintage's); nil for anything else (types.ParseDerthDenom).
    public static func parse(_ denom: String) -> (valoper: String, month: UInt32)? {
        guard denom.hasPrefix(PrivacyWallet.derthPrefix) else { return nil }
        let rest = denom.dropFirst(PrivacyWallet.derthPrefix.count)
        guard !rest.isEmpty else { return nil }
        guard let slash = rest.firstIndex(of: "/") else { return (String(rest), 0) }
        let v = rest[..<slash], suffix = rest[rest.index(after: slash)...]
        guard !v.isEmpty, suffix.count == 6, suffix.allSatisfy({ $0.isASCII && $0.isNumber }),
              let n = UInt32(suffix), valid(n) else { return nil }
        return (String(v), n)
    }

    /// "Oct 2026" for a vintage, as a sentence names it.
    public static func monthText(_ m: UInt32) -> String {
        let names = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        guard valid(m) else { return String(m) }
        return "\(names[Int(m % 100) - 1]) \(m / 100)"
    }
}

/// What resolves a stake msg's vintages to assets (types.VintageCtx): the
/// base vintage (the unsuffixed denom's month) and the current month (the
/// vintage a delegation, a move's credit or a check-in lands in), read from
/// Query/Vintages. Nil wherever the chain predates v1.3.0: every msg then
/// names vintage 0 and every denom is unsuffixed, byte for byte as before.
public struct VintageCtx: Sendable, Equatable {
    public let base: UInt32
    public let current: UInt32

    public init(base: UInt32, current: UInt32) { self.base = base; self.current = current }

    /// A msg's vintage field: 0 names the base vintage.
    public func resolve(_ m: UInt32) -> UInt32 { m == 0 ? base : m }

    public func denom(_ valoper: String, _ m: UInt32) -> String { Vintage.denom(valoper, resolve(m), base: base) }

    /// The month a held denom is in (its suffix, or the base for the unsuffixed form); nil when it is no derth denom.
    public func month(of denom: String) -> UInt32? { Vintage.parse(denom).map { resolve($0.month) } }

    public func live(_ m: UInt32) -> Bool { Vintage.live(resolve(m), current: current) }
    public func retired(_ m: UInt32) -> Bool { Vintage.retired(resolve(m), current: current) }
}

/// A held vintage's state in the wallet's own words.
public enum VintageStanding: Sendable, Equatable {
    /// Earning; checks in by `retiresAt` (`dueSoon`: within `Vintage.checkInLeadSeconds`).
    case live(retiresAt: Int64, dueSoon: Bool)
    /// Retired at `retiredAt`: earns nothing, can only be withdrawn.
    case retired(retiredAt: Int64)
}
