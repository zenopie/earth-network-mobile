import EarthCore
import SwiftUI

/// Change how this wallet is opened.
///
/// The vault is re-sealed rather than re-gated: choosing a PIN makes the PIN
/// the encryption key, and choosing away from one stops it opening anything.
/// That is why this asks for the new PIN before it will do it, and why it can
/// only be done while unlocked.
struct SecurityScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var choice: WalletStore.Method = .pin
    @State private var askingPin = false
    @State private var error: String?
    @State private var saved = false

    var body: some View {
        NavigationStack {
            Group {
                if askingPin {
                    SetPinScreen(error: error) { pin in
                        apply(pin: pin)
                        askingPin = false
                    }
                } else {
                    form
                }
            }
            .navigationTitle(askingPin ? "New PIN" : "Unlocking")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .task { choice = model.method }
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x12) {
                Text("However you unlock it, the recovery phrase is the only way back if this device is lost.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)

                row(.pin, "PIN", "Four digits, entered each time you open the app.")
                if WalletStore.biometricsAvailable {
                    row(.biometrics, WalletStore.biometryName,
                        "No PIN to remember. If \(WalletStore.biometryName) stops working, only your recovery phrase gets you back in.")
                    row(.both, "Both", "\(WalletStore.biometryName) normally, your PIN as a fallback.")
                }

                if let error {
                    Text(error)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                }
                if saved {
                    Text("Saved.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.accentInk)
                }

                Spacer().frame(height: theme.space.x8)
                EarthButton(title: "Save") { save() }
                    .disabled(choice == model.method)
            }
            .padding(theme.space.gutter)
        }
        .scrollContentBackground(.hidden)
    }

    private func row(_ option: WalletStore.Method, _ title: String, _ detail: String) -> some View {
        Button { choice = option; saved = false } label: {
            HStack(alignment: .top, spacing: theme.space.x12) {
                Image(systemName: choice == option ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(choice == option ? theme.colors.accentInk : theme.colors.textDisabled)
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

    private func save() {
        error = nil
        // A method with a PIN needs one chosen now: the old one cannot be
        // reused, because on a biometrics-only wallet there never was one.
        if choice.usesPin {
            askingPin = true
        } else {
            apply(pin: nil)
        }
    }

    private func apply(pin: String?) {
        do {
            try model.setMethod(choice, pin: pin)
            saved = true
            error = nil
        } catch {
            self.error = model.describe(error)
        }
    }
}
