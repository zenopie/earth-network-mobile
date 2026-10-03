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

/// A registration broadcast and committed whose identity leaf the wallet has
/// not resolved yet (C2): everything needed to rebuild the identity record,
/// persisted before any sync so a lagging indexer cannot lose it. Each sync
/// retries until the local identity tree holds `leafIndex`.
public struct PendingRegistration: Codable, Equatable, Sendable {
    public let txHash: String
    public let leafIndex: UInt64
    public let dscKey: Fr
    public let passportNullifier: String
    public let publicSignals: [String]
    /// The registration block's time: the leaf's activated_at.
    public let activatedAt: UInt64
    /// ISO alpha-2 guess at the verifying CSCA's country ("" for none).
    public let countryHint: String
    /// Why the last attempt to resolve it failed, for the UI (nil: waiting for the indexer).
    public var failure: String?

    public init(txHash: String, leafIndex: UInt64, dscKey: Fr, passportNullifier: String, publicSignals: [String], activatedAt: UInt64,
                countryHint: String, failure: String? = nil) {
        self.txHash = txHash; self.leafIndex = leafIndex; self.dscKey = dscKey; self.passportNullifier = passportNullifier
        self.publicSignals = publicSignals; self.activatedAt = activatedAt; self.countryHint = countryHint; self.failure = failure
    }
}

/// A registration record note found by sync (PRIVACY_FORMATS.md 3a): what a
/// wallet restored from the mnemonic finds its identity leaf by. `height` is
/// the registration's block.
public struct RegRecord: Codable, Equatable, Sendable {
    public let height: UInt64
    public let position: UInt64
    public let dscKey: Fr
    public let country: String
    public let builtAt: UInt64

    public init(height: UInt64, position: UInt64, dscKey: Fr, country: String, builtAt: UInt64) {
        self.height = height; self.position = position; self.dscKey = dscKey; self.country = country; self.builtAt = builtAt
    }
}

/// What the wallet keeps between syncs: cursors into each indexer stream, its
/// own notes, its registration, and the automations' bookkeeping. Small; the
/// trees live beside it in per-level files. Ports PrivacyState in
/// `privacy/sync/PrivacyStore.kt`.
public struct PrivacyState: Codable, Sendable {
    public var chainID: String?
    /// The indexer's genesis key (first block hash prefix) the synced data is from.
    public var genesis: String?
    public var notesNext: UInt64 = 0
    public var notesHeight: UInt64 = 0
    public var nullifiersNext: UInt64 = 0
    public var identityNext: UInt64 = 0
    public var zeroedNext: UInt64 = 0
    public var notes: [OwnedNote] = []
    public var identity: IdentityRecord?
    /// A committed registration not yet matched to its leaf (C2).
    public var pendingRegistration: PendingRegistration?
    /// Registration record notes found (restore, L8).
    public var regRecords: [RegRecord] = []
    /// Whether the last sync's roots matched the chain's own (C3), and why not.
    public var rootsVerified: Bool = false
    public var rootsError: String?
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
    /// Next unused Groundworks owner-tag counter (PrivacyKeys.otagSalt).
    public var nextOtagCounter: UInt32 = 0
    /// The stake tree's stream cursors and this wallet's stake notes.
    public var stakeNext: UInt64 = 0
    public var stakeHeight: UInt64 = 0
    public var stakeNullifiersNext: UInt64 = 0
    public var stakeNotes: [OwnedStakeNote] = []
    /// Every denom seen in a public amount: resolves the asset ids ciphertexts carry.
    public var denoms: Set<String> = []

    public init() {}

    enum CodingKeys: String, CodingKey {
        case chainID, genesis, notesNext, notesHeight, nullifiersNext, identityNext, zeroedNext, notes, identity, pendingRegistration,
             regRecords, rootsVerified, rootsError, claimedDays, caretakerCastAt, caretakerSplit, referrerAddress, referrerBoundAt,
             unbondRetryAt, nextOtagCounter, stakeNext, stakeHeight, stakeNullifiersNext, stakeNotes, denoms
    }

