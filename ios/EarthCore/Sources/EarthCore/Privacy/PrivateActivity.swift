import BigInt
import Foundation

/// What a private activity row says happened. The first group are txs this
/// wallet sent (a `SentPrivateTx`); the rest are what sync alone shows:
/// notes received, and spends a restored wallet has no record of. Ports
/// `privacy/PrivateActivity.kt`.
public enum PrivateActivityKind: String, Codable, Sendable, CaseIterable {
    case register = "REGISTER", switchIdentity = "SWITCH", move = "MOVE", shield = "SHIELD", unshield = "UNSHIELD"
    case send = "SEND", merge = "MERGE", swap = "SWAP", addLiquidity = "ADD_LIQUIDITY", removeLiquidity = "REMOVE_LIQUIDITY"
    case stake = "STAKE", unstake = "UNSTAKE", redelegate = "REDELEGATE", restake = "RESTAKE", vote = "VOTE"
    case caretaker = "CARETAKER", position = "POSITION", claimAnml = "CLAIM_ANML", handle = "HANDLE"
    case gasGrant = "GAS_GRANT", unbondingPayout = "UNBONDING_PAYOUT", lpPayout = "LP_PAYOUT"
    case registrationReward = "REGISTRATION_REWARD", referral = "REFERRAL", fromEarth = "FROM_EARTH"
    case received = "RECEIVED", inferred = "INFERRED"

    public var label: String {
        switch self {
        case .register: "Registered"
        case .switchIdentity: "Switched identity"
        case .move: "Moved to your new identity"
        case .shield: "Shielded"
        case .unshield: "Unshielded"
        case .send: "Sent privately"
        case .merge: "Merged notes"
        case .swap: "Swapped"
        case .addLiquidity: "Added liquidity"
        case .removeLiquidity: "Removed liquidity"
        case .stake: "Staked"
        case .unstake: "Unstaked"
        case .redelegate: "Redelegated"
        case .restake: "Merged stake"
        case .vote: "Voted"
        case .caretaker: "Caretaker vote"
        case .position: "Groundworks position"
        case .claimAnml: "Claimed ANML"
        case .handle: "Handle"
        case .gasGrant: "Gas grant from Earth"
        case .unbondingPayout: "Unbonding payout"
        case .lpPayout: "Liquidity payout"
        case .registrationReward: "Registration reward"
        case .referral: "Referral reward"
        case .fromEarth: "Received from Earth"
        case .received: "Received privately"
        case .inferred: "Private transaction"
        }
    }

    /// A tx this wallet sent (not a note sync found).
    public var sent: Bool {
        switch self {
        case .gasGrant, .unbondingPayout, .lpPayout, .registrationReward, .referral, .fromEarth, .received, .inferred: false
        default: true
        }
    }
}

/// An amount of one denom; `amount` signed in a row (negative: it left the wallet).
public struct ActivityCoin: Codable, Equatable, Hashable, Sendable {
    public let denom: String
    public let amount: Int64

    public init(_ denom: String, _ amount: Int64) { self.denom = denom; self.amount = amount }
}

/// A private tx this wallet sent, recorded in its sealed store when the node
/// accepted it (never looked up by hash for this). `change` and `receives`
/// are the rho (hex) of every note the tx makes for this wallet: its own
/// bundle outputs and the notes the chain mints to it. A synced note with one
/// of them is this tx's, folded into its row: `change` never shows,
/// `receives` shows as what came in. `spent` are the nullifiers (hex) of the
/// notes and stake notes it spends. `height` is set once it is in a block
/// (the broadcast's own wait, or sync seeing its nullifiers or outputs);
/// `failure` once the wallet learns it failed (the broadcast's wait, or the
/// checks sync already makes on a tx it marked notes for).
public struct SentPrivateTx: Codable, Equatable, Sendable {
    public let hash: String
    public var kind: PrivateActivityKind
    public let generation: Int
    public let counterparty: String
    public let fee: UInt64
    public let submittedAt: Int64
    public let outs: [ActivityCoin]
    public let ins: [ActivityCoin]
    public let change: [String]
    public let receives: [String]
    public let spent: [String]
    public var height: UInt64?
    /// The block's time when the wallet learned it from the broadcast's wait.
    public var time: Int64?
    public var failure: String?

