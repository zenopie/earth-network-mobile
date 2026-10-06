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

/// earth.shieldedstaking.v1.StakeProof (circuits/stake v2): lane
/// A (asset; nullifiers, commitment) and the credit lane (credit_*), the
/// clear_before and debt root the proof reads. Every byte field is exactly 32
/// bytes (zero for none) but the proof and the ciphertexts. Fields 4, 5, 6 and
/// 8 (commitments, ciphertexts, spc_mint, spc_ciphertext) are retired.
public struct StakeProof: ProtoMessage, Equatable, Sendable {
    public var proof: Data
    public var anchor: Data
    /// Exactly two (lane A): the owner's note(s), or padding, or zero.
    public var nullifiers: [Data]
    public var ownerTag: Data
    /// Lane A's output (the merged note, the change or a zero note) and its
    /// 201-byte wallet stake ciphertext (empty iff the commitment is zero).
    public var commitment: Data
    public var ciphertext: Data
    /// The credit lane: the owner's note of the credited asset (or padding),
    /// the merged (labelled) note, its ciphertext; zero/empty when unused.
    public var creditNullifier: Data
    public var creditCommitment: Data
    public var creditCiphertext: Data
    /// A label with move_time < clear_before may clear (0: the proof clears
    /// nothing); debt_root is then the current slash debt root, else zero.
    public var clearBefore: UInt64
    public var debtRoot: Data

