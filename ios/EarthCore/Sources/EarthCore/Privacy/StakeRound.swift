import Foundation

/// What the Stake screen says about private stake and the chain's daily round
/// (x/shieldedstaking's epoch). Ports `privacy/StakeRound.kt`.
///
/// A delegation's ERTH is queued (the book's P) and delegated to its validator
/// when the round ends; the derth it bought is credited at once, at the live
/// rate, and shares in the rate from then on. So "joining" is about the
/// validator, not about earning: the screen says when the stake joins, and that
/// it is earning already.
public enum StakeRound {
    /// The derth at `denom` this wallet's own delegations credited in blocks
    /// after `height` (the block that ended the last round), at most what is
    /// held there now. From the wallet's stake notes alone, spent ones
    /// included: a block where the notes made outweigh the notes spent added
    /// value. A note with a label no spent note carried is a move arriving
    /// (bonded at once, not queued), so that block is not counted; an
    /// undelegation, a merge or a lock adds nothing.
    public static func joining(_ notes: [OwnedStakeNote], denom: String, after height: UInt64) -> UInt64 {
        let ns = notes.filter { $0.denom == denom }
        var credited: UInt64 = 0
        for h in Set(ns.map(\.height).filter { $0 > height }) {
            let made = ns.filter { $0.height == h }
            let spent = ns.filter { $0.spentHeight == h }
            let carried = Set(spent.compactMap { $0.label?.moveKey })
            if made.contains(where: { $0.label.map { !carried.contains($0.moveKey) } ?? false }) { continue }
            let m = sum(made), s = sum(spent)
            if m > s { credited = sat(credited, m - s) }
        }
        return min(credited, sum(ns.filter(\.unspent)))
    }

    /// The first block whose time is at or after `time`: given the round's
    /// start_time, the block that ended the last round (its EndBlocker
    /// delegated everything queued up to and including that block).
    ///
    /// Found by probing block times, interpolating and bisecting in turn.
    /// Every probe follows from the tip and `time` alone, both public and the
    /// same for every wallet at that moment: nothing asked depends on this
    /// wallet's notes. Nil when a probe goes unanswered (a pruned node) or the
    /// probes run out.
    public static func firstHeight(atOrAfter time: Int64, tip: UInt64, tipTime: Int64, maxProbes: Int = 40,
                                   blockTime: (UInt64) async -> Int64?) async -> UInt64? {
        guard tip >= 1, tipTime >= time else { return nil }
        var hi = tip, hiT = tipTime
        var probes = 0
        func probe(_ h: UInt64) async -> Int64? {
            guard probes < maxProbes else { return nil }
            probes += 1
            return await blockTime(h)
        }
        // A block below `time`: back by the gap at 5 s a block, doubling.
        var lo: UInt64 = 0, loT: Int64 = 0
        var step = UInt64(max(1, (hiT - time) / 5 + 1))
        while true {
            if hi <= 1 { return 1 }
            let g = hi > step ? hi - step : 1
            guard let t = await probe(g) else { return nil }
            if t >= time {
                hi = g; hiT = t
                step = step > UInt64.max / 2 ? step : step * 2
            } else {
                lo = g; loT = t
                break
            }
        }
        var bisect = false
        while hi - lo > 1 {
            var g: UInt64
            if bisect || hiT <= loT {
                g = lo + (hi - lo) / 2
            } else {
                let f = Double(time - loT) / Double(hiT - loT)
                g = lo + UInt64((Double(hi - lo) * f).rounded())
            }
            g = min(max(g, lo + 1), hi - 1)
            bisect.toggle()
            guard let t = await probe(g) else { return nil }
            if t >= time { hi = g; hiT = t } else { lo = g; loT = t }
        }
        return hi
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

    private static func sum(_ ns: [OwnedStakeNote]) -> UInt64 { ns.reduce(UInt64(0)) { sat($0, $1.amount) } }
    private static func sat(_ a: UInt64, _ b: UInt64) -> UInt64 { let (r, o) = a.addingReportingOverflow(b); return o ? .max : r }
}
