import EarthCore
import SwiftUI

/// First run: name the wallet, choose a PIN, then the phrase.
///
/// The PIN comes before the phrase because it is what the phrase will be
/// encrypted with — asking afterwards would mean holding an unprotected
/// mnemonic in the meantime, however briefly.
struct SetupFlow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    @State private var route: Route?

    enum Route: Hashable { case create, restore }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                Spacer()
                EarthAsset.logo?
                    .resizable().scaledToFit()
                    .frame(width: 88, height: 88)
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
            .background(theme.colors.bgPrimary)
            .navigationDestination(item: $route) { NewWalletFlow(restoring: $0 == .restore) }
        }
    }
}

/// Name, PIN, phrase — in that order, on one screen at a time.
struct NewWalletFlow: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    let restoring: Bool

    @State private var step = Step.name
    @State private var name = ""
    @State private var method = WalletStore.Method.pin
    @State private var pin = ""
    @State private var phrase = ""
    @State private var written = false
    @State private var error: String?
    @State private var saving = false

    enum Step { case name, method, pin, phrase }

    var body: some View {
        Group {
            switch step {
            case .name: nameStep
            case .method: methodStep
            case .pin:
                SetPinScreen(error: error) { chosen in
                    pin = chosen
                    step = .phrase
                }
            case .phrase: phraseStep
            }
        }
        .background(theme.colors.bgPrimary)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var title: String {
        switch step {
        case .name: restoring ? "Restore" : "New wallet"
        case .method: "How to unlock"
        case .pin: "PIN"
        case .phrase: restoring ? "Recovery phrase" : "Write these down"
        }
    }

    private var nameStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                EarthLabel("Wallet name")
                TextField(defaultName, text: $name)
                    .font(EarthType.body)
                    .padding(theme.space.x12)
                    .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
                Text("Only you see this. It names the wallet in the app, and you can hold more than one.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                EarthButton(title: "Continue") { step = .method }
            }
            .padding(theme.space.gutter)
        }
    }

    /// PIN, biometrics, or both.
    ///
    /// Not three ways of gating the same readable secret — the choice decides
    /// what the wallet is *encrypted* with. A PIN wallet is sealed by the PIN;
    /// a biometrics wallet is sealed by a random key only the biometric prompt
    /// can fetch; both means the PIN seals it and that prompt holds a copy.
    private var methodStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x12) {
                Text("However you unlock it, the recovery phrase is the only way back if this device is lost.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)

                methodRow(.pin, "PIN", "Four digits, entered each time you open the app.")
                if WalletStore.biometricsAvailable {
                    methodRow(.biometrics, WalletStore.biometryName,
                              "No PIN to remember. If \(WalletStore.biometryName) stops working, only your recovery phrase gets you back in.")
                    methodRow(.both, "Both",
                              "\(WalletStore.biometryName) normally, your PIN as a fallback.")
                } else {
                    // Deliberately unnamed. When the hardware exists but is not
                    // enrolled, biometryType still reports it and naming it
                    // would be right; when there is no such hardware it reports
                    // nothing, and "biometrics is not set up" reads as a broken
                    // sentence. One line covers both.
                    Text("No biometric unlock is available on this device, so a PIN is the only option.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }

                Spacer().frame(height: theme.space.x8)
                EarthButton(title: "Continue") {
                    step = method.usesPin ? .pin : .phrase
                }
            }
            .padding(theme.space.gutter)
        }
    }

    private func methodRow(_ option: WalletStore.Method, _ title: String, _ detail: String) -> some View {
        Button { method = option } label: {
            HStack(alignment: .top, spacing: theme.space.x12) {
                Image(systemName: method == option ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(method == option ? theme.colors.accentInk : theme.colors.textDisabled)
                VStack(alignment: .leading, spacing: theme.space.x2) {
                    Text(title)
                        .font(EarthType.body).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text(detail)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
            }
            .padding(theme.space.x16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
        }
        .buttonStyle(.plain)
    }

    private var phraseStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                if restoring {
                    EarthLabel("Recovery phrase")
                    TextEditor(text: $phrase)
                        .font(EarthType.mono)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .frame(minHeight: 140)
                        .scrollContentBackground(.hidden)
                        .padding(theme.space.x8)
                        .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusMd))
                    // Checked as typed. BIP-39's checksum catches a wrong or
                    // transposed word here, where it is fixable, rather than
                    // silently restoring a different empty wallet.
                    if !phrase.isEmpty, !BIP39.isValid(mnemonic: phrase) {
                        Text("That is not a valid recovery phrase yet.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textError)
                    }
                } else {
                    Text("Write these words down, in order, on paper. They are the only way to recover this wallet — nobody can reset them for you, and anyone who has them has the wallet.")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textSecondary)
                    SeedGrid(words: phrase.split(separator: " ").map(String.init))
                    Toggle(isOn: $written) {
                        Text("I have written them down.")
                            .font(EarthType.body)
                            .foregroundStyle(theme.colors.textSecondary)
                    }
                    .tint(theme.colors.brandButtonBg)
                }

                if let error {
                    Text(error)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                }

                EarthButton(title: restoring ? "Restore" : "Create wallet", busy: saving) { save() }
                    .disabled(!ready)
            }
            .padding(theme.space.gutter)
        }
        .task {
            // Generated on arrival rather than on tap, so the words are on
            // screen for as long as the user is looking at this page.
            if !restoring, phrase.isEmpty {
                phrase = (try? BIP39.generateMnemonic()) ?? ""
            }
        }
    }

    private var defaultName: String { "Wallet 1" }

    private var ready: Bool {
        guard BIP39.isValid(mnemonic: phrase) else { return false }
        return restoring || written
    }

    private func save() {
        saving = true
        Task {
            do {
                try await model.adopt(
                    mnemonic: phrase,
                    name: name.trimmingCharacters(in: .whitespaces).isEmpty ? defaultName : name,
                    method: method,
                    pin: method.usesPin ? pin : nil
                )
            } catch {
                self.error = model.describe(error)
            }
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
                .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusSm))
            }
        }
    }
}

