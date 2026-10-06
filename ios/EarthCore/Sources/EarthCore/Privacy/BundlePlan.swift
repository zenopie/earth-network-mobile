import BigInt
import Foundation

/// One output of a bundle, or a note a msg has the chain mint: its denom, pc,
/// ciphertext and (when ours) opening. Ports `privacy/tx/BundlePlan.kt`.
public struct NoteOut: Sendable {
    public let denom: String
    public let value: UInt64
    public let pc: Fr
    public let ciphertext: Data
    public let note: NotePlaintext?

    /// A note of `value` `denom` to `to`.
    public static func to(_ to: ShieldedAddress, denom: String, value: UInt64, memo: Data = Data()) throws -> NoteOut {
        // The circuits bound a note to 2^63-1; refused here, before proving.
        guard value <= UInt64(Int64.max) else { throw PrivacyError("a note holds at most 2^63-1 of a denom") }
        let n = NotePlaintext.fresh(denom, value, memo: memo)
        return NoteOut(denom: denom, value: value, pc: n.pc(ownerPK: to.ownerPK), ciphertext: try NoteCipher.encrypt(n, to: to), note: n)
    }

    public static func toSelf(_ keys: PrivacyKeys, denom: String, value: UInt64) throws -> NoteOut {
        try to(keys.address, denom: denom, value: value)
    }

    /// A note the chain will mint to us (a reward, a claim, a shield, an
    /// undelegation's payout, swap output, LP shares, refunds or withdrawal legs):
    /// fresh rho and rcm and a value-blind (v2) ciphertext of them to our own
    /// address, which sync opens against the amount the chain publishes with
    /// the note. No counter: every such note is found by trial decryption alone.
    public static func mintToSelf(_ keys: PrivacyKeys, denom: String, memo: Data = Data()) throws -> NoteOut {
        let n = NotePlaintext.fresh(denom, 0, memo: memo)
        return NoteOut(denom: denom, value: 0, pc: n.pc(ownerPK: keys.ownerPK), ciphertext: try NoteCipher.encryptBlind(n, to: keys.address), note: n)
    }

    /// A note the chain will mint to `to` at a value and asset it decides (a
    /// swap's output): `to`'s pc with a value-blind (v2) ciphertext, which
    /// `to` opens against the amount the chain publishes.
    public static func blindTo(_ to: ShieldedAddress, denom: String, memo: Data = Data()) throws -> NoteOut {
        let n = NotePlaintext.fresh(denom, 0, memo: memo)
        return NoteOut(denom: denom, value: 0, pc: n.pc(ownerPK: to.ownerPK), ciphertext: try NoteCipher.encryptBlind(n, to: to), note: nil)
    }

    /// Dummies carry the fee asset; at value 0 the asset adds nothing to cv.
    public static let dummyDenom = "uerth"

    /// A value-0 output nobody can open: the same shape as any other.
    public static func dummy() -> NoteOut {
        NoteOut(denom: dummyDenom, value: 0, pc: NotePlaintext.randomField(), ciphertext: NoteCipher.dummy(), note: nil)
    }
}

/// One action's spend: an owned note with its Merkle path, or a value-0 dummy.
public struct ActionSpend: Sendable {
    public let denom: String
    public let value: UInt64
    public let rho: Fr
    public let rcm: Fr
    public let position: UInt64
    public let path: [Fr]
    public let note: OwnedNote?

    public static func of(_ n: OwnedNote, path: [Fr]) -> ActionSpend {
        ActionSpend(denom: n.note.denom, value: n.note.value, rho: n.note.rho, rcm: n.note.rcm, position: n.position, path: path, note: n)
    }

    /// A dummy: value 0, so the circuit skips its membership, and a fresh
    /// rho, so its nullifier (which is still published and spent) is new.
    public static func dummy() -> ActionSpend {
        ActionSpend(denom: NoteOut.dummyDenom, value: 0, rho: NotePlaintext.randomField(), rcm: NotePlaintext.randomField(), position: 0,
                    path: Array(repeating: .zero, count: Merkle.depth), note: nil)
    }
}

/// A spend paired with an output, and the action's value-commitment randomness.
public struct PlannedAction: Sendable {
    public let spend: ActionSpend
    public let out: NoteOut
    public let rcv: Fr
}

/// Go's string order (bytewise), the order the chain sorts denoms in.
func denomLess(_ a: String, _ b: String) -> Bool { a.utf8.lexicographicallyPrecedes(b.utf8) }

/// One bundle laid out: its actions (real or dummy spends and outputs, any
/// asset on either side), all proven against `anchor`, and its public value
/// balance per denom, which is computed here from the actions themselves
/// (spends less outputs; zk/orchard refuses a negative one) so the plan can
/// never disagree with its own binding signature. Everything the sighash
/// binds is final once built; proofs and the binding signature are made over it.
public struct BundlePlan: Sendable {
    /// x/shielded MinActionsPerBundle: every bundle is padded to at least two.
    public static let minActions = 2
    /// zk/orchard.MaxActions; the chain's max_actions_per_bundle param may only be lower.
    public static let maxActions = 32

