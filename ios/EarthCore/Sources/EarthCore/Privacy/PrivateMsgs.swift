import BigInt
import Foundation

// The chain's private msgs: their wire encoding (field numbers from
// android/app/src/main/proto/earth/{shielded,personhood,assembly,
// shieldedstaking,dex}), their type URLs, and the signal each one's proofs
// bind, ported field for field from the msg's Go `Signal` via
// `privacy/tx/PrivateMsgs.kt`. PrivateMsgsTests pins every encoding and
// every signal to the chain's output.
//
// Fields the chain parses as field elements (pcs, idc, nullifiers) must be
// canonical 32-byte values; `Fr(bytes:)` refuses anything else, as the chain
// does, so computing a signal throws on them.

/// earth.shielded.v1.Transfer: one transfer proof's public statement.
public struct ShieldedTransfer: ProtoMessage, Equatable, Sendable {
    public var proof: Data
    public var root: Data
    public var nullifiers: [Data]
    public var commitments: [Data]
    public var ciphertexts: [Data]
    public var fee: UInt64
    public var valueOut: UInt64
    public var denomOut: String

    public init(proof: Data, root: Data, nullifiers: [Data], commitments: [Data], ciphertexts: [Data],
                fee: UInt64, valueOut: UInt64, denomOut: String) {
        self.proof = proof; self.root = root; self.nullifiers = nullifiers; self.commitments = commitments
        self.ciphertexts = ciphertexts; self.fee = fee; self.valueOut = valueOut; self.denomOut = denomOut
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, proof)
        w.bytes(2, root)
        w.repeatedBytes(3, nullifiers)
        w.repeatedBytes(4, commitments)
        w.repeatedBytes(5, ciphertexts)
        w.uint64(6, fee)
        w.uint64(7, valueOut)
        w.string(8, denomOut)
        return w.data
    }

    public static func decode(_ d: Data) throws -> ShieldedTransfer {
        let f = try ProtoFields(d)
        return ShieldedTransfer(proof: f.bytes(1), root: f.bytes(2), nullifiers: f.repeatedBytes(3), commitments: f.repeatedBytes(4),
                                ciphertexts: f.repeatedBytes(5), fee: f.uint64(6), valueOut: f.uint64(7), denomOut: f.string(8))
    }

    public func ciphertextList() throws -> [Data] {
        guard ciphertexts.count == 3 else { throw PrivateMsgs.Error.shape("a transfer carries three ciphertexts") }
        return ciphertexts
    }

    public func nullifierList() throws -> [Fr] {
        guard nullifiers.count == 3 else { throw PrivateMsgs.Error.shape("a transfer carries three nullifiers") }
        return try nullifiers.map { try Fr(bytes: $0) }
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

/// A msg the private ante takes: unsigned, its fee paid from shielded notes.
public protocol PrivateMsg: ProtoMessage {
    static var typeURL: String { get }
    /// Every transfer it spends, the primary first (MultiTransferMsg.PrivateTransfers).
    var transfers: [ShieldedTransfer] { get }
    /// fee_from_output, for the msgs that may pay their fee out of the ERTH they produce.
    var feeFromOutput: UInt64 { get }
    /// The signal its proofs bind on `chainID` (the msg's Go Signal).
    func signal(chainID: String) throws -> Fr
}

public extension PrivateMsg {
    var feeFromOutput: UInt64 { 0 }
    var typeURL: String { Self.typeURL }
    /// The whole fee the tx declares: every transfer's fee plus fee_from_output (types.TotalFee).
    var totalFee: UInt64 { transfers.reduce(0) { $0 + $1.fee } + feeFromOutput }
    func asAny() -> ProtoAny { asAny(typeURL: Self.typeURL) }
}

public enum PrivateMsgs {
    public enum Error: Swift.Error, Equatable {
        case shape(String)
        case badWeight(String)
        case badSignal(String)
        case unknownType(String)
    }

    static func f(_ b: Data) throws -> Fr { try Fr(bytes: b) }
    static func bytes(_ b: Data) -> Fr { PrivacyHash.bytes(b) }
    static func bytes(_ s: String) -> Fr { PrivacyHash.bytes(s) }
    static func u(_ v: UInt64) -> Fr { PrivacyHash.u64(v) }

    static func action(_ type: String, _ chainID: String, _ fee: ShieldedTransfer, _ extra: [Fr]) throws -> Fr {
        PrivacyHash.actionSignal(msgType: type, chainID: chainID, ciphertexts: try fee.ciphertextList(),
                                 nullifiers: try fee.nullifierList(), extra: extra)
    }

    static func spend(_ type: String, _ chainID: String, _ t: ShieldedTransfer, _ extra: [Fr]) throws -> Fr {
        PrivacyHash.spendSignal(msgType: type, chainID: chainID, ciphertexts: try t.ciphertextList(), extra: extra)
    }

    static func multi(_ type: String, _ chainID: String, _ ts: [ShieldedTransfer], _ extra: [Fr]) throws -> Fr {
        PrivacyHash.multiSpendSignal(msgType: type, chainID: chainID, ciphertexts: try ts.map { try $0.ciphertextList() },
                                     nullifiers: try ts.map { try $0.nullifierList() }, extra: extra)
    }

    /// A bech32 address's raw bytes, as the chain's address codec gives them.
    public static func addressBytes(_ bech32: String) throws -> Data { Data(try Bech32.decode(bech32).data) }

    /// The registration binding's affiliate: Bytes(address bytes), or 0 for none.
    public static func affiliateField(_ affiliate: String) throws -> Fr {
        affiliate.isEmpty ? .zero : PrivacyHash.bytes(try addressBytes(affiliate))
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
        guard (1 ... 2).contains(parts.count), parts.allSatisfy({ $0.allSatisfy(\.isNumber) }),
              !(parts[0].isEmpty && (parts.count == 1 || parts[1].isEmpty))
        else { throw Error.badWeight(weight) }
        var intPart = String(parts[0].drop { $0 == "0" })
        if intPart.isEmpty { intPart = "0" }
        var frac = parts.count == 2 ? String(parts[1]) : ""
        while frac.count > 18, frac.last == "0" { frac.removeLast() }
        guard frac.count <= 18 else { throw Error.badWeight(weight) }
        frac += String(repeating: "0", count: 18 - frac.count)
        let scaled = BigUInt(intPart + frac, radix: 10)!
        let one = BigUInt(10).power(18)
        guard scaled > 0, scaled <= one else { throw Error.badWeight(weight) }
        return intPart + "." + frac
    }

    /// OptionsBytes: per option, u64 BE option, u32 BE length, the weight's LegacyDec string.
    public static func optionsBytes(_ opts: [WeightedVoteOption]) throws -> Data {
        var out = Data()
        for o in opts {
            let ws = Data(try legacyDec(o.weight).utf8)
            out += be64(UInt64(o.option)) + be32(UInt32(ws.count)) + ws
        }
        return out
    }

    /// PositionSignBytes: what a position key signs (secp256k1 over its sha256,
    /// low-S, 64-byte r||s):
    /// "earth.shieldedstaking.position" 0 action 0 chain_id 0 id(u64 BE) nonce(u64 BE) payload.
    public static func positionSignBytes(chainID: String, action: String, positionID: UInt64, nonce: UInt64, payload: Data) -> Data {
        Data("earth.shieldedstaking.position".utf8) + Data([0]) + Data(action.utf8) + Data([0]) +
            Data(chainID.utf8) + Data([0]) + be64(positionID) + be64(nonce) + payload
    }

    public static func positionVotePayload(proposalID: UInt64, options: [WeightedVoteOption]) throws -> Data {
        be64(proposalID) + (try optionsBytes(options))
    }

    /// A passport public signal (decimal) as a canonical field element (personhood ParseSignal).
    public static func decimalField(_ s: String) throws -> Fr {
        guard let n = BigUInt(s, radix: 10), n < Fr.modulus, !s.isEmpty, s.allSatisfy(\.isNumber) else { throw Error.badSignal(s) }
        return Fr(n)
    }

    /// Decodes a private msg from its Any (the in-memory chain, reading a tx back).
    public static func decode(typeURL: String, value: Data) throws -> any PrivateMsg {
        let types: [any PrivateMsg.Type] = [
            MsgShieldedTransfer.self, MsgRegisterPrivate.self, MsgClaimAnmlPrivate.self, MsgSetCaretaker.self, MsgBindReferrer.self,
            MsgVoteProposalPrivate.self, MsgProposeRemoval.self, MsgVoteRemoval.self, MsgShieldedDelegate.self,
            MsgShieldedUndelegate.self, MsgClaimUnbonding.self, MsgStakeVote.self, MsgLockPosition.self, MsgUpdatePosition.self,
            MsgUnlockPosition.self, MsgPositionVote.self, MsgNoteSwap.self, MsgAddLiquidityShielded.self,
        ]
        guard let t = types.first(where: { $0.typeURL == typeURL }) as? any DecodablePrivateMsg.Type else {
            throw Error.unknownType(typeURL)
        }
        return try t.decodeMsg(value)
    }
}

public protocol DecodablePrivateMsg: PrivateMsg {
    static func decodeMsg(_ d: Data) throws -> Self
}

// MARK: - x/shielded

/// earth.shielded.v1.MsgTransfer: a private send, or an unshield (receiver set).
public struct MsgShieldedTransfer: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shielded.v1.MsgTransfer"
    public var transfer: ShieldedTransfer
    public var receiver: String
    public var feeFromOutput: UInt64

    public init(transfer: ShieldedTransfer, receiver: String = "", feeFromOutput: UInt64 = 0) {
        self.transfer = transfer; self.receiver = receiver; self.feeFromOutput = feeFromOutput
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.string(2, receiver)
        w.uint64(3, feeFromOutput)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), receiver: f.string(2), feeFromOutput: f.uint64(3))
    }

    public func signal(chainID: String) throws -> Fr {
        PrivacyHash.transferSignal(chainID: chainID, receiver: receiver.isEmpty ? nil : try PrivateMsgs.addressBytes(receiver),
                                   ciphertexts: try transfer.ciphertextList(), feeFromOutput: feeFromOutput)
    }
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

