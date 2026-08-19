import BigInt
import EarthCore
import Observation
import SwiftUI

/// Govern: the three places a vote can go.
///
/// A menu, not a dashboard. Each entry is a whole screen's worth of detail —
/// two charts and a vote, or a list of proposals — and summarising all three
/// here left every one of them too small to act on while still being too much
/// to scan.
///
/// The two streams are Earth's own governance and the third is the SDK's. They
/// are grouped together because both are voting and separated by a heading
/// because they are not the same vote: the streams steer an emission
/// continuously by personhood or stake, proposals change the chain itself for a
/// fixed period by bonded stake alone.
struct GovernScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @State private var streams = StreamsModel()
    @State private var route: Route?

    enum Route: Hashable, Identifiable {
        case stream(caretaker: Bool)
        case proposals

        var id: String {
            switch self {
            case let .stream(caretaker): "stream-\(caretaker)"
            case .proposals: "proposals"
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: theme.space.x16)
                Text("Two of Earth's four emission streams are directed by vote. The Caretaker Fund counts people, Groundworks counts stake — you can hold a say in both.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)

                Spacer().frame(height: theme.space.x16)
                GovernRow(
                    title: "Caretaker Fund",
                    detail: "One verified human, one vote.",
                    status: streams.caretaker.status(
                        eligible: model.isRegistered,
                        blocked: "Register to take part"
                    ),
                    loading: !streams.loaded
                ) { route = .stream(caretaker: true) }

                Spacer().frame(height: theme.space.x8)
                GovernRow(
                    title: "Groundworks Fund",
                    detail: "Weighted by the ERTH you have staked.",
                    status: streams.groundworks.status(
                        eligible: model.totalStaked > 0,
                        blocked: "Stake ERTH to take part"
                    ),
                    loading: !streams.loaded
                ) { route = .stream(caretaker: false) }

                Spacer().frame(height: theme.space.x24)
                EarthLabel("Chain governance")
                Spacer().frame(height: theme.space.x8)
                GovernRow(
                    title: "Proposals",
                    detail: "Changes to the chain itself, voted on by staked ERTH.",
                    status: proposalStatus,
                    loading: !streams.loaded
                ) { route = .proposals }

                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .refreshable { await streams.load(model: model) }
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
        .task { await streams.load(model: model) }
        .sheet(item: $route) { route in
            switch route {
            case let .stream(caretaker):
                StreamDetailScreen(
                    title: caretaker ? "Caretaker Fund" : "Groundworks Fund",
                    detail: caretaker
                        ? "One verified human, one vote."
                        : "Weighted by the ERTH you have staked.",
                    stream: caretaker ? .caretaker : .groundworks,
                    state: caretaker ? streams.caretaker : streams.groundworks,
                    eligibility: caretaker
                        ? (model.isRegistered ? nil : "Register with your passport to vote here.")
                        : (model.totalStaked > 0 ? nil : "Stake ERTH to vote here."),
                    onChanged: { Task { await streams.load(model: model) } }
                )
                .earthThemed()
            case .proposals:
                ProposalsScreen(proposals: streams.proposals).earthThemed()
            }
        }
    }

    private var proposalStatus: String? {
        guard streams.loaded else { return nil }
        let live = streams.proposals.filter { $0.status == "PROPOSAL_STATUS_VOTING_PERIOD" }.count
        if live > 0 { return Figures.count(live, "open for voting", "open for voting") }
        return streams.proposals.isEmpty ? "None yet" : "\(streams.proposals.count) closed"
    }
}

@Observable
@MainActor
final class StreamsModel {
    struct State {
        var stream: Allocation.Stream = .empty
        var mine: [Allocation.Weight] = []

        /// Where the stream actually goes, across every voter.
        ///
        /// Each option's allocated amount is its share of the total weight, so
        /// this is the tally rather than anyone's preference. Percentages are
        /// computed against the total rather than read off, because the chain
        /// stores weights and not shares.
        var actualSlices: [AllocationSlice] {
            let total = stream.options.reduce(0.0) { $0 + (Double($1.amountAllocated) ?? 0) }
            guard total > 0 else { return [] }
            return stream.options.compactMap { option in
                let weight = Double(option.amountAllocated) ?? 0
                let percent = Int(weight / total * 100)
                return percent > 0 ? AllocationSlice(name: option.description, percent: percent) : nil
            }
            .sorted { $0.percent > $1.percent }
        }

        /// Where this wallet asked its share to go.
        var slices: [AllocationSlice] {
            stream.options.compactMap { option in
                guard let weight = mine.first(where: { $0.optionID == option.id })?.percent,
                      weight > 0 else { return nil }
                return AllocationSlice(name: option.description, percent: Int(weight))
            }
            .sorted { $0.percent > $1.percent }
        }

        /// What this wallet's position is, in a few words.
        ///
        /// Ineligibility outranks the split: someone who cannot vote does not
        /// need to be told they have allocated nothing, they need to be told
        /// why.
        func status(eligible: Bool, blocked: String) -> String? {
            guard eligible else { return blocked }
            guard !slices.isEmpty else { return "Not allocated" }
            return slices.map { "\($0.name) \($0.percent)%" }.joined(separator: " · ")
        }
    }

    var caretaker = State()
    var groundworks = State()
    var proposals: [Gov.Proposal] = []
    private(set) var loaded = false

    func load(model: AppModel) async {
        async let caretakerStream = model.client.stream(.caretaker)
        async let caretakerVote = model.client.voterAllocations(.caretaker, address: model.address)
        async let groundworksStream = model.client.stream(.groundworks)
        async let groundworksVote = model.client.voterAllocations(.groundworks, address: model.address)
        async let proposals = model.client.proposals(limit: 20)

        caretaker = State(stream: await caretakerStream, mine: await caretakerVote)
        groundworks = State(stream: await groundworksStream, mine: await groundworksVote)
        self.proposals = await proposals
        loaded = true
    }
}

struct GovernRow: View {
    @Environment(\.earth) private var theme
    let title: String
    let detail: String
    let status: String?
    let loading: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(EarthType.body).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text(detail)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                        .multilineTextAlignment(.leading)
                    Spacer().frame(height: theme.space.x4)
                    if loading {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(theme.colors.bgTertiary)
                            .frame(width: 140, height: 12)
                    } else if let status {
                        Text(status)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.accentInk)
                            .multilineTextAlignment(.leading)
                    }
                }
                Spacer(minLength: theme.space.x8)
                Text("›")
                    .font(EarthType.textLg)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            .padding(theme.space.x16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: 20))
        }
        .buttonStyle(.plain)
    }
}
