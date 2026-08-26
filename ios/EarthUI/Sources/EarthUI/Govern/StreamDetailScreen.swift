import BigInt
import EarthCore
import SwiftUI

/// One allocation stream, as the chain has it and as you asked for it.
///
/// Two views of the same options, which is the comparison worth making: Actual
/// is where the stream's emission is going once every voter is counted;
/// Preferred is where you asked it to go. Your vote is one of many, so the two
/// differ, and the gap between them is the only measure of whether a vote
/// changed anything.
struct StreamDetailScreen: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss

    let title: String
    let detail: String
    let stream: Msg.StreamID
    let state: StreamsModel.State
    /// Non-nil when this wallet cannot vote here, and why.
    let eligibility: String?
    let onChanged: () -> Void

    /// Which split the chart is showing.
    enum Lens: String, CaseIterable { case actual = "Actual", preferred = "Preferred" }

    @State private var lens = Lens.actual
    @State private var editing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer().frame(height: theme.space.x8)
                    Text(detail)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textSecondary)

                    Spacer().frame(height: theme.space.x16)
                    lensPicker

                    Spacer().frame(height: theme.space.x24)
                    PieChart(
                        slices: slices,
                        // Only when there is nothing to draw. A stream nobody
                        // has voted in is an empty circle, indistinguishable
                        // from one that failed to load; a total in the middle
                        // of a populated ring is just "100%" restated.
                        centreLabel: slices.isEmpty ? "none" : nil
                    )
                    .frame(maxWidth: .infinity)

                    Spacer().frame(height: theme.space.x16)
                    PieLegend(slices: slices)

                    Spacer().frame(height: theme.space.x16)
                    Text(caption)
                        .font(EarthType.caption)
                        .foregroundStyle(theme.colors.textTertiary)

                    // Only under Preferred. Actual is the whole stream's
                    // tally — a vote button there would sit under a chart it
                    // cannot change, and read as editing everyone's split
                    // rather than your own.
                    if lens == .preferred, eligibility == nil {
                        Spacer().frame(height: theme.space.x24)
                        EarthButton(title: state.slices.isEmpty ? "Allocate" : "Change allocation") {
                            editing = true
                        }
                    }
                    Spacer().frame(height: theme.space.x32)
                }
                .padding(.horizontal, theme.space.gutter)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .sheet(isPresented: $editing) {
                AllocationEditSheet(stream: stream, state: state, onChanged: onChanged)
                    .earthThemed()
            }
            // This sheet hosts its own confirmation. It is the only flow that
            // presents a sheet over itself, and the root's copy draws behind
            // both — closing them to reveal it was tried and still left the
            // card under the tab. Hosting it here keeps you on the stream you
            // were editing, which is where the confirmation makes sense anyway.
            .overlay { TxOverlay(host: .allocation) }
        }
    }

    private var lensPicker: some View {
        HStack(spacing: 0) {
            ForEach(Lens.allCases, id: \.self) { option in
                Button { lens = option } label: {
                    Text(option.rawValue)
                        .font(EarthType.bodySmall)
                        .fontWeight(lens == option ? .semibold : .regular)
                        .foregroundStyle(lens == option
                                         ? theme.colors.secondaryButtonFg
                                         : theme.colors.textTertiary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, theme.space.x8)
                        .background(
                            lens == option ? theme.colors.secondaryButtonBg : theme.colors.bgSecondary,
                            in: .rect(cornerRadius: theme.space.x16)
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(theme.space.x4)
        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.x20))
    }

    private var slices: [AllocationSlice] {
        lens == .actual ? state.actualSlices : state.slices
    }

    private var caption: String {
        if lens == .actual {
            return "Where this stream's emission goes once every voter is counted."
        }
        if let eligibility { return eligibility }
        if slices.isEmpty { return "You have not allocated your share of this stream." }
        return "Where you asked your share to go. It is one vote among many, so the actual split will differ."
    }
}

/// Set the split. Percentages, and they must total 100.
struct AllocationEditSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    let stream: Msg.StreamID
    let state: StreamsModel.State
    let onChanged: () -> Void

    @State private var weights: [UInt64: Double] = [:]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    Text("Your vote directs this stream's emission continuously — there is no voting period to miss, and it stands until you change it.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    ForEach(state.stream.options) { option in
                        VStack(alignment: .leading, spacing: theme.space.x4) {
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
                            .tint(theme.colors.brandButtonBg)
                        }
                    }

                    HStack {
                        EarthLabel("Total")
                        Spacer()
                        Text("\(assigned)%")
                            .font(EarthType.body).fontWeight(.semibold)
                            .foregroundStyle(assigned == 100 ? theme.colors.accentInk : theme.colors.textError)
                    }

                    EarthButton(title: "Review") { review() }
                        .disabled(assigned != 100)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Your allocation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .task {
                for weight in state.mine { weights[weight.optionID] = Double(weight.percent) }
            }
        }
    }

    private var assigned: Int { Int(weights.values.reduce(0, +)) }

    private func review() {
        // Zero-weight options are dropped rather than sent: the chain stores
        // the record verbatim, and a list of zeroes is noise in it.
        let chosen = weights
            .filter { $0.value > 0 }
            .map { Allocation.Weight(optionID: $0.key, percent: UInt64($0.value)) }
            .sorted { $0.optionID < $1.optionID }
        let target = stream

        tx.request(
            .init(
                action: "Set allocation",
                rows: chosen.map { (label($0.optionID), "\($0.percent)%") }
            ),
            // Drawn by the stream sheet this editor sits on, not by the root:
            // a root overlay cannot appear over a sheet that is still up.
            host: .allocation,
            onSuccess: { onChanged() }
        ) { key in
            [model.client.msgSetAllocations(creator: key.address, stream: target, weights: chosen)]
        }
        dismiss()
    }

    private func label(_ optionID: UInt64) -> String {
        let description = state.stream.options.first { $0.id == optionID }?.description ?? ""
        return description.isEmpty ? "Option \(optionID)" : description
    }
}

