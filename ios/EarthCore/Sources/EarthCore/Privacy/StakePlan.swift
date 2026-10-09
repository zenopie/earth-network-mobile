import BigInt
import Foundation

/// One stake proof laid out (circuits/stake v2, ORCHARD_DESIGN 4.1). Ports
/// `privacy/tx/StakePlan.kt`: lane A spends up to two of this wallet's notes
/// of `denom` (padding the slots it does not fill) and creates one note back to it (the
/// merged note, the change, or a zero note), crediting `vIn` and releasing
/// `vOut`; the credit lane (`credit`) merges a redelegation's credit into the
/// wallet's note at the destination. Everything the sighash binds (the
/// StakeFields) is final once built.
///
/// `vote` and `creditVote`: the outputs that vote in Groundworks (the msg
/// names the split). Every proof names the chain's current clear_before and
/// debt root (`clear`) whether or not it clears a label:
/// a proof naming them only to clear would be linkable to the redelegation
/// that labelled the note.
public struct StakePlan: Sendable {
    /// A created stake note: its amount, secrets, label and ciphertext (to ourselves).
    public struct Out: Sendable {
        public let amount: UInt64
        public let rho: Fr
        public let rcm: Fr
        public let label: StakeLabel?
        public let ciphertext: Data
    }

    /// What the proof names of the slash debt: `clearBefore` and `debtRoot`
    /// (0 and zero only while the block time is below the label window, which no real chain sees), and
    /// whether lane A's labelled input clears (`witness` and `retained`, read
    /// from the debt tree at debtRoot) or keeps its label.
    public struct Clear: Sendable {
        public let clearBefore: UInt64
        public let debtRoot: Fr
        public let witness: DebtTree.Witness?
        public let retained: UInt64

        public init(clearBefore: UInt64, debtRoot: Fr, witness: DebtTree.Witness? = nil, retained: UInt64 = 0) {
            self.clearBefore = clearBefore; self.debtRoot = debtRoot; self.witness = witness; self.retained = retained
        }

        public var clears: Bool { witness != nil }

        public static let none = Clear(clearBefore: 0, debtRoot: .zero)
    }

    /// The credit lane (a redelegation): the wallet's unlabelled note of
    /// `denom` merged with `vIn` (`spend`, or a padding input when there is
    /// none), labelled with the move made at `moveTime`.
    public struct Credit: Sendable {
        public let denom: String
        public let spend: OwnedStakeNote?
        public let path: [Fr]?
        public let vIn: UInt64
        public let moveTime: UInt64
        public let padRho: Fr
        public let padRcm: Fr
        public let out: Out

        /// The credit nullifier: the move's key.
        public var nullifier: Fr { out.label!.moveKey }
    }

    private let nk: Fr
    public let denom: String
    public let spends: [OwnedStakeNote]
    private let paths: [[Fr]]
    /// Lane A's output.
    public let out: Out
    public let vIn: UInt64
    public let vOut: UInt64
    public let clear: Clear
    public let credit: Credit?
    public let vote: Bool
    public let creditVote: Bool
    public let anchor: Fr
    public let asset: Fr
    private let noneIn: [(Fr, Fr)]
    private let noneOut: (Fr, Fr)

    public init(nk: Fr, denom: String, spends: [OwnedStakeNote], paths: [[Fr]], out: Out, vIn: UInt64, vOut: UInt64, clear: Clear,
                credit: Credit?, vote: Bool = false, creditVote: Bool = false, anchor: Fr) throws {
        try require(spends.count <= 2 && paths.count == spends.count, "a stake proof spends at most two notes")
        try require(spends.allSatisfy { $0.denom == denom && $0.amount > 0 }, "a stake proof spends notes of its own denom")
        try require(spends.filter { $0.label != nil }.count <= 1, "a stake proof spends at most one labelled note")
        try require(!vote || out.amount > 0, "a padding output cannot vote")
        try require(!creditVote || credit != nil, "no credit lane, no credit vote")
        try require(!clear.clears || spends.contains { $0.label != nil }, "nothing to clear")
        try require(clear.clearBefore != 0 || (clear.debtRoot.isZero && !clear.clears), "debt_root is zero exactly when clear_before is")
        let l = spends.compactMap(\.label).first
        try require(out.label == (clear.clears ? nil : l), "the output keeps the label unless it clears")
        let ex = BigUInt(l?.exposed ?? 0)
        let ins = spends.reduce(BigUInt(0)) { $0 + BigUInt($1.amount) } - ex + BigUInt(clear.clears ? clear.retained : 0) + BigUInt(vIn)
        let outs = BigUInt(out.amount - (out.label?.exposed ?? 0)) + BigUInt(vOut)
        try require(ins == outs, "stake amounts do not balance: in \(ins), out \(outs)")
        if let c = credit {
            try require(c.spend == nil || (c.spend!.label == nil && c.spend!.denom == c.denom), "the credit lane merges only into an unlabelled note")
            let (sum, o) = (c.spend?.amount ?? 0).addingReportingOverflow(c.vIn)
            try require(!o && c.out.amount == sum, "the credit lane's note is its input and the credit")
        }
        self.nk = nk; self.denom = denom; self.spends = spends; self.paths = paths; self.out = out; self.vIn = vIn; self.vOut = vOut
        self.clear = clear; self.credit = credit; self.vote = vote; self.creditVote = creditVote; self.anchor = anchor
        asset = PrivacyHash.assetID(denom)
        noneIn = (0 ..< 2).map { _ in (NotePlaintext.randomField(), NotePlaintext.randomField()) }
        noneOut = (NotePlaintext.randomField(), NotePlaintext.randomField())
    }