    public let actions: [PlannedAction]
    public let anchor: Fr
    private let nk: Fr
    /// Public balances, positive only, in denom order.
    public let balances: [(denom: String, amount: UInt64)]
    public let nullifiers: [Fr]
    public let commitments: [Fr]
    public let cvs: [Grumpkin.Point]
    private let spendAssets: [Fr]
    private let outAssets: [Fr]

    public init(actions: [PlannedAction], anchor: Fr, nk: Fr) throws {
        try require((Self.minActions ... Self.maxActions).contains(actions.count),
                    "a bundle carries \(Self.minActions)..\(Self.maxActions) actions")
        self.actions = actions; self.anchor = anchor; self.nk = nk
        var net: [String: BigInt] = [:]
        for a in actions {
            net[a.spend.denom, default: 0] += BigInt(a.spend.value)
            net[a.out.denom, default: 0] -= BigInt(a.out.value)
        }
        for (d, v) in net where v < 0 { throw PrivacyError("bundle creates \(-v)\(d)") }
        balances = net.filter { $0.value > 0 }.sorted { denomLess($0.key, $1.key) }.map { ($0.key, UInt64($0.value)) }
        var assets: [String: Fr] = [:]
        func asset(_ d: String) -> Fr {
            if let a = assets[d] { return a }
            let a = NotePlaintext.assetOf(d); assets[d] = a; return a
        }
        let sa = actions.map { asset($0.spend.denom) }
        let oa = actions.map { asset($0.out.denom) }
        spendAssets = sa
        outAssets = oa
        nullifiers = actions.map { PrivacyHash.nf(nk: nk, rho: $0.spend.rho, position: $0.spend.position) }
        commitments = actions.indices.map { PrivacyHash.cm(asset: oa[$0], value: actions[$0].out.value, pc: actions[$0].out.pc) }
        cvs = actions.indices.map {
            Grumpkin.valueCommit(assetSpend: sa[$0], vSpend: actions[$0].spend.value, assetOut: oa[$0], vOut: actions[$0].out.value,
                                 rcv: actions[$0].rcv)
        }
    }

    public func balance(_ denom: String) -> UInt64 { balances.first { $0.denom == denom }?.amount ?? 0 }

    /// The owned notes this bundle spends.
    public var spends: [OwnedNote] { actions.compactMap(\.spend.note) }

    public func witness(_ i: Int, sighash: Fr) throws -> ActionWitness {
        let a = actions[i]
        return try ActionWitness(nk: nk, sAsset: spendAssets[i], sValue: a.spend.value, sRho: a.spend.rho, sRcm: a.spend.rcm,
                                 sPos: a.spend.position, sPath: a.spend.path, oAsset: outAssets[i], oValue: a.out.value, oPc: a.out.pc,
                                 rcv: a.rcv, anchor: anchor, sighash: sighash, cv: cvs[i])
    }

    /// bsk = sum of rcv mod n.
    public func bindingKey() -> BigUInt { Grumpkin.bindingKey(actions.map(\.rcv)) }

    /// The bundle with `proofs` (placeholders before proving) and `bindingSig` (zeros before signing).
    public func proto(proofs: [Data]? = nil, bindingSig: Data? = nil) -> ShieldedBundle {
        let anchorBytes = anchor.bytes
        let acts = actions.indices.map { i in
            ShieldedAction(anchor: anchorBytes, nullifier: nullifiers[i].bytes, commitment: commitments[i].bytes, cv: cvs[i].bytes,
                   ciphertext: actions[i].out.ciphertext, proof: proofs?[i] ?? PrivateTxEngine.placeholder)
        }
        return ShieldedBundle(actions: acts, balances: balances.map { ValueBalance(denom: $0.denom, amount: $0.amount) },
                      bindingSig: bindingSig ?? Data(count: Grumpkin.bindingSigBytes))
    }

    /// Proves every action under `sighash` and signs the balance.
    public func prove(sighash: Fr, _ prove: (ActionWitness) async throws -> Data) async throws -> ShieldedBundle {
        var proofs: [Data] = []
        for i in actions.indices { proofs.append(try await prove(witness(i, sighash: sighash))) }
        return proto(proofs: proofs, bindingSig: Grumpkin.signBinding(bsk: bindingKey(), sighash: sighash, rnd: NotePlaintext.randomBytes(32)))
    }

