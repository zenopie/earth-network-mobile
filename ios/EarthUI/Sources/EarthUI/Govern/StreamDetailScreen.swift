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
    @Environment(AppModel.self) private var model
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
    @State private var addingOption = false
    @State private var opened: Allocation.OptionInfo?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer().frame(height: theme.space.x16)
                    lensPicker

                    Spacer().frame(height: theme.space.x24)
                    PieChart(
                        slices: slices,
                        // Only when there is nothing to draw. A stream nobody
                        // has voted in is an empty circle, indistinguishable
                        // from one that failed to load; a total in the middle
                        // of a populated ring is just "100%" restated.
                        centreLabel: !slicesLoaded ? Figures.loading : slices.isEmpty ? "none" : nil
                    )
                    .frame(maxWidth: .infinity)

                    Spacer().frame(height: theme.space.x16)
                    PieLegend(slices: slices, unallocated: unallocated) { slice in
                        opened = state.stream.options.first { $0.id == slice.optionID }
                    }

                    if let caption {
                        Spacer().frame(height: theme.space.x16)
                        FigureText(caption)
                            .font(EarthType.body)
                            .foregroundStyle(theme.colors.textTertiary)
                            .frame(maxWidth: .infinity)
                    }

                    // Only under Preferred. Actual is the whole stream's
                    // tally — a vote button there would sit under a chart it
                    // cannot change, and read as editing everyone's split
                    // rather than your own.
                    if stream == .groundworks, lens == .preferred, eligibility == nil {
                        // Your stake votes: every stake tx carries the split
                        // onto the note it makes. With its lease and renewal,
                        // all of it is your vote, so it lives with your split.
                        Spacer().frame(height: theme.space.x24)
                        GroundworksVoteControls(state: state, onChanged: onChanged) { editing = true }
                    } else if lens == .preferred, eligibility == nil {
                        Spacer().frame(height: theme.space.x24)
                        EarthPillButton(title: state.slices.isEmpty ? "Allocate" : "Change allocation") {
                            editing = true
                        }
                    }
                    // Open to anyone on the caretaker stream (a fee is burned);
                    // groundworks options come only through governance.
                    if stream == .caretaker {
                        Button("Add an option") { addingOption = true }
                            .font(EarthType.body).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.accentInk)
                            .frame(maxWidth: .infinity)
                            .padding(.top, theme.space.x24)
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
            // A Groundworks vote due for renewal opens on Preferred, where Renew
            // is: the Home reminder lands here for exactly that.
            .task {
                let now = Int64(Date().timeIntervalSince1970)
                if stream == .groundworks, let lease = model.groundworksLease, lease.lapsed(now) || lease.renewalDue(now) {
                    lens = .preferred
                }
            }
            #if targetEnvironment(simulator)
            // `-demoAddOption 1` opens the add sheet, as `-demoStream` opens this screen.
            .task {
                if UserDefaults.standard.bool(forKey: "demoAddOption") { addingOption = true }
                // `-demoOption 1` opens the first option's detail.
                if UserDefaults.standard.bool(forKey: "demoOption") { opened = state.stream.options.first }
            }
            #endif
            .sheet(item: $opened) { option in
                OptionDetailSheet(option: option, rewardIndex: state.stream.rewardIndex, onClaimed: onChanged, stream: stream,
                                  share: state.actualSlices.first { $0.optionID == option.id }?.percent ?? 0,
                                  mine: state.slices.first { $0.optionID == option.id }?.percent)
                    .earthThemed()
            }
            .sheet(isPresented: $addingOption) {
                AddCaretakerOptionSheet(onAdded: onChanged).earthThemed()
            }
            .sheet(isPresented: $editing) {
                AllocationEditSheet(stream: stream, state: state, onChanged: onChanged)
                    .earthThemed()
            }
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

    /// The tally needs the stream; your split needs the stream and the store.
    private var slicesLoaded: Bool {
        lens == .actual ? state.loaded : state.loaded && state.mineLoaded
    }

    /// Live options with no share, under Actual only: Preferred is your own
    /// split, where an option you gave nothing is not part of it.
    private var unallocated: [AllocationSlice] {
        guard lens == .actual else { return [] }
        let shown = Set(slices.compactMap(\.optionID))
        return state.stream.options.filter { !$0.removed && !shown.contains($0.id) }
            .map { AllocationSlice(name: $0.displayName, percent: 0, optionID: $0.id, detail: $0.source) }
    }

    /// One line, only when the chart cannot say it: why you cannot vote, or
    /// that you have not.
    private var caption: String? {
        if lens == .actual { return nil }
        if let eligibility { return eligibility }
        guard slicesLoaded else { return nil }
        return slices.isEmpty ? "Not allocated" : nil
    }
}