/// Chain proposals — the SDK's governance, not Earth's allocation streams.
struct ProposalsScreen: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss

    let proposals: [Gov.Proposal]


    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x8) {
                    if proposals.isEmpty {
                        Text("No proposals yet. Anything that changes the chain itself — parameters, upgrades, spending from the community pool — is proposed here and voted on by staked ERTH.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    } else {
                        // Pushed, not presented. This screen is already a
                        // sheet; a sheet over it would put the detail two
                        // presentations deep, and a confirmation raised from
                        // there — hosted on the root — draws behind both. A
                        // push stays inside this presentation, so voting can
                        // close the whole sheet and land on the confirmation
                        // the way Send and Stake do.
                        ForEach(proposals) { proposal in
                            NavigationLink(value: proposal) {
                                ProposalRow(proposal: proposal)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Proposals")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .navigationDestination(for: Gov.Proposal.self) { proposal in
                // `onVoted` closes this sheet rather than popping the push:
                // dismiss() inside a pushed view pops, which would leave the
                // list on screen and the confirmation behind it.
                ProposalDetailScreen(proposal: proposal, onVoted: { dismiss() })
            }
        }
    }
}

struct ProposalRow: View {
    @Environment(\.earth) private var theme
    let proposal: Gov.Proposal

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("#\(proposal.id)")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
                Spacer()
                EarthStatusPill(status: status, text: shortStatus)
            }
            Spacer().frame(height: theme.space.x4)
            Text(proposal.title)
                .font(EarthType.body).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textPrimary)
            if !proposal.summary.isEmpty {
                Text(proposal.summary)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                    .lineLimit(3)
            }
            if proposal.total > 0 {
                Spacer().frame(height: theme.space.x8)
                TallyBar(proposal: proposal)
            }
        }
        .padding(theme.space.x16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: 20))
    }

    private var status: EarthStatus {
        switch proposal.status {
        case "PROPOSAL_STATUS_PASSED": .success
        case "PROPOSAL_STATUS_VOTING_PERIOD", "PROPOSAL_STATUS_DEPOSIT_PERIOD": .pending
        case "PROPOSAL_STATUS_REJECTED", "PROPOSAL_STATUS_FAILED": .failed
        default: .neutral
        }
    }

    private var shortStatus: String {
        proposal.status
            .replacingOccurrences(of: "PROPOSAL_STATUS_", with: "")
            .replacingOccurrences(of: "_", with: " ")
            .lowercased()
    }
}

