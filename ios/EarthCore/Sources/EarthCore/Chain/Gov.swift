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

        /// The status the chain reports while a proposal is open. Named rather
        /// than spelled out twice: `proposals()` has to test it before it has a
        /// Proposal to ask.
        public static let votingPeriodStatus = "PROPOSAL_STATUS_VOTING_PERIOD"

        /// Open for voting. The only state in which a vote is accepted.
        public var isLive: Bool { status == Self.votingPeriodStatus }
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
        var out: [Gov.Proposal] = []
        for p in json.proposals.array {
            let id = p.id.uint64(default: 0)
            let status = p.status.string(default: "")

            // final_tally_result is written by the gov EndBlocker when voting
            // closes, so it reads 0/0/0/0 for as long as a proposal is open --
            // which is exactly when the numbers are worth looking at. A voter
            // who has just voted sees their vote missing and reasonably
            // concludes it did not land.
            //
            // The running count is its own endpoint, and only while the
            // proposal is live: for a closed one final_tally_result is both
            // correct and the historical record, and costs no second request.
            // A failed tally call falls back to those zeros rather than
            // dropping the proposal from the list.
            var tally = p.final_tally_result
            if status == Gov.Proposal.votingPeriodStatus,
               let running = try? await rest.get("/cosmos/gov/v1/proposals/\(id)/tally") {
                tally = running.tally
            }

            let title = p.title.string(default: "")
            out.append(Gov.Proposal(
                id: id,
                title: title.isEmpty ? "Proposal \(id)" : title,
                summary: p.summary.string(default: ""),
                status: status,
                votingEndTime: p.voting_end_time.string(default: ""),
                yes: tally.yes_count.int64(default: 0),
                no: tally.no_count.int64(default: 0),
                abstain: tally.abstain_count.int64(default: 0),
                veto: tally.no_with_veto_count.int64(default: 0)
            ))
        }
        return out
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
