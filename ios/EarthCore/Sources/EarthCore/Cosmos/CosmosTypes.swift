import Foundation

// The subset of the Cosmos SDK's protobuf types a wallet needs to build,
// sign, and broadcast a transaction. Field numbers come from the .proto files
// checked in under android/app/src/main/proto — read those rather than this
// file if the two ever disagree.

public struct ProtoAny: ProtoMessage {
    public let typeURL: String
    public let value: Data

    public init(typeURL: String, value: Data) {
        self.typeURL = typeURL
        self.value = value
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.string(1, typeURL)
        w.bytes(2, value)
        return w.data
    }
}

public struct Coin: ProtoMessage {
    public let denom: String
    /// Base units as a decimal string — the chain's own representation, kept
    /// as text so an amount larger than 64 bits survives the round trip.
    public let amount: String

    public init(denom: String, amount: String) {
        self.denom = denom
        self.amount = amount
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.string(1, denom)
        w.string(2, amount)
        return w.data
    }
}

public struct Secp256k1PubKey: ProtoMessage {
    public static let typeURL = "/cosmos.crypto.secp256k1.PubKey"
    public let key: Data

    public init(key: Data) { self.key = key }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, key)
        return w.data
    }
}

public struct TxBody: ProtoMessage {
    public let messages: [ProtoAny]
    public let memo: String
    public let timeoutHeight: UInt64

    public init(messages: [ProtoAny], memo: String = "", timeoutHeight: UInt64 = 0) {
        self.messages = messages
        self.memo = memo
        self.timeoutHeight = timeoutHeight
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.repeatedMessage(1, messages)
        w.string(2, memo)
        w.uint64(3, timeoutHeight)
        return w.data
    }
}

/// `cosmos.tx.signing.v1beta1.SignMode`.
public enum SignMode: Int {
    case direct = 1
}

public struct ModeInfo: ProtoMessage {
    public let mode: SignMode

    public init(mode: SignMode = .direct) { self.mode = mode }

    private struct Single: ProtoMessage {
        let mode: SignMode
        func encoded() -> Data {
            var w = ProtoWriter()
            w.enumValue(1, mode.rawValue)
            return w.data
        }
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, Single(mode: mode))
        return w.data
    }
}

public struct SignerInfo: ProtoMessage {
    public let publicKey: ProtoAny
    public let modeInfo: ModeInfo
    public let sequence: UInt64

    public init(publicKey: ProtoAny, modeInfo: ModeInfo = ModeInfo(), sequence: UInt64) {
        self.publicKey = publicKey
        self.modeInfo = modeInfo
        self.sequence = sequence
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.message(1, publicKey)
        w.message(2, modeInfo)
        w.uint64(3, sequence)
        return w.data
    }
}

public struct Fee: ProtoMessage {
    public let amount: [Coin]
    public let gasLimit: UInt64

    public init(amount: [Coin], gasLimit: UInt64) {
        self.amount = amount
        self.gasLimit = gasLimit
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.repeatedMessage(1, amount)
        w.uint64(2, gasLimit)
        return w.data
    }
}

public struct AuthInfo: ProtoMessage {
    public let signerInfos: [SignerInfo]
    public let fee: Fee

    public init(signerInfos: [SignerInfo], fee: Fee) {
        self.signerInfos = signerInfos
        self.fee = fee
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.repeatedMessage(1, signerInfos)
        w.message(2, fee)
        return w.data
    }
}

/// What SIGN_MODE_DIRECT actually signs. Carries the body and auth info as
/// *bytes*, not as messages, so the signature commits to a single encoding —
/// which is the whole point of the mode, and the reason the writer above must
/// not emit default values the chain would elide.
public struct SignDoc: ProtoMessage {
    public let bodyBytes: Data
    public let authInfoBytes: Data
    public let chainID: String
    public let accountNumber: UInt64

    public init(bodyBytes: Data, authInfoBytes: Data, chainID: String, accountNumber: UInt64) {
        self.bodyBytes = bodyBytes
        self.authInfoBytes = authInfoBytes
        self.chainID = chainID
        self.accountNumber = accountNumber
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, bodyBytes)
        w.bytes(2, authInfoBytes)
        w.string(3, chainID)
        w.uint64(4, accountNumber)
        return w.data
    }
}

public struct TxRaw: ProtoMessage {
    public let bodyBytes: Data
    public let authInfoBytes: Data
    public let signatures: [Data]

    public init(bodyBytes: Data, authInfoBytes: Data, signatures: [Data]) {
        self.bodyBytes = bodyBytes
        self.authInfoBytes = authInfoBytes
        self.signatures = signatures
    }

    public func encoded() -> Data {
        var w = ProtoWriter()
        w.bytes(1, bodyBytes)
        w.bytes(2, authInfoBytes)
        w.repeatedBytes(3, signatures)
        return w.data
    }
}
