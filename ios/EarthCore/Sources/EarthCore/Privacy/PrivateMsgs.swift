import BigInt
import CryptoKit
import Foundation

// The chain's private msgs: their wire encoding (field numbers from
// android/app/src/main/proto/earth/{shielded,personhood,assembly,
// shieldedstaking,dex}), their type URLs, their bundles, and the sighash
// every proof and binding signature of one binds, ported field for field
// from each msg's Go `SighashFields` and zk/orchard.Sighash via
// `privacy/tx/PrivateMsgs.kt`. PrivateMsgsTests pins every encoding and
// every sighash to the chain's output.
//
// Fields the chain parses as field elements (pcs, idc) must be canonical
// 32-byte values; `Fr(bytes:)` refuses anything else, as the chain does, so
// computing a sighash throws on them.

// MARK: - bundles

/// earth.shielded.v1.Action: one spend paired with one output.
public struct ShieldedAction: ProtoMessage, Equatable, Sendable {
    public var anchor: Data
    public var nullifier: Data
    public var commitment: Data
    public var cv: Data
    public var ciphertext: Data
    public var proof: Data

    public init(anchor: Data, nullifier: Data, commitment: Data, cv: Data, ciphertext: Data, proof: Data) {
        self.anchor = anchor; self.nullifier = nullifier; self.commitment = commitment; self.cv = cv
        self.ciphertext = ciphertext; self.proof = proof
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, anchor)
        w.bytes(2, nullifier)
        w.bytes(3, commitment)
        w.bytes(4, cv)
        w.bytes(5, ciphertext)
        w.bytes(6, proof)
        return w.data
    }

    public static func decode(_ d: Data) throws -> ShieldedAction {
        let f = try ProtoFields(d)
        return ShieldedAction(anchor: f.bytes(1), nullifier: f.bytes(2), commitment: f.bytes(3), cv: f.bytes(4), ciphertext: f.bytes(5), proof: f.bytes(6))
    }
}

/// earth.shielded.v1.ValueBalance: what a bundle releases of one denom.
public struct ValueBalance: ProtoMessage, Equatable, Sendable {
    public var denom: String
    public var amount: UInt64

    public init(denom: String, amount: UInt64) { self.denom = denom; self.amount = amount }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.string(1, denom)
        w.uint64(2, amount)
        return w.data
    }

    public static func decode(_ d: Data) throws -> ValueBalance {
        let f = try ProtoFields(d)
        return ValueBalance(denom: f.string(1), amount: f.uint64(2))
    }
}

/// earth.shielded.v1.ShieldedBundle: actions, the public balance per denom, and the
/// binding signature over the sighash.
public struct ShieldedBundle: ProtoMessage, Equatable, Sendable {
    public var actions: [ShieldedAction]
    public var balances: [ValueBalance]
    public var bindingSig: Data

    public init(actions: [ShieldedAction] = [], balances: [ValueBalance] = [], bindingSig: Data = Data()) {
        self.actions = actions; self.balances = balances; self.bindingSig = bindingSig
    }

    public func balance(_ denom: String) -> UInt64 { balances.first { $0.denom == denom }?.amount ?? 0 }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.repeatedMessage(1, actions)
        w.repeatedMessage(2, balances)
        w.bytes(3, bindingSig)
        return w.data
    }

    public static func decode(_ d: Data) throws -> ShieldedBundle {
        let f = try ProtoFields(d)
        return ShieldedBundle(actions: try f.repeatedMessage(1, ShieldedAction.decode), balances: try f.repeatedMessage(2, ValueBalance.decode),
                      bindingSig: f.bytes(3))
    }
}

/// earth.shieldedstaking.v1.StakeProof: a stake proof's public statement.
public struct StakeProof: ProtoMessage, Equatable, Sendable {
    public var proof: Data
    public var anchor: Data
    public var nullifiers: [Data]
    public var commitments: [Data]
    public var ciphertexts: [Data]
    public var spcMint: Data
    public var ownerTag: Data
    /// The blind stake ciphertext (177 bytes) of the note minted to spc_mint:
    /// required on Delegate, Undelegate, UnlockPosition; empty otherwise.
    public var spcCiphertext: Data

    public init(proof: Data, anchor: Data, nullifiers: [Data], commitments: [Data], ciphertexts: [Data], spcMint: Data, ownerTag: Data,
                spcCiphertext: Data = Data()) {
        self.proof = proof; self.anchor = anchor; self.nullifiers = nullifiers; self.commitments = commitments
        self.ciphertexts = ciphertexts; self.spcMint = spcMint; self.ownerTag = ownerTag; self.spcCiphertext = spcCiphertext
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, proof)
        w.bytes(2, anchor)
        w.repeatedBytes(3, nullifiers)
        w.repeatedBytes(4, commitments)
        w.repeatedBytes(5, ciphertexts)
        w.bytes(6, spcMint)
        w.bytes(7, ownerTag)
        w.bytes(8, spcCiphertext)
        return w.data
    }

    public static func decode(_ d: Data) throws -> StakeProof {
        let f = try ProtoFields(d)
        return StakeProof(proof: f.bytes(1), anchor: f.bytes(2), nullifiers: f.repeatedBytes(3), commitments: f.repeatedBytes(4),
                          ciphertexts: f.repeatedBytes(5), spcMint: f.bytes(6), ownerTag: f.bytes(7), spcCiphertext: f.bytes(8))
    }
}

/// earth.personhood.v1.Membership.
public struct Membership: ProtoMessage, Equatable, Sendable {
    public var proof: Data
    public var root: Data
    public var nullifier: Data

    public init(proof: Data, root: Data, nullifier: Data) {
        self.proof = proof; self.root = root; self.nullifier = nullifier
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, proof)
        w.bytes(2, root)
        w.bytes(3, nullifier)
        return w.data
    }

    public static func decode(_ d: Data) throws -> Membership {
        let f = try ProtoFields(d)
        return Membership(proof: f.bytes(1), root: f.bytes(2), nullifier: f.bytes(3))
    }
}

/// cosmos.gov.v1.WeightedVoteOption. `option` is cosmos.gov.v1.VoteOption
/// (1 yes, 2 abstain, 3 no, 4 no with veto); `weight` a decimal in (0, 1].
public struct WeightedVoteOption: ProtoMessage, Equatable, Sendable {
    public static let yes = 1, abstain = 2, no = 3, noWithVeto = 4
    public var option: Int
    public var weight: String