// MARK: - x/personhood

public struct MsgRegisterPrivate: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgRegister"
    public var fee: ShieldedTransfer?
    public var proof: Data
    public var publicSignals: [String]
    public var signatureAlgorithm: String
    public var dscDer: Data
    public var idc: Data
    public var pcAnml: Data
    public var ciphertextAnml: Data
    public var pcErth: Data
    public var ciphertextErth: Data
    public var affiliate: String

    public init(fee: ShieldedTransfer?, proof: Data, publicSignals: [String], signatureAlgorithm: String, dscDer: Data,
                idc: Data, pcAnml: Data, ciphertextAnml: Data, pcErth: Data, ciphertextErth: Data, affiliate: String) {
        self.fee = fee; self.proof = proof; self.publicSignals = publicSignals; self.signatureAlgorithm = signatureAlgorithm
        self.dscDer = dscDer; self.idc = idc; self.pcAnml = pcAnml; self.ciphertextAnml = ciphertextAnml
        self.pcErth = pcErth; self.ciphertextErth = ciphertextErth; self.affiliate = affiliate
    }

    public var transfers: [ShieldedTransfer] { fee.map { [$0] } ?? [] }

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
        w.string(13, affiliate)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: f.has(1) ? try f.message(1, ShieldedTransfer.decode) : nil, proof: f.bytes(2), publicSignals: f.repeatedString(3),
                    signatureAlgorithm: f.string(4), dscDer: f.bytes(5), idc: f.bytes(6), pcAnml: f.bytes(7), ciphertextAnml: f.bytes(8),
                    pcErth: f.bytes(9), ciphertextErth: f.bytes(10), affiliate: f.string(13))
    }

    /// The passport proof's `address` input this msg must carry.
    public func binding() throws -> Fr {
        PrivacyHash.registrationBinding(idc: try PrivateMsgs.f(idc), pcAnml: try PrivateMsgs.f(pcAnml),
                                        pcErth: try PrivateMsgs.f(pcErth), affiliate: try PrivateMsgs.affiliateField(affiliate))
    }

    public func signal(chainID: String) throws -> Fr {
        guard let fee else { throw PrivateMsgs.Error.shape("MsgRegister needs its fee transfer") }
        return try PrivateMsgs.action(Self.typeURL, chainID, fee, [
            try PrivateMsgs.f(idc), try PrivateMsgs.f(pcAnml), PrivateMsgs.bytes(ciphertextAnml), try PrivateMsgs.f(pcErth),
            PrivateMsgs.bytes(ciphertextErth), try PrivateMsgs.affiliateField(affiliate), PrivateMsgs.bytes(signatureAlgorithm),
        ] + publicSignals.map(PrivateMsgs.decimalField))
    }
}

