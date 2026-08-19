import BigInt
import EarthCore
import Observation
import SwiftUI

/// Explore: the chain itself.
///
/// Height and registered humans lead because they are the two counters that say
/// what this chain is — one measures the chain running, the other measures the
/// thing it exists to count. Blocks and validators follow as lists.
///
/// Registered humans is here rather than on the identity screen because it is a
/// fact about the network, not about you; identity answers whether *this
/// wallet* is verified, which is a different question.
struct ExploreScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var chain = ChainModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer().frame(height: theme.space.x16)

                    HStack(spacing: theme.space.x8) {
                        StatCard(label: "Block height", value: chain.height.map(Figures.grouped))
                        StatCard(label: "Verified humans", value: chain.registrations.map(Figures.grouped))
                    }

                    if let chainID = chain.chainID {
                        Spacer().frame(height: theme.space.x8)
                        Text("Chain \(chainID)")
                            .font(EarthType.caption)
                            .foregroundStyle(theme.colors.textTertiary)
                    }

                    Spacer().frame(height: theme.space.x24)
                    EarthLabel("Latest blocks")
                    Spacer().frame(height: theme.space.x8)

                    ForEach(Array(chain.blocks.enumerated()), id: \.element.id) { index, block in
                        HStack(spacing: theme.space.x8) {
                            Text(Figures.grouped(block.height))
                                .font(EarthType.body).fontWeight(.semibold)
                                .foregroundStyle(theme.colors.textPrimary)
                            Text(time(block.time))
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            // "empty" rather than "0 tx": a block with nothing
                            // in it is the normal case on a quiet chain, and a
                            // column of zeroes reads as an error.
                            Text(block.txCount == 0 ? "empty" : "\(block.txCount) tx")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                        }
                        .padding(.vertical, theme.space.x12)
                        if index != chain.blocks.count - 1 { EarthDivider() }
                    }
                    if chain.blocks.isEmpty {
                        Text("No blocks read yet.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }

                    if !model.validators.isEmpty {
                        Spacer().frame(height: theme.space.x24)
                        EarthLabel("Validators")
                        Spacer().frame(height: theme.space.x8)
                        ForEach(model.validators) { validator in
                            EarthListRow(
                                initial: String(validator.moniker.prefix(1)).uppercased(),
                                name: validator.moniker.isEmpty ? validator.operatorAddress : validator.moniker,
                                subtitle: String(format: "%.0f%% commission", validator.commission * 100),
                                value: Figures.whole(validator.tokens),
                                badgeBackground: theme.colors.accentTint,
                                badgeForeground: theme.colors.accentInk
                            )
                        }
                    }

                    Spacer().frame(height: theme.space.x32)
                }
                .padding(.horizontal, theme.space.gutter)
            }
            .refreshable { await chain.load(model: model) }
            .navigationTitle("Explorer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .task { await chain.load(model: model) }
        }
    }

    /// Just the clock part. The date is the same for every block in the list.
    private func time(_ iso: String) -> String {
        String(iso.components(separatedBy: "T").last?.prefix(8) ?? "")
    }
}

@Observable
@MainActor
final class ChainModel {
    var chainID: String?
    var height: Int64?
    var registrations: Int64?
    var blocks: [Explorer.Block] = []

    func load(model: AppModel) async {
        async let status = model.client.status()
        async let blocks = model.client.recentBlocks(8)
        async let registrations = model.client.registrationCount()

        let read = await status
        chainID = read?.chainID
        self.blocks = await blocks
        height = read?.height ?? self.blocks.first?.height
        self.registrations = await registrations
    }
}

struct StatCard: View {
    @Environment(\.earth) private var theme
    let label: String
    let value: String?

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x4) {
            Text(label)
                .font(EarthType.caption)
                .foregroundStyle(theme.colors.textTertiary)
                .lineLimit(1)
            if let value {
                Text(value)
                    .font(EarthType.headline)
                    .foregroundStyle(theme.colors.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            } else {
                RoundedRectangle(cornerRadius: 4)
                    .fill(theme.colors.bgTertiary)
                    .frame(width: 72, height: 22)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(theme.space.x16)
        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: 20))
    }
}
