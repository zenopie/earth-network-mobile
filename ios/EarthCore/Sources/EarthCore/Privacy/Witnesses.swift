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

/// One stake input slot: a note of this owner (amount > 0) under the anchor, or padding (amount 0).
public struct StakeIn: Sendable, Equatable {
    public let amount: UInt64
    public let rho: Fr
    public let rcm: Fr
    public let pos: UInt64
    public let path: [Fr]
    /// Its label (lane A only; lane B inputs are unlabelled).
    public let label: StakeLabel?
    /// For amount 0: publish its own would-be nullifier (padding) rather than 0 (no input).
    public let pad: Bool

    public init(amount: UInt64, rho: Fr, rcm: Fr, pos: UInt64, path: [Fr], label: StakeLabel? = nil, pad: Bool = false) throws {
        try require(path.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        try require(pos <= 0xffff_ffff, "position is a u32")
        try require(amount <= UInt64(Int64.max), "a stake amount is at most 2^63-1")
        try require(label == nil || (amount > 0 && label!.exposed <= amount), "a label sits in a note holding its exposure")
        self.amount = amount; self.rho = rho; self.rcm = rcm; self.pos = pos; self.path = path; self.label = label; self.pad = pad
    }

    static let zeroPath = [Fr](repeating: .zero, count: Merkle.depth)

    /// No input: nullifier 0.
    public static func none(rho: Fr, rcm: Fr) -> StakeIn { try! StakeIn(amount: 0, rho: rho, rcm: rcm, pos: 0, path: zeroPath) }

    /// Padding: an amount-0 input at position 0 publishing its own nullifier (a first delegation looks like a top-up).
    public static func padding(rho: Fr, rcm: Fr) -> StakeIn { try! StakeIn(amount: 0, rho: rho, rcm: rcm, pos: 0, path: zeroPath, pad: true) }
}

/// The stake circuit's witness (circuits/stake v2, ORCHARD_DESIGN 4.1).
/// Ports `privacy/prove/Witnesses.kt`: two lanes of one owner (nk), every
/// real input under `anchor`.
///
/// Lane A (`asset`): up to two inputs (at most one labelled), one output;
/// `vIn` credited, `vOut` leaving. A labelled input either keeps its label
/// (the output carries it and its exposure; only unexposed value moves) or,
/// with `clear`, clears it once its move_time < `clearBefore` at what the
/// debt tree under `debtRoot` says it is worth (`debt`):
///
///     in_0 + in_1 - exposed + retained + v_in == out - out_exposed + v_out
///
/// Lane B (`crAsset`, the credit lane): one unlabelled input or padding, one
/// output = cr_in + `crVIn`, labelled (move key = its own nullifier,
/// `crMoveTime`, exposed = crVIn) when crMoveTime != 0. All zero when the msg
/// credits no second asset.
///
/// An amount-0 output publishes 0 or, with `padOut`, the zero note's
/// commitment (a full exit looks like a partial one).
///
/// Groundworks: every input publishes its tag H(TAG_GW, nk, rho) (as it
/// publishes its nullifier: a padding input its own, no input 0); an output
/// that votes (`vote`, `crVote`) publishes its own tag and its unexposed
/// amount as the weight. Public inputs, in the chain's order
/// (StakeProof.PublicInputs): anchor, asset, nf_0, nf_1, cm_out, v_in,
/// v_out, clear_before, debt_root, cr_asset, cr_nf, cr_cm, cr_v_in,
/// cr_move_time, gw_0, gw_1, cr_gw, gw_out, w_out, cr_gw_out, cr_w_out,
/// sighash.
public struct StakeWitness: Sendable {
    public let nk: Fr
    public let ins: [StakeIn]
    public let outAmount: UInt64
    public let outRho: Fr
    public let outRcm: Fr
    public let padOut: Bool
    public let clear: Bool
    public let debt: DebtTree.Witness
    public let crIn: StakeIn
    public let crOutRho: Fr
    public let crOutRcm: Fr
    public let vote: Bool
    public let crVote: Bool
    public let anchor: Fr
    public let asset: Fr
    public let vIn: UInt64
    public let vOut: UInt64
    public let clearBefore: UInt64
    public let debtRoot: Fr
    public let crAsset: Fr
    public let crVIn: UInt64
    public let crMoveTime: UInt64
    public let sighash: Fr
    public let nullifiers: [Fr]
    public let commitment: Fr
    public let crNF: Fr
    public let crOutAmount: UInt64
    public let crLabel: StakeLabel?
    public let crCM: Fr
    /// The inputs' Groundworks tags (lane A's two, the credit lane's).
    public let gw: [Fr]
    public let crGW: Fr
    /// The outputs' votes: tag and weight, zero and 0 when not voting.
    public let gwOut: Fr
    public let wOut: UInt64
    public let crGWOut: Fr
    public let crWOut: UInt64