public struct MsgClaimAnmlPrivate: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgClaimAnml"
    public var fee: ShieldedTransfer
    public var membership: Membership
    public var day: UInt64
    public var pc: Data
    public var ciphertext: Data

    public init(fee: ShieldedTransfer, membership: Membership, day: UInt64, pc: Data, ciphertext: Data) {
        self.fee = fee; self.membership = membership; self.day = day; self.pc = pc; self.ciphertext = ciphertext
    }

    public var transfers: [ShieldedTransfer] { [fee] }

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
        return Self(fee: try f.message(1, ShieldedTransfer.decode), membership: try f.message(2, Membership.decode),
                    day: f.uint64(3), pc: f.bytes(4), ciphertext: f.bytes(5))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.action(Self.typeURL, chainID, fee, [PrivateMsgs.u(day), try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext)])
    }
}

public struct MsgSetCaretaker: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgSetCaretaker"
    public var fee: ShieldedTransfer
    public var membership: Membership
    public var percentages: [Msg.AllocationWeight]
    public var maxActivation: UInt64

    public init(fee: ShieldedTransfer, membership: Membership, percentages: [Msg.AllocationWeight], maxActivation: UInt64) {
        self.fee = fee; self.membership = membership; self.percentages = percentages; self.maxActivation = maxActivation
    }

    public var transfers: [ShieldedTransfer] { [fee] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.repeatedMessage(3, percentages)
        w.uint64(4, maxActivation)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedTransfer.decode), membership: try f.message(2, Membership.decode),
                    percentages: try f.repeatedMessage(3, Msg.AllocationWeight.decode), maxActivation: f.uint64(4))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.action(Self.typeURL, chainID, fee, percentages.flatMap { [PrivateMsgs.u($0.optionID), PrivateMsgs.u($0.percent)] })
    }
}

