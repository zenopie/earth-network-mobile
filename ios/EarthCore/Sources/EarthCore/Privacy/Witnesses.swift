import BigInt
import Foundation

/// Why the wallet refused to lay out or prove something: a precondition the
/// circuit or the chain would refuse anyway, caught first.
public struct PrivacyError: Swift.Error, LocalizedError, Equatable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
    public var description: String { message }
}

func require(_ ok: Bool, _ message: @autoclosure () -> String) throws {
    if !ok { throw PrivacyError(message()) }
}

/// The action circuit's witness (circuits/action). Ports
/// `privacy/prove/Witnesses.kt`: one spend (a real note with its path, or a
/// value-0 dummy whose path is not checked) and one output, either of any
/// asset, and the value commitment
///
///     cv = s_value*G(s_asset) - o_value*G(o_asset) + rcv*R
///
/// Public inputs, in the chain's order (zk/orchard ShieldedBundle.PublicInputs):
/// anchor, nf, cm_out, cv_x, cv_y, sighash.
public struct ActionWitness: Sendable {
    public let nk: Fr
    public let sAsset: Fr
    public let sValue: UInt64
    public let sRho: Fr
    public let sRcm: Fr
    public let sPos: UInt64
    public let sPath: [Fr]
    public let oAsset: Fr
    public let oValue: UInt64
    public let oPc: Fr
    public let rcv: Fr
    public let anchor: Fr
    public let sighash: Fr
    public let nf: Fr
    public let cmOut: Fr
    public let cv: Grumpkin.Point

    public init(nk: Fr, sAsset: Fr, sValue: UInt64, sRho: Fr, sRcm: Fr, sPos: UInt64, sPath: [Fr], oAsset: Fr, oValue: UInt64,
                oPc: Fr, rcv: Fr, anchor: Fr, sighash: Fr, cv: Grumpkin.Point? = nil) throws {
        try require(sPos <= 0xffff_ffff, "position is a u32")
        try require(sPath.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        self.nk = nk; self.sAsset = sAsset; self.sValue = sValue; self.sRho = sRho; self.sRcm = sRcm; self.sPos = sPos
        self.sPath = sPath; self.oAsset = oAsset; self.oValue = oValue; self.oPc = oPc; self.rcv = rcv; self.anchor = anchor
        self.sighash = sighash
        nf = PrivacyHash.nf(nk: nk, rho: sRho, position: sPos)
        cmOut = PrivacyHash.cm(asset: oAsset, value: oValue, pc: oPc)
        self.cv = cv ?? Grumpkin.valueCommit(assetSpend: sAsset, vSpend: sValue, assetOut: oAsset, vOut: oValue, rcv: rcv)
    }

    /// What the circuit will assert of the spend, checked before spending a second on a proof that cannot verify.
    public func check() throws {
        if sValue != 0 {
            let cm = PrivacyHash.cm(asset: sAsset, value: sValue, pc: PrivacyHash.pc(ownerPK: PrivacyHash.ownerPK(nk), rho: sRho, rcm: sRcm))
            try require(Merkle.rootFromPath(leaf: cm, index: sPos, siblings: sPath) == anchor, "spend not in the note tree at its anchor")
        }
    }

    public func publicInputs() -> [Fr] { [anchor, nf, cmOut, cv.x, cv.y, sighash] }

    public func noirInputs() -> [String: Any] {
        [
            "nk": nk.noir,
            "s_asset": sAsset.noir,
            "s_value": noirHex(sValue),
            "s_rho": sRho.noir,
            "s_rcm": sRcm.noir,
            "s_pos": noirHex(sPos),
            "s_path": sPath.map(\.noir),
            "o_asset": oAsset.noir,
            "o_value": noirHex(oValue),
            "o_pc": oPc.noir,
            "rcv": rcv.noir,
            "anchor": anchor.noir,
            "nf": nf.noir,
            "cm_out": cmOut.noir,
            "cv_x": cv.x.noir,
            "cv_y": cv.y.noir,
            "sighash": sighash.noir,
        ]
    }

    static let inputOrder = ["nk", "s_asset", "s_value", "s_rho", "s_rcm", "s_pos", "s_path", "o_asset", "o_value", "o_pc", "rcv",
                             "anchor", "nf", "cm_out", "cv_x", "cv_y", "sighash"]

    /// The same witness as a nargo Prover.toml, for checking against the circuit off-device.
    public func proverToml() -> String { toml(noirInputs(), order: Self.inputOrder) }
}

/// The stake circuit's witness (circuits/stake): up to two stake notes of one
/// owner spent under `anchor` (amount 0 = none: nf 0, no path), up to two
/// created (amount 0 = none: cm 0), all of `asset`, with
///
///     in_0 + in_1 + v_in == out_0 + out_1 + v_out
///
/// and spc_mint, otag of the same owner. Public inputs, in the chain's order
/// (x/shieldedstaking StakeProof.PublicInputs): anchor, asset, nf_0, nf_1,
/// cm_out_0, cm_out_1, v_in, v_out, spc_mint, otag, sighash.
public struct StakeWitness: Sendable {
    public let nk: Fr
    public let inAmount: [UInt64]
    public let inRho: [Fr]
    public let inRcm: [Fr]
    public let inPos: [UInt64]
    public let inPath: [[Fr]]
    public let outAmount: [UInt64]
    public let outRho: [Fr]
    public let outRcm: [Fr]
    public let mintRho: Fr
    public let mintRcm: Fr
    public let tagSalt: Fr
    public let anchor: Fr
    public let asset: Fr
    public let vIn: UInt64
    public let vOut: UInt64
    public let sighash: Fr
    public let nullifiers: [Fr]
    public let commitments: [Fr]
    public let spcMint: Fr
    public let otag: Fr