    public init(proof: Data, anchor: Data, nullifiers: [Data], ownerTag: Data, commitment: Data, ciphertext: Data,
                creditNullifier: Data, creditCommitment: Data, creditCiphertext: Data, clearBefore: UInt64, debtRoot: Data) {
        self.proof = proof; self.anchor = anchor; self.nullifiers = nullifiers; self.ownerTag = ownerTag
        self.commitment = commitment; self.ciphertext = ciphertext; self.creditNullifier = creditNullifier
        self.creditCommitment = creditCommitment; self.creditCiphertext = creditCiphertext; self.clearBefore = clearBefore; self.debtRoot = debtRoot
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, proof)
        w.bytes(2, anchor)
        w.repeatedBytes(3, nullifiers)
        w.bytes(7, ownerTag)
        w.bytes(9, commitment)
        w.bytes(10, ciphertext)
        w.bytes(11, creditNullifier)
        w.bytes(12, creditCommitment)
        w.bytes(13, creditCiphertext)
        w.uint64(14, clearBefore)
        w.bytes(15, debtRoot)
        return w.data
    }

    public static func decode(_ d: Data) throws -> StakeProof {
        let f = try ProtoFields(d)
        return StakeProof(proof: f.bytes(1), anchor: f.bytes(2), nullifiers: f.repeatedBytes(3), ownerTag: f.bytes(7),
                          commitment: f.bytes(9), ciphertext: f.bytes(10), creditNullifier: f.bytes(11), creditCommitment: f.bytes(12),
                          creditCiphertext: f.bytes(13), clearBefore: f.uint64(14), debtRoot: f.bytes(15))
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

/// earth.personhood.v1.MoveProof: a move circuit proof (circuits/move). Its
/// prover knows the identity secrets of an identity and of the identity that
/// succeeded it under the same passport (the chain's succession leaf
/// H(TAG_SUCC, idc_old, idc_new) and the successor's live identity leaf are
/// both in the tree at root). Public inputs, in order: root, scope (the
/// msg's), old_nullifier, new_nullifier, signal (the msg's sighash).
public struct MoveProof: ProtoMessage, Equatable, Sendable {
    public var proof: Data
    public var root: Data
    public var oldNullifier: Data
    public var newNullifier: Data

    public init(proof: Data, root: Data, oldNullifier: Data, newNullifier: Data) {
        self.proof = proof; self.root = root; self.oldNullifier = oldNullifier; self.newNullifier = newNullifier
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, proof)
        w.bytes(2, root)
        w.bytes(3, oldNullifier)
        w.bytes(4, newNullifier)
        return w.data
    }

    public static func decode(_ d: Data) throws -> MoveProof {
        let f = try ProtoFields(d)
        return MoveProof(proof: f.bytes(1), root: f.bytes(2), oldNullifier: f.bytes(3), newNullifier: f.bytes(4))
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
    /// The msg's own fields, bound after the bundle digests (the msg's Go SighashFields).
    func sighashFields() throws -> [Fr]
}

public extension PrivateMsg {
    var stakeProof: StakeProof? { nil }
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

    /// The whole fee the tx declares (types.TotalFee): the private fee.
    var totalFee: UInt64 { privateFee }
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

    /// a + b, clamped at UInt64.max (sums of chain-published amounts never trap or wrap).
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

    /// StakeFields: anchor, nf_0, nf_1, cm, Bytes(ct), credit_nf, credit_cm,
    /// Bytes(credit_ct), owner_tag, clear_before, debt_root (an absent
    /// ciphertext is Bytes of nothing).
    public static func stakeFields(_ p: StakeProof) throws -> [Fr] {
        func at(_ xs: [Data], _ i: Int) -> Data? { i < xs.count ? xs[i] : nil }
        return [
            try fieldOrZero(p.anchor), try fieldOrZero(at(p.nullifiers, 0)), try fieldOrZero(at(p.nullifiers, 1)),
            try fieldOrZero(p.commitment), bytes(p.ciphertext),
            try fieldOrZero(p.creditNullifier), try fieldOrZero(p.creditCommitment), bytes(p.creditCiphertext),
            try fieldOrZero(p.ownerTag), u(p.clearBefore), try fieldOrZero(p.debtRoot),
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
        // ASCII digits only (Character.isNumber takes "٥", which BigUInt would not parse).
        guard let scaled = BigUInt(intPart + frac, radix: 10) else { throw Error.badWeight(weight) }
        let one = BigUInt(10).power(18)
        guard scaled > 0, scaled <= one else { throw Error.badWeight(weight) }
        return intPart + "." + frac
    }

    /// OptionsBytes: per option, u64 BE option, u32 BE length, the weight's LegacyDec string.
    /// `opts` with every weight in its canonical LegacyDec form ("1" -> "1.000000000000000000"): the only form the chain takes.
    public static func canonicalOptions(_ opts: [WeightedVoteOption]) throws -> [WeightedVoteOption] {
        try opts.map { WeightedVoteOption(option: $0.option, weight: try legacyDec($0.weight)) }
    }

    /// Every module account the chain declares (app_config moduleAccPerms):
    /// an unshield to one is refused. Address = the first 20
    /// bytes of SHA-256(name) (authtypes.NewModuleAddress).
    public static let moduleAccounts = [
        "fee_collector", "distribution", "mint", "bonded_tokens_pool", "not_bonded_tokens_pool", "gov", "nft", "transfer",
        "interchainaccounts", "shielded", "shieldedstaking", "dex", "allocation", "personhood", "earth", "wasm",
    ]

    public static func moduleAddress(_ name: String) -> Data { Data(SHA256.hash(data: Data(name.utf8))).prefix(20) }

    /// The module whose account `address` (20 raw bytes) is, or nil.
    public static func moduleAccount(of address: Data) -> String? { moduleAccounts.first { moduleAddress($0) == address } }

    /// Whether YYMMDD `s` is a real calendar date (the chain refuses 250231).
    public static func isCalendarDate(_ s: String) -> Bool {
        guard s.count == 6, s.allSatisfy({ $0.isASCII && $0.isNumber }), let n = Int(s) else { return false }
        let y = 2000 + n / 10000, m = n / 100 % 100, d = n % 100
        guard (1 ... 12).contains(m), d >= 1 else { return false }
        let leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        return d <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]
    }

    /// YYMMDD `s` as unix seconds at midnight UTC (the chain's yymmddToUnix), or nil when it is no calendar date.
    public static func calendarDateUnix(_ s: String) -> Int64? {
        guard isCalendarDate(s), let n = Int64(s) else { return nil }
        let y = 2000 + n / 10000, m = n / 100 % 100, d = n % 100
        // Days from the civil date (Hinnant), so no calendar API is involved.
        let yy = m <= 2 ? y - 1 : y
        let era = yy / 400, yoe = yy - era * 400
        let doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146_097 + doe - 719_468) * 86_400
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
            MsgShieldedUndelegate.self, MsgStakeVote.self, MsgLockPosition.self, MsgUpdatePosition.self,
            MsgUnlockPosition.self, MsgPositionVote.self, MsgRedelegate.self, MsgNoteSwap.self, MsgAddLiquidityShielded.self,
            MsgRemoveLiquidityShielded.self,
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
    /// affiliate_pc / affiliate_ciphertext (11, 12) are gone.
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

    /// The passport proof's `address` input this msg must carry on [chainID].
    public func binding(chainID: String) throws -> Fr {
        PrivacyHash.registrationBinding(chainID: chainID, idc: try PrivateMsgs.f(idc), pcAnml: try PrivateMsgs.f(pcAnml), ctAnml: ciphertextAnml,
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

/// Hands the live caretaker split of move.old_nullifier, percentages and
/// expiry unchanged, to move.new_nullifier, its successor under the same
/// passport, in the caretaker scope. sighash fields: none beyond the fee
/// bundle's (the move proof binds the sighash).
public struct MsgMoveCaretaker: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgMoveCaretaker"
    public var fee: ShieldedBundle
    public var move: MoveProof

    public init(fee: ShieldedBundle, move: MoveProof) {
        self.fee = fee; self.move = move
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, move)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), move: try f.message(2, MoveProof.decode))
    }

    public func sighashFields() throws -> [Fr] { [] }
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

/// Hands a live handle, lease unchanged, from move.old_nullifier to
/// move.new_nullifier, its successor under the same passport, in the handle
/// scope. sighash fields: Bytes(handle).
public struct MsgMoveHandle: DecodablePrivateMsg, FeeBundleMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgMoveHandle"
    public var fee: ShieldedBundle
    public var move: MoveProof
    public var handle: String

    public init(fee: ShieldedBundle, move: MoveProof, handle: String) {
        self.fee = fee; self.move = move; self.handle = handle
    }

    public var feeBundle: ShieldedBundle { fee }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, move)
        w.string(3, handle)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedBundle.decode), move: try f.message(2, MoveProof.decode), handle: f.string(3))
    }

    public func sighashFields() throws -> [Fr] { [PrivateMsgs.bytes(handle)] }
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
extension MsgBindHandle: MembershipMsg {}
extension MsgVoteProposalPrivate: MembershipMsg {}
extension MsgProposeRemoval: MembershipMsg {}
extension MsgVoteRemoval: MembershipMsg {}

