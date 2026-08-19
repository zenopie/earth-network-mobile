import BigInt
import EarthCore
import Observation
import SwiftUI

/// Two kinds of governance, kept apart because they are not the same thing.
///
/// Allocation votes direct an emission stream continuously and are weighted by
/// personhood or by stake; x/gov proposals change the chain itself, run for a
/// fixed period, and are weighted by bonded stake alone. Folding them into one
/// list would suggest a vote here and a vote there mean the same, and they do
/// not.
struct GovernScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @State private var streams = StreamsModel()

    var body: some View {
        EarthScreen(title: "Govern") {
            StreamCard(
                title: "Caretaker Fund",
                subtitle: "One human, one vote. Needs a live registration.",
                stream: .caretaker,
                state: streams.caretaker,
                enabled: model.isRegistered
            )
            StreamCard(
                title: "Deflation Fund",
                subtitle: "Weighted by bonded stake.",
                stream: .groundworks,
                state: streams.groundworks,
                enabled: model.totalStaked > 0
            )
            ProposalsSection(proposals: streams.proposals)
        }
        .task { await streams.load(model: model) }
        .environment(streams)
    }
}

@Observable
@MainActor
final class StreamsModel {
    struct State {
        var stream: Allocation.Stream = .empty
        var mine: [Allocation.Weight] = []
    }

    var caretaker = State()
    var groundworks = State()
    var proposals: [Gov.Proposal] = []

    func load(model: AppModel) async {
        async let caretakerStream = model.client.stream(.caretaker)
        async let caretakerVote = model.client.voterAllocations(.caretaker, address: model.address)
        async let groundworksStream = model.client.stream(.groundworks)
        async let groundworksVote = model.client.voterAllocations(.groundworks, address: model.address)
        async let proposals = model.client.proposals(limit: 10)

        caretaker = State(stream: await caretakerStream, mine: await caretakerVote)
        groundworks = State(stream: await groundworksStream, mine: await groundworksVote)
        self.proposals = await proposals
    }
}

struct StreamCard: View {
    @Environment(\.earth) private var theme
    let title: String
    let subtitle: String
    let stream: Msg.StreamID
    let state: StreamsModel.State
    let enabled: Bool

    @State private var editing = false

    var body: some View {
        EarthCard {
            VStack(alignment: .leading, spacing: theme.space.x2) {
                Text(title)
                    .font(EarthType.title)
                    .foregroundStyle(theme.colors.textPrimary)
                Text(subtitle)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }

            if state.stream.options.isEmpty {
                EarthEmpty(systemName: "chart.pie", title: "No options yet")
            } else {
                Divider().overlay(theme.colors.strokeSecondary)
                ForEach(state.stream.options, id: \.id) { option in
                    OptionRow(option: option, total: state.stream.totalWeight, mine: mine(option.id))
                }
                EarthButton(title: state.mine.isEmpty ? "Set your allocation" : "Change your allocation",
                            role: .secondary) { editing = true }
                    .disabled(!enabled)
                if !enabled {
                    Text(stream == .caretaker
                         ? "Register with your passport to vote here."
                         : "Stake ERTH to vote here.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
            }
        }
        .sheet(isPresented: $editing) {
            AllocationEditor(title: title, stream: stream, state: state).earthThemed()
        }
    }

    private func mine(_ optionID: UInt64) -> UInt64? {
        state.mine.first { $0.optionID == optionID }?.percent
    }
}

struct OptionRow: View {
    @Environment(\.earth) private var theme
    let option: Allocation.OptionInfo
    let total: String
    let mine: UInt64?

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x4) {
            HStack {
                Text(option.description.isEmpty ? "Option \(option.id)" : option.description)
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textPrimary)
                Spacer()
                if let mine, mine > 0 {
                    EarthStatusPill(status: .success, text: "yours \(mine)%")
                }
                Text(share)
                    .font(EarthType.amount)
                    .foregroundStyle(theme.colors.textSecondary)
            }
            // The bar is the share of the stream this option draws, which is
            // the number a voter is actually moving.
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(theme.colors.bgTertiary)
                    Capsule().fill(theme.colors.accentInk)
                        .frame(width: geometry.size.width * fraction)
                }
            }
            .frame(height: 6)
        }
        .padding(.vertical, theme.space.x8)
    }

    /// `amountAllocated / totalWeight` — the denominator comes back with the
    /// response rather than being summed here, which would drift the moment an
    /// option is added between queries.
    private var fraction: Double {
        guard let total = Double(total), total > 0,
              let mine = Double(option.amountAllocated) else { return 0 }
        return min(max(mine / total, 0), 1)
    }

    private var share: String { String(format: "%.1f%%", fraction * 100) }
}

