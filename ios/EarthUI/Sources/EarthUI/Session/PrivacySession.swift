import EarthCore
import Foundation

/// The seam to the on-device privacy prover (membership, action and stake).
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
        func proveAction(_ w: ActionWitness) async throws -> Data { throw Failure() }
        func proveStake(_ w: StakeWitness) async throws -> Data { throw Failure() }
        func proveMembership(_ w: MembershipWitness) async throws -> Data { throw Failure() }
        func proveVote(_ w: VoteWitness) async throws -> Data { throw Failure() }
    }
}

/// Builds the selected wallet's `PrivacyWallet` from its mnemonic (the privacy
/// keys are derived, never stored apart from it). Ports PrivacySession.kt.
enum PrivacySession {
    /// Where every wallet's `privacy/<id>/` directory lives.
    static func dataRoot() throws -> URL {
        try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    }

    /// Deletes every wallet's private data (notes, identity, records, trees):
    /// forgetting the wallets forgets what they held privately too (audit 3).
    static func forgetAll() throws {
        try PrivacyStore.delete(root: try dataRoot())
    }

    /// The app's one handle directory (see HandleDirectory): the privacy
    /// backend's whole-directory stream first, the chain's own pages to fall
    /// back on and to check an entry against before money moves on it.
    static let handles: HandleDirectory = {
        let queries = PrivacyQueries(rest: EarthRest())
        let indexer = HTTPPrivacyIndexer()
        return HandleDirectory(fetchChainPage: { start, limit in try await queries.handlesPage(start: start, limit: limit) },
                               fetchStream: { from, limit in try await indexer.handles(fromIndex: from, limit: limit) })
    }()

    static func open(mnemonic: String, client: EarthClient) throws -> PrivacyWallet {
        let keys = try PrivacyKeys.fromMnemonic(mnemonic)
        // Named by a hash of the owner key, not the address: nothing on disk
        // pairs the transparent address with the shielded one.
        let id = String(PrivacyHash.h(PrivacyHash.tagOwner, keys.ownerPK).hex.prefix(16))
        return PrivacyWallet(
            keys: keys,
            // An unreadable store is an error the user sees, never an empty wallet (audit 3).
            store: try PrivacyStore.open(root: try dataRoot(), walletID: id),
            indexer: HTTPPrivacyIndexer(),
            chain: RESTPrivateChain(rest: client.rest),
            reads: PrivacyQueries(rest: client.rest),
            prover: PrivacyProving.prover,
            chainID: Constants.chainID,
            // Every root the indexer serves is checked against the chain's own (C3).
            roots: LCDChainRoots(rest: client.rest)
        )
    }
}
