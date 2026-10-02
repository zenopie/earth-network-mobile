import EarthCore
import Foundation

/// The seam to the on-device privacy prover (membership and transfer).
///
/// Barretenberg lives in the app target, not here, for the reasons given on
/// `PassportProving`; the app installs a prover at launch. Without one a
/// private action fails with a clear message rather than broadcasting
/// anything (the simulator ships no Barretenberg slice for Intel).
public enum PrivacyProving {
    nonisolated(unsafe) private static var installed: PrivacyProver?

    /// Whether a passport registration may still be proved in this launch.
    /// The app's prover reads it to size Barretenberg's one-per-process SRS:
    /// while true it provisions for the largest passport circuit first.
    nonisolated(unsafe) public static var registrationMayFollow = true

    public static func install(_ prover: PrivacyProver) { installed = prover }

    public static var isAvailable: Bool { installed != nil }

    static var prover: PrivacyProver { installed ?? Unavailable() }

    struct Unavailable: PrivacyProver {
        struct Failure: LocalizedError {
            var errorDescription: String? { "This build has no zero-knowledge prover, so private actions cannot be proved." }
        }
        func proveTransfer(_ w: TransferWitness) async throws -> Data { throw Failure() }
        func proveMembership(_ w: MembershipWitness) async throws -> Data { throw Failure() }
    }
}

/// Builds the selected wallet's `PrivacyWallet` from its mnemonic (the privacy
/// keys are derived, never stored apart from it). Ports PrivacySession.kt.
enum PrivacySession {
    static func open(mnemonic: String, client: EarthClient) throws -> PrivacyWallet {
        let keys = try PrivacyKeys.fromMnemonic(mnemonic)
        // Named by a hash of the owner key, not the address: nothing on disk
        // pairs the transparent address with the shielded one.
        let id = String(PrivacyHash.h(PrivacyHash.tagOwner, keys.ownerPK).hex.prefix(16))
        let root = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return PrivacyWallet(
            keys: keys,
            store: PrivacyStore.open(root: root, walletID: id),
            indexer: HTTPPrivacyIndexer(),
            chain: RESTPrivateChain(rest: client.rest),
            reads: PrivacyQueries(rest: client.rest),
            prover: PrivacyProving.prover,
            chainID: Constants.chainID
        )
    }
}