/// Set the split. Percentages, and they must total 100.
struct AllocationEditor: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let title: String
    let stream: Msg.StreamID
    let state: StreamsModel.State

    @State private var weights: [UInt64: Double] = [:]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    Text("Your vote directs this stream's emission continuously — there is no voting period to miss, and it stands until you change it.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    ForEach(state.stream.options, id: \.id) { option in
                        EarthCard {
                            HStack {
                                Text(option.description.isEmpty ? "Option \(option.id)" : option.description)
                                    .font(EarthType.body)
                                    .foregroundStyle(theme.colors.textPrimary)
                                Spacer()
                                Text("\(Int(weights[option.id] ?? 0))%")
                                    .font(EarthType.amount)
                                    .foregroundStyle(theme.colors.textPrimary)
                            }
                            Slider(
                                value: Binding(
                                    get: { weights[option.id] ?? 0 },
                                    set: { weights[option.id] = $0.rounded() }
                                ),
                                in: 0 ... 100,
                                step: 1
                            )
                            .tint(Palette.Brand.b600)
                        }
                    }

                    HStack {
                        EarthLabel("Total")
                        Spacer()
                        Text("\(assigned)%")
                            .font(EarthType.title)
                            .foregroundStyle(assigned == 100 ? theme.colors.accentInk : theme.colors.textError)
                    }

                    EarthButton(title: "Review") { review() }
                        .disabled(assigned != 100)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .earthBackground()
            .scrollContentBackground(.hidden)
            .task {
                for weight in state.mine { weights[weight.optionID] = Double(weight.percent) }
            }
        }
    }

    private var assigned: Int {
        Int(weights.values.reduce(0, +))
    }

    private func review() {
        // Zero-weight options are dropped rather than sent: the chain stores
        // the record verbatim, and a list of zeroes is noise in it.
        let chosen = weights
            .filter { $0.value > 0 }
            .map { Allocation.Weight(optionID: $0.key, percent: UInt64($0.value)) }
            .sorted { $0.optionID < $1.optionID }
        let target = stream

        tx.request(.init(
            action: "Set allocation",
            rows: chosen.map { weight in
                (label(weight.optionID), "\(weight.percent)%")
            }
        )) { key in
            [model.client.msgSetAllocations(creator: key.address, stream: target, weights: chosen)]
        }
        dismiss()
    }

    private func label(_ optionID: UInt64) -> String {
        let option = state.stream.options.first { $0.id == optionID }
        let description = option?.description ?? ""
        return description.isEmpty ? "Option \(optionID)" : description
    }
}

struct ProposalsSection: View {
    @Environment(\.earth) private var theme
    let proposals: [Gov.Proposal]

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthSectionHeader(title: "Chain proposals")

            if proposals.isEmpty {
                EarthEmpty(
                    systemName: "doc.text",
                    title: "No proposals",
                    detail: "Nothing has been put to the chain yet."
                )
            } else {
                ForEach(proposals) { proposal in
                    EarthCard {
                        HStack(alignment: .top) {
                            Text(proposal.title)
                                .font(EarthType.title)
                                .foregroundStyle(theme.colors.textPrimary)
                            Spacer()
                            EarthStatusPill(status: status(proposal), text: shortStatus(proposal))
                        }
                        if !proposal.summary.isEmpty {
                            Text(proposal.summary)
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                                .lineLimit(3)
                        }
                        if proposal.total > 0 {
                            TallyBar(proposal: proposal)
                        }
                    }
                }
                // Voting needs cosmos/gov/v1beta1/tx.proto, which the message
                // set here does not carry. Reading comes first: a chain with no
                // proposals still needs to say so.
                Text("Voting on chain proposals is not in this build.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
    }

    private func status(_ proposal: Gov.Proposal) -> EarthStatus {
        switch proposal.status {
        case "PROPOSAL_STATUS_PASSED": .success
        case "PROPOSAL_STATUS_VOTING_PERIOD", "PROPOSAL_STATUS_DEPOSIT_PERIOD": .pending
        case "PROPOSAL_STATUS_REJECTED", "PROPOSAL_STATUS_FAILED": .failed
        default: .neutral
        }
    }

    private func shortStatus(_ proposal: Gov.Proposal) -> String {
        proposal.status
            .replacingOccurrences(of: "PROPOSAL_STATUS_", with: "")
            .replacingOccurrences(of: "_", with: " ")
            .lowercased()
    }
}

struct TallyBar: View {
    @Environment(\.earth) private var theme
    let proposal: Gov.Proposal

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x4) {
            GeometryReader { geometry in
                HStack(spacing: 1) {
                    segment(proposal.yes, theme.colors.accentInk, geometry.size.width)
                    segment(proposal.no, theme.colors.errorInk, geometry.size.width)
                    segment(proposal.veto, theme.colors.warnInk, geometry.size.width)
                    segment(proposal.abstain, theme.colors.textDisabled, geometry.size.width)
                }
            }
            .frame(height: 6)
            .clipShape(.capsule)

            Text("\(fraction(proposal.yes)) yes · \(fraction(proposal.no)) no")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
    }

    private func segment(_ votes: Int64, _ color: Color, _ width: CGFloat) -> some View {
        Rectangle()
            .fill(color)
            .frame(width: width * (proposal.total > 0 ? Double(votes) / Double(proposal.total) : 0))
    }

    private func fraction(_ votes: Int64) -> String {
        guard proposal.total > 0 else { return "0%" }
        return String(format: "%.0f%%", Double(votes) / Double(proposal.total) * 100)
    }
}
