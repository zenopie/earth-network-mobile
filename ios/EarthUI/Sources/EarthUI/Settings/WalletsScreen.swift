import EarthCore
import SwiftUI

/// The wallets in this install.
///
/// A list with the current one marked, and two ways to add another. Switching
/// is the whole reason the screen exists, so it happens on a tap of the row
/// rather than behind a menu — there is nothing else a row could usefully do.
///
/// Delete sits behind a fresh unlock and the phrase itself: removing a wallet
/// removes the only copy of its mnemonic on this phone, so it is shown once
/// more and its owner says they have it before anything goes.
struct WalletsScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @State private var adding: Adding?
    @State private var revealed: String?
    @State private var confirming = false
    @State private var error: String?
    /// Delete: the unlock is being asked for, then the wallet whose phrase is shown.
    @State private var confirmingDelete = false
    @State private var deleting: (entry: WalletStore.Entry, confirmation: AppModel.Confirmation)?

    enum Adding: String, Identifiable { case create, restore; var id: String { rawValue } }

    var body: some View {
        NavigationStack {
            Group {
                if confirming {
                    ConfirmIdentity(reason: "Show your recovery phrase") { confirmation in
                        reveal(from: confirmation)
                        confirming = false
                    }
                } else if confirmingDelete {
                    ConfirmIdentity(reason: "Delete this wallet") { confirmation in
                        confirmingDelete = false
                        if let entry = confirmation.wallets.first(where: { $0.address == model.address }) {
                            deleting = (entry, confirmation)
                        } else {
                            error = model.describe(WalletStore.Error.notFound)
                        }
                    }
                } else if let deleting {
                    DeleteWalletView(entry: deleting.entry, last: deleting.confirmation.wallets.count == 1) {
                        let d = deleting
                        self.deleting = nil
                        Task {
                            do { try await model.deleteWallet(address: d.entry.address, confirmation: d.confirmation) }
                            catch { self.error = model.describe(error) }
                        }
                    }
                } else {
                    list
                }
            }
            .navigationTitle(confirming ? "Recovery phrase" : confirmingDelete || deleting != nil ? "Delete wallet" : "Wallets")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if confirming || confirmingDelete || deleting != nil {
                        Button("Cancel") { confirming = false; confirmingDelete = false; deleting = nil }
                    } else {
                        Button("Done") { dismiss() }
                    }
                }
            }
            .background(theme.colors.bgPrimary)
            .task { model.loadWallets() }
            // The phrase lives only while the screen is in front, as on Android.
            .onChange(of: scenePhase) { if scenePhase != .active { revealed = nil; deleting = nil } }
            .onDisappear { revealed = nil; deleting = nil }
            .sheet(item: $adding) { AddWalletSheet(mode: $0).earthThemed() }
        }
    }

    private var list: some View {
        ScrollView {
            VStack(spacing: 0) {
                hero

                Spacer().frame(height: theme.space.x32)
                HStack(spacing: theme.space.x12) {
                    EarthPillButton(title: "Create") { adding = .create }
                    EarthPillButton(title: "Import", role: .secondary) { adding = .restore }
                }

                Spacer().frame(height: theme.space.x24)
                ForEach(Array(model.wallets.enumerated()), id: \.element.id) { index, wallet in
                    walletRow(wallet, selected: index == model.selected) {
                        Task { await model.select(index) }
                    }
                }

                // Not on the Android screen. Self-custody without a way to
                // read the phrase back is custody with extra steps. It sits
                // behind a fresh unlock, not the session's: an unlocked
                // phone should not be enough to read the wallet out of it.
                VStack(alignment: .leading, spacing: theme.space.x8) {
                    EarthLabel("Recovery phrase")
                    if let revealed {
                        SeedGrid(words: revealed.split(separator: " ").map(String.init))
                    } else {
                        Text("Anyone with this phrase can take your funds.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, theme.space.x24)

                Spacer().frame(height: theme.space.x12)
                if revealed != nil {
                    EarthPillButton(title: "Hide", role: .secondary) { self.revealed = nil }
                } else {
                    EarthPillButton(title: "Show phrase", role: .secondary) {
                        error = nil
                        confirming = true
                    }
                }

                Button("Delete wallet") {
                    error = nil
                    confirmingDelete = true
                }
                .font(EarthType.body).fontWeight(.semibold)
                .foregroundStyle(theme.colors.textError)
                .padding(.top, theme.space.x24)

                if let error {
                    Text(error)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                        .padding(.top, theme.space.x8)
                }

                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .scrollContentBackground(.hidden)
    }

    /// The wallet in use: its initial, its name, its address.
    @ViewBuilder
    private var hero: some View {
        let current = model.wallets.indices.contains(model.selected) ? model.wallets[model.selected] : nil
        VStack(spacing: 0) {
            Text(String(current?.name.prefix(1) ?? "").uppercased())
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(theme.colors.accentInk)
                .frame(width: 56, height: 56)
                .background(theme.colors.accentTint, in: .circle)
            Text(current?.name ?? "")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(theme.colors.textPrimary)
                .lineLimit(1)
                .padding(.top, theme.space.x12)
            Text(current?.address ?? "")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
                .lineLimit(1)
                .truncationMode(.middle)
                .padding(.top, theme.space.x4)
                .padding(.horizontal, theme.space.x32)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }

    private func walletRow(_ wallet: WalletStore.Entry, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 0) {
                Text(String(wallet.name.prefix(1)).uppercased())
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(selected ? theme.colors.accentInk : theme.colors.textTertiary)
                    .frame(width: 44, height: 44)
                    .background(selected ? theme.colors.accentTint : theme.colors.bgTertiary, in: .circle)
                VStack(alignment: .leading, spacing: 0) {
                    Text(wallet.name)
                        .font(EarthType.body)
                        .fontWeight(.semibold)
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
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 20))
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

/// The last look before a wallet goes: its phrase, a box to say it is
/// written down, and Delete only once it is.
private struct DeleteWalletView: View {
    @Environment(\.earth) private var theme
    let entry: WalletStore.Entry
    /// The only wallet: deleting it returns the app to setup.
    let last: Bool
    let delete: () -> Void
    @State private var written = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Text("Delete \(entry.name)?")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(theme.colors.textPrimary)
                    .padding(.top, theme.space.x24)
                Text(last ? "Your only wallet · the app resets" : "Without this phrase it's gone")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textError)
                    .padding(.top, theme.space.x4)

                SeedGrid(words: entry.mnemonic.split(separator: " ").map(String.init))
                    .padding(.top, theme.space.x24)

                Toggle(isOn: $written) {
                    Text("I wrote this phrase down")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textPrimary)
                }
                .tint(theme.colors.brandButtonBg)
                .padding(.top, theme.space.x24)

                EarthButton(title: "Delete", role: .destructive, action: delete)
                    .disabled(!written)
                    .padding(.top, theme.space.x16)
            }
            .padding(.horizontal, theme.space.gutter)
            .padding(.bottom, theme.space.x32)
        }
        .scrollContentBackground(.hidden)
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
                    Spacer().frame(height: theme.space.x8)
                    EarthLabel("Name")
                    TextField(defaultName, text: $name)
                        .font(EarthType.body)
                        .padding(theme.space.x12)
                        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))

                    if mode == .create {
                        Text("Write these down. Anyone with them can take your funds.")
                            .font(EarthType.bodySmall).fontWeight(.semibold)
                            .foregroundStyle(theme.colors.warnInk)
                        SeedGrid(words: phrase.split(separator: " ").map(String.init))
                        Toggle(isOn: $written) {
                            Text("I wrote them down")
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
                            Text("Not a valid phrase yet")
                                .font(EarthType.bodySmall)
                                .foregroundStyle(theme.colors.textError)
                        }
                    }

                    if let error {
                        Text(error)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textError)
                    }

                    // The pill has no spinner: the title says it, and the
                    // disable stands in for EarthButton's busy.
                    EarthPillButton(title: saving ? "Saving…" : (mode == .create ? "Create" : "Import")) { save() }
                        .disabled(!ready || saving)
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