    public init(nk: Fr, inAmount: [UInt64], inRho: [Fr], inRcm: [Fr], inPos: [UInt64], inPath: [[Fr]], outAmount: [UInt64],
                outRho: [Fr], outRcm: [Fr], mintRho: Fr, mintRcm: Fr, tagSalt: Fr, anchor: Fr, asset: Fr, vIn: UInt64, vOut: UInt64,
                sighash: Fr) throws {
        try require([inAmount.count, inRho.count, inRcm.count, inPos.count, inPath.count, outAmount.count, outRho.count, outRcm.count]
            .allSatisfy { $0 == 2 }, "a stake proof has two input and two output slots")
        try require(inPath.allSatisfy { $0.count == Merkle.depth }, "a path is \(Merkle.depth) siblings")
        try require(inPos.allSatisfy { $0 <= 0xffff_ffff }, "position is a u32")
        self.nk = nk; self.inAmount = inAmount; self.inRho = inRho; self.inRcm = inRcm; self.inPos = inPos; self.inPath = inPath
        self.outAmount = outAmount; self.outRho = outRho; self.outRcm = outRcm; self.mintRho = mintRho; self.mintRcm = mintRcm
        self.tagSalt = tagSalt; self.anchor = anchor; self.asset = asset; self.vIn = vIn; self.vOut = vOut; self.sighash = sighash
        let opk = PrivacyHash.ownerPK(nk)
        nullifiers = (0 ..< 2).map { inAmount[$0] == 0 ? .zero : PrivacyHash.stakeNF(nk: nk, rho: inRho[$0], position: inPos[$0]) }
        commitments = (0 ..< 2).map {
            outAmount[$0] == 0 ? .zero : PrivacyHash.stakeCM(asset: asset, amount: outAmount[$0],
                                                             spc: PrivacyHash.stakePC(ownerPK: opk, rho: outRho[$0], rcm: outRcm[$0]))
        }
        spcMint = PrivacyHash.stakePC(ownerPK: opk, rho: mintRho, rcm: mintRcm)
        otag = PrivacyHash.ownerTag(ownerPK: opk, salt: tagSalt)
    }

    public func check() throws {
        let opk = PrivacyHash.ownerPK(nk)
        for i in 0 ..< 2 where inAmount[i] != 0 {
            let cm = PrivacyHash.stakeCM(asset: asset, amount: inAmount[i], spc: PrivacyHash.stakePC(ownerPK: opk, rho: inRho[i], rcm: inRcm[i]))
            try require(Merkle.rootFromPath(leaf: cm, index: inPos[i], siblings: inPath[i]) == anchor,
                        "stake input \(i) not in the stake tree at its anchor")
        }
        let ins = inAmount.reduce(BigUInt(vIn)) { $0 + BigUInt($1) }
        let outs = outAmount.reduce(BigUInt(vOut)) { $0 + BigUInt($1) }
        try require(ins == outs, "stake amounts do not balance")
    }

