import Foundation

/// One output of a transfer, or of a msg that mints a note: its pc,
/// ciphertext and (when ours) opening. Ports `privacy/tx/TransferPlan.kt`.
public struct NoteOut: Sendable {
    public let value: UInt64
    public let pc: Fr
    public let ciphertext: Data
    public let note: NotePlaintext?

    /// A note of `value` `denom` to `to`.
    public static func to(_ to: ShieldedAddress, denom: String, value: UInt64, memo: Data = Data()) throws -> NoteOut {
        let n = NotePlaintext.fresh(denom, value, memo: memo)
        return NoteOut(value: value, pc: n.pc(ownerPK: to.ownerPK), ciphertext: try NoteCipher.encrypt(n, to: to), note: n)
    }

    public static func toSelf(_ keys: PrivacyKeys, denom: String, value: UInt64) throws -> NoteOut {
        try to(keys.address, denom: denom, value: value)
    }

    /// A note the chain will mint to us at a value we cannot know yet (a
    /// reward, derth at the live rate, an unbonding payout): its pc is
    /// self-mint `counter`'s (PrivacyKeys.mintSecrets) and it carries no
    /// ciphertext; sync finds it by its public mint amount.
    public static func mintToSelf(_ keys: PrivacyKeys, denom: String, counter: UInt32) -> NoteOut {
        let (rho, rcm) = keys.mintSecrets(counter)
        let n = NotePlaintext(denom: denom, value: 0, rho: rho, rcm: rcm)
        return NoteOut(value: 0, pc: n.pc(ownerPK: keys.ownerPK), ciphertext: Data(), note: n)
    }

    /// A note the chain will mint to `to` at a value and asset it decides (a
    /// swap's output, an LP withdrawal): `to`'s pc with a value-blind (v2)
    /// ciphertext, which `to` opens against the amount the chain publishes.
    public static func blindTo(_ to: ShieldedAddress, denom: String, memo: Data = Data()) throws -> NoteOut {
        let n = NotePlaintext.fresh(denom, 0, memo: memo)
        return NoteOut(value: 0, pc: n.pc(ownerPK: to.ownerPK), ciphertext: try NoteCipher.encryptBlind(n, to: to), note: nil)
    }

    /// A value-0 output nobody can open: same shape as any other.
    public static func dummy() -> NoteOut {
        NoteOut(value: 0, pc: NotePlaintext.randomField(), ciphertext: NoteCipher.dummy(), note: nil)
    }
}

/// Everything a transfer proof needs except the signal: which notes fill the
/// three input slots (dummies where empty), the three outputs, the anchor and
/// the public values. Slots 0-1 carry asset `denom`; slot 2 is ERTH and pays
/// `fee` (for an ERTH transfer all three slots share one balance, so any
/// input pays it). Built once per fee: its nullifiers, commitments and
/// ciphertexts are final, so the msg's signal can be computed before
/// anything is proven.
public struct TransferPlan: Sendable {
    public let denom: String
    public let inputs: [TransferInput]
    public let spends: [OwnedNote]
    public let outputs: [NoteOut]
    public let root: Fr
    public let fee: UInt64
    public let vPubOut: UInt64
    private let nk: Fr
    /// The witness with a zero signal: nullifiers and commitments do not depend on it.
    private let draft: TransferWitness

    init(denom: String, inputs: [TransferInput], spends: [OwnedNote], outputs: [NoteOut], root: Fr, fee: UInt64, vPubOut: UInt64, nk: Fr) throws {
        try require(inputs.count == 3 && outputs.count == 3, "a transfer has three slots")
        self.denom = denom; self.inputs = inputs; self.spends = spends; self.outputs = outputs
        self.root = root; self.fee = fee; self.vPubOut = vPubOut; self.nk = nk
        draft = try TransferWitness(asset: PrivacyHash.assetID(denom), nk: nk, inputs: inputs,
                                    outputs: outputs.map { TransferOutput(value: $0.value, pc: $0.pc) },
                                    root: root, fee: fee, vPubOut: vPubOut, signal: .zero)
    }

    public var denomOut: String { vPubOut > 0 ? denom : "" }

    public func witness(signal: Fr) throws -> TransferWitness {
        try TransferWitness(asset: draft.asset, nk: nk, inputs: inputs, outputs: draft.outputs, root: root,
                            fee: fee, vPubOut: vPubOut, signal: signal)
    }

    /// The msg's Transfer, with `proof` (a placeholder before proving).
    public func proto(proof: Data) -> ShieldedTransfer {
        ShieldedTransfer(proof: proof, root: root.bytes, nullifiers: draft.nullifiers.map(\.bytes),
                         commitments: draft.commitments.map(\.bytes), ciphertexts: outputs.map(\.ciphertext),
                         fee: fee, valueOut: vPubOut, denomOut: denomOut)
    }

