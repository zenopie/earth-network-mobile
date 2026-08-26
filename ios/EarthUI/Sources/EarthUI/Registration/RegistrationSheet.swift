import EarthCore
import SwiftUI

/// Registration: the three MRZ fields, then the chip, then a proof, then one
/// transaction.
///
/// The order is the one the Android flow settled on, and the split between
/// proving and broadcasting is the load-bearing part of it. Proving is slow and
/// can fail; broadcasting needs a fee a new human does not have yet. Doing them
/// as one step means asking someone to watch an ad for gas before anyone knows
/// whether the proof will succeed — spending their time on a transaction that
/// may never exist. So the proof completes first, and only then does the
/// confirmation (and with it the gas gate) appear.
///
/// The chip step owns nothing on screen while it runs: iOS gives the reader
/// session its own system sheet and will not let anything draw over it, so this
/// starts the read and waits.
struct RegistrationSheet: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    @State private var step = Step.intro
    @State private var key = MRZ.Key(documentNumber: "", dateOfBirth: "", dateOfExpiry: "")

    @State private var referrer = ""

    /// What the chip gave up, held only until the proof is built from it.
    @State private var scan: PassportRegistration.Scan?
    @State private var proof: PassportRegistration.Proof?
    @State private var failure: String?

    /// The chip step's own progress. Not an enum on `Step` because the step
    /// does not change — the same screen reports reading, then proving, then
    /// what went wrong.
    @State private var phase = Phase.idle

    enum Step: Hashable { case intro, scan, mrz, chip, ready }

    enum Phase: Equatable {
        case idle
        /// The system sheet is up and the passport is against the phone.
        case reading
        /// The passport can go back in a pocket; Barretenberg is working.
        case proving
        case failed
    }

    var body: some View {
        NavigationStack {
            Group {
                if step == .scan {
                    MrzCameraScreen(
                        onDetected: { key in
                            self.key = key
                            step = .mrz
                        },
                        onManualEntry: { step = .mrz }
                    )
                } else {
                    ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    switch step {
                    case .intro: intro
                    case .scan: EmptyView()
                    case .mrz: mrzEntry
                    case .chip: chip
                    case .ready: ready
                    }
                    }
                    .padding(theme.space.gutter)
                    }
                }
            }
            .navigationTitle(step == .scan ? "Scan" : "Register")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Closing mid-read would leave the reader session up with
                    // nothing behind it, and mid-proof would throw away a proof
                    // that is nearly done.
                    Button("Close") { dismiss() }
                        .disabled(phase == .reading || phase == .proving)
                }
            }
            .earthBackground()
            .scrollContentBackground(.hidden)
            // The passport is in shot for the whole flow. Keeping the screen
            // awake is not a nicety here: the reader session dies with the
            // display, and it takes a few attempts to seat a passport well.
            .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            EarthAsset.logo?
                .resizable().scaledToFit()
                .frame(width: 72, height: 72)
            Text("Prove you are a unique human")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
            Text("Your passport's chip signs a proof on this device. The proof shows a government signed your document and that you have not registered before — it does not carry your name, your photo, or your document number, and nothing about the passport leaves the phone.")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textSecondary)

            EarthCard {
                StepRow(number: 1, title: "Type three fields", detail: "Document number, date of birth, date of expiry — from the two lines at the bottom of the photo page.")
                StepRow(number: 2, title: "Hold the passport to the phone", detail: "The chip is read over NFC.")
                StepRow(number: 3, title: "Prove and register", detail: "A few seconds of proving, then one transaction.")
            }

            if !PassportChip.isAvailable {
                unsupported("This device cannot read passport chips. Registration needs an iPhone 7 or later.")
            } else if !PassportProving.isAvailable {
                unsupported("This build has no prover, so a chip read would have nothing to prove with.")
            } else {
                EarthButton(title: "Start") { step = .scan }
            }
        }
    }

    private var mrzEntry: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            Text("These three fields unlock the chip. They are on the two machine-readable lines at the bottom of the photo page.")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textSecondary)

            // Prefilled from the scan when there was one, and editable either
            // way: OCR mangles this typeface often enough that a correction
            // has to be possible without starting over.
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

            EarthButton(title: "Read the chip") {
                step = .chip
                start()
            }
            .disabled(seed == nil || referrerInvalid)
            EarthButton(title: "Scan again", role: .secondary) { step = .scan }
        }
    }

    private var chip: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            EarthGlyph(systemName: phase == .proving ? "cpu" : "wave.3.right", size: 56)

            Text(chipTitle)
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)

            Text(chipDetail)
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textSecondary)

            if phase == .reading || phase == .proving {
                ProgressView().progressViewStyle(.circular)
            }

            if phase == .failed, let failure {
                EarthCard {
                    Text(failure)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textError)
                        .textSelection(.enabled)
                }
            }

            if phase == .idle || phase == .failed {
                EarthButton(title: phase == .failed ? "Try again" : "Hold the passport to the phone") {
                    start()
                }
                EarthButton(title: "Check the three fields", role: .secondary) { step = .mrz }
            }
        }
    }

    /// The proof is built and the passport is no longer needed. What is left is
    /// one transaction, which goes through `TxController` like every other —
    /// so the gas gate a new human needs is the same one every screen gets.
    private var ready: some View {
        VStack(alignment: .leading, spacing: theme.space.x16) {
            EarthGlyph(systemName: "checkmark.seal.fill", size: 56)
            Text("Proved")
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
            Text("The proof is on this device. Registering it is one transaction; the passport can go away now.")
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textSecondary)

            EarthCard {
                if let mrz = scan?.mrz {
                    EarthDetailRow(label: "Issued by", value: mrz.issuingState)
                    EarthDetailRow(label: "Nationality", value: mrz.nationality)
                }
                if let proof {
                    EarthDetailRow(label: "Circuit", value: proof.signatureAlgorithm)
                    if let nullifier = proof.nullifier {
                        // The one figure worth showing: it is what the chain
                        // stores, it is one per human, and it is not derived
                        // from anything that identifies the document.
                        EarthDetailRow(label: "Nullifier", value: String(nullifier.prefix(12)) + "…")
                    }
                }
            }

            if let failure {
                Text(failure)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textError)
                    .textSelection(.enabled)
            }

            EarthButton(title: "Register") { register() }
        }
    }

    private var chipTitle: String {
        switch phase {
        case .idle: "Hold the passport to the phone"
        case .reading: "Reading the chip"
        case .proving: "Building the proof"
        case .failed: "That did not work"
        }
    }

    private var chipDetail: String {
        switch phase {
        case .idle:
            "Rest the passport flat against the top of the phone, photo page down, and hold it still."
        case .reading:
            "Keep it still. This takes a few seconds."
        case .proving:
            "The passport can go away now. This runs on the phone and takes a few seconds."
        case .failed:
            "Nothing has been sent anywhere."
        }
    }

    private func unsupported(_ text: String) -> some View {
        EarthCard {
            Text(text)
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textSecondary)
        }
    }

    /// Read the chip, then prove — one task, because the two are a single wait
    /// from the user's side and nothing between them needs a decision.
    private func start() {
        failure = nil
        phase = .reading
        let key = key
        Task {
            do {
                let scan = try await PassportChip.read(key: key)
                self.scan = scan
                phase = .proving
                // Off the main actor: proving holds a core for seconds and
                // peaks a few hundred megabytes, and the screen has a spinner
                // on it that has to keep turning.
                let proof = try await Task.detached(priority: .userInitiated) {
                    try await PassportProving.prove(scan: scan)
                }.value
                self.proof = proof
                phase = .idle
                step = .ready
            } catch let error as PassportChip.Failure {
                // Cancelling is a decision, not a failure — the system sheet
                // has its own Cancel and pressing it should land back on a
                // screen offering another go, not on an error.
                failure = error == .cancelled ? nil : error.message
                phase = error == .cancelled ? .idle : .failed
            } catch {
                failure = describe(error)
                phase = .failed
            }
        }
    }

    private func register() {
        guard let scan, let proof else { return }
        let affiliate = referrer.trimmingCharacters(in: .whitespacesAndNewlines)
        let address = model.address

        // Built once, here, so a message the chain would reject for a bad
        // referrer fails on this screen rather than at broadcast — after the
        // confirmation, after the ad.
        let message: ProtoAny
        do {
            message = try PassportRegistration.message(
                scan: scan,
                proof: proof,
                creator: address,
                referrer: affiliate
            )
        } catch {
            failure = describe(error)
            return
        }

        tx.request(
            .init(
                action: "Register",
                rows: [
                    ("Nullifier", proof.nullifier.map { String($0.prefix(12)) + "…" } ?? "—"),
                    ("Circuit", proof.signatureAlgorithm),
                    ("Referrer", affiliate.isEmpty ? "None" : affiliate),
                    ("Fee", "\(Token.erth.format(Personhood.registerFeeUerth)) ERTH"),
                ],
                // Registration verifies a proof on chain, which costs far more
                // than a transfer. The default limit is nowhere near enough.
                gasLimit: Personhood.registerGasLimit
            )
        ) { _ in
            [message]
        }
        // Out of the way, so the confirmation card at the root is not drawn
        // behind this sheet. Same reason Send does it.
        dismiss()
    }

    private func describe(_ error: Error) -> String {
        if let failure = error as? PassportChip.Failure { return failure.message }
        if case PassportProving.Failure.unavailable = error {
            return "This build has no prover."
        }
        if let inputs = error as? PassportInputs.Error {
            return "This passport's chip data is not in a shape the circuit accepts (\(inputs))."
        }
        if let registration = error as? PassportRegistration.Error {
            switch registration {
            case .referrerIsSelf: return "You cannot refer yourself."
            case let .malformedReferrer(address): return "\(address) is not a valid earth address."
            case let .algorithmMismatch(expected, got):
                return "The proof came from \(got) but the certificate selects \(expected)."
            }
        }
        return model.describe(error)
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
                .font(EarthType.bodySmall)
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
