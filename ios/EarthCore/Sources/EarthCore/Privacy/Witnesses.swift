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

/// One transfer input: a real note (with its path), or a value-0 dummy.
public struct TransferInput: Equatable, Sendable {
    public let value: UInt64
    public let rho: Fr
    public let rcm: Fr
    public let position: UInt64
    public let path: [Fr]

    public init(value: UInt64, rho: Fr, rcm: Fr, position: UInt64, path: [Fr]) throws {
        try require(position <= 0xffff_ffff, "position is a u32")
        try require(path.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        self.value = value; self.rho = rho; self.rcm = rcm; self.position = position; self.path = path
    }

    /// A dummy: value 0, so the circuit skips its membership, and a fresh
    /// rho, so its nullifier (which is still published and spent) is new.
    public static func dummy() -> TransferInput {
        try! TransferInput(value: 0, rho: NotePlaintext.randomField(), rcm: NotePlaintext.randomField(), position: 0,
                           path: Array(repeating: .zero, count: Merkle.depth))
    }
}

/// One transfer output: a value to a pc (the recipient's hidden owner).
public struct TransferOutput: Equatable, Sendable {
    public let value: UInt64
    public let pc: Fr
    public init(value: UInt64, pc: Fr) { self.value = value; self.pc = pc }
}

/// The transfer circuit's witness (circuits/transfer). Ports
/// `privacy/prove/Witnesses.kt`. Slots 0-1 one hidden asset A, slot 2 ERTH
/// for the fee. For A != ERTH two balances:
///
///     in0 + in1 == out0 + out1 + v_pub_out    (asset A)
///     in2       == out2 + fee                 (ERTH)
///
/// For A == ERTH every slot is ERTH and the circuit enforces one combined
/// balance, so a single ERTH note can pay both a spend and its fee:
///
///     in0 + in1 + in2 == out0 + out1 + out2 + v_pub_out + fee
///
/// Public inputs, in the chain's order (types.Transfer.PublicInputs): root,
/// nf[3], cm_out[3], fee, v_pub_out, asset_pub, signal. asset_pub is A when
/// value leaves the pool and 0 otherwise; the chain requires the 0.
public struct TransferWitness: Sendable {
    public let asset: Fr
    public let nk: Fr
    public let inputs: [TransferInput]
    public let outputs: [TransferOutput]
    public let root: Fr
    public let fee: UInt64
    public let vPubOut: UInt64
    public let signal: Fr
    public let nullifiers: [Fr]
    public let commitments: [Fr]

    public init(asset: Fr, nk: Fr, inputs: [TransferInput], outputs: [TransferOutput], root: Fr, fee: UInt64, vPubOut: UInt64, signal: Fr) throws {
        try require(inputs.count == 3 && outputs.count == 3, "a transfer has three inputs and three outputs")
        // Sums in 128 bits: the circuit range-checks each value to 64.
        func sum(_ xs: [UInt64]) -> (UInt64, Bool) { xs.reduce((0, false)) { acc, x in let r = acc.0.addingReportingOverflow(x); return (r.partialValue, acc.1 || r.overflow) } }
        if asset == PrivacyHash.assetErth {
            let (i, io) = sum(inputs.map(\.value))
            let (o, oo) = sum(outputs.map(\.value) + [vPubOut, fee])
            try require(!io && !oo && i == o, "ERTH unbalanced")
        } else {
            let (i, io) = sum([inputs[0].value, inputs[1].value])
            let (o, oo) = sum([outputs[0].value, outputs[1].value, vPubOut])
            try require(!io && !oo && i == o, "asset A unbalanced")
            let (f, fo) = sum([outputs[2].value, fee])
            try require(!fo && inputs[2].value == f, "fee slot unbalanced")
        }
        self.asset = asset; self.nk = nk; self.inputs = inputs; self.outputs = outputs
        self.root = root; self.fee = fee; self.vPubOut = vPubOut; self.signal = signal
        nullifiers = inputs.map { PrivacyHash.nf(nk: nk, rho: $0.rho, position: $0.position) }
        let assets = [asset, asset, PrivacyHash.assetErth]
        commitments = outputs.enumerated().map { PrivacyHash.cm(asset: assets[$0.offset], value: $0.element.value, pc: $0.element.pc) }
    }

    public var assets: [Fr] { [asset, asset, PrivacyHash.assetErth] }
    public var assetPub: Fr { vPubOut > 0 ? asset : .zero }

    public func publicInputs() -> [Fr] {
        [root] + nullifiers + commitments + [PrivacyHash.u64(fee), PrivacyHash.u64(vPubOut), assetPub, signal]
    }