    public init(option: Int, weight: String) { self.option = option; self.weight = weight }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.enumValue(1, option)
        w.string(2, weight)
        return w.data
    }

    public static func decode(_ d: Data) throws -> WeightedVoteOption {
        let f = try ProtoFields(d)
        return WeightedVoteOption(option: Int(f.uint64(1)), weight: f.string(2))
    }
}

extension Msg.AllocationWeight {
    public static func decode(_ d: Data) throws -> Msg.AllocationWeight {
        let f = try ProtoFields(d)
        return Msg.AllocationWeight(optionID: f.uint64(1), percent: f.uint64(2))
    }
}

/// x/assembly's two-sided ballot option (no abstain).
public enum AssemblyVoteOption: Int, Sendable {
    case yes = 1
    case no = 2
}

// MARK: - the private msg protocol

/// A msg the private ante takes: unsigned, its fee paid from shielded notes.
public protocol PrivateMsg: ProtoMessage {
    static var typeURL: String { get }
    /// Every bundle it spends, in sighash order (PrivateMsg.PrivateBundles).
    var bundles: [ShieldedBundle] { get }
    /// Its stake proof, if it carries one.
    var stakeProof: StakeProof? { get }
    /// The uerth the msg itself moves out of its bundles' uerth balance (a
    /// delegation's amount, a uerth swap's amount in, an LP deposit's ERTH
    /// leg): what the one fee rule does not count as fee.
    var movedUerth: UInt64 { get }
    /// The uerth the bundles pay to fee_collector (PrivateMsg.PrivateFee; the one fee rule by default).
    var privateFee: UInt64 { get }
    /// fee_from_output: only an unbonding claim pays its fee out of what it produces.
    var feeFromOutput: UInt64 { get }
    /// The msg's own fields, bound after the bundle digests (the msg's Go SighashFields).
    func sighashFields() throws -> [Fr]
}

public extension PrivateMsg {
    var stakeProof: StakeProof? { nil }
    var feeFromOutput: UInt64 { 0 }
    var movedUerth: UInt64 { 0 }
    var typeURL: String { Self.typeURL }

    /// The bundles' summed uerth balance (shielded UerthBalance), saturating:
    /// a hostile or broken sum never wraps.
    var uerthBalance: UInt64 {
        bundles.reduce(UInt64(0)) { acc, b in
            b.balances.filter { $0.denom == "uerth" }.reduce(acc) { PrivateMsgs.saturatingAdd($0, $1.amount) }
        }
    }

    /// The uerth the bundles pay to fee_collector (PrivateMsg.PrivateFee):
    /// the one fee rule (shielded FeeAfter), every bundle's uerth balance
    /// less the uerth the msg moves itself, 0 if it moves more. MsgSend
    /// overrides it with its named fee.
    var privateFee: UInt64 {
        let (d, o) = uerthBalance.subtractingReportingOverflow(movedUerth)
        return o ? 0 : d
    }

    /// The whole fee the tx declares (types.TotalFee).
    var totalFee: UInt64 { PrivateMsgs.saturatingAdd(privateFee, feeFromOutput) }
    func asAny() -> ProtoAny { asAny(typeURL: Self.typeURL) }

    /// The msg's sighash on `chainID` in a tx with fields `tx` (x/shielded
    /// types.Sighash): Signal(type URL, chain id, K, digest(bundle_0..K-1),
    /// Bytes(memo), timeout_height, gas_limit, fields...).
    func sighash(chainID: String, tx: PrivateMsgs.TxFields) throws -> Fr {
        let bs = bundles
        let txf = [PrivateMsgs.bytes(Data(tx.memo.utf8)), PrivateMsgs.u(tx.timeoutHeight), PrivateMsgs.u(tx.gasLimit)]
        return PrivacyHash.signal(msgType: Self.typeURL, chainID: chainID,
                                  fields: [PrivateMsgs.u(UInt64(bs.count))] + (try bs.map(PrivateMsgs.digest)) + txf + (try sighashFields()))
    }
}

public protocol DecodablePrivateMsg: PrivateMsg {
    static func decodeMsg(_ d: Data) throws -> Self
}

public enum PrivateMsgs {
    public enum Error: Swift.Error, Equatable {
        case shape(String)
        case badWeight(String)
        case badSignal(String)
        case unknownType(String)
    }

    /// The tx fields every private sighash binds (zk/orchard.TxFields): the
    /// body's memo and timeout_height (0: none) and the auth info's gas limit.
    public struct TxFields: Equatable, Sendable {
        public var memo: String
        public var timeoutHeight: UInt64
        public var gasLimit: UInt64
        public init(memo: String = "", timeoutHeight: UInt64 = 0, gasLimit: UInt64 = 0) {
            self.memo = memo; self.timeoutHeight = timeoutHeight; self.gasLimit = gasLimit
        }
    }

    /// a + b, clamped at UInt64.max (audit L1: sums of chain-published amounts never trap or wrap).
    public static func saturatingAdd(_ a: UInt64, _ b: UInt64) -> UInt64 {
        let (s, o) = a.addingReportingOverflow(b)
        return o ? .max : s
    }

    /// zk/orchard.TagBundle.
    public static let tagBundle = Fr(BigUInt(Data("earth.bundle".utf8)))

    static func f(_ b: Data) throws -> Fr { try Fr(bytes: b) }
    static func fieldOrZero(_ b: Data?) throws -> Fr { (b == nil || b!.isEmpty) ? .zero : try Fr(bytes: b!) }
    static func bytes(_ b: Data) -> Fr { PrivacyHash.bytes(b) }
    static func bytes(_ s: String) -> Fr { PrivacyHash.bytes(s) }
    static func u(_ v: UInt64) -> Fr { PrivacyHash.u64(v) }

    /// zk/orchard ShieldedBundle.Digest:
    /// H(TAG_BUNDLE, N, [anchor_i, nf_i, cm_i, cvx_i, cvy_i, Bytes(ct_i)]..., M, [AssetID(denom_j), amount_j]...).
    public static func digest(_ b: ShieldedBundle) throws -> Fr {
        var xs: [Fr] = [tagBundle, u(UInt64(b.actions.count))]
        xs.reserveCapacity(3 + 6 * b.actions.count + 2 * b.balances.count)
        for a in b.actions {
            guard a.cv.count == 64 else { throw Error.shape("cv is 64 bytes") }
            let cv = Data(a.cv)
            xs += [try f(a.anchor), try f(a.nullifier), try f(a.commitment), try f(cv.prefix(32)), try f(cv.suffix(32)), bytes(a.ciphertext)]
        }
        xs.append(u(UInt64(b.balances.count)))
        for bal in b.balances { xs += [PrivacyHash.assetID(bal.denom), u(bal.amount)] }
        return Poseidon2.hash(xs)
    }

