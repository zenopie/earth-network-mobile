import EarthCore
import SwiftUI

/// Change how this wallet is opened.
///
/// The vault is re-sealed rather than re-gated: choosing a PIN makes the PIN
/// the encryption key, and choosing away from one stops it opening anything.
/// That is why this asks for the new PIN before it will do it, and why it can
/// only be done while unlocked.
///
/// The current method is asked for first, fresh. Otherwise anyone holding the
/// phone unlocked could set a PIN of their own — or swap in their own face —
/// and keep the wallet after it locks.
struct SecurityScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var choice: WalletStore.Method = .pin
    @State private var step = Step.form
    @State private var error: String?
    @State private var saved = false

    enum Step {
        case form, confirming
        case newPin(AppModel.Confirmation)
    }

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .form:
                    form
                case .confirming:
                    ConfirmIdentity(reason: "Change how your wallet unlocks") { confirmation in
                        // A method with a PIN needs one chosen now: the old one
                        // cannot be reused, because on a biometrics-only wallet
                        // there never was one.
                        if choice.usesPin {
                            step = .newPin(confirmation)
                        } else {
                            apply(pin: nil, confirmation: confirmation)
                        }
                    }
                case let .newPin(confirmation):
                    SetPinScreen(error: error) { pin in
                        apply(pin: pin, confirmation: confirmation)
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .task { choice = model.method }
        }
    }

    private var form: some View {
        ScrollView {
            VStack(spacing: 0) {
                hero

                Spacer().frame(height: theme.space.x24)
                VStack(spacing: theme.space.x8) {
                    row(.pin, "PIN", "Four digits")
                    if WalletStore.biometricsAvailable {
                        row(.biometrics, name(.biometrics), "No PIN to remember")
                        row(.both, "Both", "Both, every time")
                    }
                }

                if let error {
                    Text(error)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                        .padding(.top, theme.space.x12)
                }
                if saved {
                    Text("Saved")
                        .font(EarthType.bodySmall).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.accentInk)
                        .padding(.top, theme.space.x12)
                }

                Spacer().frame(height: theme.space.x24)
                EarthPillButton(title: "Save") { save() }
                    .disabled(choice == model.method)
                Spacer().frame(height: theme.space.x32)
            }
            .padding(.horizontal, theme.space.gutter)
        }
        .scrollContentBackground(.hidden)
    }

    /// How it unlocks now, big. The one line is the backup warning: whichever
    /// method is chosen, the phrase is the only way back on a lost device.
    private var hero: some View {
        VStack(spacing: theme.space.x8) {
            EarthGlyph(systemName: glyph, size: 56)
            Text(name(model.method))
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(theme.colors.textPrimary)
                .padding(.top, theme.space.x4)
            Text("Lost phone? Only your phrase recovers it.")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, theme.space.x24)
    }

    private var glyph: String {
        guard model.method.usesBiometrics else { return "lock.fill" }
        return WalletStore.biometryName == "Touch ID" ? "touchid" : "faceid"
    }

    private func name(_ method: WalletStore.Method) -> String {
        // biometryName's fallback is lowercase, for mid-sentence use.
        let bio = WalletStore.biometryName.prefix(1).uppercased() + WalletStore.biometryName.dropFirst()
        return switch method {
        case .pin: "PIN"
        case .biometrics: bio
        case .both: "PIN + \(bio)"
        }
    }

    private func row(_ option: WalletStore.Method, _ title: String, _ detail: String) -> some View {
        Button { choice = option; saved = false } label: {
            HStack(spacing: theme.space.x12) {
                Image(systemName: choice == option ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(choice == option ? theme.colors.accentInk : theme.colors.textDisabled)
                VStack(alignment: .leading, spacing: theme.space.x2) {
                    Text(title)
                        .font(EarthType.body).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text(detail)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
                Spacer()
            }
            .padding(theme.space.x16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusLg))
        }
        .buttonStyle(.plain)
    }

    private var title: String {
        switch step {
        case .form: "Unlocking"
        case .confirming: "Current unlock"
        case .newPin: "New PIN"
        }
    }

    private func save() {
        error = nil
        step = .confirming
    }

    private func apply(pin: String?, confirmation: AppModel.Confirmation) {
        do {
            try model.setMethod(choice, pin: pin, confirmedBy: confirmation)
            saved = true
            error = nil
        } catch {
            self.error = model.describe(error)
        }
        step = .form
    }
}
