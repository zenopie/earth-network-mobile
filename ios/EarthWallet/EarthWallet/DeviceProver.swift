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
/// Ports `wallet/passport/PassportProver.kt`, whose shape this follows exactly:
/// pick the circuit the certificate selects, load it, prove, hand back the
/// chain's (proof, public signals) form.
enum DeviceProver {

    static func install() {
        PassportProving.install(prove)
    }

    enum Failure: Error, LocalizedError {
        case circuitMissing(String)

        var errorDescription: String? {
            switch self {
            case let .circuitMissing(name):
                // Reachable only if a passport selects a circuit the bundle
                // does not carry, which means the folder reference and
                // `Certificate.swift`'s table have drifted apart.
                return "This build has no \(name) circuit, so this passport's signature algorithm cannot be proved."
            }
        }
    }

    /// The circuits are the Android app's own asset folder, referenced into
    /// this target rather than copied. One set of files, so a recompile cannot
    /// leave the two platforms proving against different circuits — and they
    /// carry a `noir_version` that has to match the prover's Noir, which makes
    /// a silent divergence expensive.
    private static let circuitDirectory = "circuits"

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
        let circuit = try LeanPoaProver.loadCircuit(manifest: manifest, size: nil)
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
