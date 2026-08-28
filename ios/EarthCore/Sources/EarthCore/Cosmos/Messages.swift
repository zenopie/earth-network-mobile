import Foundation

// Every message the wallet sends. Field numbers mirror the .proto files under
// android/app/src/main/proto — cosmos/{bank,staking,distribution} and
// earth/{dex,personhood,allocation}.

public enum Msg {

    // --- cosmos/bank ---

    public struct Send: ProtoMessage {
        public static let typeURL = "/cosmos.bank.v1beta1.MsgSend"
        public let from: String, to: String, amount: [Coin]

        public init(from: String, to: String, amount: [Coin]) {
            self.from = from; self.to = to; self.amount = amount
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, from)
            w.string(2, to)
            w.repeatedMessage(3, amount)
            return w.data
        }
    }

    // --- cosmos/staking ---

    public struct Delegate: ProtoMessage {
        public static let typeURL = "/cosmos.staking.v1beta1.MsgDelegate"
        public let delegator: String, validator: String, amount: Coin

        public init(delegator: String, validator: String, amount: Coin) {
            self.delegator = delegator; self.validator = validator; self.amount = amount
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, delegator)
            w.string(2, validator)
            w.message(3, amount)
            return w.data
        }
    }

    public struct Undelegate: ProtoMessage {
        public static let typeURL = "/cosmos.staking.v1beta1.MsgUndelegate"
        public let delegator: String, validator: String, amount: Coin

        public init(delegator: String, validator: String, amount: Coin) {
            self.delegator = delegator; self.validator = validator; self.amount = amount
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, delegator)
            w.string(2, validator)
            w.message(3, amount)
            return w.data
        }
    }

    public struct BeginRedelegate: ProtoMessage {
        public static let typeURL = "/cosmos.staking.v1beta1.MsgBeginRedelegate"
        public let delegator: String, source: String, destination: String, amount: Coin

        public init(delegator: String, source: String, destination: String, amount: Coin) {
            self.delegator = delegator; self.source = source
            self.destination = destination; self.amount = amount
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, delegator)
            w.string(2, source)
            w.string(3, destination)
            w.message(4, amount)
            return w.data
        }
    }

    public struct CancelUnbondingDelegation: ProtoMessage {
        public static let typeURL = "/cosmos.staking.v1beta1.MsgCancelUnbondingDelegation"
        public let delegator: String, validator: String, amount: Coin, creationHeight: Int64

        public init(delegator: String, validator: String, amount: Coin, creationHeight: Int64) {
            self.delegator = delegator; self.validator = validator
            self.amount = amount; self.creationHeight = creationHeight
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, delegator)
            w.string(2, validator)
            w.message(3, amount)
            w.int64(4, creationHeight)
            return w.data
        }
    }

    // --- cosmos/distribution ---

    public struct WithdrawDelegatorReward: ProtoMessage {
        public static let typeURL = "/cosmos.distribution.v1beta1.MsgWithdrawDelegatorReward"
        public let delegator: String, validator: String

        public init(delegator: String, validator: String) {
            self.delegator = delegator; self.validator = validator
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, delegator)
            w.string(2, validator)
            return w.data
        }
    }

    /// A vote on a chain proposal.
    ///
    /// `cosmos.gov.v1`, not v1beta1. The chain runs SDK 0.53 and the read side
    /// already uses v1 — mixing the two would have the app reading a proposal
    /// by one id space and voting in another.
    public struct Vote: ProtoMessage {
        public static let typeURL = "/cosmos.gov.v1.MsgVote"
        public let proposalID: UInt64, voter: String, option: Gov.Vote

        public init(proposalID: UInt64, voter: String, option: Gov.Vote) {
            self.proposalID = proposalID; self.voter = voter; self.option = option
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.uint64(1, proposalID)
            w.string(2, voter)
            // Never `.unspecified`, which is zero and would be elided — the
            // chain then reads a vote with no option and rejects it.
            w.enumValue(3, option.proto)
            // metadata (4) is left off: proto3 elides an empty string, and
            // emitting one changes the bytes SIGN_MODE_DIRECT signs over.
            return w.data
        }
    }

    // --- earth/dex ---

    public struct Swap: ProtoMessage {
        public static let typeURL = "/earth.dex.v1.MsgSwap"
        public let creator: String, tokenIn: Coin, denomOut: String, minAmountOut: String

        public init(creator: String, tokenIn: Coin, denomOut: String, minAmountOut: String) {
            self.creator = creator; self.tokenIn = tokenIn
            self.denomOut = denomOut; self.minAmountOut = minAmountOut
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.message(2, tokenIn)
            w.string(3, denomOut)
            w.string(4, minAmountOut)
            return w.data
        }
    }

    public struct AddLiquidity: ProtoMessage {
        public static let typeURL = "/earth.dex.v1.MsgAddLiquidity"
        public let creator: String, poolID: UInt64, amountA: Coin, amountB: Coin

        public init(creator: String, poolID: UInt64, amountA: Coin, amountB: Coin) {
            self.creator = creator; self.poolID = poolID
            self.amountA = amountA; self.amountB = amountB
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.uint64(2, poolID)
            w.message(3, amountA)
            w.message(4, amountB)
            return w.data
        }
    }

    public struct RemoveLiquidity: ProtoMessage {
        public static let typeURL = "/earth.dex.v1.MsgRemoveLiquidity"
        public let creator: String, poolID: UInt64, shares: Coin

        public init(creator: String, poolID: UInt64, shares: Coin) {
            self.creator = creator; self.poolID = poolID; self.shares = shares
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.uint64(2, poolID)
            w.message(3, shares)
            return w.data
        }
    }

    // --- earth/personhood ---

    /// The registration message. `proof` and `dscDer` are raw bytes; the LCD
    /// renders them base64 on the way back out, but on the wire they are bytes.
    public struct Register: ProtoMessage {
        public static let typeURL = Constants.msgRegisterTypeURL
        public let creator: String
        public let proof: Data
        public let publicSignals: [String]
        public let affiliate: String
        public let signatureAlgorithm: String
        public let dscDer: Data

        public init(
            creator: String,
            proof: Data,
            publicSignals: [String],
            affiliate: String = "",
            signatureAlgorithm: String,
            dscDer: Data
        ) {
            self.creator = creator
            self.proof = proof
            self.publicSignals = publicSignals
            self.affiliate = affiliate
            self.signatureAlgorithm = signatureAlgorithm
            self.dscDer = dscDer
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.bytes(2, proof)
            w.repeatedString(3, publicSignals)
            w.string(4, affiliate)
            w.string(5, signatureAlgorithm)
            w.bytes(6, dscDer)
            return w.data
        }
    }

    public struct ClaimAnml: ProtoMessage {
        public static let typeURL = "/earth.personhood.v1.MsgClaimAnml"
        public let creator: String

        public init(creator: String) { self.creator = creator }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            return w.data
        }
    }

    /// Retires the signer's own registration and frees its nullifier. Carries
    /// no proof: the signer is the registered address, so the worst a wrong
    /// signature can do is retire the signer's own registration.
    public struct Unregister: ProtoMessage {
        public static let typeURL = "/earth.personhood.v1.MsgUnregister"
        public let creator: String

        public init(creator: String) { self.creator = creator }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            return w.data
        }
    }

    // --- earth/allocation ---

    public enum StreamID: Int {
        case unspecified = 0
        case caretaker = 1
        case groundworks = 2
    }

    public struct AllocationWeight: ProtoMessage {
        public let optionID: UInt64, percent: UInt64

        public init(optionID: UInt64, percent: UInt64) {
            self.optionID = optionID; self.percent = percent
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.uint64(1, optionID)
            w.uint64(2, percent)
            return w.data
        }
    }

    public struct SetAllocations: ProtoMessage {
        public static let typeURL = "/earth.allocation.v1.MsgSetAllocations"
        public let creator: String, stream: StreamID, percentages: [AllocationWeight]

        public init(creator: String, stream: StreamID, percentages: [AllocationWeight]) {
            self.creator = creator; self.stream = stream; self.percentages = percentages
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.enumValue(2, stream.rawValue)
            w.repeatedMessage(3, percentages)
            return w.data
        }
    }

    public struct ClaimAllocation: ProtoMessage {
        public static let typeURL = "/earth.allocation.v1.MsgClaimAllocation"
        public let creator: String, stream: StreamID, optionID: UInt64

        public init(creator: String, stream: StreamID, optionID: UInt64) {
            self.creator = creator; self.stream = stream; self.optionID = optionID
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.enumValue(2, stream.rawValue)
            w.uint64(3, optionID)
            return w.data
        }
    }
}
