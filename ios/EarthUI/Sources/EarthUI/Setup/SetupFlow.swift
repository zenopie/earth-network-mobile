import EarthCore
import SwiftUI

/// First run: make a wallet or restore one.
struct SetupFlow: View {
    @Environment(\.earth) private var theme
    @State private var route: Route?

    enum Route: Hashable { case create, restore }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                Spacer()
                EarthGlyph(systemName: "globe.europe.africa.fill", size: 64)
                Text("Earth Wallet")
                    .font(EarthType.display)
                    .foregroundStyle(theme.colors.textPrimary)
                Text("A self-custody wallet for earth-1. Your recovery phrase never leaves this device.")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textTertiary)
                Spacer()
                EarthButton(title: "Create a new wallet") { route = .create }
                EarthButton(title: "I have a recovery phrase", role: .secondary) { route = .restore }
            }
            .padding(theme.space.gutter)
            .earthBackground()
            .navigationDestination(item: $route) { route in
                switch route {
                case .create: CreateWalletScreen()
                case .restore: RestoreWalletScreen()
                }
            }
        }
    }
}

/// A new phrase, shown once and confirmed once.
///
/// The confirmation step is not ceremony: a phrase that was never written down
/// is a wallet that will be lost, and the only moment anyone will do it is
/// before there is money in the account.
struct CreateWalletScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    @State private var mnemonic = ""
    @State private var written = false
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                Text("Write these 12 words down, in order, on paper. Anyone who has them has your funds.")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textSecondary)

                SeedGrid(words: mnemonic.split(separator: " ").map(String.init))

                Toggle(isOn: $written) {
                    Text("I have written them down.")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textSecondary)
                }
                .tint(Palette.Brand.b600)

                if let error {
                    Text(error).font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                }

                EarthButton(title: "Continue", busy: saving) { save() }
                    .disabled(!written || mnemonic.isEmpty)
            }
            .padding(theme.space.gutter)
        }
        .navigationTitle("Recovery phrase")
        .navigationBarTitleDisplayMode(.inline)
        .earthBackground()
        .scrollContentBackground(.hidden)
        .task {
            // Generated on arrival rather than on tap, so the words are on
            // screen for as long as the user is looking at this page.
            mnemonic = (try? BIP39.generateMnemonic()) ?? ""
        }
    }

    private func save() {
        saving = true
        Task {
            do { try await model.adopt(mnemonic: mnemonic) }
            catch { self.error = model.describe(error) }
            saving = false
        }
    }
}

struct RestoreWalletScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    @State private var phrase = ""
    @State private var error: String?
    @State private var saving = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                EarthLabel("Recovery phrase")
                TextEditor(text: $phrase)
                    .font(EarthType.mono)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .frame(minHeight: 140)
                    .scrollContentBackground(.hidden)
                    .padding(theme.space.x8)
                    .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusMd))
                    .overlay {
                        RoundedRectangle(cornerRadius: theme.space.radiusMd)
                            .strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke)
                    }

                // Checked as typed. BIP-39's checksum catches a wrong or
                // transposed word here, where it is fixable, rather than
                // silently restoring a different empty wallet.
                if !phrase.isEmpty, !BIP39.isValid(mnemonic: phrase) {
                    Text("That is not a valid recovery phrase yet.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                }
                if let error {
                    Text(error).font(EarthType.bodySmall).foregroundStyle(theme.colors.textError)
                }

                EarthButton(title: "Restore", busy: saving) { restore() }
                    .disabled(!BIP39.isValid(mnemonic: phrase))
            }
            .padding(theme.space.gutter)
        }
        .navigationTitle("Restore")
        .navigationBarTitleDisplayMode(.inline)
        .earthBackground()
        .scrollContentBackground(.hidden)
    }

    private func restore() {
        saving = true
        Task {
            do { try await model.adopt(mnemonic: phrase) }
            catch { self.error = model.describe(error) }
            saving = false
        }
    }
}

/// The phrase, numbered, two columns.
///
/// Numbered because order is half the secret, and a phrase copied out of order
/// restores nothing while looking exactly right.
struct SeedGrid: View {
    @Environment(\.earth) private var theme
    let words: [String]

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: theme.space.x8) {
            ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                HStack(spacing: theme.space.x8) {
                    Text("\(index + 1)")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textDisabled)
                        .frame(width: 18, alignment: .trailing)
                    Text(word)
                        .font(EarthType.label)
                        .foregroundStyle(theme.colors.textPrimary)
                    Spacer()
                }
                .padding(.vertical, theme.space.x8)
                .padding(.horizontal, theme.space.x12)
                .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusSm))
            }
        }
    }
}

struct UnlockScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @State private var failed = false

    var body: some View {
        VStack(spacing: theme.space.x16) {
            Spacer()
            EarthGlyph(systemName: "lock.fill", size: 64)
            Text("Earth Wallet")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
            if failed {
                Text("Authentication failed.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textError)
            }
            Spacer()
            EarthButton(title: "Unlock") { unlock() }
                .padding(.horizontal, theme.space.gutter)
        }
        .padding(.bottom, theme.space.x32)
        .earthBackground()
        // Prompt on appear: an unlock screen whose only control is "Unlock" is
        // a tap nobody chose to make.
        .task { unlock() }
    }

    private func unlock() {
        Task { failed = !(await model.unlock()) }
    }
}