public struct MsgBindReferrer: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.personhood.v1.MsgBindReferrer"
    public var fee: ShieldedTransfer
    public var membership: Membership
    public var address: String
    public var maxActivation: UInt64

    public init(fee: ShieldedTransfer, membership: Membership, address: String, maxActivation: UInt64) {
        self.fee = fee; self.membership = membership; self.address = address; self.maxActivation = maxActivation
    }

    public var transfers: [ShieldedTransfer] { [fee] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.string(3, address)
        w.uint64(4, maxActivation)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedTransfer.decode), membership: try f.message(2, Membership.decode),
                    address: f.string(3), maxActivation: f.uint64(4))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.action(Self.typeURL, chainID, fee, [PrivateMsgs.bytes(address.isEmpty ? Data() : try PrivateMsgs.addressBytes(address))])
    }
}

// MARK: - x/assembly

public struct MsgVoteProposalPrivate: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.assembly.v1.MsgVoteProposal"
    public var fee: ShieldedTransfer
    public var membership: Membership
    public var proposalID: UInt64
    public var option: AssemblyVoteOption

    public init(fee: ShieldedTransfer, membership: Membership, proposalID: UInt64, option: AssemblyVoteOption) {
        self.fee = fee; self.membership = membership; self.proposalID = proposalID; self.option = option
    }

    public var transfers: [ShieldedTransfer] { [fee] }

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
        return Self(fee: try f.message(1, ShieldedTransfer.decode), membership: try f.message(2, Membership.decode),
                    proposalID: f.uint64(3), option: AssemblyVoteOption(rawValue: Int(f.uint64(4))) ?? .yes)
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.action(Self.typeURL, chainID, fee, [PrivateMsgs.u(proposalID), PrivateMsgs.u(UInt64(option.rawValue))])
    }
}

