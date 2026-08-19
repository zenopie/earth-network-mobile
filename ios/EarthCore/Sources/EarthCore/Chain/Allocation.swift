import Foundation

/// x/allocation — both vote-directed emission streams, over one engine.
///
/// Every read and message names a stream. The two share the option mechanics
/// and share no state: ids, totals and epochs are per stream, so an option id
/// only means something together with the stream it belongs to.
///
///   caretaker   — one human, one vote; needs a live registration.
///   groundworks — weighted by bonded stake, kept in step by the staking hooks.
public enum Allocation {

    public struct OptionInfo: Sendable, Equatable {
        public let id: UInt64
        public let description: String
        public let kind: String
        public let amountAllocated: String
        /// What the chain does with this option's emission.
        ///
        /// "lp_rewards" is the one the liquidity screen cares about — it names
        /// the option whose accrual is handed to the dex. Matching on the
        /// handler rather than the description means a rename in governance
        /// does not silently detach the APR from its source.
        public let handler: String
    }

    /// A stream's options and the weight they are shares of.
    ///
    /// `totalWeight` is the denominator: an option earns
    /// `amountAllocated / totalWeight` of the stream's 1 ERTH/sec. Returned
    /// alongside rather than summed client-side, which would drift the moment
    /// an option is added between queries.
    public struct Stream: Sendable, Equatable {
        public let options: [OptionInfo]
        public let totalWeight: String

        public static let empty = Stream(options: [], totalWeight: "0")
    }

    /// One voter's weight on one option.
    public struct Weight: Sendable, Equatable {
        public let optionID: UInt64
        public let percent: UInt64
    }

    /// The LCD spells the stream out in full: grpc-gateway parses the enum by
    /// name and rejects the short `human` / `capital` form the chain's CLI takes.
    static func path(_ stream: Msg.StreamID) -> String {
        switch stream {
        case .caretaker: return "STREAM_ID_CARETAKER"
        case .groundworks: return "STREAM_ID_GROUNDWORKS"
        case .unspecified: return "STREAM_ID_UNSPECIFIED"
        }
    }
}

public extension EarthClient {

    func stream(_ stream: Msg.StreamID) async -> Allocation.Stream {
        guard let json = try? await rest.get(
            "/earth-network/earth/allocation/v1/options/\(Allocation.path(stream))"
        ) else { return .empty }
        return Allocation.Stream(
            options: json.options.array.map {
                Allocation.OptionInfo(
                    id: $0.id.uint64(default: 0),
                    description: $0.description.string(default: ""),
                    kind: $0.kind.string(default: ""),
                    amountAllocated: $0.amount_allocated.string(default: "0"),
                    handler: $0.handler.string(default: "")
                )
            },
            totalWeight: json.total_weight.string(default: "0")
        )
    }

    func allocationOptions(_ stream: Msg.StreamID) async -> [Allocation.OptionInfo] {
        await self.stream(stream).options
    }

    /// A voter's current split in one stream.
    ///
    /// `QueryVoterResponse` wraps the record in a `voter` field, so the
    /// percentages are one level down. Reading them off the top level parses as
    /// empty and shows an unallocated voter — silently, since an empty split is
    /// a valid state for someone who has never allocated.
    func voterAllocations(_ stream: Msg.StreamID, address: String) async -> [Allocation.Weight] {
        guard let json = try? await rest.get(
            "/earth-network/earth/allocation/v1/voter/\(Allocation.path(stream))/\(address)"
        ) else { return [] }
        return json.voter.percentages.array.map {
            Allocation.Weight(
                optionID: $0.option_id.uint64(default: 0),
                percent: $0.percent.uint64(default: 0)
            )
        }
    }

    // --- messages ---

    func msgSetAllocations(
        creator: String,
        stream: Msg.StreamID,
        weights: [Allocation.Weight]
    ) -> ProtoAny {
        Msg.SetAllocations(
            creator: creator,
            stream: stream,
            percentages: weights.map { Msg.AllocationWeight(optionID: $0.optionID, percent: $0.percent) }
        ).asAny(typeURL: Msg.SetAllocations.typeURL)
    }

    func msgClaimAllocation(creator: String, stream: Msg.StreamID, optionID: UInt64) -> ProtoAny {
        Msg.ClaimAllocation(creator: creator, stream: stream, optionID: optionID)
            .asAny(typeURL: Msg.ClaimAllocation.typeURL)
    }
}
