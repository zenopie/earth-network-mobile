import BigInt
import EarthCore
import SwiftUI

/// Merge notes: each private asset and, where it is spread over many notes,
/// Merge. Shielding is on the coin's own sheet in Portfolio.
///
/// A private payment spends any number of notes, up to
/// max_actions_per_bundle in one transaction, so merging only matters for a
/// balance spread over more notes than that: it joins the smallest notes one
/// transaction carries (ERTH pays its fee from them). Stake notes move two to
/// a proof, so a stake balance spread over many merges by a restake.
struct NotesScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    hero
                    if let error = model.privacySyncError {
                        Text("Sync: \(error)")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textError)
                            .padding(.top, theme.space.x12)
                    }

                    Spacer().frame(height: theme.space.x24)
                    VStack(spacing: 0) {
                        ForEach(model.shielded.filter { $0.value > 0 }.sorted(by: { $0.key < $1.key }), id: \.key) { denom, amount in
                            noteRow(denom: denom, amount: amount)
                            EarthDivider()
                        }
                    }

                    Spacer().frame(height: theme.space.x32)
                }
                .padding(.horizontal, theme.space.gutter)
            }
            .refreshable { await model.syncPrivacy() }
            .navigationTitle("Merge notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    /// How many notes could be merged, big.
    private var hero: some View {
        let mergeable = model.mergeable.values.reduce(0, +)
        return VStack(spacing: 0) {
            Text(Figures.when(model.has(.privacy), "\(mergeable)"))
                .font(EarthType.header2).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textPrimary)
            Text(!model.has(.privacy) ? "Notes to merge" : mergeable == 0 ? "Nothing to merge" : "Notes to merge")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }

    /// One asset: its name and balance, and Merge when its notes need it.
    private func noteRow(denom: String, amount: UInt64) -> some View {
        HStack(spacing: theme.space.x12) {
            VStack(alignment: .leading, spacing: theme.space.x2) {
                Text(label(denom))
                    .font(EarthType.body).fontWeight(.semibold)
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
                if let count = model.mergeable[denom] {
                    Button {
                        tx.requestPrivate(.private(action: "Merge notes", rows: [("Asset", label(denom))])) { w in
                            // Stake notes merge by a restake (owner-locked, two per proof).
                            denom.hasPrefix(PrivacyWallet.derthPrefix) ? try await w.mergeStake(denom: denom) : try await w.merge(denom: denom)
                        }
                    } label: {
                        Text("Merge \(count) notes")
                            .font(EarthType.bodySmall).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.accentInk)
                    }
                    .buttonStyle(.plain)
                }
            }
            Spacer(minLength: theme.space.x8)
            Text(Figures.balance(BigInt(amount)))
                .font(.system(size: 17, weight: .semibold).monospacedDigit())
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
        }
        .padding(.vertical, theme.space.x12)
    }

    private func label(_ denom: String) -> String {
        if denom == "uerth" { return "ERTH" }
        if denom == "uanml" { return "ANML" }
        if denom.hasPrefix("derth/") { return "Staked ERTH · " + String(denom.dropFirst(6).prefix(20)) + "…" }
        if denom.hasPrefix("dexlp/") { return "LP shares · pool " + String(denom.dropFirst(6)) }
        return denom
    }
}
