import EarthCore
import EarthUI
import Foundation
import ProverGate

/// Barretenberg on the phone. Installed into `EarthUI`'s `PassportProving`
/// seam at launch.
///
/// Here rather than in `EarthUI` because of what it drags in: a ~140MB static
/// framework and the seven compiled circuits in the bundle. `EarthUI` has to
/// stay typecheckable from the command line and `EarthCore` has to stay
/// runnable on a Mac, and neither survives that. The app bundle is the one
/// place that can carry it.
///
/// Ports `passport/PassportProver.kt`, whose shape this follows exactly:
/// pick the circuit the certificate selects, load it, prove, hand back the
/// chain's (proof, public signals) form.
enum DeviceProver {

    static func install() {
        PassportProving.install(prove, ready: { passportFits })
        PrivacyProving.install(PrivacyDeviceProver())
        // The passport SRS, fetched once at launch rather than when a proof
        // needs it (the privacy SRS is bundled).
        Task.detached(priority: .background) { await PassportSRS.prefetch() }
    }

    enum Failure: Error, LocalizedError {
        case circuitMissing(String)
        case srsTooSmall

        var errorDescription: String? {
            switch self {
            case let .circuitMissing(name):
                // Reachable only if a passport selects a circuit the bundle
                // does not carry, which means the folder reference and
                // `Certificate.swift`'s table have drifted apart.
                return "This build has no \(name) circuit, so this passport's signature algorithm cannot be proved."
            case .srsTooSmall:
                return PassportProving.relaunchToRegister
            }
        }
    }

    /// The circuits are the Android app's own asset folder, referenced into
    /// this target rather than copied. One set of files, so a recompile cannot
    /// leave the two platforms proving against different circuits — and they
    /// carry a `noir_version` that has to match the prover's Noir, which makes
    /// a silent divergence expensive.
    private static let circuitDirectory = "circuits"

    /// Whether this process's SRS can still hold a passport circuit: nothing
    /// has sized it yet, or the privacy prover reserved it for the largest
    /// passport circuit, or a passport proof sized it.
    static var passportFits: Bool {
        !SRS.isProvisioned || PrivacyDeviceProver.reservedForPassport || passportSized
    }

    nonisolated(unsafe) private static var passportSized = false

    /// barretenberg sizes its SRS once per process: if a private action
    /// proved earlier in this launch sized it for itself, the passport circuit
    /// cannot be set up now, and the honest instruction is to relaunch.
    private static func loadOrExplain(_ manifest: Data, provisioned: Bool) throws -> LeanPoaProver.LoadedCircuit {
        do {
            return try LeanPoaProver.loadCircuit(manifest: manifest, size: nil, srsPath: PassportSRS.path)
        } catch where provisioned && String(describing: error).contains("SRS") {
            throw Failure.srsTooSmall
        }
    }

    private static func prove(
        _ inputs: PassportInputs.Inputs
    ) async throws -> PassportRegistration.Proof {
        guard let url = Bundle.main.url(
            forResource: inputs.algorithm,
            withExtension: "json",
            subdirectory: circuitDirectory
        ) else {
            throw Failure.circuitMissing(inputs.algorithm)
        }

        let manifest = try Data(contentsOf: url)
        // `size: nil` — the SRS is sized from this circuit's own gate count.
        // Seven circuits ship here and they are not the same size, and
        // barretenberg honours only the first SRS initialization of a process,
        // so a hardcoded hint that turns out to be too small for the circuit a
        // passport selects cannot be corrected afterwards. Reading it off the
        // bytecode cannot be wrong.
        let provisioned = SRS.isProvisioned
        let circuit = try loadOrExplain(manifest, provisioned: provisioned)
        if !provisioned { passportSized = true }
        let result = try LeanPoaProver.prove(circuit: circuit, inputs: inputs.witness)

        return PassportRegistration.Proof(
            proof: result.proof,
            publicSignals: result.publicSignals,
            // The algorithm the *inputs* selected, not one the prover decides.
            // `PassportRegistration.prove` checks these agree — the chain picks
            // its verifying key from this string, so a mismatch would fail on
            // chain with nothing in the app to explain it.
            signatureAlgorithm: inputs.algorithm
        )
    }
}

/// The privacy circuits (membership, action, stake, vote) on the phone, installed into
/// EarthUI's `PrivacyProving` seam. Their compiled circuits are in the same
/// bundled folder as the passport's (the Android assets, referenced), so one
/// recompile cannot leave the platforms apart.
struct PrivacyDeviceProver: PrivacyProver {
    /// The largest passport circuit (brainpool512, ~425k gates). Barretenberg
    /// sizes its SRS once per process, so a private action proved before a
    /// registration that may still follow in this launch provisions for it.
    private static let largestPassportCircuit = "lean_poa_brainpool512"

    /// Set once the SRS reservation is the largest passport circuit's.
    nonisolated(unsafe) static var reservedForPassport = false

    private static func manifest(_ name: String) -> Data? {
        Bundle.main.url(forResource: name, withExtension: "json", subdirectory: "circuits").flatMap { try? Data(contentsOf: $0) }
    }

    /// The bundled privacy SRS: the Android asset folder, referenced.
    static let privacySRS = Bundle.main.path(forResource: "bn254_g1_32769", ofType: "dat", inDirectory: "srs")

    private static let prover: PrivacyCircuitProver = {
        var manifests: [PrivacyCircuitProver.Kind: Data] = [:]
        for k in PrivacyCircuitProver.Kind.allCases { manifests[k] = manifest(k.rawValue) }
        return PrivacyCircuitProver(manifests: manifests, privacySRS: privacySRS) {
            // Asked only while nothing is provisioned, just before reserving
            // with what it returns. The passport size only from the local
            // file: a private proof never fetches the SRS. Without
            // it, a registration later in this launch asks for a relaunch.
            guard PrivacyProving.registrationMayFollow, let path = PassportSRS.path, let m = manifest(largestPassportCircuit) else {
                reservedForPassport = false
                return nil
            }
            reservedForPassport = true
            return (m, path)
        }
    }()

    func proveAction(_ w: ActionWitness) async throws -> Data {
        try w.check()
        return try await Task.detached(priority: .userInitiated) {
            try Self.prover.prove(.action, inputs: w.noirInputs(), expected: w.publicInputs().map(\.hex))
        }.value
    }

    func proveStake(_ w: StakeWitness) async throws -> Data {
        try w.check()
        return try await Task.detached(priority: .userInitiated) {
            try Self.prover.prove(.stake, inputs: w.noirInputs(), expected: w.publicInputs().map(\.hex))
        }.value
    }

    func proveMembership(_ w: MembershipWitness) async throws -> Data {
        try w.check()
        return try await Task.detached(priority: .userInitiated) {
            try Self.prover.prove(.membership, inputs: w.noirInputs(), expected: w.publicInputs().map(\.hex))
        }.value
    }

    func proveVote(_ w: VoteWitness) async throws -> Data {
        try w.check()
        return try await Task.detached(priority: .userInitiated) {
            try Self.prover.prove(.vote, inputs: w.noirInputs(), expected: w.publicInputs().map(\.hex))
        }.value
    }
}
