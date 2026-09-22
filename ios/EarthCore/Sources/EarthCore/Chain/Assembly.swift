import Foundation

/// x/assembly — the chain's second governance house.
///
/// Every x/gov proposal is voted twice. ``Gov`` is the stake house, weighted by
/// bonded ERTH; this is the human one, where a live proof-of-personhood
/// registration is one vote and holdings count for nothing. A proposal needs
/// two thirds of the votes cast here before x/gov's own result is allowed to
/// take effect, so neither house substitutes for the other and a wallet with
/// both kinds of standing has two separate votes to cast.
///
/// There is no abstain and no quorum. Approval is measured against the votes
/// cast, with two consequences worth surfacing rather than burying: a proposal
/// nobody votes on **fails**, and at low turnout a single vote decides it. One
/// objector defeats one supporter — two thirds of (1 yes, 1 no) is not met — so
/// turnout scales with disagreement instead of being demanded up front.
public enum Assembly {

    /// Yes or no. See the type note on why there is no abstain.
    public enum Vote: Int, Sendable, CaseIterable {
        case yes = 1
        case no = 2

        public var proto: Int { rawValue }

        public var label: String {
            switch self {
            case .yes: "Yes"
            case .no: "No"
            }
        }
    }

    /// The human vote on one proposal.
    public struct Tally: Sendable, Equatable, Hashable {
        public let yes: Int64
        public let no: Int64
        /// The chain's own answer to "does this clear two thirds".
        ///
        /// Read rather than recomputed: the rule lives in one place, and a
        /// client repeating the arithmetic would quietly disagree the day the
        /// constant moves in a chain upgrade.
        public let approved: Bool

        public var total: Int64 { yes + no }

        /// Nobody has voted — which is a refusal here, not a blank. Worth
        /// telling apart from a low tally because it is the state most easily
        /// mistaken for "nothing has happened yet".
        public var isSilent: Bool { total == 0 }
    }
}

public extension EarthClient {

    /// The human tally on a proposal, or `nil` if this chain has no assembly.
    ///
    /// `nil` is the expected answer before the v0.9.0 upgrade lands, not an
    /// error: the endpoint does not exist on an older node, and a wallet that
    /// shipped ahead of the upgrade — as it must, or nobody could vote when it
    /// arrives — has to render a proposal without a second house and without
    /// an error message about it.
    func assemblyTally(proposalID: UInt64) async -> Assembly.Tally? {
        guard let json = try? await rest.get(
            "/earth/assembly/v1/proposal_tally/\(proposalID)"
        ) else { return nil }
        let tally = json.tally
        return Assembly.Tally(
            yes: tally.yes.int64(default: 0),
            no: tally.no.int64(default: 0),
            approved: json.approved.bool(default: false)
        )
    }

    // --- messages ---

    /// A human vote on `proposalID`.
    ///
    /// Weighted by personhood alone. An address without a live registration can
    /// broadcast this and have it rejected, so the screen checks first rather
    /// than spending the gas to find out.
    func msgVoteProposal(voter: String, proposalID: UInt64, option: Assembly.Vote) -> ProtoAny {
        Msg.VoteProposal(voter: voter, proposalID: proposalID, option: option)
            .asAny(typeURL: Msg.VoteProposal.typeURL)
    }

    @discardableResult
    func voteAsPerson(
        key: EarthKey,
        proposalID: UInt64,
        option: Assembly.Vote
    ) async throws -> String {
        try await broadcast(
            [msgVoteProposal(voter: key.address, proposalID: proposalID, option: option)],
            key: key
        )
    }
}