    public func publicInputs() -> [Fr] {
        [anchor, asset] + nullifiers + commitments + [PrivacyHash.u64(vIn), PrivacyHash.u64(vOut), spcMint, otag, sighash]
    }

    public func noirInputs() -> [String: Any] {
        [
            "nk": nk.noir,
            "in_amount": inAmount.map(noirHex),
            "in_rho": inRho.map(\.noir),
            "in_rcm": inRcm.map(\.noir),
            "in_pos": inPos.map(noirHex),
            "in_path": inPath.map { $0.map(\.noir) },
            "out_amount": outAmount.map(noirHex),
            "out_rho": outRho.map(\.noir),
            "out_rcm": outRcm.map(\.noir),
            "mint_rho": mintRho.noir,
            "mint_rcm": mintRcm.noir,
            "tag_salt": tagSalt.noir,
            "anchor": anchor.noir,
            "asset": asset.noir,
            "nf_0": nullifiers[0].noir,
            "nf_1": nullifiers[1].noir,
            "cm_out_0": commitments[0].noir,
            "cm_out_1": commitments[1].noir,
            "v_in": noirHex(vIn),
            "v_out": noirHex(vOut),
            "spc_mint": spcMint.noir,
            "otag": otag.noir,
            "sighash": sighash.noir,
        ]
    }

    static let inputOrder = ["nk", "in_amount", "in_rho", "in_rcm", "in_pos", "in_path", "out_amount", "out_rho", "out_rcm", "mint_rho",
                             "mint_rcm", "tag_salt", "anchor", "asset", "nf_0", "nf_1", "cm_out_0", "cm_out_1", "v_in", "v_out",
                             "spc_mint", "otag", "sighash"]

    public func proverToml() -> String { toml(noirInputs(), order: Self.inputOrder) }
}

/// The membership circuit's witness (circuits/membership). Public inputs in
/// the chain's order (personhood MembershipPublicInputs): root, scope,
/// nullifier, signal, excluded_dsc, excluded_country, max_activation,
/// max_predecessor. A bound of PrivacyHash.noBound (2^63 - 1) bounds nothing.
public struct MembershipWitness: Sendable {
    public let idSecret: Fr
    public let dscKey: Fr
    public let country: Fr
    public let activatedAt: UInt64
    /// The leaf's predecessor_at: its switch or re-entry time, 0 for a passport never registered before.
    public let predecessorAt: UInt64
    public let leafIndex: UInt64
    public let siblings: [Fr]
    public let root: Fr
    public let scope: Fr
    public let signal: Fr
    public let excludedDsc: Fr
    public let excludedCountry: Fr
    public let maxActivation: UInt64
    public let maxPredecessor: UInt64
    public let nullifier: Fr