    /// bvk = sum cv_i - sum value_a * G_a: what `b`'s binding signature verifies under.
    public static func bindingKey(_ b: ShieldedBundle) throws -> Grumpkin.Point {
        var bvk = Grumpkin.Point.infinity
        for a in b.actions { bvk = bvk + (try Grumpkin.Point(bytes: a.cv)) }
        for bal in b.balances { bvk = bvk - Grumpkin.valueBase(PrivacyHash.assetID(bal.denom)) * BigUInt(bal.amount) }
        return bvk
    }

    /// Whether `b`'s binding signature holds over `sighash` (the chain's CheckBalance).
    public static func checkBalance(_ b: ShieldedBundle, sighash: Fr) -> Bool {
        guard let bvk = try? bindingKey(b) else { return false }
        return Grumpkin.verifyBinding(bvk: bvk, sighash: sighash, sig: b.bindingSig)
    }

    /// StakeFields: anchor, nf_0, nf_1, cm_0, cm_1, Bytes(ct_0), Bytes(ct_1),
    /// spc_mint, owner_tag, Bytes(spc_ciphertext) (an absent ciphertext is
    /// Bytes of nothing).
    public static func stakeFields(_ p: StakeProof) throws -> [Fr] {
        func at(_ xs: [Data], _ i: Int) -> Data? { i < xs.count ? xs[i] : nil }
        return [
            try fieldOrZero(p.anchor), try fieldOrZero(at(p.nullifiers, 0)), try fieldOrZero(at(p.nullifiers, 1)),
            try fieldOrZero(at(p.commitments, 0)), try fieldOrZero(at(p.commitments, 1)),
            bytes(at(p.ciphertexts, 0) ?? Data()), bytes(at(p.ciphertexts, 1) ?? Data()),
            try fieldOrZero(p.spcMint), try fieldOrZero(p.ownerTag), bytes(p.spcCiphertext),
        ]
    }

    /// A bech32 address's raw bytes, as the chain's address codec gives them.
    public static func addressBytes(_ bech32: String) throws -> Data { Data(try Bech32.decode(bech32).data) }

    /// Bytes(address bytes), or Bytes of nothing for none.
    static func addressField(_ a: String) throws -> Fr { bytes(a.isEmpty ? Data() : try addressBytes(a)) }

    /// The registration binding's affiliate field (personhood
    /// MsgRegister.AffiliateField): 0 when the registration names no
    /// referrer, else H(TAG_AFFILIATE, Bytes(affiliate_handle)).
    public static func affiliateField(handle: String) throws -> Fr {
        if handle.isEmpty { return .zero }
        guard Handles.valid(handle) else { throw Error.shape("affiliate_handle \(handle) is not a handle") }
        return PrivacyHash.affiliateField(handle: handle)
    }

    /// SplitsBytes: option_id then percent, big-endian u64, per entry.
    public static func splitsBytes(_ splits: [Msg.AllocationWeight]) -> Data {
        var out = Data()
        for s in splits { out += be64(s.optionID) + be64(s.percent) }
        return out
    }

    static func be64(_ v: UInt64) -> Data { Data((0 ..< 8).map { UInt8(truncatingIfNeeded: v >> UInt64(56 - 8 * $0)) }) }
    static func be32(_ v: UInt32) -> Data { Data((0 ..< 4).map { UInt8(truncatingIfNeeded: v >> UInt32(24 - 8 * $0)) }) }

    /// math.LegacyDec.String: the canonical 18-place decimal of a weight in (0, 1].
    public static func legacyDec(_ weight: String) throws -> String {
        let s = weight.trimmingCharacters(in: .whitespaces)
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard (1 ... 2).contains(parts.count), parts.allSatisfy({ $0.allSatisfy { $0.isASCII && $0.isNumber } }),
              !(parts[0].isEmpty && (parts.count == 1 || parts[1].isEmpty))
        else { throw Error.badWeight(weight) }
        var intPart = String(parts[0].drop { $0 == "0" })
        if intPart.isEmpty { intPart = "0" }
        var frac = parts.count == 2 ? String(parts[1]) : ""
        while frac.count > 18, frac.last == "0" { frac.removeLast() }
        guard frac.count <= 18 else { throw Error.badWeight(weight) }
        frac += String(repeating: "0", count: 18 - frac.count)
        // ASCII digits only (audit 4: Character.isNumber takes "٥", which BigUInt would not parse).
        guard let scaled = BigUInt(intPart + frac, radix: 10) else { throw Error.badWeight(weight) }
        let one = BigUInt(10).power(18)
        guard scaled > 0, scaled <= one else { throw Error.badWeight(weight) }
        return intPart + "." + frac
    }

    /// OptionsBytes: per option, u64 BE option, u32 BE length, the weight's LegacyDec string.
    /// `opts` with every weight in its canonical LegacyDec form ("1" -> "1.000000000000000000"): the only form the chain takes (wave 3, F3).
    public static func canonicalOptions(_ opts: [WeightedVoteOption]) throws -> [WeightedVoteOption] {
        try opts.map { WeightedVoteOption(option: $0.option, weight: try legacyDec($0.weight)) }
    }

    /// Every module account the chain declares (app_config moduleAccPerms):
    /// an unshield to one is refused (wave 3, B/F2). Address = the first 20
    /// bytes of SHA-256(name) (authtypes.NewModuleAddress).
    public static let moduleAccounts = [
        "fee_collector", "distribution", "mint", "bonded_tokens_pool", "not_bonded_tokens_pool", "gov", "nft", "transfer",
        "interchainaccounts", "shielded", "shieldedstaking", "dex", "allocation", "personhood", "earth", "wasm",
    ]

    public static func moduleAddress(_ name: String) -> Data { Data(SHA256.hash(data: Data(name.utf8))).prefix(20) }

    /// The module whose account `address` (20 raw bytes) is, or nil.
    public static func moduleAccount(of address: Data) -> String? { moduleAccounts.first { moduleAddress($0) == address } }

