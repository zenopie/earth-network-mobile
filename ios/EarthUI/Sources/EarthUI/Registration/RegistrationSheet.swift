import EarthCore
import SwiftUI

/// Registration: the three MRZ fields, then the chip, then a proof.
///
/// The chip step is not built. Raw APDU exchange needs the `TAG` reader-session
/// format, which needs the "Near Field Communication Tag Reading" entitlement,
/// which a free Personal Team cannot enable — so it cannot be written *and*
/// run until the Apple Developer Program enrolment lands. The step is shown
/// rather than hidden: someone opening this should learn what registration
/// involves and what is missing, not find a dead end.
///
/// Everything on either side of it is real. The MRZ is validated here with the
/// same check digits the chip uses, and `PassportRegistration` already turns a
/// DG1 and an EF.SOD into a witness the circuit accepts.
struct RegistrationSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var step = Step.intro
    @State private var key = MRZ.Key(documentNumber: "", dateOfBirth: "", dateOfExpiry: "")
    @State private var referrer = ""

    enum Step: Hashable { case intro, mrz, chip }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    switch step {
                    case .intro: intro
                    case .mrz: mrzEntry
                    case .chip: chip
                    }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Register")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .earthBackground()
            .scrollContentBackground(.hidden)
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            EarthGlyph(systemName: "person.badge.key.fill", size: 56)
            Text("Prove you are a unique human")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
            Text("Your passport's chip signs a proof on this device. The proof shows a government signed your document and that you have not registered before — it does not carry your name, your photo, or your document number, and nothing about the passport leaves the phone.")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textSecondary)

            EarthCard {
                StepRow(number: 1, title: "Type three fields", detail: "Document number, date of birth, date of expiry — from the two lines at the bottom of the photo page.")
                StepRow(number: 2, title: "Hold the passport to the phone", detail: "The chip is read over NFC.")
                StepRow(number: 3, title: "Prove and register", detail: "About a second and a half of proving, then one transaction.")
            }

            EarthButton(title: "Start") { step = .mrz }
        }
    }

    private var mrzEntry: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            Text("These three fields unlock the chip. They are on the two machine-readable lines at the bottom of the photo page.")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textSecondary)

            field("Document number", text: $key.documentNumber, placeholder: "L898902C", uppercase: true)
            field("Date of birth", text: $key.dateOfBirth, placeholder: "YYMMDD", numeric: true)
            field("Date of expiry", text: $key.dateOfExpiry, placeholder: "YYMMDD", numeric: true)

            // Checked here rather than at the chip: a mistyped field comes back
            // from the chip only as "wrong key", with the passport held against
            // the phone and nothing to point at.
            if !key.isComplete {
                Text("Fill all three to continue.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            } else if seed == nil {
                Text("Those characters are not valid in a machine-readable zone.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textError)
            }

            VStack(alignment: .leading, spacing: theme.space.x8) {
                EarthLabel("Referrer (optional)")
                TextField("earth1…", text: $referrer)
                    .font(EarthType.mono)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(theme.space.x12)
                    .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusMd))
                    .overlay {
                        RoundedRectangle(cornerRadius: theme.space.radiusMd)
                            .strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke)
                    }
                Text("The chain splits the registration reward with a referrer. They must be a different, already-registered human.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                if !referrer.isEmpty, !EarthKey.isValidAddress(referrer) {
                    Text("Not a valid earth address.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                }
            }

            EarthButton(title: "Read the chip") { step = .chip }
                .disabled(seed == nil || referrerInvalid)
        }
    }

    private var chip: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            EarthGlyph(systemName: "wave.3.right", size: 56)
            Text("Chip reading is not in this build")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)

            Text("Reading a passport needs raw APDU exchange, which iOS only allows with the Near Field Communication Tag Reading entitlement. That entitlement requires a paid Apple Developer account, so this step cannot be enabled yet.")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textSecondary)

            EarthCard {
                EarthLabel("What is already done")
                Text("Everything after the chip read. The MRZ you just typed is validated with the same check digits the chip uses, and a passport's DG1 and EF.SOD already produce a proof this chain's own verifier accepts.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
            }

            // Shown so the step reads as a real one waiting on a key, not as an
            // unwritten screen.
            EarthCard {
                EarthDetailRow(label: "Document number", value: key.documentNumber.uppercased())
                EarthDetailRow(label: "Date of birth", value: key.dateOfBirth)
                EarthDetailRow(label: "Date of expiry", value: key.dateOfExpiry)
                if let seed {
                    EarthDetailRow(label: "Chip access key", value: String(seed.hexString.prefix(16)) + "…")
                }
            }

            EarthButton(title: "Back", role: .secondary) { step = .mrz }
        }
    }

    /// The BAC key seed, which doubles as a validity check: it only computes
    /// when every character is one a machine-readable zone can carry.
    private var seed: Data? {
        guard key.isComplete else { return nil }
        return try? MRZ.bacKeySeed(key)
    }

    private var referrerInvalid: Bool {
        !referrer.isEmpty && !EarthKey.isValidAddress(referrer)
    }

    private func field(
        _ label: String,
        text: Binding<String>,
        placeholder: String,
        uppercase: Bool = false,
        numeric: Bool = false
    ) -> some View {
        VStack(alignment: .leading, spacing: theme.space.x8) {
            EarthLabel(label)
            TextField(placeholder, text: text)
                .font(EarthType.mono)
                .textInputAutocapitalization(uppercase ? .characters : .never)
                .autocorrectionDisabled()
                .keyboardType(numeric ? .numberPad : .asciiCapable)
                .padding(theme.space.x12)
                .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusMd))
                .overlay {
                    RoundedRectangle(cornerRadius: theme.space.radiusMd)
                        .strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke)
                }
        }
    }
}

struct StepRow: View {
    @Environment(\.earth) private var theme
    let number: Int
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: theme.space.x12) {
            Text("\(number)")
                .font(EarthType.eyebrow)
                .foregroundStyle(theme.colors.accentInk)
                .frame(width: 24, height: 24)
                .background(theme.colors.accentTint, in: .circle)
            VStack(alignment: .leading, spacing: theme.space.x2) {
                Text(title)
                    .font(EarthType.label)
                    .foregroundStyle(theme.colors.textPrimary)
                Text(detail)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
        .padding(.vertical, theme.space.x4)
    }
}