    public init(idSecret: Fr, dscKey: Fr, country: Fr, activatedAt: UInt64, predecessorAt: UInt64, leafIndex: UInt64, siblings: [Fr], root: Fr,
                scope: Fr, signal: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: UInt64, maxPredecessor: UInt64) throws {
        try require(siblings.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        try require(leafIndex <= 0xffff_ffff, "leaf index is a u32")
        try require([activatedAt, predecessorAt, maxActivation, maxPredecessor].allSatisfy { $0 <= PrivacyHash.noBound }, "a bound is a u64 below 2^63")
        self.idSecret = idSecret; self.dscKey = dscKey; self.country = country; self.activatedAt = activatedAt
        self.predecessorAt = predecessorAt
        self.leafIndex = leafIndex; self.siblings = siblings; self.root = root; self.scope = scope; self.signal = signal
        self.excludedDsc = excludedDsc; self.excludedCountry = excludedCountry; self.maxActivation = maxActivation
        self.maxPredecessor = maxPredecessor
        nullifier = PrivacyHash.scopeNullifier(idSecret: idSecret, scope: scope)
    }

    public var leaf: Fr {
        PrivacyHash.identityLeaf(idc: PrivacyHash.idc(idSecret), dscKey: dscKey, country: country, activatedAt: activatedAt, predecessorAt: predecessorAt)
    }

    /// What the circuit will assert, checked before spending seconds on a proof that cannot verify.
    public func check() throws {
        try require(Merkle.rootFromPath(leaf: leaf, index: leafIndex, siblings: siblings) == root, "identity leaf is not in the tree at this root")
        try require(dscKey != excludedDsc, "this registration's document signer is excluded from this ballot")
        try require(excludedCountry.isZero || country != excludedCountry, "this registration's country is excluded from this ballot")
        try require(activatedAt <= maxActivation, "this identity was activated too recently for this action")
        try require(predecessorAt <= maxPredecessor, "this identity replaced another too recently for this action")
    }

    public func publicInputs() -> [Fr] {
        [root, scope, nullifier, signal, excludedDsc, excludedCountry, PrivacyHash.u64(maxActivation), PrivacyHash.u64(maxPredecessor)]
    }

    public func noirInputs() -> [String: Any] {
        [
            "id_secret": idSecret.noir,
            "dsc_key": dscKey.noir,
            "country": country.noir,
            "activated_at": noirHex(activatedAt),
            "predecessor_at": noirHex(predecessorAt),
            "leaf_index": noirHex(leafIndex),
            "siblings": siblings.map(\.noir),
            "root": root.noir,
            "scope": scope.noir,
            "nullifier": nullifier.noir,
            "signal": signal.noir,
            "excluded_dsc": excludedDsc.noir,
            "excluded_country": excludedCountry.noir,
            "max_activation": noirHex(maxActivation),
            "max_predecessor": noirHex(maxPredecessor),
        ]
    }

    static let inputOrder = ["id_secret", "dsc_key", "country", "activated_at", "predecessor_at", "leaf_index", "siblings", "root", "scope",
                             "nullifier", "signal", "excluded_dsc", "excluded_country", "max_activation", "max_predecessor"]

    public func proverToml() -> String { toml(noirInputs(), order: Self.inputOrder) }
}

/// One used slot of a vote witness: a derth stake note under the proposal's
/// snapshot note root and the low leaf proving its spend nullifier absent
/// from the snapshot stake nullifier tree.
public struct VoteSlot: Sendable {
    public let amount: UInt64
    public let rho: Fr
    public let rcm: Fr
    public let pos: UInt64
    public let path: [Fr]
    public let low: IndexedTree.Witness

    public init(amount: UInt64, rho: Fr, rcm: Fr, pos: UInt64, path: [Fr], low: IndexedTree.Witness) throws {
        try require(path.count == Merkle.depth && low.lowPath.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        try require(pos <= 0xffff_ffff && low.lowIndex <= 0xffff_ffff && low.lowNextIndex <= 0xffff_ffff, "a u32")
        try require(amount > 0, "a used slot holds a note")
        self.amount = amount; self.rho = rho; self.rcm = rcm; self.pos = pos; self.path = path; self.low = low
    }
}

/// The vote circuit's witness (circuits/vote, ORCHARD_DESIGN 18.2): up to
/// `maxNotes` derth stake notes of one owner (one nk) at one validator, each
/// under the proposal's snapshot note root with its spend nullifier absent
/// from the snapshot stake nullifier tree, and one weight, 0 < weight <=
/// their sum. `slots` are the used slots, in order; the rest are unused
/// (amount 0, vote nullifier 0, every other field 0). Public inputs in the
/// chain's order (MsgStakeVote.VotePublicInputs): note_root, nf_root, asset,
/// weight, proposal_id, vnf[0..3], sighash.
public struct VoteWitness: Sendable {
    /// circuits/vote MAX_NOTES: the most notes one vote proof carries.
    public static let maxNotes = 4
    public let nk: Fr
    public let slots: [VoteSlot]
    public let noteRoot: Fr
    public let nfRoot: Fr
    public let asset: Fr
    public let weight: UInt64
    public let proposalID: UInt64
    public let sighash: Fr
    /// The used slots' spend nullifiers: private, never published by a vote.
    public let spendNFs: [Fr]
    /// Every slot's vote nullifier: the used ones', then 0 for each unused slot.
    public let vnfs: [Fr]