    /// Whether YYMMDD `s` is a real calendar date (the chain refuses 250231; wave 3, I1).
    public static func isCalendarDate(_ s: String) -> Bool {
        guard s.count == 6, s.allSatisfy({ $0.isASCII && $0.isNumber }), let n = Int(s) else { return false }
        let y = 2000 + n / 10000, m = n / 100 % 100, d = n % 100
        guard (1 ... 12).contains(m), d >= 1 else { return false }
        let leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        return d <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]
    }

    public static func optionsBytes(_ opts: [WeightedVoteOption]) throws -> Data {
        var out = Data()
        for o in opts {
            let ws = Data(try legacyDec(o.weight).utf8)
            out += be64(UInt64(o.option)) + be32(UInt32(ws.count)) + ws
        }
        return out
    }

    /// A passport public signal (decimal) as a canonical field element (personhood ParseSignal).
    public static func decimalField(_ s: String) throws -> Fr {
        guard let n = BigUInt(s, radix: 10), n < Fr.modulus, !s.isEmpty, s.allSatisfy({ $0.isASCII && $0.isNumber }) else { throw Error.badSignal(s) }
        return Fr(n)
    }

    /// Decodes a private msg from its Any (the in-memory chain, reading a tx back).
    public static func decode(typeURL: String, value: Data) throws -> any PrivateMsg {
        let types: [any DecodablePrivateMsg.Type] = [
            MsgSend.self, MsgRegisterPrivate.self, MsgClaimAnmlPrivate.self, MsgSetCaretaker.self, MsgMoveCaretaker.self,
            MsgBindHandle.self, MsgMoveHandle.self,
            MsgVoteProposalPrivate.self, MsgProposeRemoval.self, MsgVoteRemoval.self, MsgShieldedDelegate.self, MsgRestake.self,
            MsgShieldedUndelegate.self, MsgClaimUnbonding.self, MsgStakeVote.self, MsgLockPosition.self, MsgUpdatePosition.self,
            MsgUnlockPosition.self, MsgPositionVote.self, MsgNoteSwap.self, MsgAddLiquidityShielded.self, MsgRemoveLiquidityShielded.self,
        ]
        guard let t = types.first(where: { $0.typeURL == typeURL }) else { throw Error.unknownType(typeURL) }
        return try t.decodeMsg(value)
    }
}

// MARK: - x/shielded

/// earth.shielded.v1.MsgSend: a private send, a merge, or an unshield (receiver set).
public struct MsgSend: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shielded.v1.MsgSend"
    public var bundle: ShieldedBundle
    public var receiver: String
    public var fee: UInt64

    public init(bundle: ShieldedBundle, receiver: String = "", fee: UInt64) {
        self.bundle = bundle; self.receiver = receiver; self.fee = fee
    }

    public var bundles: [ShieldedBundle] { [bundle] }
    public var privateFee: UInt64 { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, receiver)
        w.uint64(3, fee)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), receiver: f.string(2), fee: f.uint64(3))
    }

    public func sighashFields() throws -> [Fr] { [try PrivateMsgs.addressField(receiver), PrivateMsgs.u(fee)] }
}

/// earth.shielded.v1.MsgShield: transparent coins into the pool. Signed, not private.
public struct MsgShield: ProtoMessage, Equatable {
    public static let typeURL = "/earth.shielded.v1.MsgShield"
    public var sender: String
    public var amount: Coin
    public var pc: Data
    public var ciphertext: Data

    public init(sender: String, amount: Coin, pc: Data, ciphertext: Data) {
        self.sender = sender; self.amount = amount; self.pc = pc; self.ciphertext = ciphertext
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.string(1, sender)
        w.message(2, amount)
        w.bytes(3, pc)
        w.bytes(4, ciphertext)
        return w.data
    }
}

extension Coin: Equatable {
    public static func == (a: Coin, b: Coin) -> Bool { a.denom == b.denom && a.amount == b.amount }
}

// MARK: - fee-bundle msgs (x/personhood, x/assembly)

/// A msg paying its fee with one fee bundle (its whole uerth balance is the fee).
public protocol FeeBundleMsg: PrivateMsg {
    var feeBundle: ShieldedBundle { get }
}

public extension FeeBundleMsg {
    var bundles: [ShieldedBundle] { [feeBundle] }
}

public struct MsgRegisterPrivate: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgRegister"
    /// Absent only in the body /gas/register checks, which carries no fee.
    public var fee: ShieldedBundle?
    public var proof: Data
    public var publicSignals: [String]
    public var signatureAlgorithm: String
    public var dscDer: Data
    public var idc: Data
    public var pcAnml: Data
    public var ciphertextAnml: Data
    public var pcErth: Data
    public var ciphertextErth: Data
    /// The referrer: a live handle (15), empty for none. The chain mints the
    /// referrer's half itself, to the handle's address (ReferralOpening);
    /// affiliate_pc / affiliate_ciphertext (11, 12) are gone (chain 203d3b2).
    public var affiliateHandle: String

    public init(fee: ShieldedBundle?, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data,
                idc: Data, pcAnml: Data, ciphertextAnml: Data, pcErth: Data, ciphertextErth: Data,
                affiliateHandle: String = "") {
        self.fee = fee; self.proof = proof; self.publicSignals = publicSignals; self.signatureAlgorithm = signatureAlgorithm
        self.dscDer = dscDer; self.idc = idc; self.pcAnml = pcAnml; self.ciphertextAnml = ciphertextAnml
        self.pcErth = pcErth; self.ciphertextErth = ciphertextErth
        self.affiliateHandle = affiliateHandle
    }

    public var feeBundle: ShieldedBundle { fee ?? ShieldedBundle() }

    public func encoded() -> Data {
        var w = ProtoWriter()
        if let fee { w.message(1, fee) }
        w.bytes(2, proof)
        w.repeatedString(3, publicSignals)
        w.string(4, signatureAlgorithm)
        w.bytes(5, dscDer)
        w.bytes(6, idc)
        w.bytes(7, pcAnml)
        w.bytes(8, ciphertextAnml)
        w.bytes(9, pcErth)
        w.bytes(10, ciphertextErth)
        w.string(15, affiliateHandle)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: f.has(1) ? try f.message(1, ShieldedBundle.decode) : nil, proof: f.bytes(2), publicSignals: f.repeatedString(3),
                    signatureAlgorithm: f.string(4), dscDer: f.bytes(5), idc: f.bytes(6), pcAnml: f.bytes(7), ciphertextAnml: f.bytes(8),
                    pcErth: f.bytes(9), ciphertextErth: f.bytes(10),
                    affiliateHandle: f.string(15))
    }

    /// The binding's affiliate field: 0, or H(TAG_AFFILIATE, Bytes(handle)).
    public func affiliateField() throws -> Fr {
        try PrivateMsgs.affiliateField(handle: affiliateHandle)
    }

    /// The passport proof's `address` input this msg must carry.
    public func binding() throws -> Fr {
        PrivacyHash.registrationBinding(idc: try PrivateMsgs.f(idc), pcAnml: try PrivateMsgs.f(pcAnml), ctAnml: ciphertextAnml,
                                        pcErth: try PrivateMsgs.f(pcErth), ctErth: ciphertextErth,
                                        affiliate: try affiliateField())
    }

    public func sighashFields() throws -> [Fr] {
        [
            try PrivateMsgs.f(idc), try PrivateMsgs.f(pcAnml), PrivateMsgs.bytes(ciphertextAnml), try PrivateMsgs.f(pcErth),
            PrivateMsgs.bytes(ciphertextErth), try affiliateField(), PrivateMsgs.bytes(signatureAlgorithm),
        ] + (try publicSignals.map(PrivateMsgs.decimalField))
    }
}

