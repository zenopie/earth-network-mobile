import EarthCore
import SwiftUI

/// Removal ballots: the human chamber's say over Groundworks options. Any
/// registered person may open one against an option (one open ballot per
/// option, no deposit) and vote in it; who opened or voted is never known,
/// and a second vote replaces the first. Ports RemovalBallotsScreen.
///
/// Rare, so they live inside Proposals rather than on the Govern tab: the
/// open ones as cards above the proposals, opening one a link at the bottom.

/// One open ballot, with Yes / No for a registered person.
struct RemovalBallotCard: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    let ballot: PrivacyReads.RemovalBallot
    let groundworks: Allocation.Stream
    let onDone: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            HStack {
                Text("Removal")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
                Spacer()
                Text("Closes \(Date(timeIntervalSince1970: TimeInterval(ballot.closesAt)).formatted(date: .abbreviated, time: .omitted))")
                    .font(EarthType.caption)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            Text("Remove \(RemovalBallots.name(ballot.optionID, groundworks))?")
                .font(EarthType.body).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textPrimary)
            Text("\(ballot.yes) yes · \(ballot.no) no")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textSecondary)
            if model.isRegistered {
                HStack(spacing: theme.space.x8) {
                    EarthPillButton(title: "Yes") { vote(yes: true) }
                    EarthPillButton(title: "No", role: .secondary) { vote(yes: false) }
                }
                .padding(.top, theme.space.x4)
            }
        }
        .padding(theme.space.x16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: 20))
    }

    private func vote(yes: Bool) {
        let name = RemovalBallots.name(ballot.optionID, groundworks)
        let id = ballot.optionID
        tx.requestPrivate(.private(
            action: "Vote \(yes ? "Yes" : "No") on removing \(name)",
            rows: [("Ballot", name), ("Vote", yes ? "Yes" : "No")]
        ), onSuccess: { await model.syncPrivacy() }) { w in
            try await w.voteRemoval(optionID: id, yes: yes)
        }
        onDone()
    }
}

/// Open a ballot against an option that has none: pushed from Proposals.
struct ProposeRemovalScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    let groundworks: Allocation.Stream
    let onDone: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                let open = Set(model.removalBallots.map(\.optionID))
                ForEach(groundworks.options.filter { !open.contains($0.id) && !$0.removed }) { o in
                    HStack {
                        Text(RemovalBallots.name(o.id, groundworks))
                            .font(EarthType.body)
                            .foregroundStyle(theme.colors.textPrimary)
                        Spacer()
                        Button("Propose") { propose(o.id) }
                            .font(EarthType.body).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.accentInk)
                    }
                    .padding(.vertical, theme.space.x12)
                    EarthDivider()
                }
            }
            .padding(theme.space.gutter)
        }
        .navigationTitle("Remove an option")
        .navigationBarTitleDisplayMode(.inline)
        .background(theme.colors.bgPrimary)
        .scrollContentBackground(.hidden)
    }

    private func propose(_ optionID: UInt64) {
        let name = RemovalBallots.name(optionID, groundworks)
        tx.requestPrivate(.private(
            action: "Propose removing \(name)",
            rows: [("Option", name)]
        ), onSuccess: { await model.syncPrivacy() }) { w in
            try await w.proposeRemoval(optionID: optionID)
        }
        onDone()
    }
}

enum RemovalBallots {
    static func name(_ id: UInt64, _ groundworks: Allocation.Stream) -> String {
        let d = groundworks.options.first { $0.id == id }?.description ?? ""
        return d.isEmpty ? "Option \(id)" : d
    }
}