    /// Pairs `spends` with `outputs` into max(#spends, #outputs, `minActions`)
    /// actions, dummies filling either side, each list shuffled first so the
    /// action order says nothing about which output is change.
    public static func pair(keys: PrivacyKeys, anchor: Fr, spends: [ActionSpend], outputs: [NoteOut], minActions: Int = minActions,
                            maxActions: Int = maxActions) throws -> BundlePlan {
        let n = max(spends.count, outputs.count, minActions)
        if n > maxActions {
            throw NoteSelection.Insufficient(message: "this needs \(n) actions, more than the \(maxActions) one transaction may carry; merge notes first")
        }
        let ss = spends.shuffled()
        let os = outputs.shuffled()
        let actions = (0 ..< n).map { i in
            PlannedAction(spend: i < ss.count ? ss[i] : .dummy(), out: i < os.count ? os[i] : .dummy(), rcv: NotePlaintext.randomField())
        }
        return try BundlePlan(actions: actions, anchor: anchor, nk: keys.nk)
    }
}

/// Lays bundles out: picks notes for what must leave each denom (the payments
/// `outputs` plus the public `release`: a fee, an unshield, what a module
/// takes), returns each denom's change to us, and pairs it all into actions.
/// Any number of notes, any number of assets, up to max_actions_per_bundle.
public enum BundleBuilder {
    public static func plan(keys: PrivacyKeys, tree: MerkleTree, notes: [OwnedNote], outputs: [NoteOut], release: [String: UInt64],
                            maxActions: Int, exclude: Set<UInt64> = [], forced: [OwnedNote] = []) throws -> BundlePlan {
        var need: [String: UInt64] = [:]
        func add(_ d: String, _ v: UInt64) throws {
            let (s, o) = need[d, default: 0].addingReportingOverflow(v)
            try require(!o, "amount overflows")
            need[d] = s
        }
        for o in outputs where o.value > 0 { try add(o.denom, o.value) }
        for (d, v) in release where v > 0 { try add(d, v) }
        for n in forced where need[n.note.denom] == nil { need[n.note.denom] = 0 }
        var spends = forced
        var outs = outputs
        for denom in need.keys.sorted(by: denomLess) {
            let amount = need[denom]!
            let have = forced.filter { $0.note.denom == denom }.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.note.value) }
            let chosen = have >= amount ? [] : try NoteSelection.cover(notes, denom: denom, amount: amount - have,
                                                                      exclude: exclude.union(spends.map(\.position)),
                                                                      maxNotes: maxActions - spends.count)
            spends += chosen
            let (total, o) = have.addingReportingOverflow(chosen.reduce(UInt64(0)) { PrivateMsgs.saturatingAdd($0, $1.note.value) })
            try require(!o && total != .max, "note values overflow")
            let change = total - amount
            if change > 0 { outs.append(try NoteOut.toSelf(keys, denom: denom, value: change)) }
        }
        return try fromNotes(keys: keys, tree: tree, spends: spends, outputs: outs, maxActions: maxActions)
    }

    /// A bundle spending exactly `spends` into `outputs` (no selection, no change).
    public static func fromNotes(keys: PrivacyKeys, tree: MerkleTree, spends: [OwnedNote], outputs: [NoteOut], maxActions: Int,
                                 anchor: Fr? = nil) throws -> BundlePlan {
        try BundlePlan.pair(keys: keys, anchor: anchor ?? tree.root(), spends: spends.map { ActionSpend.of($0, path: tree.path($0.position)) },
                            outputs: outputs, maxActions: maxActions)
    }
}

/// Picks notes to spend.
public enum NoteSelection {
    public struct Insufficient: Swift.Error, LocalizedError, Equatable {
        public let message: String
        public init(message: String) { self.message = message }
        public var errorDescription: String? { message }
    }

    /// Ascending by value, ties by tree position (the order sync found them in).
    static func ascending(_ a: OwnedNote, _ b: OwnedNote) -> Bool {
        a.note.value != b.note.value ? a.note.value < b.note.value : a.position < b.position
    }

    public static func spendable(_ notes: [OwnedNote], denom: String, exclude: Set<UInt64> = []) -> [OwnedNote] {
        notes.filter { $0.unspent && $0.pendingAt == nil && $0.note.denom == denom && !exclude.contains($0.position) && $0.note.value > 0 }
    }

    /// The most of `denom` one transaction can spend: its `maxNotes` largest
    /// spendable notes (max_actions_per_bundle; the whole balance unless it
    /// is spread over more notes than that).
    public static func maxSpendable(_ notes: [OwnedNote], denom: String, maxNotes: Int) -> UInt64 {
        spendable(notes, denom: denom).map(\.note.value).sorted(by: >).prefix(maxNotes).reduce(0, PrivateMsgs.saturatingAdd)
    }