public struct MsgClaimAnmlPrivate: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgClaimAnml"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var day: UInt64
    public var pc: Data
    public var ciphertext: Data

    public init(fee: ShieldedBundle, membership: Membership, day: UInt64, pc: Data, ciphertext: Data) {
        self.fee = fee; self.membership = membership; self.day = day; self.pc = pc; self.ciphertext = ciphertext
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.uint64(3, day)
        w.bytes(4, pc)
        w.bytes(5, ciphertext)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode),
                    day: f.uint64(3), pc: f.bytes(4), ciphertext: f.bytes(5))
    }

    public func sighashFields() throws -> [Fr] { [PrivateMsgs.u(day), try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext)] }
}

public struct MsgSetCaretaker: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgSetCaretaker"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var percentages: [Msg.AllocationWeight]
    /// The membership proof's max_predecessor (field 5; max_activation 4 is reserved).
    public var maxPredecessor: UInt64

    public init(fee: ShieldedBundle, membership: Membership, percentages: [Msg.AllocationWeight], maxPredecessor: UInt64) {
        self.fee = fee; self.membership = membership; self.percentages = percentages; self.maxPredecessor = maxPredecessor
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.repeatedMessage(3, percentages)
        w.uint64(5, maxPredecessor)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode),
                    percentages: try f.repeatedMessage(3, Msg.AllocationWeight.decode), maxPredecessor: f.uint64(5))
    }

    public func sighashFields() throws -> [Fr] { percentages.flatMap { [PrivateMsgs.u($0.optionID), PrivateMsgs.u($0.percent)] } }
}

/// Hands the prover's live caretaker split to new_owner, the caretaker-scope
/// nullifier of the identity that is to hold it. sighash fields: new_owner.
public struct MsgMoveCaretaker: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgMoveCaretaker"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var newOwner: Data

    public init(fee: ShieldedBundle, membership: Membership, newOwner: Data) {
        self.fee = fee; self.membership = membership; self.newOwner = newOwner
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.bytes(3, newOwner)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode), newOwner: f.bytes(3))
    }

    public func sighashFields() throws -> [Fr] { [try PrivateMsgs.f(newOwner)] }
}

/// Claims, renews, changes or (both empty) releases the prover's handle.
/// sighash fields: Bytes(handle), owner_pk, Bytes(ek_pub) of the address
/// (Bytes of nothing, 0 and Bytes of nothing for a release).
public struct MsgBindHandle: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgBindHandle"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var handle: String
    /// The shielded address, canonical lowercase "erthz1...".
    public var address: String
    /// The membership proof's max_predecessor (field 6; max_activation 5 is reserved).
    public var maxPredecessor: UInt64

    public init(fee: ShieldedBundle, membership: Membership, handle: String, address: String, maxPredecessor: UInt64) {
        self.fee = fee; self.membership = membership; self.handle = handle; self.address = address; self.maxPredecessor = maxPredecessor
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.string(3, handle)
        w.string(4, address)
        w.uint64(6, maxPredecessor)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode),
                    handle: f.string(3), address: f.string(4), maxPredecessor: f.uint64(6))
    }

    public func sighashFields() throws -> [Fr] {
        guard handle.isEmpty == address.isEmpty else { throw PrivateMsgs.Error.shape("a bind names a handle and an address; a release neither") }
        if address.isEmpty { return [PrivateMsgs.bytes(Data()), .zero, PrivateMsgs.bytes(Data())] }
        guard Handles.valid(handle) else { throw PrivateMsgs.Error.shape("\(handle) is not a handle") }
        let a = try ShieldedAddress.decode(address)
        guard a.encode() == address else { throw PrivateMsgs.Error.shape("the address is not in its canonical form") }
        return [PrivateMsgs.bytes(handle), a.ownerPK, PrivateMsgs.bytes(a.ekPub)]
    }
}

/// Hands the prover's handle to new_owner, the handle-scope nullifier of the
/// identity that is to hold it. sighash fields: Bytes(handle), new_owner.
public struct MsgMoveHandle: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgMoveHandle"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var handle: String
    public var newOwner: Data

    public init(fee: ShieldedBundle, membership: Membership, handle: String, newOwner: Data) {
        self.fee = fee; self.membership = membership; self.handle = handle; self.newOwner = newOwner
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.string(3, handle)
        w.bytes(4, newOwner)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode),
                    handle: f.string(3), newOwner: f.bytes(4))
    }

    public func sighashFields() throws -> [Fr] { [PrivateMsgs.bytes(handle), try PrivateMsgs.f(newOwner)] }
}

public struct MsgVoteProposalPrivate: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.assembly.v1.MsgVoteProposal"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var proposalID: UInt64
    public var option: AssemblyVoteOption

    public init(fee: ShieldedBundle, membership: Membership, proposalID: UInt64, option: AssemblyVoteOption) {
        self.fee = fee; self.membership = membership; self.proposalID = proposalID; self.option = option
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.uint64(3, proposalID)
        w.enumValue(4, option.rawValue)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode),
                    proposalID: f.uint64(3), option: AssemblyVoteOption(rawValue: Int(f.uint64(4))) ?? .yes)
    }

    public func sighashFields() throws -> [Fr] { [PrivateMsgs.u(proposalID), PrivateMsgs.u(UInt64(option.rawValue))] }
}