/// One proposal in full, and the four ways to vote on it.
///
/// A pushed screen, not a sheet. `ProposalsScreen` is itself presented, so a
/// sheet here would be the second presentation — and `TxController`'s
/// confirmation is hosted on the root view, which cannot draw over anything
/// presented above it. Pushing keeps the whole flow inside one presentation,
/// which is what lets `onVoted` close it and reveal the confirmation.
///
/// Four options, not two. Abstain counts toward quorum without taking a side,
/// and no-with-veto is a distinct outcome on this chain — collapsing them into
/// a yes/no pair would hide the two votes that behave differently.
struct ProposalDetailScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    let proposal: Gov.Proposal
    /// Closes the presentation this was pushed inside.
    let onVoted: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                HStack {
                    Text("#\(proposal.id)")
                        .font(EarthType.caption)
                        .foregroundStyle(theme.colors.textTertiary)
                    Spacer()
                    EarthStatusPill(status: pillStatus, text: shortStatus)
                }

                Text(proposal.title)
                    .font(EarthType.headline)
                    .foregroundStyle(theme.colors.textPrimary)

                if !proposal.summary.isEmpty {
                    Text(proposal.summary)
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textSecondary)
                        .textSelection(.enabled)
                }

                EarthCard {
                    EarthDetailRow(label: "Status", value: shortStatus)
                    if !closesAt.isEmpty {
                        EarthDetailRow(label: proposal.isLive ? "Voting ends" : "Voting ended",
                                       value: closesAt)
                    }
                }

                if proposal.total > 0 {
                    EarthLabel("Tally")
                    TallyBar(proposal: proposal)
                    EarthCard {
                        EarthDetailRow(label: "Yes", value: Figures.display(BigInt(proposal.yes)))
                        EarthDetailRow(label: "No", value: Figures.display(BigInt(proposal.no)))
                        EarthDetailRow(label: "Abstain", value: Figures.display(BigInt(proposal.abstain)))
                        EarthDetailRow(label: "No with veto", value: Figures.display(BigInt(proposal.veto)))
                    }
                } else if proposal.isLive {
                    Text("No votes counted yet.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }

                if proposal.isLive { voting }
            }
            .padding(theme.space.gutter)
        }
        .navigationTitle("Proposal")
        .navigationBarTitleDisplayMode(.inline)
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
    }

    private var voting: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthDivider()
            // Stake decides this, not personhood — unlike an allocation vote.
            // An address with nothing delegated can broadcast a vote that
            // succeeds and moves the tally by nothing, which would otherwise
            // look like a vote that failed.
            if model.totalStaked <= 0 {
                EarthCard {
                    Text("You have no ERTH staked")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text("Chain proposals are weighted by bonded stake alone. A vote from here would be accepted and count for nothing. Stake first, from Earn.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textSecondary)
                }
            } else {
                Text("Voting with \(Figures.display(model.totalStaked)) ERTH of stake.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }

            ForEach(Gov.Vote.allCases, id: \.rawValue) { option in
                EarthButton(
                    title: option.label,
                    role: option == .yes ? .primary : .secondary
                ) { cast(option) }
                .disabled(model.totalStaked <= 0)
            }
        }
    }

    private var pillStatus: EarthStatus {
        switch proposal.status {
        case "PROPOSAL_STATUS_PASSED": .success
        case "PROPOSAL_STATUS_VOTING_PERIOD", "PROPOSAL_STATUS_DEPOSIT_PERIOD": .pending
        case "PROPOSAL_STATUS_REJECTED", "PROPOSAL_STATUS_FAILED": .failed
        default: .neutral
        }
    }

    private var shortStatus: String {
        proposal.status
            .replacingOccurrences(of: "PROPOSAL_STATUS_", with: "")
            .replacingOccurrences(of: "_", with: " ")
            .lowercased()
    }

    /// The chain sends RFC 3339; the date alone is what anyone plans around.
    private var closesAt: String {
        String(proposal.votingEndTime.prefix(10))
    }

    private func cast(_ option: Gov.Vote) {
        let id = proposal.id
        let title = proposal.title
        tx.request(.init(
            action: "Vote",
            rows: [
                ("Proposal", "#\(id) \(title)"),
                ("Vote", option.label),
                ("Weight", "\(Figures.display(model.totalStaked)) ERTH staked"),
                ("Fee", "\(Token.erth.format(TransactionSigner.defaultFeeUerth)) ERTH"),
            ]
        )) { key in
            [model.client.msgVote(voter: key.address, proposalID: id, option: option)]
        }
        onVoted()
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
                .font(EarthType.caption)
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