public struct MsgProposeRemoval: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.assembly.v1.MsgProposeRemoval"
    public var fee: ShieldedTransfer
    public var membership: Membership
    public var optionID: UInt64

    public init(fee: ShieldedTransfer, membership: Membership, optionID: UInt64) {
        self.fee = fee; self.membership = membership; self.optionID = optionID
    }

    public var transfers: [ShieldedTransfer] { [fee] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, fee)
        w.message(2, membership)
        w.uint64(3, optionID)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(fee: try f.message(1, ShieldedTransfer.decode), membership: try f.message(2, Membership.decode), optionID: f.uint64(3))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.action(Self.typeURL, chainID, fee, [PrivateMsgs.u(optionID)])
    }
}

public struct MsgVoteRemoval: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.assembly.v1.MsgVoteRemoval"
    public var fee: ShieldedTransfer
    public var membership: Membership
    public var optionID: UInt64
    public var option: AssemblyVoteOption

    public init(fee: ShieldedTransfer, membership: Membership, optionID: UInt64, option: AssemblyVoteOption) {
        self.fee = fee; self.membership = membership; self.optionID = optionID; self.option = option
    }

    public var transfers: [ShieldedTransfer] { [fee] }

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
        return Self(fee: try f.message(1, ShieldedTransfer.decode), membership: try f.message(2, Membership.decode),
                    optionID: f.uint64(3), option: AssemblyVoteOption(rawValue: Int(f.uint64(4))) ?? .yes)
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.action(Self.typeURL, chainID, fee, [PrivateMsgs.u(optionID), PrivateMsgs.u(UInt64(option.rawValue))])
    }
}

// MARK: - x/shieldedstaking

public struct MsgShieldedDelegate: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgDelegate"
    public var transfer: ShieldedTransfer
    public var validator: String
    public var pc: Data
    public var ciphertext: Data

    public init(transfer: ShieldedTransfer, validator: String, pc: Data, ciphertext: Data) {
        self.transfer = transfer; self.validator = validator; self.pc = pc; self.ciphertext = ciphertext
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.string(2, validator)
        w.bytes(3, pc)
        w.bytes(4, ciphertext)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), validator: f.string(2), pc: f.bytes(3), ciphertext: f.bytes(4))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [PrivateMsgs.bytes(validator), try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext)])
    }
}

public struct MsgShieldedUndelegate: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgUndelegate"
    public var transfer: ShieldedTransfer
    public var validator: String
    public var pc: Data
    public var ciphertext: Data

    public init(transfer: ShieldedTransfer, validator: String, pc: Data, ciphertext: Data) {
        self.transfer = transfer; self.validator = validator; self.pc = pc; self.ciphertext = ciphertext
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.string(2, validator)
        w.bytes(3, pc)
        w.bytes(4, ciphertext)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), validator: f.string(2), pc: f.bytes(3), ciphertext: f.bytes(4))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [PrivateMsgs.bytes(validator), try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext)])
    }
}

public struct MsgClaimUnbonding: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgClaimUnbonding"
    public var transfer: ShieldedTransfer
    public var validator: String
    public var epoch: UInt64
    public var pc: Data
    public var ciphertext: Data
    public var feeFromOutput: UInt64

    public init(transfer: ShieldedTransfer, validator: String, epoch: UInt64, pc: Data, ciphertext: Data, feeFromOutput: UInt64) {
        self.transfer = transfer; self.validator = validator; self.epoch = epoch; self.pc = pc
        self.ciphertext = ciphertext; self.feeFromOutput = feeFromOutput
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.string(2, validator)
        w.uint64(3, epoch)
        w.bytes(4, pc)
        w.bytes(5, ciphertext)
        w.uint64(6, feeFromOutput)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), validator: f.string(2), epoch: f.uint64(3),
                    pc: f.bytes(4), ciphertext: f.bytes(5), feeFromOutput: f.uint64(6))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [
            PrivateMsgs.bytes(validator), PrivateMsgs.u(epoch), try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext), PrivateMsgs.u(feeFromOutput),
        ])
    }
}

