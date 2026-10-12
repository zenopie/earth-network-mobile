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

    public struct OptionInfo: Sendable, Equatable, Identifiable {
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
        /// Struck by the assembly (x/allocation Option.removed): kept until
        /// pruned, but no split may name it.
        public var removed = false
        /// An ADDRESS option's payout address ("" for INTEGRATED).
        public var recipient = ""
        /// Who alone may trigger its claim ("": anyone; the payout goes to `recipient` either way).
        public var claimer = ""
        /// uerth owed to it as of its last settlement, awaiting its claim.
        public var accumulated = "0"
        /// The stream's reward index at that settlement (scaled by 1e18).
        public var lastRewardIndex = "0"

        /// Built into the chain (a handler), not listed by an account.
        public var integrated: Bool { kind == "ALLOCATION_KIND_INTEGRATED" }
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
        /// The stream's reward index now (scaled by 1e18): with an option's
        /// last one, what it has earned since it was last settled.
        public var rewardIndex = "0"

        public static let empty = Stream(options: [], totalWeight: "0")
    }

    /// One voter's weight on one option.
    public struct Weight: Sendable, Equatable, Identifiable {
        public var id: UInt64 { optionID }

        public let optionID: UInt64
        public let percent: UInt64

        public init(optionID: UInt64, percent: UInt64) {
            self.optionID = optionID
            self.percent = percent
        }
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

    /// x/allocation's address_option_fee: the uerth burned to list a caretaker
    /// option. Nil when the node could not say.
    func addressOptionFee() async -> UInt64? {
        guard let json = try? await rest.get("/earth/allocation/v1/params") else { return nil }
        return json.params.address_option_fee.uint64
    }

    /// Every page (the chain serves at most 100 options a page).
    func stream(_ stream: Msg.StreamID) async -> Allocation.Stream {
        var options: [Allocation.OptionInfo] = []
        var total = "0"
        var rewardIndex = "0"
        var key: String?
        var keys = Set<String>()
        var seen = Set<UInt64>()
        repeat {
            // Capped: a node serving new keys forever is not followed.
            guard keys.count < 100 else { return .empty }
            let path = "/earth/allocation/v1/options/\(Allocation.path(stream))" +
                (key.map { "?pagination.key=" + ($0.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0) } ?? "")
            guard let json = try? await rest.get(path) else { return .empty }
            if key == nil {
                total = json.total_weight.string(default: "0")
                rewardIndex = json.reward_index.string(default: "0")
            }
            options += json.options.array.filter { seen.insert($0.id.uint64(default: 0)).inserted }.map {
                Allocation.OptionInfo(
                    id: $0.id.uint64(default: 0),
                    description: $0.description.string(default: ""),
                    kind: $0.kind.string(default: ""),
                    amountAllocated: $0.amount_allocated.string(default: "0"),
                    handler: $0.handler.string(default: ""),
                    removed: $0.removed.bool(default: false),
                    recipient: $0.recipient.string(default: ""),
                    claimer: $0.claimer.string(default: ""),
                    accumulated: $0.accumulated.string(default: "0"),
                    lastRewardIndex: $0.last_reward_index.string(default: "0")
                )
            }
            key = json.pagination.next_key.string.flatMap { $0.isEmpty || $0 == "null" ? nil : $0 }
            if let k = key, !keys.insert(k).inserted { return .empty }
        } while key != nil
        return Allocation.Stream(options: options, totalWeight: total, rewardIndex: rewardIndex)
    }
}