/// Unlock, or choose a PIN — the same keypad either way.
struct UnlockScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    @State private var status = UnlockAttempts.status()
    @State private var error: String?
    /// Ticks only while a lockout is running, so the message counts down.
    @State private var now = Date()

    var body: some View {
        VStack(spacing: 0) {
            if model.method.usesPin {
                PinKeypad(
                    title: "Welcome back",
                    message: status.message ?? error ?? "Enter your PIN to continue",
                    isError: status.message != nil || error != nil,
                    enabled: !status.lockedOut,
                    onComplete: submit
                )
            } else {
                Spacer()
                EarthAsset.logo?
                    .resizable().scaledToFit()
                    .frame(width: 88, height: 88)
                Spacer().frame(height: theme.space.x24)
                Text("Welcome back")
                    .font(EarthType.headline)
                    .foregroundStyle(theme.colors.textPrimary)
                Text(error ?? "Unlock with \(WalletStore.biometryName).")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(error != nil ? theme.colors.textError : theme.colors.textTertiary)
                Spacer()
            }

            if model.method.usesBiometrics {
                Button("Use \(WalletStore.biometryName)") {
                    Task { _ = await model.unlockWithBiometrics() }
                }
                .font(EarthType.body)
                .foregroundStyle(theme.colors.accentInk)
                .padding(.bottom, theme.space.x32)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bgPrimary)
        .task {
            // Offered without being asked for: a wallet that unlocks by face
            // should not need a tap to say so.
            if model.method.usesBiometrics {
                _ = await model.unlockWithBiometrics()
            }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                now = Date()
                status = UnlockAttempts.status()
            }
        }
    }

    private func submit(_ pin: String) {
        Task {
            if await model.unlock(pin: pin) { return }
            status = UnlockAttempts.status()
            error = status.lockedOut
                ? nil
                : "Incorrect PIN. \(status.attemptsLeft) attempts left."
        }
    }
}