    /// The prover's input map: every scalar a "0x" hex string, arrays as lists.
    public func noirInputs() -> [String: Any] {
        [
            "asset": asset.noir,
            "nk": nk.noir,
            "in_value": inputs.map { noirHex($0.value) },
            "in_rho": inputs.map(\.rho.noir),
            "in_rcm": inputs.map(\.rcm.noir),
            "in_pos": inputs.map { noirHex($0.position) },
            "in_path": inputs.map { $0.path.map(\.noir) },
            "out_value": outputs.map { noirHex($0.value) },
            "out_pc": outputs.map(\.pc.noir),
            "root": root.noir,
            "nf": nullifiers.map(\.noir),
            "cm_out": commitments.map(\.noir),
            "fee": noirHex(fee),
            "v_pub_out": noirHex(vPubOut),
            "asset_pub": assetPub.noir,
            "signal": signal.noir,
        ]
    }

    static let inputOrder = ["asset", "nk", "in_value", "in_rho", "in_rcm", "in_pos", "in_path", "out_value", "out_pc",
                             "root", "nf", "cm_out", "fee", "v_pub_out", "asset_pub", "signal"]

    /// The same witness as a nargo Prover.toml, for checking against the circuit off-device.
    public func proverToml() -> String { toml(noirInputs(), order: Self.inputOrder) }
}

/// The membership circuit's witness (circuits/membership). Public inputs in
/// the chain's order (personhood MembershipPublicInputs): root, scope,
/// nullifier, signal, excluded_dsc, excluded_country, max_activation.
public struct MembershipWitness: Sendable {
    public let idSecret: Fr
    public let dscKey: Fr
    public let country: Fr
    public let activatedAt: UInt64
    public let leafIndex: UInt64
    public let siblings: [Fr]
    public let root: Fr
    public let scope: Fr
    public let signal: Fr
    public let excludedDsc: Fr
    public let excludedCountry: Fr
    public let maxActivation: UInt64
    public let nullifier: Fr

    public init(idSecret: Fr, dscKey: Fr, country: Fr, activatedAt: UInt64, leafIndex: UInt64, siblings: [Fr], root: Fr,
                scope: Fr, signal: Fr, excludedDsc: Fr, excludedCountry: Fr, maxActivation: UInt64) throws {
        try require(siblings.count == Merkle.depth, "a path is \(Merkle.depth) siblings")
        try require(leafIndex <= 0xffff_ffff, "leaf index is a u32")
        self.idSecret = idSecret; self.dscKey = dscKey; self.country = country; self.activatedAt = activatedAt
        self.leafIndex = leafIndex; self.siblings = siblings; self.root = root; self.scope = scope; self.signal = signal
        self.excludedDsc = excludedDsc; self.excludedCountry = excludedCountry; self.maxActivation = maxActivation
        nullifier = PrivacyHash.scopeNullifier(idSecret: idSecret, scope: scope)
    }

    public var leaf: Fr {
        PrivacyHash.identityLeaf(idc: PrivacyHash.idc(idSecret), dscKey: dscKey, country: country, activatedAt: activatedAt)
    }

    /// What the circuit will assert, checked before spending seconds on a proof that cannot verify.
    public func check() throws {
        try require(Merkle.rootFromPath(leaf: leaf, index: leafIndex, siblings: siblings) == root, "identity leaf is not in the tree at this root")
        try require(dscKey != excludedDsc, "this registration's document signer is excluded from this ballot")
        try require(excludedCountry.isZero || country != excludedCountry, "this registration's country is excluded from this ballot")
        try require(activatedAt <= maxActivation, "this identity was activated too recently for this action")
    }

    public func publicInputs() -> [Fr] {
        [root, scope, nullifier, signal, excludedDsc, excludedCountry, PrivacyHash.u64(maxActivation)]
    }

    public func noirInputs() -> [String: Any] {
        [
            "id_secret": idSecret.noir,
            "dsc_key": dscKey.noir,
            "country": country.noir,
            "activated_at": noirHex(activatedAt),
            "leaf_index": noirHex(leafIndex),
            "siblings": siblings.map(\.noir),
            "root": root.noir,
            "scope": scope.noir,
            "nullifier": nullifier.noir,
            "signal": signal.noir,
            "excluded_dsc": excludedDsc.noir,
            "excluded_country": excludedCountry.noir,
            "max_activation": noirHex(maxActivation),
        ]
    }

    static let inputOrder = ["id_secret", "dsc_key", "country", "activated_at", "leaf_index", "siblings", "root", "scope",
                             "nullifier", "signal", "excluded_dsc", "excluded_country", "max_activation"]

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