/// A move msg: its move proof, set by the engine once proven.
public protocol MoveMsg: PrivateMsg {
    var move: MoveProof { get set }
}

extension MsgMoveCaretaker: MoveMsg {}
extension MsgMoveHandle: MoveMsg {}

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

/// The wallet names `derth` (the derth/<validator> credited to its note,
/// v_in); the chain refuses it unless `amount` buys it at the live rate.
/// sighash fields: StakeFields, Bytes(validator), amount, derth.
public struct MsgShieldedDelegate: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgDelegate"
    public var bundle: ShieldedBundle
    public var validator: String
    public var stake: StakeProof
    public var amount: UInt64
    public var derth: UInt64

    public init(bundle: ShieldedBundle, validator: String, amount: UInt64, derth: UInt64, stake: StakeProof) {
        self.bundle = bundle; self.validator = validator; self.amount = amount; self.derth = derth; self.stake = stake
    }

    public var movedUerth: UInt64 { amount }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, validator)
        w.message(4, stake)
        w.uint64(5, amount)
        w.uint64(6, derth)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), validator: f.string(2), amount: f.uint64(5), derth: f.uint64(6),
                    stake: try f.message(4, StakeProof.decode))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [PrivateMsgs.bytes(validator), PrivateMsgs.u(amount), PrivateMsgs.u(derth)]
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

/// The chain pays the undelegation out by itself at maturity: value x
/// payout / requested uerth as pool notes to `pc` with `ciphertext` (several notes sharing it past 2^63-1). The proof creates the
/// change, or a zero note when nothing is left. sighash fields: StakeFields,
/// Bytes(validator), amount, pc, Bytes(ciphertext).
public struct MsgShieldedUndelegate: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgUndelegate"
    public var bundle: ShieldedBundle
    public var validator: String
    public var amount: UInt64
    public var stake: StakeProof
    /// The payout notes' pc (32 bytes) and v2 amount-blind ciphertext (177 bytes).
    public var pc: Data
    public var ciphertext: Data

    public init(bundle: ShieldedBundle, validator: String, amount: UInt64, stake: StakeProof, pc: Data, ciphertext: Data) {
        self.bundle = bundle; self.validator = validator; self.amount = amount; self.stake = stake
        self.pc = pc; self.ciphertext = ciphertext
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, validator)
        w.uint64(3, amount)
        w.message(5, stake)
        w.bytes(6, pc)
        w.bytes(7, ciphertext)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), validator: f.string(2), amount: f.uint64(3),
                    stake: try f.message(5, StakeProof.decode), pc: f.bytes(6), ciphertext: f.bytes(7))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [PrivateMsgs.bytes(validator), PrivateMsgs.u(amount), try PrivateMsgs.f(pc),
                                              PrivateMsgs.bytes(ciphertext)]
    }
}