/// The Groundworks vote: Vote / Change, when it counts until, Renew when
/// due, Stop. Each is one confirmation; nothing renews on its own.
struct GroundworksVoteControls: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    let state: StreamsModel.State
    let onChanged: () -> Void
    let edit: () -> Void

    var body: some View {
        let split = model.groundworksSplit
        let lease = model.groundworksLease
        let now = Int64(Date().timeIntervalSince1970)
        VStack(spacing: theme.space.x12) {
            EarthPillButton(title: split.isEmpty ? "Vote" : "Change vote") { edit() }
            if !split.isEmpty, removedOption(split) {
                Text("An option was removed · vote again")
                    .font(EarthType.bodySmall).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.warnInk)
                    .multilineTextAlignment(.center)
            } else if !split.isEmpty, model.balancesVisible {
                FigureText("Voting \(Figures.when(model.stakeValuesLoaded, Figures.whole(BigInt(voting)))) of \(Figures.when(model.stakeValuesLoaded, Figures.whole(BigInt(model.privateStakeValue)))) ERTH")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            if !split.isEmpty, let until = pendingUntil {
                Text("Moved stake counts from \(Date(timeIntervalSince1970: TimeInterval(until)).formatted(.dateTime.month(.abbreviated).day()))")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            if let lease {
                if lease.lapsed(now) {
                    Text("Your vote lapsed")
                        .font(EarthType.bodySmall).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.warnInk)
                } else if lease.expiresAt > 0 {
                    Text("Counts until \(Date(timeIntervalSince1970: TimeInterval(lease.expiresAt)).formatted(.dateTime.month(.abbreviated).day()))")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
                if (lease.lapsed(now) || lease.renewalDue(now)) && !removedOption(lease.split) {
                    EarthPillButton(title: "Renew", role: .secondary) {
                        Self.cast(lease.split, rows: rows(lease.split), action: "Renew Groundworks vote", tx: tx, model: model, onChanged: onChanged)
                    }
                }
            }
            if !split.isEmpty {
                Button("Stop voting") {
                    Self.cast([:], rows: [], action: "Stop Groundworks vote", tx: tx, model: model, onChanged: onChanged)
                }
                .font(EarthType.bodySmall).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textTertiary)
            }
        }
        .frame(maxWidth: .infinity)
    }

    /// What this wallet's live votes weigh, in ERTH at the live rates.
    /// Votes on the split chosen now that count (not stale since a stream
    /// reset), moved-in stake still pending included: the chain counts it by
    /// itself when its window closes.
    private var counted: [PrivacyWallet.GroundworksVoteView] {
        let split = model.groundworksSplit
        return model.groundworksVotes.filter { ($0.weight > 0 || $0.pending > 0) && $0.split == split }
    }

    private var voting: UInt64 {
        counted.reduce(UInt64(0)) {
            PrivateMsgs.saturatingAdd($0, model.derthValue(PrivateMsgs.saturatingAdd($1.derth, $1.pending), validator: $1.validator))
        }
    }

    /// When the last moved-in stake starts counting (nil: none pending).
    private var pendingUntil: Int64? { counted.filter { $0.pending > 0 }.map(\.maturesAt).max() }

    private func rows(_ split: [UInt64: UInt64]) -> [(String, String)] {
        split.sorted { $0.value > $1.value }.map { id, pct in
            return (state.stream.options.first { $0.id == id }?.displayName ?? "Option \(id)", "\(pct)%")
        }
    }

    /// The split names an option the fund no longer has: the chain refuses it as it is.
    /// Struck (still listed, removed) or pruned (gone).
    private func removedOption(_ split: [UInt64: UInt64]) -> Bool {
        let options = state.stream.options
        return !options.isEmpty && split.keys.contains { id in !options.contains { $0.id == id && !$0.removed } }
    }

    /// Votes all of this wallet's stake with `split` (empty: stops): one
    /// restake per validator, sent one after the other on this one confirmation.
    @MainActor
    static func cast(_ split: [UInt64: UInt64], rows: [(String, String)], action: String = "Vote in Groundworks",
                     tx: TxController, model: AppModel, onChanged: @escaping () -> Void) {
        let validators = model.privateStake.filter { $0.value > 0 }.count
        let sends = validators == 1 ? "At least 1 transaction" : "At least \(validators) transactions, one per validator"
        tx.requestPrivate(.private(action: action, rows: rows + [("Sends", sends)]), onSuccess: { onChanged() }) { w in
            guard let last = try await w.castGroundworks(split: split).last else {
                throw PrivacyError("Stake ERTH first: your stake is what votes.")
            }
            return last
        }
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
    /// A split to start from, and a handler that takes the weights instead of
    /// the sheet raising the transaction itself.
    var initial: [UInt64: UInt64]? = nil
    var title = "Your allocation"
    var onSubmit: (([UInt64: UInt64]) -> Void)? = nil

    @State private var weights: [UInt64: Double] = [:]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    ForEach(state.stream.options.filter { !$0.removed }) { option in
                        VStack(alignment: .leading, spacing: theme.space.x4) {
                            HStack {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(option.displayName)
                                        .font(EarthType.body)
                                        .foregroundStyle(theme.colors.textPrimary)
                                        .lineLimit(3)
                                    Text(option.source)
                                        .font(EarthType.caption)
                                        .foregroundStyle(theme.colors.textTertiary)
                                }
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

                    EarthPillButton(title: "Review") { review() }
                        .disabled(assigned != 100)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .task {
                // A struck or pruned option is not offered, nor kept in the total.
                let live = Set(state.stream.options.filter { !$0.removed }.map(\.id))
                if let initial {
                    for (k, v) in initial where live.contains(k) { weights[k] = Double(v) }
                } else {
                    for weight in state.mine where live.contains(weight.optionID) { weights[weight.optionID] = Double(weight.percent) }
                }
            }
        }
    }

    private var assigned: Int { Int(weights.values.reduce(0, +)) }

    private func review() {
        // Zero-weight options are dropped rather than sent: the chain stores
        // the record verbatim, and a list of zeroes is noise in it.
        var split: [UInt64: UInt64] = [:]
        for (k, v) in weights where v > 0 { split[k] = UInt64(v) }
        if let onSubmit {
            dismiss()
            onSubmit(split)
            return
        }
        if stream == .groundworks {
            GroundworksVoteControls.cast(split, rows: split.sorted { $0.key < $1.key }.map { (label($0.key), "\($0.value)%") },
                                         tx: tx, model: model, onChanged: onChanged)
            dismiss()
            return
        }
        // The caretaker split is private: a membership proof in the
        // caretaker scope. The split is public, who cast it is not, and it
        // lapses after R (a year) unless its owner casts again: nothing
        // refreshes it on its own; a reminder comes before it lapses.
        tx.requestPrivate(
            .private(
                action: "Set caretaker split",
                rows: split.sorted { $0.key < $1.key }.map { (label($0.key), "\($0.value)%") }
            ),
            onSuccess: { onChanged() }
        ) { w in
            try await w.setCaretaker(split: split)
        }
        dismiss()
    }

    private func label(_ optionID: UInt64) -> String {
        state.stream.options.first { $0.id == optionID }?.displayName ?? "Option \(optionID)"
    }
}

