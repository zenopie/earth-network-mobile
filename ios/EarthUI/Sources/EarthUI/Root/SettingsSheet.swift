import EarthCore
import SwiftUI

/// The way out of the four tabs: the address, the phrase, and locking up.
struct SettingsSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var revealed: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x24) {
                    section("Address") {
                        Text(model.address)
                            .font(EarthType.mono)
                            .foregroundStyle(theme.colors.textSecondary)
                            .textSelection(.enabled)
                    }

                    section("Recovery phrase") {
                        if let revealed {
                            SeedGrid(words: revealed.split(separator: " ").map(String.init))
                            EarthButton(title: "Hide", role: .secondary) { self.revealed = nil }
                        } else {
                            Text("Anyone who has these twelve words has this wallet.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textTertiary)
                            EarthButton(title: "Reveal", role: .secondary) { reveal() }
                        }
                    }

                    if let error {
                        Text(error)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textError)
                    }

                    section("Network") {
                        EarthRow(title: "Chain", value: Constants.chainID)
                        EarthDivider()
                        EarthRow(title: "Node", value: Constants.lcdURL.host ?? "")
                    }

                    EarthButton(title: "Lock", role: .secondary) {
                        model.lock()
                        dismiss()
                    }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel(title)
            content()
        }
    }

    /// Reading the phrase goes through the same Face ID prompt every signature
    /// does — showing it is exactly as sensitive as spending with it.
    private func reveal() {
        do {
            revealed = try model.store.mnemonic(reason: "Reveal your recovery phrase")
            error = nil
        } catch {
            self.error = model.describe(error)
        }
    }
}