public struct MsgProposeRemoval: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.assembly.v1.MsgProposeRemoval"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var optionID: UInt64

    public init(fee: ShieldedBundle, membership: Membership, optionID: UInt64) {
        self.fee = fee; self.membership = membership; self.optionID = optionID
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.uint64(3, optionID)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode), optionID: f.uint64(3))
    }

    public func sighashFields() throws -> [Fr] { [PrivateMsgs.u(optionID)] }
}

public struct MsgVoteRemoval: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.assembly.v1.MsgVoteRemoval"
    public var fee: ShieldedBundle
    public var membership: Membership
    public var optionID: UInt64
    public var option: AssemblyVoteOption

    public init(fee: ShieldedBundle, membership: Membership, optionID: UInt64, option: AssemblyVoteOption) {
        self.fee = fee; self.membership = membership; self.optionID = optionID; self.option = option
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.uint64(3, optionID)
        w.enumValue(4, option.rawValue)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), membership: try f.message(2, Membership.decode),
                    optionID: f.uint64(3), option: AssemblyVoteOption(rawValue: Int(f.uint64(4))) ?? .yes)
    }

    public func sighashFields() throws -> [Fr] { [PrivateMsgs.u(optionID), PrivateMsgs.u(UInt64(option.rawValue))] }
}

/// The membership a personhood or assembly msg carries.
public protocol MembershipMsg: PrivateMsg {
    var membership: Membership { get }
}

extension MsgClaimAnmlPrivate: MembershipMsg {}
extension MsgSetCaretaker: MembershipMsg {}
extension MsgMoveCaretaker: MembershipMsg {}
extension MsgBindHandle: MembershipMsg {}
extension MsgMoveHandle: MembershipMsg {}
extension MsgVoteProposalPrivate: MembershipMsg {}
extension MsgProposeRemoval: MembershipMsg {}
extension MsgVoteRemoval: MembershipMsg {}

// MARK: - x/shieldedstaking

/// A staking msg: a bundle (the fee, or the delegated ERTH and the fee) and a
/// stake proof. No msg names a fee: it is the bundle's uerth balance less
/// what the msg moves (a delegation's amount).
public protocol StakingMsg: PrivateMsg {
    var bundle: ShieldedBundle { get }
    var stake: StakeProof { get }
}

public extension StakingMsg {
    var bundles: [ShieldedBundle] { [bundle] }
    var stakeProof: StakeProof? { stake }
}

/// sighash fields: StakeFields, Bytes(validator), amount.
public struct MsgShieldedDelegate: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgDelegate"
    public var bundle: ShieldedBundle
    public var validator: String
    public var stake: StakeProof
    public var amount: UInt64

    public init(bundle: ShieldedBundle, validator: String, amount: UInt64, stake: StakeProof) {
        self.bundle = bundle; self.validator = validator; self.amount = amount; self.stake = stake
    }

    public var movedUerth: UInt64 { amount }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, validator)
        w.message(4, stake)
        w.uint64(5, amount)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), validator: f.string(2), amount: f.uint64(5), stake: try f.message(4, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [PrivateMsgs.bytes(validator), PrivateMsgs.u(amount)]
    }
}

/// sighash fields: StakeFields, Bytes(validator).
public struct MsgRestake: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgRestake"
    public var bundle: ShieldedBundle
    public var validator: String
    public var stake: StakeProof

    public init(bundle: ShieldedBundle, validator: String, stake: StakeProof) {
        self.bundle = bundle; self.validator = validator; self.stake = stake
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, validator)
        w.message(4, stake)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), validator: f.string(2), stake: try f.message(4, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [PrivateMsgs.bytes(validator)]
    }
}

/// sighash fields: StakeFields, Bytes(validator), amount.
public struct MsgShieldedUndelegate: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgUndelegate"
    public var bundle: ShieldedBundle
    public var validator: String
    public var amount: UInt64
    public var stake: StakeProof

    public init(bundle: ShieldedBundle, validator: String, amount: UInt64, stake: StakeProof) {
        self.bundle = bundle; self.validator = validator; self.amount = amount; self.stake = stake
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, validator)
        w.uint64(3, amount)
        w.message(5, stake)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), validator: f.string(2), amount: f.uint64(3),
                    stake: try f.message(5, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [PrivateMsgs.bytes(validator), PrivateMsgs.u(amount)]
    }
}

/// Claims matured unbonding claims. Usually carries no bundle: its fee comes
/// out of the ERTH it pays (fee_from_output, the one msg that may). sighash
/// fields: StakeFields, Bytes(validator), epoch, amount, pc, Bytes(ciphertext),
/// fee_from_output.
public struct MsgClaimUnbonding: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgClaimUnbonding"
    public var bundle: ShieldedBundle?
    public var validator: String
    public var epoch: UInt64
    public var amount: UInt64
    public var pc: Data
    public var ciphertext: Data
    public var feeFromOutput: UInt64
    public var stake: StakeProof

    public init(bundle: ShieldedBundle? = nil, validator: String, epoch: UInt64, amount: UInt64, pc: Data, ciphertext: Data = Data(),
                feeFromOutput: UInt64 = 0, stake: StakeProof) {
        self.bundle = bundle; self.validator = validator; self.epoch = epoch; self.amount = amount; self.pc = pc
        self.ciphertext = ciphertext; self.feeFromOutput = feeFromOutput; self.stake = stake
    }

    public var bundles: [ShieldedBundle] { bundle.map { [$0] } ?? [] }
    public var stakeProof: StakeProof? { stake }

    public func encoded() -> Data {
        var w = ProtoWriter()
        if let bundle { w.message(1, bundle) }
        w.string(2, validator)
        w.uint64(3, epoch)
        w.uint64(4, amount)
        w.bytes(5, pc)
        w.bytes(6, ciphertext)
        w.uint64(7, feeFromOutput)
        w.message(9, stake)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: f.has(1) ? try f.message(1, ShieldedBundle.decode) : nil, validator: f.string(2), epoch: f.uint64(3),
                    amount: f.uint64(4), pc: f.bytes(5), ciphertext: f.bytes(6), feeFromOutput: f.uint64(7),
                    stake: try f.message(9, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [
            PrivateMsgs.bytes(validator), PrivateMsgs.u(epoch), PrivateMsgs.u(amount), try PrivateMsgs.f(pc),
            PrivateMsgs.bytes(ciphertext), PrivateMsgs.u(feeFromOutput),
        ]
    }
}