    static func input(_ n: OwnedNote, _ tree: MerkleTree, _ pathOverride: [Fr]?) throws -> TransferInput {
        try TransferInput(value: n.note.value, rho: n.note.rho, rcm: n.note.rcm, position: n.position,
                          path: pathOverride ?? tree.path(n.position))
    }

    /// Lays a transfer out. `aInputs` (0-2 notes of `denom`) fund `aOutputs`
    /// (0-2) plus `vPubOut`; any surplus returns to `keys` as change.
    ///
    /// `denom` other than ERTH: `feeNote` (ERTH) pays `fee` with its change
    /// to `keys` in slot 2; without one, `fee` must be 0. The A change takes
    /// the first free A output.
    ///
    /// ERTH: the circuit balances all three slots together, so `feeNote` is
    /// just a third input (or none) and `fee` may come from any input; the
    /// one change note goes to slot 2.
    public static func build(
        keys: PrivacyKeys, tree: MerkleTree, denom: String, aInputs: [OwnedNote], aOutputs: [NoteOut], vPubOut: UInt64,
        feeNote: OwnedNote?, fee: UInt64, root: Fr? = nil, paths: [UInt64: [Fr]] = [:]
    ) throws -> TransferPlan {
        let root = root ?? tree.root()
        try require(aInputs.count <= 2 && aOutputs.count <= 2, "at most two inputs and outputs of the asset")
        try require(aInputs.allSatisfy { $0.note.denom == denom }, "inputs must all be \(denom)")
        try require(feeNote == nil || feeNote!.note.denom == "uerth", "the fee note must be ERTH")
        let spends = aInputs + (feeNote.map { [$0] } ?? [])
        try require(Set(spends.map(\.position)).count == spends.count, "a note fills one slot")
        if denom == "uerth" {
            return try buildErth(keys: keys, tree: tree, aInputs: aInputs, aOutputs: aOutputs, vPubOut: vPubOut,
                                 third: feeNote, fee: fee, root: root, paths: paths)
        }
        let inSum = aInputs.reduce(UInt64(0)) { $0 + $1.note.value }
        let outSum = aOutputs.reduce(UInt64(0)) { $0 + $1.value } + vPubOut
        try require(inSum >= outSum, "insufficient \(denom): have \(inSum), need \(outSum)")
        var outs = aOutputs
        if inSum > outSum {
            try require(outs.count < 2, "no output slot left for change")
            outs.append(try NoteOut.toSelf(keys, denom: denom, value: inSum - outSum))
        }
        while outs.count < 2 { outs.append(.dummy()) }
        var ins = try aInputs.map { try input($0, tree, paths[$0.position]) }
        while ins.count < 2 { ins.append(.dummy()) }

        let slot2In: TransferInput
        let slot2Out: NoteOut
        if let feeNote {
            try require(feeNote.note.value >= fee, "fee note too small")
            slot2In = try input(feeNote, tree, paths[feeNote.position])
            let change = feeNote.note.value - fee
            slot2Out = change > 0 ? try NoteOut.toSelf(keys, denom: "uerth", value: change) : .dummy()
        } else {
            try require(fee == 0, "a fee needs a fee note")
            slot2In = .dummy()
            slot2Out = .dummy()
        }
        return try TransferPlan(denom: denom, inputs: ins + [slot2In], spends: spends, outputs: outs + [slot2Out],
                                root: root, fee: fee, vPubOut: vPubOut, nk: keys.nk)
    }

    /// `build` for ERTH: one balance over every slot, change in slot 2.
    private static func buildErth(
        keys: PrivacyKeys, tree: MerkleTree, aInputs: [OwnedNote], aOutputs: [NoteOut], vPubOut: UInt64,
        third: OwnedNote?, fee: UInt64, root: Fr, paths: [UInt64: [Fr]]
    ) throws -> TransferPlan {
        let spends = aInputs + (third.map { [$0] } ?? [])
        let inSum = spends.reduce(UInt64(0)) { $0 + $1.note.value }
        let outSum = aOutputs.reduce(UInt64(0)) { $0 + $1.value } + vPubOut + fee
        try require(inSum >= outSum, "insufficient uerth: have \(inSum), need \(outSum)")
        var outs = aOutputs
        while outs.count < 2 { outs.append(.dummy()) }
        outs.append(inSum > outSum ? try NoteOut.toSelf(keys, denom: "uerth", value: inSum - outSum) : .dummy())
        var ins = try aInputs.map { try input($0, tree, paths[$0.position]) }
        while ins.count < 2 { ins.append(.dummy()) }
        ins.append(try third.map { try input($0, tree, paths[$0.position]) } ?? .dummy())
        return try TransferPlan(denom: "uerth", inputs: ins, spends: spends, outputs: outs, root: root, fee: fee, vPubOut: vPubOut, nk: keys.nk)
    }

