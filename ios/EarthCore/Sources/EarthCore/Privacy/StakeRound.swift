import Foundation

/// What the Stake screen says about private stake and the chain's daily round
/// (x/shieldedstaking's epoch). Ports `privacy/StakeRound.kt`.
///
/// A delegation's ERTH is queued (the book's P) and delegated to its validator
/// when the round ends. Rewards accrue only on what is delegated (the book's
/// D; x/distribution pays nothing on P), so queued ERTH earns nothing until it
/// joins: the screen says it is waiting to start earning, and when it joins.
/// The derth it bought is credited at once at the live rate B/S, so once the
/// validator holds bonded private stake that derth does share the rate's
/// growth from that stake's rewards; on a validator whose private stake is all
/// queued (D = 0, W = 0) nothing grows until the round ends.
///
/// Private stake is the wallet's stake notes and its Groundworks positions:
/// a lock moves derth from a note into a position at the same validator, still
/// derth in S at the same rate, so it is still this wallet's stake there.
public enum StakeRound {
    /// A Groundworks position as the Stake screen counts it: derth locked at
    /// `validator`, created (locked) at `height` (0: unknown).
    public struct Locked: Equatable, Sendable {
        public let validator: String
        public let derth: UInt64
        public let height: UInt64
        public init(validator: String, derth: UInt64, height: UInt64) {
            self.validator = validator; self.derth = derth; self.height = height
        }
    }

    /// One validator's private stake: what the Stake screen's card and total show.
    public struct Line: Equatable, Sendable {
        public let validator: String
        /// derth in spendable stake notes, and locked in positions.
        public let notes: UInt64
        public let locked: UInt64
        /// Both, at the live rate, in uerth (floor(derth x rate), as the chain converts it).
        public let value: UInt64
        public let lockedValue: UInt64
        /// The part still waiting to join its validator, in uerth; nil when unknown.
        public let joiningValue: UInt64?
        public var derth: UInt64 { sat(notes, locked) }
    }

    /// A line per validator where the wallet holds stake (notes or positions),
    /// in validator order. `rate` is rate_v (1 until read); `after` is the
    /// block that ended the last round (nil: unknown, so nothing is called joining).
    public static func lines(notes: [OwnedStakeNote], positions: [Locked], rate: (String) -> Decimal, after: UInt64?) -> [Line] {
        var held: [String: UInt64] = [:]
        for n in notes where n.spendable && n.denom.hasPrefix(PrivacyWallet.derthPrefix) {
            let v = String(n.denom.dropFirst(PrivacyWallet.derthPrefix.count))
            held[v] = sat(held[v] ?? 0, n.amount)
        }
        var locked: [String: UInt64] = [:]
        for p in positions where p.derth > 0 { locked[p.validator] = sat(locked[p.validator] ?? 0, p.derth) }
        let validators = Set(held.keys).union(locked.keys).sorted()
        return validators.map { (v: String) -> Line in
            let r = rate(v)
            let n = held[v] ?? 0, l = locked[v] ?? 0
            var waiting: UInt64?
            if let e = after { waiting = Self.joining(notes, positions: positions, denom: PrivacyWallet.derthDenom(v), after: e) }
            return Line(validator: v, notes: n, locked: l, value: PrivacyWallet.derthValue(sat(n, l), rate: r),
                        lockedValue: PrivacyWallet.derthValue(l, rate: r),
                        joiningValue: waiting.map { PrivacyWallet.derthValue($0, rate: r) })
        }
    }

    /// The derth at `denom` this wallet's own delegations credited in blocks
    /// after `height` (the block that ended the last round), at most what is
    /// held there now (notes and positions). From the wallet's stake notes,
    /// spent ones included, and its positions: a block where what was made
    /// (notes, positions locked) outweighs the notes spent added value. A
    /// note with a label no spent note carried is a move arriving (bonded at
    /// once, not queued), so that block is not counted; an undelegation, a
    /// merge or a lock (a note spent into a position in one block) adds nothing.
    public static func joining(_ notes: [OwnedStakeNote], positions: [Locked] = [], denom: String, after height: UInt64) -> UInt64 {
        let ns = notes.filter { $0.denom == denom }
        let ps = positions.filter { PrivacyWallet.derthDenom($0.validator) == denom }
        var credited: UInt64 = 0
        for h in Set(ns.map(\.height) + ps.map(\.height)).filter({ $0 > height }) {
            let made = ns.filter { $0.height == h }
            let spent = ns.filter { $0.spentHeight == h }
            let carried = Set(spent.compactMap { $0.label?.moveKey })
            if made.contains(where: { $0.label.map { !carried.contains($0.moveKey) } ?? false }) { continue }
            let m = sat(sum(made), ps.filter { $0.height == h }.reduce(UInt64(0)) { sat($0, $1.derth) }), s = sum(spent)
            if m > s { credited = sat(credited, m - s) }
        }
        return min(credited, sat(sum(ns.filter(\.unspent)), ps.reduce(UInt64(0)) { sat($0, $1.derth) }))
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