/// A stake note's vote without spending it (ORCHARD_DESIGN 15): `proof` is
/// circuits/vote against the proposal's snapshot, `voteNullifier` =
/// H(TAG_VNF, nk, rho, position, proposal_id), refused a second time on the
/// proposal (code 1119). No stake proof, nothing spent or minted (field 7,
/// the old stake proof, is reserved). sighash fields: proposal_id,
/// Bytes(validator), Bytes(OptionsBytes(options)), weight, vote_nullifier.
public struct MsgStakeVote: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgStakeVote"
    public var bundle: ShieldedBundle
    public var proposalID: UInt64
    public var validator: String
    public var options: [WeightedVoteOption]
    public var weight: UInt64
    public var proof: Data
    public var voteNullifier: Data

    public init(bundle: ShieldedBundle, proposalID: UInt64, validator: String, options: [WeightedVoteOption], weight: UInt64,
                proof: Data = Data(), voteNullifier: Data = Data()) {
        self.bundle = bundle; self.proposalID = proposalID; self.validator = validator; self.options = options
        self.weight = weight; self.proof = proof; self.voteNullifier = voteNullifier
    }

    public var bundles: [ShieldedBundle] { [bundle] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.uint64(2, proposalID)
        w.string(3, validator)
        w.repeatedMessage(4, options)
        w.uint64(5, weight)
        w.bytes(8, proof)
        w.bytes(9, voteNullifier)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), proposalID: f.uint64(2), validator: f.string(3),
                    options: try f.repeatedMessage(4, WeightedVoteOption.decode), weight: f.uint64(5),
                    proof: f.bytes(8), voteNullifier: f.bytes(9))
    }

    public func sighashFields() throws -> [Fr] {
        [
            PrivateMsgs.u(proposalID), PrivateMsgs.bytes(validator), PrivacyHash.bytes(try PrivateMsgs.optionsBytes(options)),
            PrivateMsgs.u(weight), try PrivateMsgs.f(voteNullifier),
        ]
    }
}

/// sighash fields: StakeFields, Bytes(validator), amount, Bytes(SplitsBytes(splits)).
public struct MsgLockPosition: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgLockPosition"
    public var bundle: ShieldedBundle
    public var validator: String
    public var amount: UInt64
    public var splits: [Msg.AllocationWeight]
    public var stake: StakeProof

    public init(bundle: ShieldedBundle, validator: String, amount: UInt64, splits: [Msg.AllocationWeight], stake: StakeProof) {
        self.bundle = bundle; self.validator = validator; self.amount = amount; self.splits = splits; self.stake = stake
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, validator)
        w.uint64(3, amount)
        w.repeatedMessage(4, splits)
        w.message(6, stake)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), validator: f.string(2), amount: f.uint64(3),
                    splits: try f.repeatedMessage(4, Msg.AllocationWeight.decode), stake: try f.message(6, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [
            PrivateMsgs.bytes(validator), PrivateMsgs.u(amount), PrivacyHash.bytes(PrivateMsgs.splitsBytes(splits)),
        ]
    }
}

/// sighash fields: StakeFields, position_id, Bytes(SplitsBytes(splits)).
public struct MsgUpdatePosition: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgUpdatePosition"
    public var bundle: ShieldedBundle
    public var positionID: UInt64
    public var splits: [Msg.AllocationWeight]
    public var stake: StakeProof

    public init(bundle: ShieldedBundle, positionID: UInt64, splits: [Msg.AllocationWeight], stake: StakeProof) {
        self.bundle = bundle; self.positionID = positionID; self.splits = splits; self.stake = stake
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.uint64(2, positionID)
        w.repeatedMessage(3, splits)
        w.message(5, stake)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), positionID: f.uint64(2),
                    splits: try f.repeatedMessage(3, Msg.AllocationWeight.decode), stake: try f.message(5, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [PrivateMsgs.u(positionID), PrivacyHash.bytes(PrivateMsgs.splitsBytes(splits))]
    }
}

/// sighash fields: StakeFields, position_id.
public struct MsgUnlockPosition: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgUnlockPosition"
    public var bundle: ShieldedBundle
    public var positionID: UInt64
    public var stake: StakeProof

    public init(bundle: ShieldedBundle, positionID: UInt64, stake: StakeProof) {
        self.bundle = bundle; self.positionID = positionID; self.stake = stake
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.uint64(2, positionID)
        w.message(4, stake)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), positionID: f.uint64(2), stake: try f.message(4, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [PrivateMsgs.u(positionID)]
    }
}

/// sighash fields: StakeFields, position_id, proposal_id, Bytes(OptionsBytes(options)).
public struct MsgPositionVote: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgPositionVote"
    public var bundle: ShieldedBundle
    public var positionID: UInt64
    public var proposalID: UInt64
    public var options: [WeightedVoteOption]
    public var stake: StakeProof

    public init(bundle: ShieldedBundle, positionID: UInt64, proposalID: UInt64, options: [WeightedVoteOption], stake: StakeProof) {
        self.bundle = bundle; self.positionID = positionID; self.proposalID = proposalID; self.options = options; self.stake = stake
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.uint64(2, positionID)
        w.uint64(3, proposalID)
        w.repeatedMessage(4, options)
        w.message(6, stake)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), positionID: f.uint64(2), proposalID: f.uint64(3),
                    options: try f.repeatedMessage(4, WeightedVoteOption.decode), stake: try f.message(6, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [
            PrivateMsgs.u(positionID), PrivateMsgs.u(proposalID), PrivacyHash.bytes(try PrivateMsgs.optionsBytes(options)),
        ]
    }
}

// MARK: - x/dex