    /// Tolerates a state file from before the stake tree (missing keys keep their defaults).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func v<T: Decodable>(_ k: CodingKeys, _ d: T) throws -> T { try c.decodeIfPresent(T.self, forKey: k) ?? d }
        chainID = try c.decodeIfPresent(String.self, forKey: .chainID)
        genesis = try c.decodeIfPresent(String.self, forKey: .genesis)
        pendingRegistration = try c.decodeIfPresent(PendingRegistration.self, forKey: .pendingRegistration)
        regRecords = try v(.regRecords, []); rootsVerified = try v(.rootsVerified, false)
        rootsError = try c.decodeIfPresent(String.self, forKey: .rootsError)
        notesNext = try v(.notesNext, 0); notesHeight = try v(.notesHeight, 0); nullifiersNext = try v(.nullifiersNext, 0)
        identityNext = try v(.identityNext, 0); zeroedNext = try v(.zeroedNext, 0); notes = try v(.notes, [])
        identity = try c.decodeIfPresent(IdentityRecord.self, forKey: .identity)
        claimedDays = try v(.claimedDays, []); caretakerCastAt = try v(.caretakerCastAt, 0); caretakerSplit = try v(.caretakerSplit, [:])
        referrerAddress = try v(.referrerAddress, ""); referrerBoundAt = try v(.referrerBoundAt, 0); unbondRetryAt = try v(.unbondRetryAt, [:])
        nextOtagCounter = try v(.nextOtagCounter, 0); stakeNext = try v(.stakeNext, 0); stakeHeight = try v(.stakeHeight, 0)
        stakeNullifiersNext = try v(.stakeNullifiersNext, 0); stakeNotes = try v(.stakeNotes, []); denoms = try v(.denoms, [])
    }
}

/// The wallet's privacy data on disk (or in memory, for tests): `state` and
/// the trees. One directory per wallet, named by a hash of its owner key so
/// wallets in the same app never share notes. Three trees: the pool's notes,
/// the identity leaves and the stake notes.
///
/// Not thread-safe on its own: `PrivacyWallet` holds it behind its lock.
public final class PrivacyStore {
    private static let stateFile = "state.json"
    private let dir: URL?
    public private(set) var state: PrivacyState
    public let noteTree: MerkleTree
    public let identityTree: MerkleTree
    public let stakeTree: MerkleTree

    private init(dir: URL?) {
        self.dir = dir
        let noteNodes: NodeStore = dir.map { FileNodeStore(directory: $0.appendingPathComponent("notes")) } ?? MemNodeStore()
        let identityNodes: NodeStore = dir.map { FileNodeStore(directory: $0.appendingPathComponent("identity")) } ?? MemNodeStore()
        let stakeNodes: NodeStore = dir.map { FileNodeStore(directory: $0.appendingPathComponent("stake")) } ?? MemNodeStore()
        let loaded = dir.flatMap { try? Data(contentsOf: $0.appendingPathComponent(Self.stateFile)) }
            .flatMap { try? JSONDecoder().decode(PrivacyState.self, from: $0) }
        state = loaded ?? PrivacyState()
        noteTree = MerkleTree(store: noteNodes, size: state.notesNext)
        identityTree = MerkleTree(store: identityNodes, size: state.identityNext)
        stakeTree = MerkleTree(store: stakeNodes, size: state.stakeNext)
    }

    public static func memory() -> PrivacyStore { PrivacyStore(dir: nil) }

    public static func open(root: URL, walletID: String) -> PrivacyStore {
        let top = root.appendingPathComponent("privacy")
        let d = top.appendingPathComponent(walletID)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        // C4: the notes, trees and registration are derivable from the
        // mnemonic and the chain; a device backup (iCloud, iTunes/Finder)
        // would only carry this wallet's private history off the device.
        excludeFromBackup(top)
        excludeFromBackup(d)
        return PrivacyStore(dir: d)
    }

    /// Marks `url` (and so everything under it) as excluded from device backups.
    static func excludeFromBackup(_ url: URL) {
        var u = url
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }

    public func mutate<T>(_ body: (inout PrivacyState) throws -> T) rethrows -> T { try body(&state) }

    /// Persists state after the trees, so a crash between the two leaves
    /// state behind (and resyncs) rather than ahead.
    public func save() {
        noteTree.flush(); identityTree.flush(); stakeTree.flush()
        guard let dir, let data = try? JSONEncoder().encode(state) else { return }
        try? data.write(to: dir.appendingPathComponent(Self.stateFile), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Forgets the synced data. On the same chain (an inconsistent sync) it
    /// keeps the owner-tag counter, the registration (its leaf, or the one
    /// pending) and what the wallet itself cast (claims, caretaker split,
    /// referrer); a different chain or genesis (a relaunch under the same
    /// chain id) keeps only the owner-tag counter.
    public func reset(chainID: String?) { reset(chainID: chainID, genesis: state.genesis) }

    public func reset(chainID: String?, genesis: String?) {
        noteTree.clear()
        identityTree.clear()
        stakeTree.clear()
        let old = state
        var s = PrivacyState()
        s.chainID = chainID
        s.genesis = genesis
        s.nextOtagCounter = old.nextOtagCounter
        if old.chainID == chainID && old.genesis == genesis {
            s.identity = old.identity
            s.pendingRegistration = old.pendingRegistration
            s.claimedDays = old.claimedDays
            s.caretakerCastAt = old.caretakerCastAt; s.caretakerSplit = old.caretakerSplit
            s.referrerAddress = old.referrerAddress; s.referrerBoundAt = old.referrerBoundAt
        }
        state = s
        save()
    }
}