/// Choosing the PIN.
///
/// Asked twice because the PIN encrypts the phrase rather than merely gating
/// it: a typo here does not lock someone out of an account they can reset, it
/// encrypts their wallet under a PIN they do not know.
struct SetPinScreen: View {
    @Environment(\.earth) private var theme
    let error: String?
    let onChosen: (String) -> Void

    @State private var first: String?
    @State private var mismatch = false

    var body: some View {
        PinKeypad(
            title: first == nil ? "Choose a PIN" : "Enter it again",
            message: error
                ?? (mismatch ? "Those did not match. Start again."
                    : first == nil
                        ? "It encrypts your wallet on this device. There is no way to reset it."
                        : "Confirm your PIN"),
            isError: error != nil || mismatch,
            enabled: true,
            onComplete: { entered in
                mismatch = false
                if let chosen = first {
                    if chosen == entered {
                        onChosen(entered)
                    } else {
                        // Start over rather than clearing only the second
                        // entry: the one they meant is as likely to be the
                        // first as the second.
                        mismatch = true
                        first = nil
                    }
                } else {
                    first = entered
                }
            }
        )
    }
}

/// Four dots and a keypad.
///
/// Not a text field: a PIN that echoes its length is the point, and a field
/// would bring a cursor, a keyboard and a selection handle to suppress.
struct PinKeypad: View {
    @Environment(\.earth) private var theme

    let title: String
    let message: String
    let isError: Bool
    let enabled: Bool
    let onComplete: (String) -> Void

    @State private var pin = ""

    private let keys = [["1","2","3"], ["4","5","6"], ["7","8","9"], ["","0","⌫"]]

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            EarthAsset.logo?
                .resizable().scaledToFit()
                .frame(width: 88, height: 88)

            Spacer().frame(height: theme.space.x24)
            Text(title)
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
            Spacer().frame(height: theme.space.x4)
            Text(message)
                .font(EarthType.bodySmall)
                .foregroundStyle(isError ? theme.colors.textError : theme.colors.textTertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, theme.space.gutter)

            Spacer().frame(height: theme.space.x24)
            HStack(spacing: 12) {
                ForEach(0 ..< 4, id: \.self) { index in
                    Circle()
                        .fill(dot(index))
                        .frame(width: 14, height: 14)
                }
            }

            Spacer()
            VStack(spacing: 12) {
                ForEach(keys, id: \.self) { row in
                    HStack(spacing: 12) {
                        ForEach(row, id: \.self) { key in
                            if key.isEmpty {
                                Color.clear.frame(width: 72, height: 72)
                            } else {
                                keycap(key)
                            }
                        }
                    }
                }
            }
            Spacer().frame(height: theme.space.x32)
        }
        .frame(maxWidth: .infinity)
        .onChange(of: pin) { _, value in
            guard value.count == 4 else { return }
            let entered = value
            // Cleared before the callback so a rejected PIN leaves an empty
            // field rather than four dots the user has to erase.
            pin = ""
            onComplete(entered)
        }
    }

    private func dot(_ index: Int) -> Color {
        if !enabled { return theme.colors.strokeSecondary }
        return index < pin.count ? theme.colors.brandButtonBg : theme.colors.bgSecondary
    }

    private func keycap(_ key: String) -> some View {
        Button {
            if key == "⌫" {
                pin = String(pin.dropLast())
            } else if pin.count < 4 {
                pin += key
            }
        } label: {
            Text(key)
                .font(EarthType.headline)
                .foregroundStyle(enabled ? theme.colors.textPrimary : theme.colors.textTertiary)
                .frame(width: 72, height: 72)
                .background(theme.colors.bgSecondary, in: .circle)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
