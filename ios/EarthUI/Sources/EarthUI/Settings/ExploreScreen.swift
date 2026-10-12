import BigInt
import EarthCore
import Observation
import SwiftUI

/// Explore: the chain itself.
///
/// Registered humans is the hero because it is the thing the chain exists to
/// count; height, the chain running, is the row under it. Blocks and validators
/// follow as lists.
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
                    hero

                    Spacer().frame(height: theme.space.x24)
                    EarthDetailRow(label: "Block height", value: chain.height.map(Figures.grouped) ?? "—")
                    EarthDivider()
                    EarthDetailRow(label: "Chain", value: chain.chainID ?? "—")

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
                        Text("No blocks yet")
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

    /// Verified humans, big.
    private var hero: some View {
        VStack(spacing: 0) {
            Text(chain.registrations.map(Figures.grouped) ?? "—")
                .font(EarthType.header2).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text("Verified humans")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
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
