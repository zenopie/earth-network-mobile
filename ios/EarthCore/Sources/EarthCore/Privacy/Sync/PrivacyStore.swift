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
    /// Next unused Groundworks owner-tag counter (PrivacyKeys.otagSalt).
    public var nextOtagCounter: UInt32 = 0
    /// Next unused self-mint counter (PrivacyKeys.mintSecrets).
    public var nextMintCounter: UInt32 = 0
    /// Next unused stake self-mint counter (PrivacyKeys.stakeMintSecrets).
    public var nextStakeMintCounter: UInt32 = 0
    /// The stake tree's stream cursors and this wallet's stake notes.
    public var stakeNext: UInt64 = 0
    public var stakeHeight: UInt64 = 0
    public var stakeNullifiersNext: UInt64 = 0
    public var stakeNotes: [OwnedStakeNote] = []
    /// Every denom seen in a public amount: resolves the asset ids ciphertexts carry.
    public var denoms: Set<String> = []

    public init() {}

    enum CodingKeys: String, CodingKey {
        case chainID, notesNext, notesHeight, nullifiersNext, identityNext, zeroedNext, notes, identity, claimedDays, caretakerCastAt,
             caretakerSplit, referrerAddress, referrerBoundAt, unbondRetryAt, nextOtagCounter, nextMintCounter, nextStakeMintCounter,
             stakeNext, stakeHeight, stakeNullifiersNext, stakeNotes, denoms
    }

    /// Tolerates a state file from before the stake tree (missing keys keep their defaults).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func v<T: Decodable>(_ k: CodingKeys, _ d: T) throws -> T { try c.decodeIfPresent(T.self, forKey: k) ?? d }
        chainID = try c.decodeIfPresent(String.self, forKey: .chainID)
        notesNext = try v(.notesNext, 0); notesHeight = try v(.notesHeight, 0); nullifiersNext = try v(.nullifiersNext, 0)
        identityNext = try v(.identityNext, 0); zeroedNext = try v(.zeroedNext, 0); notes = try v(.notes, [])
        identity = try c.decodeIfPresent(IdentityRecord.self, forKey: .identity)
        claimedDays = try v(.claimedDays, []); caretakerCastAt = try v(.caretakerCastAt, 0); caretakerSplit = try v(.caretakerSplit, [:])
        referrerAddress = try v(.referrerAddress, ""); referrerBoundAt = try v(.referrerBoundAt, 0); unbondRetryAt = try v(.unbondRetryAt, [:])
        nextOtagCounter = try v(.nextOtagCounter, 0); nextMintCounter = try v(.nextMintCounter, 0)
        nextStakeMintCounter = try v(.nextStakeMintCounter, 0); stakeNext = try v(.stakeNext, 0); stakeHeight = try v(.stakeHeight, 0)
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
        let d = root.appendingPathComponent("privacy").appendingPathComponent(walletID)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return PrivacyStore(dir: d)
    }

    public func mutate<T>(_ body: (inout PrivacyState) throws -> T) rethrows -> T { try body(&state) }

    /// Persists state after the trees, so a crash between the two leaves
    /// state behind (and resyncs) rather than ahead.
    public func save() {
        noteTree.flush(); identityTree.flush(); stakeTree.flush()
        guard let dir, let data = try? JSONEncoder().encode(state) else { return }
        try? data.write(to: dir.appendingPathComponent(Self.stateFile), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Forgets the synced data, keeping the key counters, the registration's
    /// leaf and what the wallet itself cast (claims, caretaker split,
    /// referrer): a fresh chain, or an inconsistent sync.
    public func reset(chainID: String?) {
        noteTree.clear()
        identityTree.clear()
        stakeTree.clear()
        let old = state
        var s = PrivacyState()
        s.chainID = chainID
        s.nextOtagCounter = old.nextOtagCounter
        s.nextMintCounter = old.nextMintCounter
        s.nextStakeMintCounter = old.nextStakeMintCounter
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