    public init(hash: String, kind: PrivateActivityKind, generation: Int, counterparty: String, fee: UInt64, submittedAt: Int64,
                outs: [ActivityCoin], ins: [ActivityCoin], change: [String], receives: [String], spent: [String],
                height: UInt64? = nil, time: Int64? = nil, failure: String? = nil) {
        self.hash = hash; self.kind = kind; self.generation = generation; self.counterparty = counterparty; self.fee = fee
        self.submittedAt = submittedAt; self.outs = outs; self.ins = ins; self.change = change; self.receives = receives
        self.spent = spent; self.height = height; self.time = time; self.failure = failure
    }
}

/// The private activity the sealed store keeps: the txs this wallet sent,
/// the notes it expects the chain to mint to it later by kind (a gas grant,
/// an unbonding payout, a liquidity payout, a shield's note), and pairs of
/// (height, block time) sync read anyway, from which a note's time is
/// estimated. Goes with the store (forgetting the wallet); a reset on the
/// same chain keeps it, a relaunch drops it.
public struct ActivityLog: Codable, Equatable, Sendable {
    public struct Expected: Codable, Equatable, Sendable { public let rho: String; public let kind: PrivateActivityKind }
    public struct Tick: Codable, Equatable, Sendable { public let height: UInt64; public let time: Int64 }

    public var sent: [SentPrivateTx] = []
    /// Oldest first.
    public var expected: [Expected] = []
    /// Ascending by height.
    public var clock: [Tick] = []

    public static let maxSent = 200
    public static let maxExpected = 256
    public static let maxClock = 32

    public init() {}

    /// Records `tx` (a hash seen again replaces its record).
    public mutating func record(_ tx: SentPrivateTx) {
        sent.removeAll { $0.hash.caseInsensitiveCompare(tx.hash) == .orderedSame }
        sent.append(tx)
        // Oldest settled first; one still pending is kept over any settled one.
        while sent.count > Self.maxSent {
            sent.remove(at: sent.firstIndex { $0.height != nil || $0.failure != nil } ?? 0)
        }
    }

    /// The broadcast was refused outright: the tx is in no mempool, and nothing happened.
    public mutating func drop(_ hash: String) { sent.removeAll { $0.hash.caseInsensitiveCompare(hash) == .orderedSame } }

    public mutating func update(_ hash: String, _ f: (inout SentPrivateTx) -> Void) {
        if let i = sent.firstIndex(where: { $0.hash.caseInsensitiveCompare(hash) == .orderedSame }) { f(&sent[i]) }
    }

    /// In a block at `height` (block `time` when known): a failure seen before stays.
    public mutating func confirm(_ hash: String, height: UInt64, time: Int64?) {
        update(hash) { $0.height = height; if let time { $0.time = time } }
    }

    /// Failed, with the wallet's reason; a tx failing in its block is in that block too.
    public mutating func fail(_ hash: String, reason: String, height: UInt64? = nil) {
        update(hash) { t in
            guard t.failure == nil else { return }
            t.failure = reason
            if let height { t.height = height }
        }
    }

    public func expectedKind(_ rho: String) -> PrivateActivityKind? { expected.last { $0.rho == rho }?.kind }

    /// A note the chain will mint to this wallet later, and what it will be.
    public mutating func expect(_ rho: Fr, _ kind: PrivateActivityKind) {
        let h = rho.hex
        expected.removeAll { $0.rho == h }
        expected.append(Expected(rho: h, kind: kind))
        if expected.count > Self.maxExpected { expected.removeFirst(expected.count - Self.maxExpected) }
    }

    /// A (height, block time) pair the sync read: kept ascending, thinned to `maxClock`.
    public mutating func tick(height: UInt64, time: Int64?) {
        guard let time, height > 0, time > 0, !clock.contains(where: { $0.height == height }) else { return }
        clock.append(Tick(height: height, time: time))
        clock.sort { $0.height < $1.height }
        // Keep the first and the newest; drop the inner pair closest together.
        while clock.count > Self.maxClock {
            var best = 1
            for i in 1 ..< clock.count - 1 where clock[i + 1].height - clock[i - 1].height < clock[best + 1].height - clock[best - 1].height { best = i }
            clock.remove(at: best)
        }
    }
}