    private static let zeros = [Fr](repeating: .zero, count: Merkle.depth)

    public func witness(sighash: Fr) throws -> StakeWitness {
        let ins: [StakeIn] = try (0 ..< 2).map { i in
            if i < spends.count {
                let n = spends[i]
                return try StakeIn(amount: n.amount, rho: n.rho, rcm: n.rcm, pos: n.position, path: paths[i], label: n.label)
            }
            // Every slot we spend nothing in is padded: the chain requires
            // both nullifiers and tags, so a merge of two notes looks like a
            // spend of one (ORCHARD_DESIGN 8.3).
            return StakeIn.padding(rho: noneIn[i].0, rcm: noneIn[i].1)
        }
        let crIn: StakeIn
        if let c = credit {
            if let sp = c.spend {
                crIn = try StakeIn(amount: sp.amount, rho: sp.rho, rcm: sp.rcm, pos: sp.position, path: c.path!)
            } else {
                crIn = StakeIn.padding(rho: c.padRho, rcm: c.padRcm)
            }
        } else {
            crIn = StakeIn.none(rho: noneIn[1].0, rcm: noneIn[1].1)
        }
        return try StakeWitness(
            nk: nk, ins: ins,
            outAmount: out.amount, outRho: out.rho, outRcm: out.rcm, padOut: true,
            clear: clear.clears, debt: clear.witness ?? .none,
            crIn: crIn, crOutRho: credit?.out.rho ?? noneOut.0, crOutRcm: credit?.out.rcm ?? noneOut.1,
            vote: vote, crVote: creditVote, anchor: anchor, asset: asset, vIn: vIn, vOut: vOut,
            clearBefore: clear.clearBefore, debtRoot: clear.debtRoot,
            crAsset: credit.map { PrivacyHash.assetID($0.denom) } ?? .zero, crVIn: credit?.vIn ?? 0, crMoveTime: credit?.moveTime ?? 0,
            sighash: sighash
        )
    }

    /// The msg's StakeProof, with `proof` (a placeholder before proving).
    public func proto(proof: Data) throws -> StakeProof {
        let w = try witness(sighash: .zero) // every public value but the sighash
        try require(!w.commitment.isZero, "lane A creates a note (the merged note, the change or a zero note)")
        // The credit's label (in its ciphertext) names the nullifier the proof publishes.
        try require(credit == nil || credit!.nullifier == w.crNF, "the credit's move key is not its nullifier")
        return StakeProof(proof: proof, anchor: anchor.bytes, nullifiers: w.nullifiers.map(\.bytes),
                          commitment: w.commitment.bytes, ciphertext: out.ciphertext,
                          creditNullifier: w.crNF.bytes, creditCommitment: w.crCM.bytes, creditCiphertext: credit?.out.ciphertext ?? Data(),
                          clearBefore: clear.clearBefore, debtRoot: clear.debtRoot.bytes,
                          groundworksTags: w.gw.map(\.bytes), creditGroundworksTag: w.crGW.bytes,
                          voteTag: w.gwOut.bytes, voteWeight: w.wOut, creditVoteTag: w.crGWOut.bytes, creditVoteWeight: w.crWOut,
                          pendingKey: w.pKey.bytes, pendingTime: w.pTime, pendingExposed: w.pEx)
    }

    /// A created stake note of `amount` `denom` back to `keys` with `label`, and its stake ciphertext.
    public static func out(_ keys: PrivacyKeys, denom: String, amount: UInt64, label: StakeLabel? = nil) throws -> Out {
        let rho = NotePlaintext.randomField()
        let rcm = NotePlaintext.randomField()
        let o = NoteCipher.StakeOpening(asset: PrivacyHash.assetID(denom), amount: amount, rho: rho, rcm: rcm, label: label)
        let cm = o.cm(ownerPK: keys.ownerPK)
        return Out(amount: amount, rho: rho, rcm: rcm, label: label, ciphertext: try NoteCipher.encryptStake(o, ekPub: keys.ekPub, cm: cm))
    }

    /// The credit lane of a redelegation into `denom`: `spend` (our
    /// unlabelled note there) or a fresh padding input, merged with `vIn` and
    /// labelled (move key = the lane's own nullifier, `moveTime`, exposed = vIn).
    public static func credit(_ keys: PrivacyKeys, denom: String, spend: OwnedStakeNote?, path: [Fr]?, vIn: UInt64, moveTime: UInt64) throws -> Credit {
        try require(vIn > 0 && moveTime > 0, "a credit names its amount and move time")
        try require(spend == nil || (spend!.label == nil && path != nil), "the credit lane merges only into an unlabelled note")
        let padRho = NotePlaintext.randomField()
        let padRcm = NotePlaintext.randomField()
        let nf = spend?.nf ?? PrivacyHash.stakeNF(nk: keys.nk, rho: padRho, position: 0)
        let label = StakeLabel(moveKey: nf, moveTime: moveTime, exposed: vIn)
        let (sum, o) = (spend?.amount ?? 0).addingReportingOverflow(vIn)
        try require(!o, "the credit overflows the note")
        return Credit(denom: denom, spend: spend, path: path, vIn: vIn, moveTime: moveTime, padRho: padRho, padRcm: padRcm,
                      out: try out(keys, denom: denom, amount: sum, label: label))
    }
}
