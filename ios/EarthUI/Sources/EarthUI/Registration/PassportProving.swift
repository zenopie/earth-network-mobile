import EarthCore
import Foundation

/// The seam to the on-device prover.
///
/// Barretenberg arrives as a ~140MB static framework and the compiled circuits
/// as ~14MB of JSON in the app bundle. Both belong to the app target, not to a
/// library that has to typecheck from the command line and whose domain half is
/// meant to keep running on a Mac — so `EarthUI` is handed a prover at launch
/// the same way it is handed an orientation setter, and the registration flow
/// asks for one rather than reaching for `ProverGate`.
///
/// It is also the seam the whole passport port was built against:
/// `PassportRegistration.prove` already takes an injected `Prover`, and
/// `corecheck` drives it with a stub. This just carries that as far as the app.
public enum PassportProving {
    nonisolated(unsafe) private static var prover: PassportRegistration.Prover?

    /// Installed by the app shell at launch. Without one, the chip step says so
    /// rather than reading a passport it cannot do anything with.
    public static func install(_ prover: @escaping PassportRegistration.Prover) {
        Self.prover = prover
    }

    public static var isAvailable: Bool { prover != nil }

    enum Failure: Error {
        case unavailable
    }

    /// Scan in, proof out. The witness is built inside
    /// `PassportRegistration.prove`, which also checks that the proof came back
    /// from the circuit the certificate selected.
    ///
    /// - Parameter address: the account that will sign MsgRegister. The chain
    ///   takes it as a public input, so a proof built for one account cannot be
    ///   broadcast from another.
    static func prove(
        scan: PassportRegistration.Scan,
        address: String
    ) async throws -> PassportRegistration.Proof {
        guard let prover else { throw Failure.unavailable }
        return try await PassportRegistration.prove(scan: scan, address: address, using: prover)
    }
}