public struct MsgStakeVote: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgStakeVote"
    public var transfer: ShieldedTransfer
    public var proposalID: UInt64
    public var validator: String
    public var options: [WeightedVoteOption]
    public var pc: Data
    public var ciphertext: Data
    public var feeTransfer: ShieldedTransfer

    public init(transfer: ShieldedTransfer, proposalID: UInt64, validator: String, options: [WeightedVoteOption],
                pc: Data, ciphertext: Data, feeTransfer: ShieldedTransfer) {
        self.transfer = transfer; self.proposalID = proposalID; self.validator = validator; self.options = options
        self.pc = pc; self.ciphertext = ciphertext; self.feeTransfer = feeTransfer
    }

    public var transfers: [ShieldedTransfer] { [transfer, feeTransfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.uint64(2, proposalID)
        w.string(3, validator)
        w.repeatedMessage(7, options)
        w.bytes(9, pc)
        w.bytes(10, ciphertext)
        w.message(11, feeTransfer)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), proposalID: f.uint64(2), validator: f.string(3),
                    options: try f.repeatedMessage(7, WeightedVoteOption.decode), pc: f.bytes(9), ciphertext: f.bytes(10),
                    feeTransfer: try f.message(11, ShieldedTransfer.decode))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.multi(Self.typeURL, chainID, transfers, [
            PrivateMsgs.u(proposalID), PrivateMsgs.bytes(validator), PrivacyHash.bytes(try PrivateMsgs.optionsBytes(options)),
            try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext),
        ])
    }
}

public struct MsgLockPosition: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgLockPosition"
    public var transfer: ShieldedTransfer
    public var validator: String
    public var splits: [Msg.AllocationWeight]
    public var pubkey: Data

    public init(transfer: ShieldedTransfer, validator: String, splits: [Msg.AllocationWeight], pubkey: Data) {
        self.transfer = transfer; self.validator = validator; self.splits = splits; self.pubkey = pubkey
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.string(2, validator)
        w.repeatedMessage(3, splits)
        w.bytes(4, pubkey)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), validator: f.string(2),
                    splits: try f.repeatedMessage(3, Msg.AllocationWeight.decode), pubkey: f.bytes(4))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [
            PrivateMsgs.bytes(validator), PrivateMsgs.bytes(pubkey), PrivacyHash.bytes(PrivateMsgs.splitsBytes(splits)),
        ])
    }
}

public struct MsgUpdatePosition: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgUpdatePosition"
    public var transfer: ShieldedTransfer
    public var positionID: UInt64
    public var splits: [Msg.AllocationWeight]
    public var signature: Data

    public init(transfer: ShieldedTransfer, positionID: UInt64, splits: [Msg.AllocationWeight], signature: Data) {
        self.transfer = transfer; self.positionID = positionID; self.splits = splits; self.signature = signature
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.uint64(2, positionID)
        w.repeatedMessage(3, splits)
        w.bytes(4, signature)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), positionID: f.uint64(2),
                    splits: try f.repeatedMessage(3, Msg.AllocationWeight.decode), signature: f.bytes(4))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [
            PrivateMsgs.u(positionID), PrivacyHash.bytes(PrivateMsgs.splitsBytes(splits)), PrivateMsgs.bytes(signature),
        ])
    }
}

public struct MsgUnlockPosition: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgUnlockPosition"
    public var transfer: ShieldedTransfer
    public var positionID: UInt64
    public var pc: Data
    public var ciphertext: Data
    public var signature: Data

    public init(transfer: ShieldedTransfer, positionID: UInt64, pc: Data, ciphertext: Data, signature: Data) {
        self.transfer = transfer; self.positionID = positionID; self.pc = pc; self.ciphertext = ciphertext; self.signature = signature
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.uint64(2, positionID)
        w.bytes(3, pc)
        w.bytes(4, ciphertext)
        w.bytes(5, signature)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), positionID: f.uint64(2), pc: f.bytes(3),
                    ciphertext: f.bytes(4), signature: f.bytes(5))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [
            PrivateMsgs.u(positionID), try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext), PrivateMsgs.bytes(signature),
        ])
    }
}