    /// An ERTH transfer from `notes` (1-3, as NoteSelection.inputs picks
    /// them for amount + fee): the first two fill slots 0-1, a third slot 2.
    public static func erth(keys: PrivacyKeys, tree: MerkleTree, notes: [OwnedNote], outputs: [NoteOut], vPubOut: UInt64, fee: UInt64) throws -> TransferPlan {
        try require((1 ... 3).contains(notes.count), "an ERTH transfer spends one to three notes")
        return try build(keys: keys, tree: tree, denom: "uerth", aInputs: Array(notes.prefix(2)), aOutputs: outputs,
                         vPubOut: vPubOut, feeNote: notes.count > 2 ? notes[2] : nil, fee: fee)
    }

    /// A transfer that only pays `fee` from `feeNote`: the fee proof of a private action.
    public static func feeOnly(keys: PrivacyKeys, tree: MerkleTree, feeNote: OwnedNote, fee: UInt64) throws -> TransferPlan {
        try build(keys: keys, tree: tree, denom: "uerth", aInputs: [], aOutputs: [], vPubOut: 0, feeNote: feeNote, fee: fee)
    }
}

/// Picks notes to spend. A transfer has two slots for its asset and one ERTH
/// slot for the fee; an ERTH transfer may use all three for one balance.
public enum NoteSelection {
    public struct Insufficient: Swift.Error, LocalizedError, Equatable {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// Ascending by value, ties by tree position (the order sync found them in).
    static func ascending(_ a: OwnedNote, _ b: OwnedNote) -> Bool {
        a.note.value != b.note.value ? a.note.value < b.note.value : a.position < b.position
    }

    public static func spendable(_ notes: [OwnedNote], denom: String, exclude: Set<UInt64> = []) -> [OwnedNote] {
        notes.filter { $0.unspent && $0.pendingAt == nil && $0.note.denom == denom && !exclude.contains($0.position) && $0.note.value > 0 }
    }

    /// The smallest ERTH note covering `fee`, so larger notes stay whole for payments.
    public static func feeNote(_ notes: [OwnedNote], fee: UInt64, exclude: Set<UInt64> = []) throws -> OwnedNote {
        guard let n = spendable(notes, denom: "uerth", exclude: exclude).filter({ $0.note.value >= fee }).min(by: ascending) else {
            throw Insufficient(message: "no shielded ERTH note covers the \(fee)uerth fee")
        }
        return n
    }

    /// The most of `denom` one transfer can spend: its `maxNotes` largest
    /// spendable notes. Less than the balance when it is spread over more
    /// notes than that, which merging fixes.
    public static func maxSpendable(_ notes: [OwnedNote], denom: String, maxNotes: Int = 2) -> UInt64 {
        spendable(notes, denom: denom).map(\.note.value).sorted(by: >).prefix(maxNotes).reduce(0, +)
    }

    /// Up to `maxNotes` notes of `denom` covering `amount`: the smallest single
    /// note that does, else the pair, else (maxNotes 3, an ERTH transfer) the
    /// triple with the smallest sufficient sum.
    public static func inputs(_ notes: [OwnedNote], denom: String, amount: UInt64, exclude: Set<UInt64> = [], maxNotes: Int = 2) throws -> [OwnedNote] {
        precondition((1 ... 3).contains(maxNotes))
        let c = spendable(notes, denom: denom, exclude: exclude).sorted(by: ascending)
        if let one = c.first(where: { $0.note.value >= amount }) { return [one] }
        var best: [OwnedNote]?
        var bestSum = UInt64.max
        func consider(_ ns: [OwnedNote]) {
            let s = ns.reduce(UInt64(0)) { $0 + $1.note.value }
            if s >= amount, s < bestSum { best = ns; bestSum = s }
        }
        if maxNotes >= 2 {
            for i in c.indices { for j in (i + 1) ..< c.count { consider([c[i], c[j]]) } }
        }
        if best == nil, maxNotes >= 3 {
            // Among the largest notes only: bounded work for a wallet of many small ones.
            let t = Array(c.suffix(64))
            for i in t.indices { for j in (i + 1) ..< t.count { for k in (j + 1) ..< t.count { consider([t[i], t[j], t[k]]) } } }
        }
        if let best { return best }
        let total = c.reduce(UInt64(0)) { $0 + $1.note.value }
        throw Insufficient(message: total >= amount ? "\(denom) is spread over too many notes; merge them first" : "insufficient shielded \(denom)")
    }
}