/// The bundle releases amount_in of denom_in into the dex plus the uerth fee
/// (the bundle's uerth balance, less amount_in when denom_in is uerth),
/// swapped for denom_out and minted to pc with a 177-byte blind ciphertext.
/// sighash fields: Bytes(denom_in), amount_in, Bytes(denom_out),
/// min_amount_out, pc, Bytes(ciphertext).
public struct MsgNoteSwap: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.dex.v1.MsgNoteSwap"
    public var bundle: ShieldedBundle
    public var denomIn: String
    public var amountIn: UInt64
    public var denomOut: String
    public var minAmountOut: UInt64
    public var pc: Data
    public var ciphertext: Data

    public init(bundle: ShieldedBundle, denomIn: String, amountIn: UInt64, denomOut: String, minAmountOut: UInt64, pc: Data, ciphertext: Data = Data()) {
        self.bundle = bundle; self.denomIn = denomIn; self.amountIn = amountIn; self.denomOut = denomOut
        self.minAmountOut = minAmountOut; self.pc = pc; self.ciphertext = ciphertext
    }

    public var bundles: [ShieldedBundle] { [bundle] }
    public var movedUerth: UInt64 { denomIn == "uerth" ? amountIn : 0 }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, denomOut)
        w.uint64(3, minAmountOut)
        w.bytes(4, pc)
        w.bytes(5, ciphertext)
        w.string(8, denomIn)
        w.uint64(9, amountIn)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), denomIn: f.string(8), amountIn: f.uint64(9), denomOut: f.string(2),
                    minAmountOut: f.uint64(3), pc: f.bytes(4), ciphertext: f.bytes(5))
    }

    public func sighashFields() throws -> [Fr] {
        [
            PrivateMsgs.bytes(denomIn), PrivateMsgs.u(amountIn), PrivateMsgs.bytes(denomOut), PrivateMsgs.u(minAmountOut),
            try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext),
        ]
    }
}

/// One bundle: its token balance the token leg, erth_amount the ERTH leg,
/// the rest of its uerth balance the fee; the LP shares (dexlp/<pool_id>)
/// minted as a note to share_pc, what the ratio does not take minted back to
/// refund_pc. sighash fields: pool_id, Bytes(min_shares), share_pc,
/// Bytes(share_ciphertext), refund_pc, Bytes(refund_ciphertext), erth_amount.
public struct MsgAddLiquidityShielded: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.dex.v1.MsgAddLiquidityShielded"
    public var bundle: ShieldedBundle
    public var poolID: UInt64
    public var minShares: String
    public var refundPC: Data
    public var refundCiphertext: Data
    public var sharePC: Data
    public var shareCiphertext: Data
    public var erthAmount: UInt64

    public init(bundle: ShieldedBundle, poolID: UInt64, minShares: String, refundPC: Data, refundCiphertext: Data = Data(),
                sharePC: Data, shareCiphertext: Data = Data(), erthAmount: UInt64) {
        self.bundle = bundle; self.poolID = poolID; self.minShares = minShares; self.refundPC = refundPC
        self.refundCiphertext = refundCiphertext; self.sharePC = sharePC; self.shareCiphertext = shareCiphertext
        self.erthAmount = erthAmount
    }

    public var bundles: [ShieldedBundle] { [bundle] }
    public var movedUerth: UInt64 { erthAmount }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.uint64(3, poolID)
        w.string(5, minShares)
        w.bytes(6, refundPC)
        w.bytes(7, refundCiphertext)
        w.bytes(9, sharePC)
        w.bytes(10, shareCiphertext)
        w.uint64(11, erthAmount)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), poolID: f.uint64(3), minShares: f.string(5), refundPC: f.bytes(6),
                    refundCiphertext: f.bytes(7), sharePC: f.bytes(9), shareCiphertext: f.bytes(10), erthAmount: f.uint64(11))
    }

    public func sighashFields() throws -> [Fr] {
        [
            PrivateMsgs.u(poolID), PrivateMsgs.bytes(minShares), try PrivateMsgs.f(sharePC), PrivateMsgs.bytes(shareCiphertext),
            try PrivateMsgs.f(refundPC), PrivateMsgs.bytes(refundCiphertext), PrivateMsgs.u(erthAmount),
        ]
    }
}

/// Begins a private withdrawal: the bundle releases dexlp/<pool_id> shares
/// and the uerth fee (its whole uerth balance); at maturity both legs are
/// minted as notes to erth_pc and token_pc. sighash fields: pool_id, erth_pc,
/// Bytes(erth_ciphertext), token_pc, Bytes(token_ciphertext).
public struct MsgRemoveLiquidityShielded: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.dex.v1.MsgRemoveLiquidityShielded"
    public var bundle: ShieldedBundle
    public var poolID: UInt64
    public var erthPC: Data
    public var erthCiphertext: Data
    public var tokenPC: Data
    public var tokenCiphertext: Data

    public init(bundle: ShieldedBundle, poolID: UInt64, erthPC: Data, erthCiphertext: Data = Data(), tokenPC: Data, tokenCiphertext: Data = Data()) {
        self.bundle = bundle; self.poolID = poolID; self.erthPC = erthPC; self.erthCiphertext = erthCiphertext
        self.tokenPC = tokenPC; self.tokenCiphertext = tokenCiphertext
    }

    public var bundles: [ShieldedBundle] { [bundle] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.uint64(2, poolID)
        w.bytes(4, erthPC)
        w.bytes(5, erthCiphertext)
        w.bytes(6, tokenPC)
        w.bytes(7, tokenCiphertext)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), poolID: f.uint64(2), erthPC: f.bytes(4),
                    erthCiphertext: f.bytes(5), tokenPC: f.bytes(6), tokenCiphertext: f.bytes(7))
    }

    public func sighashFields() throws -> [Fr] {
        [
            PrivateMsgs.u(poolID), try PrivateMsgs.f(erthPC), PrivateMsgs.bytes(erthCiphertext), try PrivateMsgs.f(tokenPC),
            PrivateMsgs.bytes(tokenCiphertext),
        ]
    }
}

/// earth.dex.v1.MsgBuyAnml (signed): transparent ERTH, or a token through
/// ERTH, into an ANML note.
public struct MsgBuyAnml: ProtoMessage, Equatable {
    public static let typeURL = "/earth.dex.v1.MsgBuyAnml"
    public var creator: String
    public var tokenIn: Coin
    public var minAmountOut: String
    public var pc: Data
    public var ciphertext: Data

    public init(creator: String, tokenIn: Coin, minAmountOut: String, pc: Data, ciphertext: Data = Data()) {
        self.creator = creator; self.tokenIn = tokenIn; self.minAmountOut = minAmountOut; self.pc = pc; self.ciphertext = ciphertext
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.string(1, creator)
        w.message(2, tokenIn)
        w.string(3, minAmountOut)
        w.bytes(4, pc)
        w.bytes(5, ciphertext)
        return w.data
    }
}

extension Msg.AllocationWeight: Equatable {
    public static func == (a: Msg.AllocationWeight, b: Msg.AllocationWeight) -> Bool {
        a.optionID == b.optionID && a.percent == b.percent
    }
}
