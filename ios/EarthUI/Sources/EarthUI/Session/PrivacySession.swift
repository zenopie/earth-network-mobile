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
        func proveMove(_ w: MoveWitness) async throws -> Data { throw Failure() }
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
    /// forgetting the wallets forgets what they held privately too.
    static func forgetAll() throws {
        lock.lock(); wallets = [:]; live = [:]; lock.unlock()
        try PrivacyStore.delete(root: try dataRoot())
    }

    /// One `PrivacyWallet` (and its one store) per wallet per process, by
    /// store id. `wallets` holds them while the wallet is unlocked and is
    /// emptied at lock, so a locked app keeps no decrypted state or key
    /// copy reachable from here. A cast still finishing in a task the lock
    /// suspended keeps its own wallet; `live` (weak) finds that one again,
    /// so the next unlock reuses it and the wallet's lock still orders its
    /// writes against the new session's.
    nonisolated(unsafe) private static var wallets: [String: PrivacyWallet] = [:]
    nonisolated(unsafe) private static var live: [String: WeakWallet] = [:]
    private static let lock = NSLock()

    private final class WeakWallet { weak var wallet: PrivacyWallet?; init(_ w: PrivacyWallet) { wallet = w } }

    /// The install's data key, which seals every wallet's private store:
    /// set from the opened vault at unlock, dropped at lock (AppModel).
    nonisolated(unsafe) private static var openKey: Data?

    /// At unlock (a key): also seals, off the main actor, every wallet's
    /// plaintext store from before sealing (PrivacyStore.sealLegacy), not
    /// only the ones opened again. At lock (nil): lets go of every wallet
    /// and store (see `wallets`).
    static func setDataKey(_ key: Data?) {
        lock.lock()
        openKey = key
        if key == nil {
            wallets = [:]
            live = live.filter { $0.value.wallet != nil }
        }
        lock.unlock()
        guard let key else { PrivacyStore.lockAll(); return }
        Task.detached(priority: .utility) {
            guard let root = try? PrivacySession.dataRoot() else { return }
            PrivacyStore.sealLegacy(root: root, key: key)
        }
    }

    struct Locked: LocalizedError {
        var errorDescription: String? { "The wallet is locked." }
    }

    static func dataKey() throws -> Data {
        lock.lock(); defer { lock.unlock() }
        guard let openKey else { throw Locked() }
        return openKey
    }

    /// The app's one handle directory (see HandleDirectory): the privacy
    /// backend's whole-directory stream first, the chain's own pages to fall
    /// back on and to check an entry against before money moves on it.
    static let handles: HandleDirectory = {
        let queries = PrivacyQueries(rest: EarthRest())
        let indexer = HTTPPrivacyIndexer()
        return HandleDirectory(fetchChainPage: { start, limit in try await queries.handlesPage(start: start, limit: limit) },
                               fetchStream: { from, limit in try await indexer.handles(fromIndex: from, limit: limit) },
                               // An http own node: the node's pages alone never resolve a payment.
                               requireBackend: { NodeSettings.isCleartext(NodeSettings.current.lcd) })
    }()

    /// A wallet's store directory: named by a hash of the owner key, not the
    /// address, so nothing on disk pairs the transparent address with the shielded one.
    static func storeID(_ keys: PrivacyKeys) -> String { String(PrivacyHash.h(PrivacyHash.tagOwner, keys.ownerPK).hex.prefix(16)) }

    /// Writes a switch's moves into another wallet's private store: before
    /// the broadcast, as pending; undone only on a definite
    /// refusal. Addressed by store id, so a retry needs no recovery phrase.
    struct Recorder: PrivacyWallet.MoveRecorder {
        let targetID: String
        func record(_ move: PendingMove) throws {
            try PrivacyWallet.recordIncoming(try PrivacyStore.shared(root: try PrivacySession.dataRoot(), walletID: targetID, key: try PrivacySession.dataKey()), move,
                                             now: Int64(Date().timeIntervalSince1970))
        }
        func rollback(_ move: PendingMove) throws {
            try PrivacyWallet.rollbackIncoming(try PrivacyStore.shared(root: try PrivacySession.dataRoot(), walletID: targetID, key: try PrivacySession.dataKey()), move,
                                               now: Int64(Date().timeIntervalSince1970))
        }
        func refusal(_ move: PendingMove) -> String? {
            guard let st = (try? PrivacyStore.shared(root: try PrivacySession.dataRoot(), walletID: targetID, key: try PrivacySession.dataKey()))?.state else { return nil }
            return PrivacyWallet.targetRefusal(st, kind: move.kind, now: Int64(Date().timeIntervalSince1970))
        }
    }

    /// What a switch target already holds: a registration, a
    /// handle; and why it cannot take this identity's handle or caretaker
    /// vote.
    static func targetInfo(_ keys: PrivacyKeys) -> TargetInfo {
        let id = storeID(keys)
        let st = (try? PrivacyStore.shared(root: try dataRoot(), walletID: id, key: try dataKey()))?.state
        let t = Int64(Date().timeIntervalSince1970)
        // A registration it sent that can still land counts: one that failed may be replayed.
        let registered = st.map { $0.identity != nil || $0.pendingRegistration != nil || $0.registrationKeepUntil > t } ?? false
        return TargetInfo(storeID: id, registered: registered, handle: st?.handle ?? "",
                          handleRefusal: st.flatMap { PrivacyWallet.targetRefusal($0, kind: PendingMove.handleKind, now: t) },
                          voteRefusal: st.flatMap { PrivacyWallet.targetRefusal($0, kind: PendingMove.caretakerKind, now: t) })
    }

    struct TargetInfo {
        let storeID: String
        let registered: Bool
        let handle: String
        let handleRefusal: String?
        let voteRefusal: String?
    }

    static func open(mnemonic: String, client: EarthClient) throws -> PrivacyWallet {
        let keys = try PrivacyKeys.fromMnemonic(mnemonic)
        let id = storeID(keys)
        let key = try dataKey()
        lock.lock(); defer { lock.unlock() }
        if let w = wallets[id] { return w }
        if let w = live[id]?.wallet { wallets[id] = w; return w }
        let w = PrivacyWallet(
            keys: keys,
            // An unreadable store is an error the user sees, never an empty wallet.
            store: try PrivacyStore.shared(root: try dataRoot(), walletID: id, key: key),
            indexer: HTTPPrivacyIndexer(),
            chain: RESTPrivateChain(rest: client.rest),
            reads: PrivacyQueries(rest: client.rest),
            prover: PrivacyProving.prover,
            chainID: Constants.chainID,
            // Every root the indexer serves is checked against the chain's own.
            roots: LCDChainRoots(rest: client.rest)
        )
        wallets[id] = w
        live[id] = WeakWallet(w)
        return w
    }
}