/// One row of private activity, ready for the activity list.
public struct PrivateActivityRow: Equatable, Sendable, Identifiable {
    public enum Status: Equatable, Sendable { case pending, confirmed, failed }

    public let kind: PrivateActivityKind
    /// Signed: negative left the wallet, positive came in.
    public let coins: [ActivityCoin]
    public let counterparty: String
    /// The fee this wallet paid (sent txs only).
    public let fee: UInt64?
    public let hash: String?
    public let height: UInt64?
    /// Unix seconds; `timeExact` false when estimated from the block height.
    public let time: Int64?
    public let timeExact: Bool
    public let status: Status
    public var failure: String?

    /// A stable id: the tx's hash, else what the row is built from.
    public var id: String {
        hash ?? "\(kind.rawValue):\(height ?? 0):" + coins.map { "\($0.amount)\($0.denom)" }.joined(separator: ",")
    }
}

/// Private activity, built locally from the sealed store: nothing here asks
/// anything of a node or the indexer. Sent txs are what the wallet recorded
/// when it sent them; received notes and a restored wallet's spends are read
/// from the notes sync already found. Ports `PrivateActivity.kt`.
public enum PrivateActivity {
    /// A block every this many seconds, for a height before or past every clock pair (earth-1's target).
    public static let defaultBlockSeconds: Int64 = 6

    public static let failedInBlock = "failed in its block"
    public static let neverLanded = "did not reach a block before it expired"

    /// What a tx moves, from what it spends and makes: pool notes spent less
    /// the outputs back to this wallet, per denom (the fee taken off ERTH),
    /// and likewise for stake notes. A positive net left (`outs`); a negative
    /// one came in (`ins`, as positive amounts).
    public static func coins(poolSpent: [ActivityCoin], poolBack: [ActivityCoin], stakeSpent: [ActivityCoin], stakeBack: [ActivityCoin],
                             fee: UInt64) -> (outs: [ActivityCoin], ins: [ActivityCoin]) {
        var net: [String: BigInt] = [:]
        func add(_ cs: [ActivityCoin], _ sign: Int) {
            for c in cs where c.amount != 0 { net[c.denom, default: 0] += BigInt(c.amount) * BigInt(sign) }
        }
        add(poolSpent, 1); add(poolBack, -1); add(stakeSpent, 1); add(stakeBack, -1)
        if fee > 0 { net[PrivacyWallet.fee, default: 0] -= BigInt(fee) }
        var outs: [ActivityCoin] = []
        var ins: [ActivityCoin] = []
        for d in net.keys.sorted() {
            let v = net[d]!
            if v > 0 { outs.append(ActivityCoin(d, clamp(v))) } else if v < 0 { ins.append(ActivityCoin(d, clamp(-v))) }
        }
        return (outs, ins)
    }

    private static func clamp(_ v: BigInt) -> Int64 { v > BigInt(Int64.max) ? Int64.max : Int64(v) }

    static func coin(_ denom: String, _ v: UInt64) -> ActivityCoin { ActivityCoin(denom, Int64(clamping: v)) }

    public static func ofNotes(_ notes: [OwnedNote]) -> [ActivityCoin] { notes.map { coin($0.note.denom, $0.note.value) } }
    public static func ofStake(_ notes: [OwnedStakeNote]) -> [ActivityCoin] { notes.map { coin($0.denom, $0.amount) } }