public struct MsgPositionVote: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.shieldedstaking.v1.MsgPositionVote"
    public var transfer: ShieldedTransfer
    public var positionID: UInt64
    public var proposalID: UInt64
    public var options: [WeightedVoteOption]
    public var signature: Data

    public init(transfer: ShieldedTransfer, positionID: UInt64, proposalID: UInt64, options: [WeightedVoteOption], signature: Data) {
        self.transfer = transfer; self.positionID = positionID; self.proposalID = proposalID; self.options = options; self.signature = signature
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.uint64(2, positionID)
        w.uint64(3, proposalID)
        w.repeatedMessage(4, options)
        w.bytes(5, signature)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), positionID: f.uint64(2), proposalID: f.uint64(3),
                    options: try f.repeatedMessage(4, WeightedVoteOption.decode), signature: f.bytes(5))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [
            PrivateMsgs.u(positionID), PrivateMsgs.u(proposalID), PrivacyHash.bytes(try PrivateMsgs.optionsBytes(options)),
            PrivateMsgs.bytes(signature),
        ])
    }
}

// MARK: - x/dex

public struct MsgNoteSwap: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.dex.v1.MsgNoteSwap"
    public var transfer: ShieldedTransfer
    public var denomOut: String
    public var minAmountOut: UInt64
    public var pc: Data
    public var ciphertext: Data
    public var feeFromOutput: UInt64

    public init(transfer: ShieldedTransfer, denomOut: String, minAmountOut: UInt64, pc: Data, ciphertext: Data = Data(), feeFromOutput: UInt64 = 0) {
        self.transfer = transfer; self.denomOut = denomOut; self.minAmountOut = minAmountOut; self.pc = pc
        self.ciphertext = ciphertext; self.feeFromOutput = feeFromOutput
    }

    public var transfers: [ShieldedTransfer] { [transfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.string(2, denomOut)
        w.uint64(3, minAmountOut)
        w.bytes(4, pc)
        w.bytes(5, ciphertext)
        w.uint64(6, feeFromOutput)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), denomOut: f.string(2), minAmountOut: f.uint64(3),
                    pc: f.bytes(4), ciphertext: f.bytes(5), feeFromOutput: f.uint64(6))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.spend(Self.typeURL, chainID, transfer, [
            PrivateMsgs.bytes(denomOut), PrivateMsgs.u(minAmountOut), try PrivateMsgs.f(pc), PrivateMsgs.bytes(ciphertext),
            PrivateMsgs.u(feeFromOutput),
        ])
    }
}

public struct MsgAddLiquidityShielded: DecodablePrivateMsg, Equatable {
    public static let typeURL = "/earth.dex.v1.MsgAddLiquidityShielded"
    public var transfer: ShieldedTransfer
    public var erthTransfer: ShieldedTransfer
    public var poolID: UInt64
    public var provider: String
    public var minShares: String
    public var refundPC: Data
    public var refundCiphertext: Data

    public init(transfer: ShieldedTransfer, erthTransfer: ShieldedTransfer, poolID: UInt64, provider: String, minShares: String,
                refundPC: Data, refundCiphertext: Data = Data()) {
        self.transfer = transfer; self.erthTransfer = erthTransfer; self.poolID = poolID; self.provider = provider
        self.minShares = minShares; self.refundPC = refundPC; self.refundCiphertext = refundCiphertext
    }

    public var transfers: [ShieldedTransfer] { [transfer, erthTransfer] }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, transfer)
        w.message(2, erthTransfer)
        w.uint64(3, poolID)
        w.string(4, provider)
        w.string(5, minShares)
        w.bytes(6, refundPC)
        w.bytes(7, refundCiphertext)
        return w.data
    }

    public static func decodeMsg(_ d: Data) throws -> Self {
        let f = try ProtoFields(d)
        return Self(transfer: try f.message(1, ShieldedTransfer.decode), erthTransfer: try f.message(2, ShieldedTransfer.decode),
                    poolID: f.uint64(3), provider: f.string(4), minShares: f.string(5), refundPC: f.bytes(6), refundCiphertext: f.bytes(7))
    }

    public func signal(chainID: String) throws -> Fr {
        try PrivateMsgs.multi(Self.typeURL, chainID, transfers, [
            PrivateMsgs.u(poolID), PrivacyHash.bytes(try PrivateMsgs.addressBytes(provider)), PrivateMsgs.bytes(minShares),
            try PrivateMsgs.f(refundPC), PrivateMsgs.bytes(refundCiphertext),
        ])
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
