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
/// fixed period, decided by bonded stake and the human chamber together.
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
                GovernRow(
                    title: "Caretaker Fund",
                    detail: "One person, one vote",
                    status: streams.caretaker.status(
                        eligible: model.isRegistered,
                        blocked: "Register to vote"
                    ),
                    // Eligibility is the registration, which the sync reads.
                    loading: !streams.caretaker.loaded || !model.has(.privacy)
                ) { route = .stream(caretaker: true) }

                Spacer().frame(height: theme.space.x8)
                GovernRow(
                    title: "Groundworks Fund",
                    detail: "Directed by stake",
                    status: streams.groundworks.status(
                        eligible: model.privateStakeTotal > 0,
                        blocked: "Stake ERTH to vote"
                    ),
                    loading: !streams.groundworks.loaded || !model.has(.privacy)
                ) { route = .stream(caretaker: false) }


                Spacer().frame(height: theme.space.x24)
                EarthLabel("Chain")
                Spacer().frame(height: theme.space.x8)
                GovernRow(
                    title: "Proposals",
                    detail: "Chain upgrades and changes",
                    status: proposalStatus,
                    loading: !streams.proposalsLoaded
                ) { route = .proposals }

                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .refreshable { await model.refresh() }
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
        // Again whenever the app's data does: a tx, a pull, a wallet switch.
        .task(id: model.dataVersion) { await streams.load(model: model) }
        #if targetEnvironment(simulator)
        // `-demoStream groundworks` opens a stream, as `-demoTab` opens a tab.
        .task {
            if let name = UserDefaults.standard.string(forKey: "demoStream") {
                while !streams.loaded { try? await Task.sleep(for: .milliseconds(300)) }
                route = name == "proposals" ? .proposals : .stream(caretaker: name == "caretaker")
            }
        }
        #endif
        // A reminder's tap on Home: straight to where it is done.
        .onChange(of: model.governLink, initial: true) { _, link in
            guard let link else { return }
            model.governLink = nil
            route = .stream(caretaker: link == .caretaker)
        }
        .sheet(item: $route) { route in
            switch route {
            case let .stream(caretaker):
                StreamDetailScreen(
                    title: caretaker ? "Caretaker Fund" : "Groundworks Fund",
                    detail: caretaker ? "One person, one vote" : "Directed by stake",
                    stream: caretaker ? .caretaker : .groundworks,
                    state: caretaker ? streams.caretaker : streams.groundworks,
                    // Before the first sync neither is known: "…" holds the
                    // place of the vote rather than a "Register" that may be wrong.
                    eligibility: !model.has(.privacy) ? Figures.loading : caretaker
                        ? (model.isRegistered ? nil : "Register to vote")
                        : (model.privateStakeTotal > 0 ? nil : "Stake ERTH to vote"),
                    onChanged: { Task { await streams.load(model: model) } }
                )
                .earthThemed()
            case .proposals:
                ProposalsScreen(proposals: streams.proposals, loaded: streams.proposalsLoaded).earthThemed()
            }
        }
    }

    private var proposalStatus: String? {
        guard streams.proposalsLoaded else { return nil }
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
        /// Whether the stream has been read, and this wallet's split (the
        /// store's, after its first sync): neither is an empty answer before.
        var loaded = false
        var mineLoaded = false

        /// Where the stream actually goes, across every voter.
        ///
        /// Each option's allocated amount is its share of the total weight, so
        /// this is the tally rather than anyone's preference. Percentages are
        /// computed against the total rather than read off, because the chain
        /// stores weights and not shares.
        var actualSlices: [AllocationSlice] {
            let weights = stream.options.map { Double($0.amountAllocated) ?? 0 }
            let total = weights.reduce(0, +)
            guard total > 0 else { return [] }
            let percents = Self.apportion(weights, total: total)
            return zip(stream.options, percents).compactMap { option, percent in
                percent > 0 ? AllocationSlice(name: option.displayName, percent: percent, optionID: option.id, detail: option.source) : nil
            }
            .sorted { $0.percent > $1.percent }
        }

        /// Whole percentages that sum to exactly 100.
        ///
        /// Truncating each share independently loses up to one point per
        /// option, and did: a 95/5 split against a stake weight that divides as
        /// 95.000000000680 and 4.999999999320 truncated to 95 and 4, and the
        /// screen reported 99%. The chain was paying 95.0000/5.0000 the whole
        /// time — the shortfall was only ever in the arithmetic describing it.
        ///
        /// Largest remainder: floor everything, then hand the leftover points
        /// to the shares with the biggest fractional parts. Rounding each share
        /// on its own fixes this case and produces 101% in others.
        static func apportion(_ weights: [Double], total: Double) -> [Int] {
            let exact = weights.map { $0 / total * 100 }
            var out = exact.map { Int($0.rounded(.down)) }
            var leftover = 100 - out.reduce(0, +)
            guard leftover > 0 else { return out }
            // Biggest fractional part first; ties go to the larger share.
            let order = exact.indices.sorted {
                let l = exact[$0] - exact[$0].rounded(.down)
                let r = exact[$1] - exact[$1].rounded(.down)
                return l == r ? exact[$0] > exact[$1] : l > r
            }
            for i in order where leftover > 0 {
                out[i] += 1
                leftover -= 1
            }
            return out
        }

        /// Where this wallet asked its share to go.
        var slices: [AllocationSlice] {
            stream.options.compactMap { option in
                guard let weight = mine.first(where: { $0.optionID == option.id })?.percent,
                      weight > 0 else { return nil }
                return AllocationSlice(name: option.displayName, percent: Int(weight), optionID: option.id, detail: option.source)
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
            guard mineLoaded else { return Figures.loading }
            guard !slices.isEmpty else { return "Not allocated" }
            return slices.map { "\($0.name) \($0.percent)%" }.joined(separator: " · ")
        }
    }

    var caretaker = State()
    var groundworks = State()
    var proposals: [Gov.Proposal] = []
    private(set) var proposalsLoaded = false
    /// A load has finished, whatever it read.
    private(set) var loaded = false

    func load(model: AppModel) async {
        // Nil when the node could not be asked: what is on screen stays (or
        // "…" stays), never an empty answer in its place.
        async let caretakerStream = try? model.client.fetchStream(.caretaker)
        async let groundworksStream = try? model.client.fetchStream(.groundworks)
        async let proposals = try? model.client.fetchProposals(limit: 20)

        // Neither vote is filed under an address any more: the caretaker
        // split is cast anonymously (kept locally by the wallet), and
        // Groundworks is the split this wallet's stake votes with.
        let caretakerSplit = model.caretakerSplit
        let mineCaretaker = caretakerSplit.sorted { $0.key < $1.key }.map { Allocation.Weight(optionID: $0.key, percent: $0.value) }
        let mineGroundworks = model.groundworksSplit
            .sorted { $0.key < $1.key }.map { Allocation.Weight(optionID: $0.key, percent: $0.value) }
        let c = await caretakerStream, g = await groundworksStream, p = await proposals
        caretaker = State(stream: c ?? caretaker.stream, mine: mineCaretaker,
                          loaded: c != nil || caretaker.loaded, mineLoaded: model.has(.privacy))
        groundworks = State(stream: g ?? groundworks.stream, mine: mineGroundworks,
                            loaded: g != nil || groundworks.loaded, mineLoaded: model.has(.groundworks))
        if let p { self.proposals = p; proposalsLoaded = true }
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
                    FigureText(detail)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                        .multilineTextAlignment(.leading)
                    Spacer().frame(height: theme.space.x4)
                    if loading {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(theme.colors.bgTertiary)
                            .frame(width: 140, height: 12)
                    } else if let status {
                        FigureText(status)
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