    /// Marks every pending sent tx in a block once sync holds its nullifiers
    /// (a spent note of its) or one of its outputs: the lowest such height.
    /// Local: what sync found already, nothing asked by hash.
    public static func settle(_ s: inout PrivacyState) {
        guard s.activity.sent.contains(where: { $0.height == nil && $0.failure == nil }) else { return }
        var spentAt: [String: UInt64] = [:]
        var byTx: [String: UInt64] = [:]
        func spent(_ nf: Fr, _ h: UInt64?, _ tx: String?) {
            guard let h else { return }
            spentAt[nf.hex] = min(spentAt[nf.hex] ?? h, h)
            if let tx { byTx[tx.uppercased()] = min(byTx[tx.uppercased()] ?? h, h) }
        }
        for n in s.notes { spent(n.nf, n.spentHeight, n.pendingTx) }
        for n in s.stakeNotes { spent(n.nf, n.spentHeight, n.pendingTx) }
        var madeAt: [String: UInt64] = [:]
        for n in s.notes { madeAt[n.note.rho.hex] = min(madeAt[n.note.rho.hex] ?? n.height, n.height) }
        for n in s.stakeNotes { madeAt[n.rho.hex] = min(madeAt[n.rho.hex] ?? n.height, n.height) }
        for t in s.activity.sent where t.height == nil && t.failure == nil {
            let hs = t.spent.compactMap { spentAt[$0] } + [byTx[t.hash.uppercased()]].compactMap { $0 } + (t.change + t.receives).compactMap { madeAt[$0] }
            if let h = hs.min() { s.activity.confirm(t.hash, height: h, time: nil) }
        }
    }

    /// A block time for `height` from the clock pairs: between two, by
    /// interpolation; outside them, at their average pace (or
    /// `defaultBlockSeconds` with fewer than two). Nil with none.
    public static func timeAt(_ clock: [ActivityLog.Tick], _ height: UInt64) -> Int64? {
        guard let first = clock.first, let last = clock.last else { return nil }
        if let hit = clock.first(where: { $0.height == height }) { return hit.time }
        var pace = Double(defaultBlockSeconds)
        if clock.count >= 2, last.height > first.height {
            let p = Double(last.time - first.time) / Double(last.height - first.height)
            if p > 0.1, p < 600 { pace = p }
        }
        if height < first.height { return first.time - Int64(Double(first.height - height) * pace) }
        if height > last.height { return last.time + Int64(Double(height - last.height) * pace) }
        let i = clock.firstIndex { $0.height > height }!
        let a = clock[i - 1], b = clock[i]
        return a.time + Int64(Double(b.time - a.time) * Double(height - a.height) / Double(b.height - a.height))
    }