/// Up to two stake notes of one owner at one validator vote with ONE weight,
/// without being spent (ORCHARD_DESIGN 8.5): `proof` is circuits/vote
/// against the proposal's snapshot and the CURRENT slash debt root (a
/// labelled note votes its value after slashes); `voteNullifiers` is exactly
/// two, the used slots' H(TAG_VNF, nk, rho, position, proposal_id) first
/// (distinct), then zeros, each refused a second time on the proposal (code
/// 1119). No stake proof, nothing spent or minted (fields 7, the old stake
/// proof, and 9, the single vote nullifier, are reserved). sighash fields:
/// proposal_id, Bytes(validator), Bytes(OptionsBytes(options)), weight,
/// vote_nullifiers[0..1], debt_root.
public struct MsgStakeVote: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgStakeVote"
    /// Vote nullifier slots in every msg (x/shieldedstaking MaxVoteNotes, circuits/vote MAX_NOTES).
    public static let maxVoteNotes = 2
    public var bundle: ShieldedBundle
    public var proposalID: UInt64
    public var validator: String
    public var options: [WeightedVoteOption]
    public var weight: UInt64
    public var proof: Data
    public var voteNullifiers: [Data]
    public var debtRoot: Data

    public init(bundle: ShieldedBundle, proposalID: UInt64, validator: String, options: [WeightedVoteOption], weight: UInt64,
                proof: Data = Data(), voteNullifiers: [Data] = [], debtRoot: Data = Data()) {
        self.bundle = bundle; self.proposalID = proposalID; self.validator = validator; self.options = options
        self.weight = weight; self.proof = proof; self.voteNullifiers = voteNullifiers; self.debtRoot = debtRoot
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
        w.repeatedBytes(10, voteNullifiers)
        w.bytes(11, debtRoot)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), proposalID: f.uint64(2), validator: f.string(3),
                    options: try f.repeatedMessage(4, WeightedVoteOption.decode), weight: f.uint64(5),
                    proof: f.bytes(8), voteNullifiers: f.repeatedBytes(10), debtRoot: f.bytes(11))
    }

    public func sighashFields() throws -> [Fr] {
        guard voteNullifiers.count == Self.maxVoteNotes else {
            throw PrivateMsgs.Error.shape("a stake vote carries exactly \(Self.maxVoteNotes) vote nullifiers")
        }
        return [
            PrivateMsgs.u(proposalID), PrivateMsgs.bytes(validator), PrivacyHash.bytes(try PrivateMsgs.optionsBytes(options)),
            PrivateMsgs.u(weight),
        ] + (try voteNullifiers.map(PrivateMsgs.f)) + [try PrivateMsgs.f(debtRoot)]
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

/// Moves `amount` derth/<src> to `dstValidator` with no unbonding gap
/// (ORCHARD_DESIGN 8.3, 8.7): lane A spends derth/<src> (v_out = amount,
/// the change or a zero note back); the credit lane merges `dstDerth` into
/// the owner's unlabelled derth/<dst> note (or pads), labelled with the move
/// (move_key = credit_nullifier, move_time, exposed = dst_derth). `moveTime`:
/// unix seconds, within 600 s before the block time. sighash fields:
/// StakeFields, Bytes(src_validator), Bytes(dst_validator), amount,
/// dst_derth, move_time.
public struct MsgRedelegate: DecodablePrivateMsg, StakingMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgRedelegate"
    public var bundle: ShieldedBundle
    public var srcValidator: String
    public var dstValidator: String
    public var amount: UInt64
    public var stake: StakeProof
    public var dstDerth: UInt64
    public var moveTime: UInt64

    public init(bundle: ShieldedBundle, srcValidator: String, dstValidator: String, amount: UInt64, stake: StakeProof,
                dstDerth: UInt64, moveTime: UInt64) {
        self.bundle = bundle; self.srcValidator = srcValidator; self.dstValidator = dstValidator; self.amount = amount
        self.stake = stake; self.dstDerth = dstDerth; self.moveTime = moveTime
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, bundle)
        w.string(2, srcValidator)
        w.string(3, dstValidator)
        w.uint64(4, amount)
        w.message(5, stake)
        w.uint64(6, dstDerth)
        w.uint64(7, moveTime)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(bundle: try f.message(1, ShieldedBundle.decode), srcValidator: f.string(2), dstValidator: f.string(3),
                    amount: f.uint64(4), stake: try f.message(5, StakeProof.decode), dstDerth: f.uint64(6), moveTime: f.uint64(7))
    }

    public func sighashFields() throws -> [Fr] {
        try PrivateMsgs.stakeFields(stake) + [
            PrivateMsgs.bytes(srcValidator), PrivateMsgs.bytes(dstValidator), PrivateMsgs.u(amount),
            PrivateMsgs.u(dstDerth), PrivateMsgs.u(moveTime),
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
