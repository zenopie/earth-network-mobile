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
    nonisolated(unsafe) private static var ready: () -> Bool = { true }

    /// Installed by the app shell at launch. Without one, the chip step says so
    /// rather than reading a passport it cannot do anything with.
    ///
    /// `ready` says whether a passport can still be proved in this launch:
    /// Barretenberg sizes its SRS once per process, and a private action
    /// proved first may have sized it below every passport circuit.
    public static func install(_ prover: @escaping PassportRegistration.Prover, ready: @escaping () -> Bool = { true }) {
        Self.prover = prover
        Self.ready = ready
    }

    public static var isAvailable: Bool { prover != nil }

    /// False when this launch's prover can no longer fit a passport circuit;
    /// a relaunch fixes it. Asked before the chip is read, so nobody holds a
    /// passport to the phone for a proof that cannot be made.
    public static var canProveThisLaunch: Bool { ready() }

    /// What to tell someone when `canProveThisLaunch` is false.
    public static let relaunchToRegister =
        "Earth Wallet needs a fresh start to register. A private action already ran since it opened, and the prover sized itself for that and cannot grow for a passport now. Close the app completely (swipe it away in the app switcher), open it again, and register before doing anything else."

    enum Failure: Error {
        case unavailable
    }

    /// Scan in, proof out. The witness is built inside
    /// `PassportRegistration.prove`, which also checks that the proof came back
    /// from the circuit the certificate selected.
    ///
    /// - Parameter binding: the registration binding from
    ///   `PrivacyWallet.prepareRegistration` — the proof's `address` input, so
    ///   it registers only the identity commitment and notes it names.
    /// - Parameter idSecret: that identity commitment's secret, the proof's
    ///   `id_secret`: the proof outputs its idc, which the chain checks.
    static func prove(
        scan: PassportRegistration.Scan,
        binding: Fr,
        idSecret: Fr
    ) async throws -> PassportRegistration.Proof {
        guard let prover else { throw Failure.unavailable }
        return try await PassportRegistration.prove(scan: scan, binding: binding, idSecret: idSecret, using: prover)
    }
}
