import Foundation

/// Chain governance: the SDK's x/gov, unchanged.
///
/// Separate from x/allocation, which Earth also calls governance and which is a
/// different thing: allocation votes direct an emission stream continuously and
/// are weighted by personhood or stake, while these proposals change the chain
/// itself, run for a fixed period, and are weighted by bonded stake alone.
public enum Gov {

    /// How to vote. Raw values are the proto enum's, which is why they are
    /// spelled out rather than left to declaration order.
    public enum Vote: Int, Sendable, CaseIterable {
        case yes = 1
        case abstain = 2
        case no = 3
        case noWithVeto = 4

        public var proto: Int { rawValue }

        public var label: String {
            switch self {
            case .yes: "Yes"
            case .abstain: "Abstain"
            case .no: "No"
            case .noWithVeto: "No with veto"
            }
        }
    }

    public struct Proposal: Sendable, Equatable, Hashable, Identifiable {
        public let id: UInt64
        public let title: String
        public let summary: String
        /// `PROPOSAL_STATUS_VOTING_PERIOD` and friends, as the chain names them.
        public let status: String
        public let votingEndTime: String
        public let yes: Int64
        public let no: Int64
        public let abstain: Int64
        public let veto: Int64

        public var total: Int64 { yes + no + abstain + veto }

        /// Open for voting. The only state in which a vote is accepted.
        public var isLive: Bool { status == "PROPOSAL_STATUS_VOTING_PERIOD" }
    }
}

public extension EarthClient {

    /// Proposals, newest first.
    ///
    /// v1 rather than v1beta1: v1 carries the title and summary as fields,
    /// where v1beta1 buries them in a content Any that has to be unpacked per
    /// message type.
    func proposals(limit: Int = 20) async -> [Gov.Proposal] {
        guard let json = try? await rest.get(
            "/cosmos/gov/v1/proposals?pagination.limit=\(limit)&pagination.reverse=true"
        ) else { return [] }
        return json.proposals.array.map { p in
            let id = p.id.uint64(default: 0)
            let tally = p.final_tally_result
            let title = p.title.string(default: "")
            return Gov.Proposal(
                id: id,
                title: title.isEmpty ? "Proposal \(id)" : title,
                summary: p.summary.string(default: ""),
                status: p.status.string(default: ""),
                votingEndTime: p.voting_end_time.string(default: ""),
                yes: tally.yes_count.int64(default: 0),
                no: tally.no_count.int64(default: 0),
                abstain: tally.abstain_count.int64(default: 0),
                veto: tally.no_with_veto_count.int64(default: 0)
            )
        }
    }

    // --- messages ---

    /// A vote on `proposalID`.
    ///
    /// Weighted by bonded stake alone. An address with nothing delegated can
    /// broadcast this successfully and move the tally by nothing, so the screen
    /// says so rather than letting it look like a vote that failed.
    func msgVote(voter: String, proposalID: UInt64, option: Gov.Vote) -> ProtoAny {
        Msg.Vote(proposalID: proposalID, voter: voter, option: option)
            .asAny(typeURL: Msg.Vote.typeURL)
    }

    @discardableResult
    func vote(
        key: EarthKey,
        proposalID: UInt64,
        option: Gov.Vote
    ) async throws -> String {
        try await broadcast(
            [msgVote(voter: key.address, proposalID: proposalID, option: option)],
            key: key
        )
    }
}
