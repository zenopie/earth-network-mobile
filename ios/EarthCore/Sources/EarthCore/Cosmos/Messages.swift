import Foundation

// The transparent messages the wallet signs, and the allocation types the
// private msgs carry. Field numbers mirror the .proto files under
// android/app/src/main/proto — cosmos/{bank,distribution,gov} and
// earth/{dex,allocation}.

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

    // x/personhood and x/assembly take no signed msgs on the privacy chain:
    // registration, the ANML claim and the human vote are private
    // (Privacy/PrivateMsgs.swift), so there is nothing transparent to build.

    // --- earth/dex ---

    public struct AddLiquidity: ProtoMessage {
        public static let typeURL = "/earth.dex.v1.MsgAddLiquidity"
        public let creator: String, poolID: UInt64, amountA: Coin, amountB: Coin
        /// The fewest shares the deposit accepts, a decimal integer ("" none): field 5 (audit 6, M9).
        public let minShares: String

        public init(creator: String, poolID: UInt64, amountA: Coin, amountB: Coin, minShares: String) {
            self.creator = creator; self.poolID = poolID
            self.amountA = amountA; self.amountB = amountB; self.minShares = minShares
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.uint64(2, poolID)
            w.message(3, amountA)
            w.message(4, amountB)
            w.string(5, minShares)
            return w.data
        }
    }

    public struct RemoveLiquidity: ProtoMessage {
        public static let typeURL = "/earth.dex.v1.MsgRemoveLiquidity"
        public let creator: String, poolID: UInt64, shares: Coin
        /// Pool 1 (ANML) only: the note its ANML leg is minted to when the
        /// withdrawal matures — a self-mint pc, since the payout is priced
        /// then (ANML exists only shielded). Empty for every other pool.
        public let pc: Data, ciphertext: Data

        public init(creator: String, poolID: UInt64, shares: Coin, pc: Data = Data(), ciphertext: Data = Data()) {
            self.creator = creator; self.poolID = poolID; self.shares = shares
            self.pc = pc; self.ciphertext = ciphertext
        }

        public func encoded() -> Data {
            var w = ProtoWriter()
            w.string(1, creator)
            w.uint64(2, poolID)
            w.message(3, shares)
            w.bytes(4, pc)
            w.bytes(5, ciphertext)
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
}