    public init(nk: Fr, slots: [VoteSlot], noteRoot: Fr, nfRoot: Fr, asset: Fr, weight: UInt64, proposalID: UInt64, sighash: Fr) throws {
        try require((1 ... Self.maxNotes).contains(slots.count), "a vote carries 1..\(Self.maxNotes) notes")
        self.nk = nk; self.slots = slots; self.noteRoot = noteRoot; self.nfRoot = nfRoot; self.asset = asset; self.weight = weight
        self.proposalID = proposalID; self.sighash = sighash
        spendNFs = slots.map { PrivacyHash.stakeNF(nk: nk, rho: $0.rho, position: $0.pos) }
        vnfs = slots.map { PrivacyHash.voteNF(nk: nk, rho: $0.rho, position: $0.pos, proposalID: proposalID) }
            + Array(repeating: Fr.zero, count: Self.maxNotes - slots.count)
    }

    /// What the circuit asserts, checked before spending seconds on a proof that cannot verify.
    public func check() throws {
        let opk = PrivacyHash.ownerPK(nk)
        for (i, sl) in slots.enumerated() {
            let cm = PrivacyHash.stakeCM(asset: asset, amount: sl.amount, spc: PrivacyHash.stakePC(ownerPK: opk, rho: sl.rho, rcm: sl.rcm))
            try require(Merkle.rootFromPath(leaf: cm, index: sl.pos, siblings: sl.path) == noteRoot, "stake note \(i) is not under the snapshot root")
            try require(sl.low.proves(spendNFs[i], root: nfRoot), "stake note \(i) was spent before the snapshot")
        }
        try require(Set(vnfs.prefix(slots.count)).count == slots.count, "the same note twice")
        try require(weight != 0, "zero vote weight")
        // The sum as the circuit takes it (u128): four notes of up to 2^63-1 overflow a u64.
        let sum = slots.reduce(BigUInt(0)) { $0 + BigUInt($1.amount) }
        try require(BigUInt(weight) <= sum, "the vote weighs more than its notes")
    }

    public func publicInputs() -> [Fr] {
        [noteRoot, nfRoot, asset, PrivacyHash.u64(weight), PrivacyHash.u64(proposalID)] + vnfs + [sighash]
    }

    public func noirInputs() -> [String: Any] {
        let zero = Fr.zero.noir
        let zeros = Array(repeating: zero, count: Merkle.depth)
        func slot<T>(_ i: Int, _ used: (VoteSlot) -> T, _ unused: T) -> T { i < slots.count ? used(slots[i]) : unused }
        let idx = 0 ..< Self.maxNotes
        return [
            "nk": nk.noir,
            "amount": idx.map { slot($0, { noirHex($0.amount) }, "0x0") },
            "rho": idx.map { slot($0, { $0.rho.noir }, zero) },
            "rcm": idx.map { slot($0, { $0.rcm.noir }, zero) },
            "pos": idx.map { slot($0, { noirHex($0.pos) }, "0x0") },
            "path": idx.map { slot($0, { $0.path.map(\.noir) }, zeros) },
            "low_value": idx.map { slot($0, { $0.low.lowValue.noir }, zero) },
            "low_next_value": idx.map { slot($0, { $0.low.lowNextValue.noir }, zero) },
            "low_next_index": idx.map { slot($0, { noirHex($0.low.lowNextIndex) }, "0x0") },
            "low_index": idx.map { slot($0, { noirHex($0.low.lowIndex) }, "0x0") },
            "low_path": idx.map { slot($0, { $0.low.lowPath.map(\.noir) }, zeros) },
            "note_root": noteRoot.noir,
            "nf_root": nfRoot.noir,
            "asset": asset.noir,
            "weight": noirHex(weight),
            "proposal_id": noirHex(proposalID),
            "vnf": vnfs.map(\.noir),
            "sighash": sighash.noir,
        ]
    }

    static let inputOrder = ["nk", "amount", "rho", "rcm", "pos", "path", "low_value", "low_next_value", "low_next_index", "low_index",
                             "low_path", "note_root", "nf_root", "asset", "weight", "proposal_id", "vnf", "sighash"]

    public func proverToml() -> String { toml(noirInputs(), order: Self.inputOrder) }
}

func noirHex(_ v: UInt64) -> String { "0x" + String(v, radix: 16) }

private func toml(_ m: [String: Any], order: [String]) -> String {
    func v(_ x: Any) -> String {
        switch x {
        case let s as String: return "\"\(s)\""
        case let a as [Any]: return "[" + a.map(v).joined(separator: ", ") + "]"
        default: return "\"\(x)\""
        }
    }
    return order.map { "\($0) = \(v(m[$0]!))\n" }.joined()
}
