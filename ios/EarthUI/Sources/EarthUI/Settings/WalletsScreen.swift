import EarthCore
import SwiftUI

/// The wallets in this install.
///
/// A list with the current one marked, and two ways to add another. Switching
/// is the whole reason the screen exists, so it happens on a tap of the row
/// rather than behind a menu — there is nothing else a row could usefully do.
///
/// No delete. Removing a wallet removes the only copy of a mnemonic, and until
/// this can show the phrase and make you acknowledge you have it, leaving one
/// in the list costs nothing and removing one can cost everything.
struct WalletsScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var adding: Adding?
    @State private var revealed: String?
    @State private var confirming = false
    @State private var error: String?

    enum Adding: String, Identifiable { case create, restore; var id: String { rawValue } }

    var body: some View {
        NavigationStack {
            Group {
                if confirming {
                    ConfirmIdentity(reason: "Show your recovery phrase") { confirmation in
                        reveal(from: confirmation)
                        confirming = false
                    }
                } else {
                    list
                }
            }
            .navigationTitle(confirming ? "Recovery phrase" : "Wallets")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if confirming {
                        Button("Cancel") { confirming = false }
                    } else {
                        Button("Done") { dismiss() }
                    }
                }
            }
            .background(theme.colors.bgPrimary)
            .task { model.loadWallets() }
            .sheet(item: $adding) { AddWalletSheet(mode: $0).earthThemed() }
        }
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: theme.space.x8)

                ForEach(Array(model.wallets.enumerated()), id: \.element.id) { index, wallet in
                    walletRow(wallet, selected: index == model.selected) {
                        Task { await model.select(index) }
                    }
                }

                Spacer().frame(height: theme.space.x24)
                EarthButton(title: "Create a wallet") { adding = .create }
                Spacer().frame(height: theme.space.x8)
                EarthButton(title: "Import a recovery phrase", role: .secondary) { adding = .restore }

                // Not on the Android screen. Self-custody without a way to
                // read the phrase back is custody with extra steps. It sits
                // behind a fresh unlock, not the session's: an unlocked
                // phone should not be enough to read the wallet out of it.
                Spacer().frame(height: theme.space.x24)
                EarthLabel("Recovery phrase")
                Spacer().frame(height: theme.space.x8)
                if let revealed {
                    SeedGrid(words: revealed.split(separator: " ").map(String.init))
                    Spacer().frame(height: theme.space.x8)
                    EarthButton(title: "Hide", role: .secondary) { self.revealed = nil }
                } else {
                    Text("Anyone who has these twelve words has this wallet.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                    Spacer().frame(height: theme.space.x8)
                    EarthButton(title: "Reveal", role: .secondary) {
                        error = nil
                        confirming = true
                    }
                }

                if let error {
                    Spacer().frame(height: theme.space.x8)
                    Text(error)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                }

                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .scrollContentBackground(.hidden)
    }

    private func walletRow(_ wallet: WalletStore.Entry, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 0) {
                Text(String(wallet.name.prefix(1)).uppercased())
                    .font(EarthType.bodySmall)
                    .foregroundStyle(selected ? theme.colors.accentInk : theme.colors.textTertiary)
                    .frame(width: 32, height: 32)
                    .background(selected ? theme.colors.accentTint : theme.colors.bgSecondary, in: .circle)
                VStack(alignment: .leading, spacing: 0) {
                    Text(wallet.name)
                        .font(EarthType.body)
                        .fontWeight(selected ? .semibold : .regular)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text(wallet.address)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .padding(.leading, theme.space.x12)
                Spacer(minLength: theme.space.x8)
                if selected {
                    Text("✓")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.accentInk)
                }
            }
            .padding(.vertical, theme.space.x12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    /// Read from the vault the confirmation just opened, not from the
    /// session — which is the point of asking.
    private func reveal(from confirmation: AppModel.Confirmation) {
        revealed = confirmation.wallets.first { $0.address == model.address }?.mnemonic
        error = revealed == nil ? model.describe(WalletStore.Error.notFound) : nil
    }
}

/// Add a wallet: a new phrase to write down, or one you already have.
struct AddWalletSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    let mode: WalletsScreen.Adding

    @State private var name = ""
    @State private var phrase = ""
    @State private var written = false
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthLabel("Name")
                    TextField(defaultName, text: $name)
                        .font(EarthType.body)
                        .padding(theme.space.x12)
                        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))

                    if mode == .create {
                        Text("Write these 12 words down, in order, on paper. Anyone who has them has this wallet.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textSecondary)
                        SeedGrid(words: phrase.split(separator: " ").map(String.init))
                        Toggle(isOn: $written) {
                            Text("I have written them down.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textSecondary)
                        }
                        .tint(theme.colors.brandButtonBg)
                    } else {
                        EarthLabel("Recovery phrase")
                        TextEditor(text: $phrase)
                            .font(EarthType.mono)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .frame(minHeight: 120)
                            .scrollContentBackground(.hidden)
                            .padding(theme.space.x8)
                            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
                        if !phrase.isEmpty, !BIP39.isValid(mnemonic: phrase) {
                            Text("That is not a valid recovery phrase yet.")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textError)
                        }
                    }

                    if let error {
                        Text(error)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textError)
                    }

                    EarthButton(title: mode == .create ? "Create" : "Import", busy: saving) { save() }
                        .disabled(!ready)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle(mode == .create ? "New wallet" : "Import wallet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .task {
                // Generated on arrival rather than on tap, so the words are on
                // screen for as long as the user is looking at this page.
                if mode == .create, phrase.isEmpty {
                    phrase = (try? BIP39.generateMnemonic()) ?? ""
                }
            }
        }
    }

    private var defaultName: String { "Wallet \(model.wallets.count + 1)" }

    private var ready: Bool {
        guard BIP39.isValid(mnemonic: phrase) else { return false }
        return mode == .restore || written
    }

    private func save() {
        saving = true
        Task {
            do {
                try await model.addWallet(
                    mnemonic: BIP39.canonical(phrase),
                    name: name.trimmingCharacters(in: .whitespaces).isEmpty ? defaultName : name
                )
                dismiss()
            } catch {
                self.error = model.describe(error)
            }
            saving = false
        }
    }
}
