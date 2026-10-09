import Foundation

/// What the Stake screen says about private stake. Ports `privacy/StakeRound.kt`.
///
/// A delegation bonds with its validator in the block it lands in (v1.2.0),
/// so stake earns from then on: there is no wait for the round to end.
public enum StakeRound {
    /// One validator's private stake: what the Stake screen's card and total show.
    public struct Line: Equatable, Sendable {
        public let validator: String
        /// derth in spendable stake notes.
        public let derth: UInt64
        /// At the live rate, in uerth (floor(derth x rate), as the chain converts it).
        public let value: UInt64
    }

    /// A line per validator where the wallet holds stake, in validator order.
    /// `rate` is rate_v (1 until read).
    public static func lines(notes: [OwnedStakeNote], rate: (String) -> Decimal) -> [Line] {
        var held: [String: UInt64] = [:]
        for n in notes where n.spendable && n.denom.hasPrefix(PrivacyWallet.derthPrefix) {
            let v = String(n.denom.dropFirst(PrivacyWallet.derthPrefix.count))
            held[v] = sat(held[v] ?? 0, n.amount)
        }
        return held.keys.sorted().map { v in
            Line(validator: v, derth: held[v]!, value: PrivacyWallet.derthValue(held[v]!, rate: rate(v)))
        }
    }

    /// "20h 15m", "45m", "under a minute"; "now" once due.
    public static func countdown(_ seconds: Int64) -> String {
        if seconds <= 0 { return "now" }
        if seconds < 60 { return "under a minute" }
        let m = (seconds + 59) / 60
        if m < 60 { return "\(m)m" }
        let h = m / 60, rest = m % 60
        if h < 48 { return rest == 0 ? "\(h)h" : "\(h)h \(rest)m" }
        return "\(h / 24) days"
    }

    /// A validator's standing, as the picker shows it.
    public enum Standing: Equatable, Sendable {
        /// In the active set and taking stake: earns.
        case active
        /// In the active set but not taking stake now (a book settling).
        case closed(String)
        /// Outside the active set: it earns nothing while it is.
        case inactive
        case jailed
        case tombstoned
        /// x/staking removed it; its book winds down.
        case removed

        public init(_ v: PrivacyReads.ValidatorQuote) {
            if v.removed { self = .removed }
            else if v.tombstoned { self = .tombstoned }
            else if v.jailed { self = .jailed }
            else if !v.bonded { self = .inactive }
            else if !v.delegatable { self = .closed(v.refusal) }
            else { self = .active }
        }

        public var label: String {
            switch self {
            case .active: return "Active"
            case .closed: return "Not taking stake"
            case .inactive: return "Not in active set"
            case .jailed: return "Jailed"
            case .tombstoned: return "Tombstoned"
            case .removed: return "Removed"
            }
        }

        /// Why it cannot be picked; nil when it can.
        public var reason: String? {
            switch self {
            case .active: return nil
            case .closed: return "Not taking new stake right now."
            case .inactive: return "Outside the active set, so it earns nothing."
            case .jailed: return "Jailed for missing blocks: earns nothing until it returns."
            case .tombstoned: return "Permanently removed for double-signing."
            case .removed: return "No longer a validator."
            }
        }

        /// Picker order: active first, the rest after, the chain's order within each.
        public var rank: Int {
            switch self {
            case .active: return 0
            case .closed: return 1
            case .inactive: return 2
            case .jailed: return 3
            case .tombstoned: return 4
            case .removed: return 5
            }
        }
    }

    private static func sat(_ a: UInt64, _ b: UInt64) -> UInt64 { let (r, o) = a.addingReportingOverflow(b); return o ? .max : r }
}