    /// Every private row, newest first (as Android):
    ///  1. each sent tx, with the notes the chain minted for it (found by
    ///     their rho) as what came in; its change never shows;
    ///  2. spends no record names (a restored wallet's history), one
    ///     "Private transaction" per block height, net of the notes made for
    ///     this wallet at that height (change, and what the tx minted); one at
    ///     the height of a registration record of this wallet's is its
    ///     registration;
    ///  3. every other note as a received row, labelled by what the wallet
    ///     expected of it, else by its format (open: the referral note; v2:
    ///     minted by the chain; v1, or a note synced before the format was
    ///     kept: received privately).
    public static func rows(_ s: PrivacyState) -> [PrivateActivityRow] {
        let log = s.activity
        var ticks = log.clock
        for r in s.regRecords {
            if let t = r.time ?? r.chainTime, t <= UInt64(Int64.max), !ticks.contains(where: { $0.height == r.height }) {
                ticks.append(ActivityLog.Tick(height: r.height, time: Int64(t)))
            }
        }
        ticks.sort { $0.height < $1.height }
        func estimated(_ h: UInt64?) -> Int64? { h.flatMap { timeAt(ticks, $0) } }

        var owner: [String: Int] = [:]
        for (i, t) in log.sent.enumerated() { for r in t.change + t.receives { owner[r] = i } }
        let expected = Dictionary(log.expected.map { ($0.rho, $0.kind) }, uniquingKeysWith: { _, b in b })
        let recordedNf = Set(log.sent.flatMap(\.spent))
        let recordedTx = Set(log.sent.map { $0.hash.uppercased() })
        let notes = s.notes.filter { $0.note.value > 0 }
        let stake = s.stakeNotes.filter { $0.amount > 0 }

        var rows: [PrivateActivityRow] = []
        for t in log.sent {
            let minted = notes.filter { t.receives.contains($0.note.rho.hex) }
            let ins = sum(t.ins + ofNotes(minted))
            let status: PrivateActivityRow.Status = t.failure != nil ? .failed : (t.height != nil ? .confirmed : .pending)
            rows.append(PrivateActivityRow(kind: t.kind, coins: t.outs.map { ActivityCoin($0.denom, -$0.amount) } + ins, counterparty: t.counterparty,
                                           fee: t.fee, hash: t.hash, height: t.height, time: t.time ?? t.submittedAt, timeExact: true,
                                           status: status, failure: t.failure))
        }

        func unrecorded(_ nf: Fr, _ tx: String?) -> Bool { !recordedNf.contains(nf.hex) && (tx.map { !recordedTx.contains($0.uppercased()) } ?? true) }
        let spentNotes = s.notes.filter { $0.spentHeight != nil && $0.note.value > 0 && unrecorded($0.nf, $0.pendingTx) }
        let spentStake = s.stakeNotes.filter { $0.spentHeight != nil && $0.amount > 0 && unrecorded($0.nf, $0.pendingTx) }
        let inferredAt = Set(spentNotes.compactMap(\.spentHeight) + spentStake.compactMap(\.spentHeight)).sorted()
        let regHeights = Set(s.regRecords.map(\.height))
        func mine(_ n: OwnedNote) -> Bool { owner[n.note.rho.hex] != nil || expected[n.note.rho.hex] != nil }
        var folded: Set<UInt64> = []
        for h in inferredAt {
            let made = notes.filter { $0.height == h && !mine($0) }
            let madeStake = stake.filter { $0.height == h && owner[$0.rho.hex] == nil }
            for n in made { folded.insert(n.position) }
            let (outs, ins) = coins(poolSpent: ofNotes(spentNotes.filter { $0.spentHeight == h }), poolBack: ofNotes(made),
                                    stakeSpent: ofStake(spentStake.filter { $0.spentHeight == h }), stakeBack: ofStake(madeStake), fee: 0)
            rows.append(PrivateActivityRow(kind: regHeights.contains(h) ? .register : .inferred,
                                           coins: outs.map { ActivityCoin($0.denom, -$0.amount) } + ins, counterparty: "", fee: nil, hash: nil,
                                           height: h, time: estimated(h), timeExact: false, status: .confirmed))
        }

        for n in notes {
            let rho = n.note.rho.hex
            if owner[rho] != nil || folded.contains(n.position) { continue }
            let want = expected[rho]
            // A shield's note is shown by the shield's own (public) row.
            if want == .shield { continue }
            let kind: PrivateActivityKind
            if let want { kind = want } else if regHeights.contains(n.height), n.origin == .blind { kind = .registrationReward } else {
                switch n.origin {
                case .open: kind = .referral
                case .blind: kind = .fromEarth
                default: kind = .received
                }
            }
            rows.append(PrivateActivityRow(kind: kind, coins: [coin(n.note.denom, n.note.value)], counterparty: "", fee: nil, hash: nil,
                                           height: n.height, time: estimated(n.height), timeExact: false, status: .confirmed))
        }
        return rows.sorted { a, b in
            let ta = a.time ?? Int64.min, tb = b.time ?? Int64.min
            if ta != tb { return ta > tb }
            return (a.height ?? UInt64.max) > (b.height ?? UInt64.max)
        }
    }

    private static func sum(_ cs: [ActivityCoin]) -> [ActivityCoin] {
        var order: [String] = []
        var total: [String: Int64] = [:]
        for c in cs {
            if total[c.denom] == nil { order.append(c.denom) }
            let (v, o) = (total[c.denom] ?? 0).addingReportingOverflow(c.amount)
            total[c.denom] = o ? Int64.max : v
        }
        return order.map { ActivityCoin($0, total[$0]!) }.filter { $0.amount > 0 }
    }

    /// "uerth" -> "ERTH"; stake, LP shares and unresolved assets by what they are.
    public static func symbol(_ denom: String) -> String {
        if denom.hasPrefix(PrivacyWallet.derthPrefix) { return "dERTH" }
        if denom.hasPrefix(PrivacyWallet.lpPrefix) { return "LP" }
        if denom.hasPrefix(NotePlaintext.unresolvedPrefix) { return "token" }
        return (denom.hasPrefix("u") ? String(denom.dropFirst()) : denom).uppercased()
    }
}
