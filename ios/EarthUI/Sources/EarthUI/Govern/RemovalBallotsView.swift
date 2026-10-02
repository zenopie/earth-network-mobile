import EarthCore
import SwiftUI

/// Removal ballots: the human chamber's say over Groundworks options. Any
/// registered person may open one against an option (one open ballot per
/// option, no deposit) and vote in it; who opened or voted is never known,
/// and a second vote replaces the first. Ports RemovalBallotsScreen.
///
/// A sheet over the Govern tab: confirmations close it and draw at the root.
struct RemovalBallotsView: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    let groundworks: Allocation.Stream

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x12) {
                    Text("People can remove a Groundworks option by ballot. Opening one and voting both prove you are a registered person without saying which one.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                    if !model.isRegistered {
                        Text("Register your identity to open or vote in a removal ballot.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textSecondary)
                    }

                    EarthLabel("Open ballots")
                    if model.removalBallots.isEmpty {
                        Text("No ballot is open.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }
                    ForEach(model.removalBallots) { b in
                        EarthCard {
                            Text("Remove \(optionName(b.optionID))?")
                                .font(EarthType.body).fontWeight(.semibold)
                                .foregroundStyle(theme.colors.textPrimary)
                            EarthDetailRow(label: "Yes / No", value: "\(b.yes) / \(b.no)")
                            EarthDetailRow(label: "Closes", value: date(b.closesAt))
                            if model.isRegistered {
                                HStack(spacing: theme.space.x8) {
                                    EarthButton(title: "Yes") { vote(b.optionID, yes: true) }
                                    EarthButton(title: "No", role: .secondary) { vote(b.optionID, yes: false) }
                                }
                            }
                        }
                    }

                    if model.isRegistered {
                        EarthLabel("Open a ballot")
                        let open = Set(model.removalBallots.map(\.optionID))
                        ForEach(groundworks.options.filter { !open.contains($0.id) }) { o in
                            HStack {
                                Text(optionName(o.id))
                                    .font(EarthType.body)
                                    .foregroundStyle(theme.colors.textPrimary)
                                Spacer()
                                Button("Propose removal") { propose(o.id) }
                                    .font(EarthType.bodySmall)
                                    .foregroundStyle(theme.colors.accentInk)
                            }
                            .padding(.vertical, theme.space.x4)
                        }
                    }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Removal ballots")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .refreshable { await model.refreshRemovalBallots() }
            .task {
                await model.refreshRemovalBallots()
                await model.syncPrivacy()
            }
        }
    }

    private func optionName(_ id: UInt64) -> String {
        let d = groundworks.options.first { $0.id == id }?.description ?? ""
        return d.isEmpty ? "Option \(id)" : d
    }

    private func date(_ unix: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(unix)).formatted(date: .abbreviated, time: .shortened)
    }

    private func vote(_ optionID: UInt64, yes: Bool) {
        tx.requestPrivate(.private(
            action: "Vote \(yes ? "Yes" : "No") on removing \(optionName(optionID))",
            rows: [("Ballot", optionName(optionID)), ("Vote", yes ? "Yes" : "No")]
        ), onSuccess: { await model.syncPrivacy() }) { w in
            try await w.voteRemoval(optionID: optionID, yes: yes)
        }
        dismiss()
    }

    private func propose(_ optionID: UInt64) {
        tx.requestPrivate(.private(
            action: "Propose removing \(optionName(optionID))",
            rows: [("Option", optionName(optionID))]
        ), onSuccess: { await model.syncPrivacy() }) { w in
            try await w.proposeRemoval(optionID: optionID)
        }
        dismiss()
    }
}
