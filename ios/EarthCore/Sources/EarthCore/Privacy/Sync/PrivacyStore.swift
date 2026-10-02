import Foundation

/// This wallet's registration as the identity tree holds it. Everything here
/// is needed to prove membership; nothing is sent anywhere. The leaf is
/// H(TAG_LEAF, idc, dsc_key, country, activated_at).
public struct IdentityRecord: Codable, Equatable, Sendable {
    public let leafIndex: UInt64
    public let dscKey: Fr
    public let country: Fr
    public let activatedAt: UInt64
    /// The passport nullifier: public in the registration, the switch/expiry key.
    public let passportNullifier: String

    public init(leafIndex: UInt64, dscKey: Fr, country: Fr, activatedAt: UInt64, passportNullifier: String) {
        self.leafIndex = leafIndex; self.dscKey = dscKey; self.country = country
        self.activatedAt = activatedAt; self.passportNullifier = passportNullifier
    }
}

/// What the wallet keeps between syncs: cursors into each indexer stream, its
/// own notes, its registration, and the automations' bookkeeping. Small; the
/// trees live beside it in per-level files. Ports PrivacyState in
/// `privacy/sync/PrivacyStore.kt`.
public struct PrivacyState: Codable, Sendable {
    public var chainID: String?
    public var notesNext: UInt64 = 0
    public var notesHeight: UInt64 = 0
    public var nullifiersNext: UInt64 = 0
    public var identityNext: UInt64 = 0
    public var zeroedNext: UInt64 = 0
    public var notes: [OwnedNote] = []
    public var identity: IdentityRecord?
    /// UTC days a claim was broadcast for (so the automation does not repeat one).
    public var claimedDays: Set<UInt64> = []
    /// When the caretaker split was last cast (unix seconds), and the split (option -> percent).
    public var caretakerCastAt: Int64 = 0
    public var caretakerSplit: [UInt64: UInt64] = [:]
    /// The transparent address bound as this person's referrer ("" for none), and when (unix seconds).
    public var referrerAddress: String = ""
    public var referrerBoundAt: Int64 = 0
    /// Unbond denoms whose claim the chain refused as not yet matured, to when the automation next tries.
    public var unbondRetryAt: [String: Int64] = [:]
    /// Next unused position-key index.
    public var nextPositionKey: UInt32 = 0
    /// Next unused self-mint counter (PrivacyKeys.mintSecrets).
    public var nextMintCounter: UInt32 = 0
    /// Every denom seen in a public amount: resolves the asset ids ciphertexts carry.
    public var denoms: Set<String> = []

    public init() {}
}

/// The wallet's privacy data on disk (or in memory, for tests): `state` and
/// the two trees. One directory per wallet, named by a hash of its owner key
/// so wallets in the same app never share notes.
///
/// Not thread-safe on its own: `PrivacyWallet` holds it behind its lock.
public final class PrivacyStore {
    private static let stateFile = "state.json"
    private let dir: URL?
    public private(set) var state: PrivacyState
    public let noteTree: MerkleTree
    public let identityTree: MerkleTree

    private init(dir: URL?) {
        self.dir = dir
        let noteNodes: NodeStore = dir.map { FileNodeStore(directory: $0.appendingPathComponent("notes")) } ?? MemNodeStore()
        let identityNodes: NodeStore = dir.map { FileNodeStore(directory: $0.appendingPathComponent("identity")) } ?? MemNodeStore()
        let loaded = dir.flatMap { try? Data(contentsOf: $0.appendingPathComponent(Self.stateFile)) }
            .flatMap { try? JSONDecoder().decode(PrivacyState.self, from: $0) }
        state = loaded ?? PrivacyState()
        noteTree = MerkleTree(store: noteNodes, size: state.notesNext)
        identityTree = MerkleTree(store: identityNodes, size: state.identityNext)
    }

    public static func memory() -> PrivacyStore { PrivacyStore(dir: nil) }

    public static func open(root: URL, walletID: String) -> PrivacyStore {
        let d = root.appendingPathComponent("privacy").appendingPathComponent(walletID)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return PrivacyStore(dir: d)
    }

    public func mutate<T>(_ body: (inout PrivacyState) throws -> T) rethrows -> T { try body(&state) }

    /// Persists state after the trees, so a crash between the two leaves
    /// state behind (and resyncs) rather than ahead.
    public func save() {
        noteTree.flush(); identityTree.flush()
        guard let dir, let data = try? JSONEncoder().encode(state) else { return }
        try? data.write(to: dir.appendingPathComponent(Self.stateFile), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Forgets the synced data, keeping the key counters, the registration's
    /// leaf and what the wallet itself cast (claims, caretaker split,
    /// referrer): a fresh chain, or an inconsistent sync.
    public func reset(chainID: String?) {
        noteTree.clear()
        identityTree.clear()
        let old = state
        var s = PrivacyState()
        s.chainID = chainID
        s.nextPositionKey = old.nextPositionKey
        s.nextMintCounter = old.nextMintCounter
        if old.chainID == chainID {
            // The leaf index cannot be found again without the registration tx.
            s.identity = old.identity
            s.claimedDays = old.claimedDays
            s.caretakerCastAt = old.caretakerCastAt; s.caretakerSplit = old.caretakerSplit
            s.referrerAddress = old.referrerAddress; s.referrerBoundAt = old.referrerBoundAt
        }
        state = s
        save()
    }
}