/// Chain proposals — the SDK's governance, not Earth's allocation streams.
struct ProposalsScreen: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss

    let proposals: [Gov.Proposal]
    /// Whether the list has been read: before, it is not "no proposals".
    let loaded: Bool

    @State private var path = NavigationPath()

    init(proposals: [Gov.Proposal], loaded: Bool) {
        self.proposals = proposals
        self.loaded = loaded
        #if targetEnvironment(simulator)
        // `-demoProposal 1` opens the newest proposal, as `-demoStream` opens this list.
        if UserDefaults.standard.bool(forKey: "demoProposal"), let p = proposals.first {
            _path = State(initialValue: NavigationPath([p]))
        }
        #endif
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x8) {
                    if !loaded {
                        ForEach(0 ..< 3, id: \.self) { _ in ActivityPlaceholder().padding(.horizontal, -24) }
                    } else if proposals.isEmpty {
                        Text("No proposals yet")
                            .font(EarthType.body)
                            .foregroundStyle(theme.colors.textTertiary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, theme.space.x32)
                    }
                    // Pushed, not presented: a sheet over this sheet would put
                    // the detail two presentations deep for no gain.
                    ForEach(proposals) { proposal in
                        NavigationLink(value: proposal) {
                            ProposalRow(proposal: proposal)
                        }
                        .buttonStyle(.plain)
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
                // list on screen behind the confirmation.
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

    /// The human house's tally, loaded when this screen opens.
    ///
    /// Fetched here rather than with the proposal list: it is one request per
    /// proposal and only the open ones have an answer, so loading twenty of
    /// them to show one would be nineteen wasted round trips on a screen that
    /// may never be opened.
    ///
    /// `nil` when the tally could not be read; the second house is then
    /// hidden rather than shown empty.
    @State private var assembly: Assembly.Tally?
    /// Whether the tally has been asked for yet: until then the second
    /// house holds its place with "…" rather than not being there.
    @State private var tallyAsked = false
    /// This wallet's stake-vote weight on the proposal, at the snapshot's
    /// rates. Nil until read (or when the proposal is not open).
    @State private var weight: PrivacyWallet.StakeWeight?
    /// A stake vote waiting on the user's choice for a validator with more
    /// notes than one vote holds (vote in parts, or merge first).
    @State private var partsChoice: StakeVoting?
    /// Why the stake votes could not be laid out, if they could not.
    @State private var voteError: String?
    /// The summary past its first three lines.
    @State private var expanded = false

    /// The stake votes still to confirm (one sheet each), and the validators
    /// the user chose to vote in parts.
    struct StakeVoting {
        let vote: Gov.Vote
        var items: [PrivacyWallet.StakeVoteItem]
        var inParts: Set<String> = []
        /// The validator whose choice is asked, if any.
        var asking: String?
    }

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
                    VStack(alignment: .leading, spacing: theme.space.x4) {
                        Text(proposal.summary)
                            .font(EarthType.body)
                            .foregroundStyle(theme.colors.textSecondary)
                            .lineLimit(expanded ? nil : 3)
                            .textSelection(.enabled)
                        Button(expanded ? "Less" : "More") { expanded.toggle() }
                            .font(EarthType.bodySmall).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.accentInk)
                    }
                }

                if !closesAt.isEmpty {
                    EarthDetailRow(label: proposal.isLive ? "Voting ends" : "Voting ended", value: closesAt)
                }

                if proposal.total > 0 {
                    // Labelled by house once there are two. Stake and people
                    // are different quantities — one is ERTH, the other a
                    // headcount — so they are never combined into one score;
                    // there is no exchange rate between them and the chain does
                    // not have one either. Both must clear their own bar.
                    EarthLabel(assembly == nil ? "Tally" : "Stake")
                    TallyBar(proposal: proposal)
                    Text("Yes \(Figures.display(BigInt(proposal.yes))) · No \(Figures.display(BigInt(proposal.no))) · Abstain \(Figures.display(BigInt(proposal.abstain))) · Veto \(Figures.display(BigInt(proposal.veto)))")
                        .font(EarthType.caption.monospacedDigit())
                        .foregroundStyle(theme.colors.textTertiary)
                } else if proposal.isLive {
                    Text("No votes yet")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }

                if let assembly {
                    people(assembly)
                } else if proposal.isLive, !tallyAsked {
                    EarthLabel("People")
                    FigureText(Figures.loading)
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
        .confirmationDialog(
            partsChoice?.asking.map { v in
                let n = partsChoice?.items.compactMap { if case let .validator(x, c) = $0, x == v { return c }; return nil }.first ?? 0
                return "\(n) stake notes at \(name(v))"
            } ?? "",
            isPresented: Binding(get: { partsChoice != nil }, set: { if !$0 { partsChoice = nil } }),
            titleVisibility: .visible
        ) {
            Button("Vote in parts") { voteInParts() }
            Button("Merge notes") { mergeFirst() }
            Button("Cancel", role: .cancel) { partsChoice = nil }
        } message: {
            Text(partsChoiceText)
        }
        .task(id: model.dataVersion) {
            async let tally = model.client.assemblyTally(proposalID: proposal.id)
            await loadWeight()
            // A failed read keeps the last tally on screen.
            if let t = await tally { assembly = t }
            tallyAsked = true
        }
    }

    private func loadWeight() async {
        guard proposal.isLive, let w = model.privacy else { return }
        weight = try? await w.stakeVoteWeight(proposalID: proposal.id)
    }

    /// The stake weight as the confirmation shows it: ERTH at the snapshot's
    /// rates, and what it is made of.
    private var weightText: String {
        guard let weight else { return "Private stake from before voting opened" }
        var parts: [String] = []
        if weight.notes > 0 { parts.append(Figures.count(weight.notes, "note")) }
        let made = parts.isEmpty ? "" : " (" + parts.joined(separator: ", ") + ")"
        return "\(Figures.balance(BigInt(weight.uerth))) ERTH" + made
    }

    /// The human house.
    private func people(_ tally: Assembly.Tally) -> some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel("People")
            if tally.isSilent {
                // Not "no votes yet". Silence is a refusal here, and a voter
                // who reads it as "nothing has happened" is looking at the one
                // state that decides the proposal if it holds.
                Text(proposal.isLive ? "No human votes · fails without them" : "No human votes")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            } else {
                PeopleTallyBar(tally: tally)
                // Plain counts, not run through the ERTH formatter: these are
                // headcounts, and formatting two voters as a token amount
                // renders them "0.000002".
                Text("Yes \(tally.yes) · No \(tally.no) · \(tally.approved ? "clears two thirds" : "below two thirds")")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
    }

    private var voting: some View {
        VStack(alignment: .leading, spacing: theme.space.x12) {
            EarthDivider()
            // Named only when there are two houses. With one, a heading over
            // the buttons is noise; with two, unlabelled buttons leave it
            // ambiguous which tally they move.
            if assembly != nil { EarthLabel("Vote with stake") }

            // Stake decides this half, not personhood. An address with nothing
            // delegated can broadcast a vote that succeeds and moves the tally
            // by nothing, which would otherwise look like a vote that failed.
            if model.privateStakeTotal == 0 {
                Text("No private stake")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textTertiary)
            } else if let weight, weight.uerth == 0 {
                // Only stake from before voting opened counts, and a voted note cannot vote again.
                Text("No stake left to vote")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textTertiary)
            } else if weight != nil {
                // Final: each validator's stake votes once.
                EarthDetailRow(label: "Your weight · final", value: weightText)
            }

            if let voteError {
                Text(voteError)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
            }

            ForEach(Gov.Vote.allCases, id: \.rawValue) { option in
                EarthPillButton(
                    title: option.label,
                    role: option == .yes ? .primary : .secondary
                ) { cast(option) }
                .disabled(model.privateStakeTotal == 0 || weight?.uerth == 0)
            }

            // The human house. A separate vote on the same proposal, not a
            // confirmation of the one above: a wallet with stake and a
            // registration has two to cast, and casting one does nothing to the
            // other.
            if assembly != nil {
                EarthDivider()
                EarthLabel("Vote as a person")
                if !model.isRegistered {
                    Text("Not registered")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textTertiary)
                }

                ForEach(Assembly.Vote.allCases, id: \.rawValue) { option in
                    EarthPillButton(
                        title: option.label,
                        role: option == .yes ? .primary : .secondary
                    ) { castAsPerson(option) }
                    .disabled(!model.isRegistered)
                }
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

    /// The validator's name, for the sheets.
    private func name(_ valoper: String) -> String {
        model.moniker(of: valoper)
    }

    /// Stake votes without spending: one vote per validator,
    /// up to two of its notes with one weight (their sum rounded down to
    /// three significant figures). Each is its own
    /// confirm sheet and tx, raised one after the other and sent only on its
    /// own tap; nothing is cast in the background. A validator with more than
    /// two notes is asked about first: vote in parts, or merge.
    private func cast(_ option: Gov.Vote) {
        voteError = nil
        Task { @MainActor in
            guard let w = model.privacy else { return }
            do {
                let items = try await w.stakeVoteItems(proposalID: proposal.id)
                guard !items.isEmpty else {
                    voteError = "No stake from before voting opened is left to vote on this proposal."
                    return
                }
                start(StakeVoting(vote: option, items: items))
            } catch {
                voteError = model.describe(error)
            }
        }
    }

    /// Asks about the first validator that needs a choice, else raises the first sheet.
    private func start(_ v: StakeVoting) {
        var v = v
        for item in v.items {
            if case let .validator(valoper, _) = item, item.parts > 1, !v.inParts.contains(valoper) {
                v.asking = valoper
                partsChoice = v
                return
            }
        }
        let tx = self.tx, model = self.model, id = proposal.id, title = proposal.title
        Self.next(v, proposalID: id, title: title, tx: tx, model: model, name: name)
        onVoted()
    }

    /// Raises the sheet for `v`'s first vote; its success raises the next.
    @MainActor
    private static func next(_ v: StakeVoting, proposalID: UInt64, title: String, tx: TxController, model: AppModel, name: @escaping (String) -> String) {
        guard let item = v.items.first, let w = model.privacy else { return }
        var rest = v
        rest.items = Array(v.items.dropFirst())
        let left = rest.items.count
        let opts = [WeightedVoteOption(option: v.vote.proto, weight: "1")]
        Task { @MainActor in
            let preview = try? await w.stakeVotePreview(proposalID: proposalID, item: item)
            let whereText: String
            switch item {
            case let .validator(valoper, _): whereText = "at \(name(valoper))"
            }
            var rows: [(String, String)] = [
                ("Proposal", "#\(proposalID) \(title)"),
                ("Vote", v.vote.label),
                ("Weight", preview.map { p in
                    "\(Figures.balance(BigInt(p.uerth))) ERTH" + (p.notes > 0 ? " (" + Figures.count(p.notes, "note") + ")" : "")
                } ?? "Private stake from before voting opened"),
            ]
            if left > 0 { rows.append(("Then", "\(Figures.count(left, "more vote")) after this one, each confirmed on its own")) }
            tx.requestPrivate(.private(action: "Vote \(v.vote.label) with stake \(whereText) (final)", rows: rows), onSuccess: {
                if !rest.items.isEmpty { next(rest, proposalID: proposalID, title: title, tx: tx, model: model, name: name) }
            }) { w in
                // The last vote's fee change lands as a note: sync before the next.
                try await w.sync()
                guard let r = try await w.castStakeVote(proposalID: proposalID, item: item, options: opts) else {
                    throw PrivacyError("Nothing of this stake is left to vote on this proposal.")
                }
                return r
            }
        }
    }

    /// The choice for a validator with more notes than one vote holds.
    private var partsChoiceText: String {
        guard let v = partsChoice, let valoper = v.asking,
              let item = v.items.first(where: { if case let .validator(x, _) = $0 { return x == valoper }; return false }) else { return "" }
        // In parts: a fee each, and the parts can be linked to each other. A
        // merge helps later proposals only: this one counts the notes as they
        // were when voting opened.
        return "In parts: \(item.parts) votes, a fee each.\nMerge: helps later proposals, not this one."
    }

    private func voteInParts() {
        guard var v = partsChoice, let valoper = v.asking else { return }
        partsChoice = nil
        v.items = v.items.flatMap { item -> [PrivacyWallet.StakeVoteItem] in
            if case let .validator(x, _) = item, x == valoper { return Array(repeating: item, count: item.parts) }
            return [item]
        }
        v.inParts.insert(valoper)
        v.asking = nil
        start(v)
    }

    private func mergeFirst() {
        guard let valoper = partsChoice?.asking else { return }
        partsChoice = nil
        tx.requestPrivate(.private(action: "Merge two stake notes", rows: [("Validator", name(valoper))])) { w in
            try await w.mergeStake(denom: PrivacyWallet.derthDenom(valoper))
        }
        onVoted()
    }

    /// Private: a membership proof in this ballot's scope; who voted is never
    /// known, and a second vote replaces the first.
    private func castAsPerson(_ option: Assembly.Vote) {
        let id = proposal.id
        let title = proposal.title
        tx.requestPrivate(.private(
            // Says which house: the stake vote on the same proposal produces an
            // otherwise identical confirmation, and the two are different acts.
            action: "Vote as a person",
            rows: [
                ("Proposal", "#\(id) \(title)"),
                ("Vote", option.label),
                ("Weight", "1 person"),
            ]
        )) { w in
            try await w.voteProposal(proposalID: id, yes: option == .yes)
        }
        onVoted()
    }
}

/// The human tally, drawn like ``TallyBar`` but over two options and counts.
struct PeopleTallyBar: View {
    @Environment(\.earth) private var theme
    let tally: Assembly.Tally

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x4) {
            GeometryReader { geometry in
                HStack(spacing: 1) {
                    segment(tally.yes, theme.colors.accentInk, geometry.size.width)
                    segment(tally.no, theme.colors.errorInk, geometry.size.width)
                }
            }
            .frame(height: 6)
            .clipShape(.capsule)

            Text("\(fraction(tally.yes)) yes · \(fraction(tally.no)) no · two thirds needed")
                .font(EarthType.caption)
                .foregroundStyle(theme.colors.textTertiary)
        }
    }

    private func segment(_ votes: Int64, _ color: Color, _ width: CGFloat) -> some View {
        Rectangle()
            .fill(color)
            .frame(width: width * (tally.total > 0 ? Double(votes) / Double(tally.total) : 0))
    }

    private func fraction(_ votes: Int64) -> String {
        guard tally.total > 0 else { return "0%" }
        return String(format: "%.0f%%", Double(votes) / Double(tally.total) * 100)
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