    public init(nk: Fr, ins: [StakeIn], outAmount: UInt64, outRho: Fr, outRcm: Fr, padOut: Bool, clear: Bool, debt: DebtTree.Witness,
                crIn: StakeIn, crOutRho: Fr, crOutRcm: Fr, vote: Bool = false, crVote: Bool = false, anchor: Fr, asset: Fr,
                vIn: UInt64, vOut: UInt64, clearBefore: UInt64, debtRoot: Fr, crAsset: Fr, crVIn: UInt64, crMoveTime: UInt64, sighash: Fr) throws {
        try require(ins.count == 2, "a stake proof has two lane A input slots")
        try require(crIn.label == nil, "the credit lane merges only into an unlabelled note")
        try require(debt.lowPath.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        try require(debt.lowIndex <= 0xffff_ffff && debt.lowNextIndex <= 0xffff_ffff, "a u32")
        try require(outAmount <= UInt64(Int64.max), "a stake amount is at most 2^63-1")
        let (crOut, overflow) = crIn.amount.addingReportingOverflow(crVIn)
        try require(!overflow, "the credit lane's note overflows")
        self.nk = nk; self.ins = ins; self.outAmount = outAmount; self.outRho = outRho; self.outRcm = outRcm; self.padOut = padOut
        self.clear = clear; self.debt = debt; self.crIn = crIn; self.crOutRho = crOutRho; self.crOutRcm = crOutRcm
        self.vote = vote; self.crVote = crVote
        self.anchor = anchor; self.asset = asset; self.vIn = vIn; self.vOut = vOut; self.clearBefore = clearBefore; self.debtRoot = debtRoot
        self.crAsset = crAsset; self.crVIn = crVIn; self.crMoveTime = crMoveTime; self.sighash = sighash
        let opk = PrivacyHash.ownerPK(nk)
        func nf(_ i: StakeIn) -> Fr { i.amount != 0 || i.pad ? PrivacyHash.stakeNF(nk: nk, rho: i.rho, position: i.pos) : .zero }
        func tag(_ i: StakeIn) -> Fr { i.amount != 0 || i.pad ? PrivacyHash.stakeGW(nk: nk, rho: i.rho) : .zero }
        nullifiers = ins.map(nf)
        gw = ins.map(tag)
        crGW = tag(crIn)
        let labelled = ins.compactMap(\.label).first
        let outLabel = clear ? nil : labelled
        commitment = outAmount == 0 && !padOut ? .zero
            : PrivacyHash.stakeCM(asset: asset, amount: outAmount, spc: PrivacyHash.stakePC(ownerPK: opk, rho: outRho, rcm: outRcm),
                                  label: StakeLabel.hash(outLabel))
        crNF = nf(crIn)
        crOutAmount = crOut
        crLabel = crMoveTime != 0 && !crNF.isZero && crVIn != 0 ? StakeLabel(moveKey: crNF, moveTime: crMoveTime, exposed: crVIn) : nil
        crCM = crOut == 0 ? .zero
            : PrivacyHash.stakeCM(asset: crAsset, amount: crOut, spc: PrivacyHash.stakePC(ownerPK: opk, rho: crOutRho, rcm: crOutRcm),
                                  label: StakeLabel.hash(crLabel))
        try require(!vote || outAmount != 0, "a padding output cannot vote")
        try require(!crVote || crOut != 0, "a padding output cannot vote")
        gwOut = vote ? PrivacyHash.stakeGW(nk: nk, rho: outRho) : .zero
        wOut = vote ? outAmount - (outLabel?.exposed ?? 0) : 0
        crGWOut = crVote ? PrivacyHash.stakeGW(nk: nk, rho: crOutRho) : .zero
        crWOut = crVote ? (crMoveTime != 0 ? crIn.amount : crOut) : 0
    }

    /// The labelled input, if any (the circuit takes at most one).
    var labelled: StakeLabel? { ins.compactMap(\.label).first }

    /// The output's label: the input's, kept unless cleared.
    public var outLabel: StakeLabel? { clear ? nil : labelled }

    /// What the circuit asserts, checked before spending a second on a proof that cannot verify.
    public func check() throws {
        try require(ins.filter { $0.label != nil }.count <= 1, "two labelled inputs")
        let opk = PrivacyHash.ownerPK(nk)
        for (i, s) in ins.enumerated() where s.amount != 0 {
            let cm = PrivacyHash.stakeCM(asset: asset, amount: s.amount, spc: PrivacyHash.stakePC(ownerPK: opk, rho: s.rho, rcm: s.rcm),
                                         label: StakeLabel.hash(s.label))
            try require(Merkle.rootFromPath(leaf: cm, index: s.pos, siblings: s.path) == anchor, "stake input \(i) not in the stake tree at its anchor")
        }
        let l = labelled
        var retained: UInt64 = 0
        if clear {
            guard let l else { throw PrivacyError("nothing to clear") }
            try require(l.moveTime < clearBefore, "the move's window is still open")
            guard let r = debt.retained(key: l.moveKey, exposed: l.exposed, root: debtRoot) else {
                throw PrivacyError("the debt witness does not read the move under debt_root")
            }
            retained = r
        }
        let outEx = outLabel?.exposed ?? 0
        try require(outAmount >= outEx, "the exposure stays in the note")
        let unexposedIn = ins.reduce(BigUInt(0)) { $0 + BigUInt($1.amount) } - BigUInt(l?.exposed ?? 0) + BigUInt(retained) + BigUInt(vIn)
        let unexposedOut = BigUInt(outAmount - outEx) + BigUInt(vOut)
        try require(unexposedIn == unexposedOut, "stake amounts do not balance")
        if crIn.amount != 0 {
            let cm = PrivacyHash.stakeCM(asset: crAsset, amount: crIn.amount, spc: PrivacyHash.stakePC(ownerPK: opk, rho: crIn.rho, rcm: crIn.rcm), label: .zero)
            try require(Merkle.rootFromPath(leaf: cm, index: crIn.pos, siblings: crIn.path) == anchor, "the credit lane's input is not in the stake tree at its anchor")
        }
        try require(crOutAmount <= UInt64(Int64.max), "the credit lane's note is at most 2^63-1")
        if crMoveTime != 0 { try require(!crNF.isZero && crVIn != 0, "a labelled credit names its move and exposure") }
    }

    public func publicInputs() -> [Fr] {
        [anchor, asset, nullifiers[0], nullifiers[1], commitment, PrivacyHash.u64(vIn), PrivacyHash.u64(vOut),
         PrivacyHash.u64(clearBefore), debtRoot, crAsset, crNF, crCM, PrivacyHash.u64(crVIn), PrivacyHash.u64(crMoveTime),
         gw[0], gw[1], crGW, gwOut, PrivacyHash.u64(wOut), crGWOut, PrivacyHash.u64(crWOut), sighash]
    }

    public func noirInputs() -> [String: Any] {
        [
            "nk": nk.noir,
            "in_amount": ins.map { noirHex($0.amount) },
            "in_rho": ins.map(\.rho.noir),
            "in_rcm": ins.map(\.rcm.noir),
            "in_pos": ins.map { noirHex($0.pos) },
            "in_path": ins.map { $0.path.map(\.noir) },
            "in_move_key": ins.map { ($0.label?.moveKey ?? .zero).noir },
            "in_move_time": ins.map { noirHex($0.label?.moveTime ?? 0) },
            "in_exposed": ins.map { noirHex($0.label?.exposed ?? 0) },
            "out_amount": noirHex(outAmount),
            "out_rho": outRho.noir,
            "out_rcm": outRcm.noir,
            // A bool is a field in the witness map: 0x1 / 0x0 (noirc_abi parses a string as a field).
            "clear": noirHex(clear ? 1 : 0),
            "debt_low_key": debt.lowKey.noir,
            "debt_low_next_key": debt.lowNextKey.noir,
            "debt_low_next_index": noirHex(debt.lowNextIndex),
            "debt_low_retained": noirHex(debt.lowRetained),
            "debt_low_index": noirHex(debt.lowIndex),
            "debt_low_path": debt.lowPath.map(\.noir),
            "cr_in_amount": noirHex(crIn.amount),
            "cr_in_rho": crIn.rho.noir,
            "cr_in_rcm": crIn.rcm.noir,
            "cr_in_pos": noirHex(crIn.pos),
            "cr_in_path": crIn.path.map(\.noir),
            "cr_out_rho": crOutRho.noir,
            "cr_out_rcm": crOutRcm.noir,
            "anchor": anchor.noir,
            "asset": asset.noir,
            "nf_0": nullifiers[0].noir,
            "nf_1": nullifiers[1].noir,
            "cm_out": commitment.noir,
            "v_in": noirHex(vIn),
            "v_out": noirHex(vOut),
            "clear_before": noirHex(clearBefore),
            "debt_root": debtRoot.noir,
            "cr_asset": crAsset.noir,
            "cr_nf": crNF.noir,
            "cr_cm": crCM.noir,
            "cr_v_in": noirHex(crVIn),
            "cr_move_time": noirHex(crMoveTime),
            "gw_0": gw[0].noir,
            "gw_1": gw[1].noir,
            "cr_gw": crGW.noir,
            "gw_out": gwOut.noir,
            "w_out": noirHex(wOut),
            "cr_gw_out": crGWOut.noir,
            "cr_w_out": noirHex(crWOut),
            "sighash": sighash.noir,
        ]
    }

    static let inputOrder = ["nk", "in_amount", "in_rho", "in_rcm", "in_pos", "in_path", "in_move_key", "in_move_time", "in_exposed",
                             "out_amount", "out_rho", "out_rcm", "clear", "debt_low_key", "debt_low_next_key", "debt_low_next_index",
                             "debt_low_retained", "debt_low_index", "debt_low_path", "cr_in_amount", "cr_in_rho", "cr_in_rcm", "cr_in_pos",
                             "cr_in_path", "cr_out_rho", "cr_out_rcm", "anchor", "asset", "nf_0", "nf_1", "cm_out", "v_in",
                             "v_out", "clear_before", "debt_root", "cr_asset", "cr_nf", "cr_cm", "cr_v_in", "cr_move_time",
                             "gw_0", "gw_1", "cr_gw", "gw_out", "w_out", "cr_gw_out", "cr_w_out", "sighash"]

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

/// circuits/move: a handle or caretaker split passes from the identity behind
/// `oldSecret` to the one behind `newSecret`, which succeeded it under the
/// same passport. The succession leaf H(TAG_SUCC, idc_old, idc_new) is at
/// `successionIndex` and the successor's live identity leaf at `leafIndex`,
/// both under `root`; `scope` is the msg's (handle or caretaker) and
/// `signal` its sighash. Public inputs: root, scope, old_nullifier,
/// new_nullifier, signal.
public struct MoveWitness: Sendable {
    public let oldSecret: Fr
    public let newSecret: Fr
    public let successionIndex: UInt64
    public let successionSiblings: [Fr]
    public let dscKey: Fr
    public let country: Fr
    public let activatedAt: UInt64
    public let predecessorAt: UInt64
    public let leafIndex: UInt64
    public let siblings: [Fr]
    public let root: Fr
    public let scope: Fr
    public let signal: Fr
    public let oldNullifier: Fr
    public let newNullifier: Fr

    public init(oldSecret: Fr, newSecret: Fr, successionIndex: UInt64, successionSiblings: [Fr], dscKey: Fr, country: Fr, activatedAt: UInt64,
                predecessorAt: UInt64, leafIndex: UInt64, siblings: [Fr], root: Fr, scope: Fr, signal: Fr) throws {
        try require(successionSiblings.count == Merkle.depth && siblings.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        try require(successionIndex <= 0xffff_ffff && leafIndex <= 0xffff_ffff, "a leaf index is a u32")
        try require(activatedAt <= PrivacyHash.noBound && predecessorAt <= PrivacyHash.noBound, "a time is a u64 below 2^63")
        self.oldSecret = oldSecret; self.newSecret = newSecret; self.successionIndex = successionIndex; self.successionSiblings = successionSiblings
        self.dscKey = dscKey; self.country = country; self.activatedAt = activatedAt; self.predecessorAt = predecessorAt
        self.leafIndex = leafIndex; self.siblings = siblings; self.root = root; self.scope = scope; self.signal = signal
        oldNullifier = PrivacyHash.scopeNullifier(idSecret: oldSecret, scope: scope)
        newNullifier = PrivacyHash.scopeNullifier(idSecret: newSecret, scope: scope)
    }

    public var succession: Fr { PrivacyHash.successionLeaf(idcOld: PrivacyHash.idc(oldSecret), idcNew: PrivacyHash.idc(newSecret)) }

    public var leaf: Fr {
        PrivacyHash.identityLeaf(idc: PrivacyHash.idc(newSecret), dscKey: dscKey, country: country, activatedAt: activatedAt, predecessorAt: predecessorAt)
    }

    /// What the circuit will assert, checked before spending seconds on a proof that cannot verify.
    public func check() throws {
        try require(Merkle.rootFromPath(leaf: succession, index: successionIndex, siblings: successionSiblings) == root,
                    "no succession from this identity to the new one in the tree at this root")
        try require(Merkle.rootFromPath(leaf: leaf, index: leafIndex, siblings: siblings) == root, "the new identity's leaf is not live in the tree at this root")
        try require(oldNullifier != newNullifier, "a move needs two identities")
    }

    public func publicInputs() -> [Fr] { [root, scope, oldNullifier, newNullifier, signal] }

    public func noirInputs() -> [String: Any] {
        [
            "old_secret": oldSecret.noir,
            "new_secret": newSecret.noir,
            "succession_index": noirHex(successionIndex),
            "succession_siblings": successionSiblings.map(\.noir),
            "dsc_key": dscKey.noir,
            "country": country.noir,
            "activated_at": noirHex(activatedAt),
            "predecessor_at": noirHex(predecessorAt),
            "leaf_index": noirHex(leafIndex),
            "siblings": siblings.map(\.noir),
            "root": root.noir,
            "scope": scope.noir,
            "old_nullifier": oldNullifier.noir,
            "new_nullifier": newNullifier.noir,
            "signal": signal.noir,
        ]
    }

    static let inputOrder = ["old_secret", "new_secret", "succession_index", "succession_siblings", "dsc_key", "country", "activated_at",
                             "predecessor_at", "leaf_index", "siblings", "root", "scope", "old_nullifier", "new_nullifier", "signal"]

    public func proverToml() -> String { toml(noirInputs(), order: Self.inputOrder) }
}

/// One used slot of a vote witness: a derth stake note under the proposal's
/// snapshot note root, the low leaf proving its spend nullifier absent from
/// the snapshot stake nullifier tree, and, for a labelled note, the debt tree
/// witness its value is read by (current debt root).
public struct VoteSlot: Sendable {
    public let amount: UInt64
    public let rho: Fr
    public let rcm: Fr
    public let pos: UInt64
    public let path: [Fr]
    public let low: IndexedTree.Witness
    public let label: StakeLabel?
    public let debt: DebtTree.Witness

    public init(amount: UInt64, rho: Fr, rcm: Fr, pos: UInt64, path: [Fr], low: IndexedTree.Witness,
                label: StakeLabel? = nil, debt: DebtTree.Witness = .none) throws {
        try require(path.count == Merkle.depth && low.lowPath.count == Merkle.depth && debt.lowPath.count == Merkle.depth,
                    "a path is \(Merkle.depth) siblings")
        try require(pos <= 0xffff_ffff && low.lowIndex <= 0xffff_ffff && low.lowNextIndex <= 0xffff_ffff, "a u32")
        try require(debt.lowIndex <= 0xffff_ffff && debt.lowNextIndex <= 0xffff_ffff, "a u32")
        try require(amount > 0, "a used slot holds a note")
        try require(label == nil || label!.exposed <= amount, "bad exposure")
        self.amount = amount; self.rho = rho; self.rcm = rcm; self.pos = pos; self.path = path; self.low = low
        self.label = label; self.debt = debt
    }

    /// What the note votes: its amount, less what slashes cut from a label's exposure (nil: the witness reads nothing under `debtRoot`).
    public func value(debtRoot: Fr) -> UInt64? {
        guard let l = label else { return amount }
        guard let r = debt.retained(key: l.moveKey, exposed: l.exposed, root: debtRoot) else { return nil }
        return amount - l.exposed + r
    }
}

/// The vote circuit's witness (circuits/vote v2, ORCHARD_DESIGN 4.2): up to
/// `maxNotes` derth stake notes of one owner (one nk) at one validator, each
/// under the proposal's snapshot note root with its spend nullifier absent
/// from the snapshot stake nullifier tree, and one weight, 0 < weight <= the
/// sum of their values (a labelled note's at the CURRENT `debtRoot`). `slots`
/// are the used slots; `layout` places them and the padding in the circuit's
/// slots (an unused slot: amount 0, rho the padding's r, everything else 0,
/// its vnf the padding nullifier). Public
/// inputs in the chain's order (MsgStakeVote.VotePublicInputs): note_root,
/// nf_root, debt_root, asset, weight, proposal_id, vnf[0..1], sighash.
public struct VoteWitness: Sendable {
    /// circuits/vote MAX_NOTES: the most notes one vote proof carries.
    public static let maxNotes = 2
    public let nk: Fr
    public let slots: [VoteSlot]
    public let layout: VoteLayout
    public let noteRoot: Fr
    public let nfRoot: Fr
    public let debtRoot: Fr
    public let asset: Fr
    public let weight: UInt64
    public let proposalID: UInt64
    public let sighash: Fr
    /// The used slots' spend nullifiers: private, never published by a vote.
    public let spendNFs: [Fr]
    /// Every circuit slot's vote nullifier: a note's, or a padding nullifier (`layout`).
    public let vnfs: [Fr]

    public init(nk: Fr, slots: [VoteSlot], layout: VoteLayout, noteRoot: Fr, nfRoot: Fr, debtRoot: Fr, asset: Fr, weight: UInt64, proposalID: UInt64,
                sighash: Fr) throws {
        try require((1 ... Self.maxNotes).contains(slots.count), "a vote carries 1..\(Self.maxNotes) notes")
        try require(layout.used == slots.count, "the layout places \(layout.used) notes, the vote has \(slots.count)")
        self.nk = nk; self.slots = slots; self.layout = layout; self.noteRoot = noteRoot; self.nfRoot = nfRoot; self.debtRoot = debtRoot; self.asset = asset
        self.weight = weight; self.proposalID = proposalID; self.sighash = sighash
        spendNFs = slots.map { PrivacyHash.stakeNF(nk: nk, rho: $0.rho, position: $0.pos) }
        vnfs = layout.vnfs(nk: nk, slots: slots, proposalID: proposalID)
    }

    /// What the circuit asserts, checked before spending seconds on a proof that cannot verify.
    public func check() throws {
        let opk = PrivacyHash.ownerPK(nk)
        var sum = BigUInt(0)
        for (i, sl) in slots.enumerated() {
            let cm = PrivacyHash.stakeCM(asset: asset, amount: sl.amount, spc: PrivacyHash.stakePC(ownerPK: opk, rho: sl.rho, rcm: sl.rcm),
                                         label: StakeLabel.hash(sl.label))
            try require(Merkle.rootFromPath(leaf: cm, index: sl.pos, siblings: sl.path) == noteRoot, "stake note \(i) is not under the snapshot root")
            try require(sl.low.proves(spendNFs[i], root: nfRoot), "stake note \(i) was spent before the snapshot")
            guard let v = sl.value(debtRoot: debtRoot) else { throw PrivacyError("stake note \(i)'s label is not read under the current debt root") }
            sum += BigUInt(v)
        }
        try require(!vnfs.contains(where: \.isZero) && Set(vnfs).count == vnfs.count, "the same note twice")
        try require(weight != 0, "zero vote weight")
        try require(BigUInt(weight) <= sum, "the vote weighs more than its notes")
    }

    public func publicInputs() -> [Fr] {
        [noteRoot, nfRoot, debtRoot, asset, PrivacyHash.u64(weight), PrivacyHash.u64(proposalID)] + vnfs + [sighash]
    }

    public func noirInputs() -> [String: Any] {
        let zero = Fr.zero.noir
        let zeros = Array(repeating: zero, count: Merkle.depth)
        func slot<T>(_ i: Int, _ used: (VoteSlot) -> T, _ unused: T) -> T { layout.order[i].map { used(slots[$0]) } ?? unused }
        let idx = 0 ..< Self.maxNotes
        return [
            "nk": nk.noir,
            "amount": idx.map { slot($0, { noirHex($0.amount) }, "0x0") },
            "rho": idx.map { i in slot(i, { $0.rho.noir }, layout.padR(i).noir) },
            "rcm": idx.map { slot($0, { $0.rcm.noir }, zero) },
            "pos": idx.map { slot($0, { noirHex($0.pos) }, "0x0") },
            "path": idx.map { slot($0, { $0.path.map(\.noir) }, zeros) },
            "move_key": idx.map { slot($0, { ($0.label?.moveKey ?? .zero).noir }, zero) },
            "move_time": idx.map { slot($0, { noirHex($0.label?.moveTime ?? 0) }, "0x0") },
            "exposed": idx.map { slot($0, { noirHex($0.label?.exposed ?? 0) }, "0x0") },
            "low_value": idx.map { slot($0, { $0.low.lowValue.noir }, zero) },
            "low_next_value": idx.map { slot($0, { $0.low.lowNextValue.noir }, zero) },
            "low_next_index": idx.map { slot($0, { noirHex($0.low.lowNextIndex) }, "0x0") },
            "low_index": idx.map { slot($0, { noirHex($0.low.lowIndex) }, "0x0") },
            "low_path": idx.map { slot($0, { $0.low.lowPath.map(\.noir) }, zeros) },
            "debt_low_key": idx.map { slot($0, { $0.debt.lowKey.noir }, zero) },
            "debt_low_next_key": idx.map { slot($0, { $0.debt.lowNextKey.noir }, zero) },
            "debt_low_next_index": idx.map { slot($0, { noirHex($0.debt.lowNextIndex) }, "0x0") },
            "debt_low_retained": idx.map { slot($0, { noirHex($0.debt.lowRetained) }, "0x0") },
            "debt_low_index": idx.map { slot($0, { noirHex($0.debt.lowIndex) }, "0x0") },
            "debt_low_path": idx.map { slot($0, { $0.debt.lowPath.map(\.noir) }, zeros) },
            "note_root": noteRoot.noir,
            "nf_root": nfRoot.noir,
            "debt_root": debtRoot.noir,
            "asset": asset.noir,
            "weight": noirHex(weight),
            "proposal_id": noirHex(proposalID),
            "vnf": vnfs.map(\.noir),
            "sighash": sighash.noir,
        ]
    }

    static let inputOrder = ["nk", "amount", "rho", "rcm", "pos", "path", "move_key", "move_time", "exposed", "low_value", "low_next_value",
                             "low_next_index", "low_index", "low_path", "debt_low_key", "debt_low_next_key", "debt_low_next_index",
                             "debt_low_retained", "debt_low_index", "debt_low_path", "note_root", "nf_root", "debt_root", "asset", "weight",
                             "proposal_id", "vnf", "sighash"]

    public func proverToml() -> String { toml(noirInputs(), order: Self.inputOrder) }
}

/// Where a vote's notes and padding sit in the circuit's `VoteWitness.maxNotes`
/// slots: `order` gives each slot's used-note index, or nil for padding, whose
/// r values are `pads` in order. Every vote publishes maxNotes non-zero vote
/// nullifiers; a padding nullifier H(TAG_VPAD, nk, r, proposal_id) looks like
/// a note's, so the number of notes voted is hidden. The wallet draws r fresh
/// at random and puts the padding in a random slot (`random`).
public struct VoteLayout: Sendable, Equatable {
    public let order: [Int?]
    public let pads: [Fr]

    public init(order: [Int?], pads: [Fr]) throws {
        try require(order.count == VoteWitness.maxNotes, "a vote has \(VoteWitness.maxNotes) slots")
        try require(order.filter { $0 == nil }.count == pads.count, "one padding value per unused slot")
        let used = order.compactMap { $0 }
        try require(used.sorted() == Array(0 ..< used.count), "each note in one slot")
        try require(!pads.contains(where: \.isZero), "a padding value is random")
        self.order = order; self.pads = pads
    }

    /// How many notes the layout places.
    public var used: Int { order.compactMap { $0 }.count }

    /// The padding r of circuit slot `i` (0 for a note's slot).
    public func padR(_ i: Int) -> Fr { order[i] != nil ? .zero : pads[order.prefix(i).filter { $0 == nil }.count] }

    public func vnfs(nk: Fr, slots: [VoteSlot], proposalID: UInt64) -> [Fr] {
        order.indices.map { i in
            if let j = order[i] { return PrivacyHash.voteNF(nk: nk, rho: slots[j].rho, position: slots[j].pos, proposalID: proposalID) }
            return PrivacyHash.votePadNF(nk: nk, r: padR(i), proposalID: proposalID)
        }
    }

    /// `used` notes and fresh random padding, in a random slot order.
    public static func random(used: Int) throws -> VoteLayout {
        try require((1 ... VoteWitness.maxNotes).contains(used), "a vote carries 1..\(VoteWitness.maxNotes) notes")
        let order = (Array(0 ..< used).map { Optional($0) } + Array(repeating: nil, count: VoteWitness.maxNotes - used)).shuffled()
        return try VoteLayout(order: order, pads: (0 ..< VoteWitness.maxNotes - used).map { _ in NotePlaintext.randomField() })
    }

    /// The notes first, then padding with the given r values (tests and fixtures).
    public static func inOrder(used: Int, pads: [Fr]) throws -> VoteLayout {
        try VoteLayout(order: Array(0 ..< used).map { Optional($0) } + Array(repeating: nil, count: VoteWitness.maxNotes - used), pads: pads)
    }
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