    /// Notes of `denom` covering `amount`: the smallest single note that
    /// does; else the fewest notes (largest first), the last swapped for the
    /// smallest note that still covers. At most `maxNotes`.
    public static func cover(_ notes: [OwnedNote], denom: String, amount: UInt64, exclude: Set<UInt64> = [],
                             maxNotes: Int = .max) throws -> [OwnedNote] {
        if amount == 0 { return [] }
        let c = spendable(notes, denom: denom, exclude: exclude).sorted(by: ascending)
        if let one = c.first(where: { $0.note.value >= amount }) { return [one] }
        var chosen: [OwnedNote] = []
        var sum: UInt64 = 0
        for n in c.reversed() {
            chosen.append(n)
            sum = PrivateMsgs.saturatingAdd(sum, n.note.value)
            if sum >= amount { break }
        }
        if sum < amount { throw Insufficient(message: "insufficient shielded \(denom)") }
        if chosen.count > maxNotes { throw Insufficient(message: "\(denom) is spread over too many notes for one transaction; merge them first") }
        let rest = sum - chosen.last!.note.value
        let taken = Set(chosen.map(\.position))
        if let swap = c.first(where: { !taken.contains($0.position) && PrivateMsgs.saturatingAdd(rest, $0.note.value) >= amount }),
           swap.note.value < chosen.last!.note.value {
            chosen[chosen.count - 1] = swap
        }
        return chosen
    }
}

/// Picks stake notes: a stake proof spends at most two.
/// Picks stake notes for lane A of a stake proof (circuits/stake v2): at most
/// two inputs, at most one of them labelled (ORCHARD_DESIGN 8.7). `free` is
/// what a note may give up: its amount, or for a labelled note its amount less
/// the exposure (the window still open) or less the exposure plus what the
/// debt tree says it retains (the window closed: the proof clears it). Ports
/// `privacy/tx/BundlePlan.kt` StakeSelection, order for order.
public enum StakeSelection {
    private static func pairs(_ c: [OwnedStakeNote]) -> [[OwnedStakeNote]] {
        var out: [[OwnedStakeNote]] = []
        for i in c.indices { for j in (i + 1) ..< c.count where c[i].label == nil || c[j].label == nil { out.append([c[i], c[j]]) } }
        return out
    }

    private static func sum(_ ns: [OwnedStakeNote], _ free: (OwnedStakeNote) -> UInt64) -> UInt64 {
        ns.reduce(UInt64(0)) { PrivacyWallet.Snapshot.satAdd63($0, free($1)) }
    }

    /// What a merge spends (a delegation's, an unlock's, a restake's): up to
    /// two of `notes`, at most one labelled, the most value first. Empty when
    /// there is none (the proof pads).
    public static func merge(_ notes: [OwnedStakeNote], free: (OwnedStakeNote) -> UInt64) -> [OwnedStakeNote] {
        let c = notes.filter(\.spendable).sorted { free($0) != free($1) ? free($0) > free($1) : $0.position < $1.position }
        if c.count <= 1 { return c }
        var best: [OwnedStakeNote]?
        for p in pairs(c) {
            guard let b = best else { best = p; continue }
            let (sp, sb) = (sum(p, free), sum(b, free))
            let (pp, pb) = (p.reduce(UInt64(0)) { $0 &+ $1.position }, b.reduce(UInt64(0)) { $0 &+ $1.position })
            if sp > sb || (sp == sb && pp < pb) { best = p }
        }
        return best ?? [c[0]]
    }

    /// Notes whose free value covers `amount`: the smallest single one that
    /// does, else the pair with the smallest sufficient free sum. Refused when
    /// none does: `locked` names the exposure the window keeps in place (the
    /// caller explains it), else the stake is spread over more notes than one
    /// proof spends, or short.
    public static func cover(_ notes: [OwnedStakeNote], amount: UInt64, free: (OwnedStakeNote) -> UInt64,
                             locked: () -> String? = { nil }) throws -> [OwnedStakeNote] {
        try require(amount > 0, "the amount must be positive")
        let c = notes.filter(\.spendable).sorted { free($0) != free($1) ? free($0) < free($1) : $0.position < $1.position }
        if let one = c.first(where: { free($0) >= amount }) { return [one] }
        var best: [OwnedStakeNote]?
        for p in pairs(c) where sum(p, free) >= amount {
            if let b = best, sum(b, free) <= sum(p, free) { continue }
            best = p
        }
        if let best { return best }
        let held = c.reduce(UInt64(0)) { PrivacyWallet.Snapshot.satAdd63($0, $1.amount) }
        let freeAll = sum(c, free)
        let message: String
        if freeAll < amount && held >= amount {
            message = locked() ?? "part of this stake was moved here recently and cannot move again yet"
        } else if freeAll >= amount {
            message = "this stake is spread over more notes than one transaction spends; merge them first (one fee each), then try again"
        } else {
            message = "insufficient stake"
        }
        throw NoteSelection.Insufficient(message: message)
    }
}
