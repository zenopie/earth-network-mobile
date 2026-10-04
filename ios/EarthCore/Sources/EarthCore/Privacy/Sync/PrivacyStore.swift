import Foundation

/// This wallet's registration as the identity tree holds it. Everything here
/// is needed to prove membership; nothing is sent anywhere. The leaf is
/// H(TAG_LEAF, idc, dsc_key, country, activated_at, predecessor_at).
public struct IdentityRecord: Codable, Equatable, Sendable {
    public let leafIndex: UInt64
    public let dscKey: Fr
    public let country: Fr
    public let activatedAt: UInt64
    /// The passport nullifier: public in the registration, the switch/expiry key.
    public let passportNullifier: String
    /// Matched against an identity tree the chain verified in the same sync,
    /// or resolved from the registration's own committed tx.
    /// A reset keeps only a verified identity; one from before is not.
    public var verified: Bool
    /// The leaf's predecessor_at: the switch or re-entry that made it (then
    /// equal to `activatedAt`), 0 for a passport never registered before.
    /// Found by matching the leaf with either value.
    public let predecessorAt: UInt64

    public init(leafIndex: UInt64, dscKey: Fr, country: Fr, activatedAt: UInt64, passportNullifier: String, verified: Bool = false,
                predecessorAt: UInt64 = 0) {
        self.leafIndex = leafIndex; self.dscKey = dscKey; self.country = country
        self.activatedAt = activatedAt; self.passportNullifier = passportNullifier; self.verified = verified
        self.predecessorAt = predecessorAt
    }

    enum CodingKeys: String, CodingKey { case leafIndex, dscKey, country, activatedAt, passportNullifier, verified, predecessorAt }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        leafIndex = try c.decode(UInt64.self, forKey: .leafIndex); dscKey = try c.decode(Fr.self, forKey: .dscKey)
        country = try c.decode(Fr.self, forKey: .country); activatedAt = try c.decode(UInt64.self, forKey: .activatedAt)
        passportNullifier = try c.decode(String.self, forKey: .passportNullifier)
        verified = try c.decodeIfPresent(Bool.self, forKey: .verified) ?? false
        predecessorAt = try c.decodeIfPresent(UInt64.self, forKey: .predecessorAt) ?? 0
    }
}

/// A registration the node accepted whose identity leaf the wallet has not
/// resolved yet: everything needed to rebuild the identity record,
/// persisted the moment the broadcast is accepted (before the wait for its
/// block), so neither a lagging indexer, a wait that times out nor a killed
/// app can lose it. `leafIndex` and `activatedAt` come from the committed tx
/// (its register event, its block time): nil until it is found by `txHash`.
public struct PendingRegistration: Codable, Equatable, Sendable {
    public let txHash: String
    public var leafIndex: UInt64?
    public let dscKey: Fr
    public let passportNullifier: String
    public let publicSignals: [String]
    /// The registration block's time: the leaf's activated_at (nil until the tx is found).
    public var activatedAt: UInt64?
    /// ISO alpha-2 guess at the verifying CSCA's country ("" for none).
    public let countryHint: String
    /// Why the last attempt to resolve it failed, for the UI (nil: waiting for the indexer).
    public var failure: String?

    public init(txHash: String, leafIndex: UInt64?, dscKey: Fr, passportNullifier: String, publicSignals: [String], activatedAt: UInt64?,
                countryHint: String, failure: String? = nil) {
        self.txHash = txHash; self.leafIndex = leafIndex; self.dscKey = dscKey; self.passportNullifier = passportNullifier
        self.publicSignals = publicSignals; self.activatedAt = activatedAt; self.countryHint = countryHint; self.failure = failure
    }
}

/// A registration record note found by sync (PRIVACY_FORMATS.md 3a), its tag
/// checked: what a wallet restored from the mnemonic finds its identity leaf
/// by. `height` is the registration's block. The search for its leaf is
/// persisted: the leaves appended at `height` as the stream passed
/// them, whether it matched or was given up, and how far the bounded
/// fallback search got, so a killed app or a later sync resumes it and never
/// repeats it.
public struct RegRecord: Codable, Equatable, Sendable {
    public struct Leaf: Codable, Equatable, Sendable {
        public let index: UInt64
        public let leaf: Fr
    }

    public let height: UInt64
    public let position: UInt64
    public let dscKey: Fr
    public let country: String
    public let builtAt: UInt64
    /// Every identity leaf appended at `height`.
    public var leaves: [Leaf] = []
    public var status: RecordStatus = .open
    /// Fallback search steps done (one activated_at offset each).
    public var cursor: UInt64 = 0
    /// Leaf hashes spent on this record so far (capped).
    public var work: UInt64 = 0
    /// `height`'s block time as the indexer's identity rows carry it (nil: not served).
    public var time: UInt64?
    /// Exact activated_at candidates already tried (each at most 677 hashes a leaf): a new one is tried even after exhausted.
    public var tried: [UInt64] = []
    /// The cover set of heights whose block times were asked of the LCD with `height`'s (chosen once, reused).
    public var cover: [UInt64] = []
    /// LCD cover-set fetches made (bounded).
    public var coverTries: Int = 0
    /// `height`'s block time as the LCD answered it (nil: not asked, or it could not say).
    public var chainTime: UInt64?
    /// How many leaves `tried` was tried against: more leaves later reopen the record.
    public var leavesTried: Int = 0

    public init(height: UInt64, position: UInt64, dscKey: Fr, country: String, builtAt: UInt64) {
        self.height = height; self.position = position; self.dscKey = dscKey; self.country = country; self.builtAt = builtAt
    }

    enum CodingKeys: String, CodingKey {
        case height, position, dscKey, country, builtAt, leaves, status, cursor, work, time, tried, cover, coverTries, chainTime, leavesTried
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        height = try c.decode(UInt64.self, forKey: .height); position = try c.decode(UInt64.self, forKey: .position)
        dscKey = try c.decode(Fr.self, forKey: .dscKey); country = try c.decode(String.self, forKey: .country)
        builtAt = try c.decode(UInt64.self, forKey: .builtAt)
        leaves = try c.decodeIfPresent([Leaf].self, forKey: .leaves) ?? []
        status = try c.decodeIfPresent(RecordStatus.self, forKey: .status) ?? .open
        cursor = try c.decodeIfPresent(UInt64.self, forKey: .cursor) ?? 0
        work = try c.decodeIfPresent(UInt64.self, forKey: .work) ?? 0
        time = try c.decodeIfPresent(UInt64.self, forKey: .time)
        tried = try c.decodeIfPresent([UInt64].self, forKey: .tried) ?? []
        cover = try c.decodeIfPresent([UInt64].self, forKey: .cover) ?? []
        coverTries = try c.decodeIfPresent(Int.self, forKey: .coverTries) ?? 0
        chainTime = try c.decodeIfPresent(UInt64.self, forKey: .chainTime)
        leavesTried = try c.decodeIfPresent(Int.self, forKey: .leavesTried) ?? 0
    }
}

/// open: still searching; matched: the identity; exhausted: the bounded
/// fallback search is spent, or the chain's time did not match. Exhausted
/// never blocks a restore for good: an exact time not tried before (the
/// indexer's, the LCD's) is still tried, and a store reset finds the record
/// afresh.
public enum RecordStatus: String, Codable, Sendable { case open, matched, exhausted }

/// An undelegation of this wallet whose payout has not arrived (chain
/// 48b631c, ORCHARD_DESIGN 18.1): recorded when the node takes the tx
/// (`until` its timeout_height), confirmed with the committed event's
/// `epoch`, `value` (uerth) and `payoutID`, dropped once a note to `pc` is
/// synced (paid) or the tx failed. Local only: what the wallet shows while
/// it waits, never a query key.
public struct PendingUnbond: Codable, Equatable, Sendable {
    public let txHash: String
    public let validator: String
    public let derth: UInt64
    public let pc: Fr
    public let startedAt: Int64
    public let until: UInt64?
    public var confirmed: Bool = false
    public var epoch: UInt64?
    public var value: UInt64?
    public var payoutID: UInt64?
    /// About when the chain pays it (PrivacyWallet.unbondDueBy at confirmation; nil: unknown).
    public var dueBy: Int64?

    public init(txHash: String, validator: String, derth: UInt64, pc: Fr, startedAt: Int64, until: UInt64?, confirmed: Bool = false,
                epoch: UInt64? = nil, value: UInt64? = nil, payoutID: UInt64? = nil, dueBy: Int64? = nil) {
        self.txHash = txHash; self.validator = validator; self.derth = derth; self.pc = pc; self.startedAt = startedAt; self.until = until
        self.confirmed = confirmed; self.epoch = epoch; self.value = value; self.payoutID = payoutID; self.dueBy = dueBy
    }
}

/// A stake vote this wallet cast (ORCHARD_DESIGN 15): its proposal and vote
/// nullifier, recorded the moment the node accepted the tx (`confirmed`
/// false, with its hash and timeout_height) and confirmed once committed or
/// refused as already voted. One note votes once per proposal.
public struct StakeVoteRecord: Codable, Equatable, Sendable {
    public let proposalID: UInt64
    public let vnf: Fr
    public let txHash: String?
    public let until: UInt64?
    public var confirmed: Bool
    public init(proposalID: UInt64, vnf: Fr, txHash: String?, until: UInt64?, confirmed: Bool) {
        self.proposalID = proposalID; self.vnf = vnf; self.txHash = txHash; self.until = until; self.confirmed = confirmed
    }
}

/// A move of a handle or caretaker split, recorded before its
/// broadcast in both wallets: the mover's (`incoming` false: it still holds
/// what it is moving until the tx is confirmed) and the new identity's
/// (`incoming` true: it holds it already, rolled back only if the tx is
/// refused, failed in its block, or missing past its timeout_height).
/// `target` is the new wallet's store id (the mover's copy), `recorded`
/// whether writing it there succeeded (retryable while false). Ports
/// PendingMove in `privacy/sync/PrivacyStore.kt`.
public struct PendingMove: Codable, Equatable, Sendable {
    public static let handleKind = "handle"
    public static let caretakerKind = "caretaker"
    /// "handle" or "caretaker".
    public var kind: String
    public var txHash: String
    public var timeoutHeight: UInt64
    public var incoming: Bool
    public var handle: String = ""
    public var split: [UInt64: UInt64] = [:]
    public var splitUnknown: Bool = false
    public var expiresAt: Int64 = 0
    public var target: String = ""
    public var recorded: Bool = false
    public var confirmed: Bool = false

    public init(kind: String, txHash: String, timeoutHeight: UInt64, incoming: Bool, handle: String = "", split: [UInt64: UInt64] = [:],
                splitUnknown: Bool = false, expiresAt: Int64 = 0, target: String = "", recorded: Bool = false, confirmed: Bool = false) {
        self.kind = kind; self.txHash = txHash; self.timeoutHeight = timeoutHeight; self.incoming = incoming; self.handle = handle
        self.split = split; self.splitUnknown = splitUnknown; self.expiresAt = expiresAt; self.target = target
        self.recorded = recorded; self.confirmed = confirmed
    }
}

/// What the wallet keeps between syncs: cursors into each indexer stream, its
/// own notes, its registration, and its own txs' bookkeeping. Small; the
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
    /// A committed registration not yet matched to its leaf.
    public var pendingRegistration: PendingRegistration?
    /// Registration record notes found (restore, L8).
    public var regRecords: [RegRecord] = []
    /// Whether the last sync's roots matched the chain's own, and why not.
    public var rootsVerified: Bool = false
    public var rootsError: String?
    /// Sync generations: `syncGeneration` is bumped, with
    /// `rootsVerified` cleared and persisted, before a sync's first request;
    /// `verifiedGeneration` is set to it only when every stream and the root
    /// checks of that same sync succeeded. Txs need the two equal.
    public var syncGeneration: UInt64 = 0
    public var verifiedGeneration: UInt64?
    /// The height the last verified sync reached (the indexer's, checked
    /// against the chain's tree and tip). A tx's tip is bounded
    /// by it, and a pending mark whose timeout is far past it is resolved by
    /// the tx's status alone. Kept across resets (heights only grow).
    public var verifiedHeight: UInt64 = 0
    /// UTC days a claim was broadcast for (so a claim is not offered twice).
    public var claimedDays: Set<UInt64> = []
    /// When the caretaker split was last cast (unix seconds), and the split (option -> percent).
    public var caretakerCastAt: Int64 = 0
    public var caretakerSplit: [UInt64: UInt64] = [:]
    /// When the split lapses (the chain's expires_at; 0: unknown, castAt + R).
    public var caretakerExpiresAt: Int64 = 0
    /// This identity moved its split away (MsgMoveCaretaker): it may never cast one again.
    public var caretakerMovedOut: Bool = false
    /// This identity's handle ("" for none), as last claimed, renewed or moved in.
    public var handle: String = ""
    /// This identity moved its handle away (MsgMoveHandle): it may never claim one again.
    public var handleMovedOut: Bool = false
    /// When `handle` last changed here (wallet clock): a directory read before it says nothing about it.
    public var handleSetAt: Int64 = 0
    /// When the handle `handleExpiresFor` stops being live (the chain's
    /// expires_at, from its bind or the chain's directory). Counts only while
    /// it is `handle`; otherwise unknown. Past it, a renewal or change is
    /// bounded like a claim and a move is refused (chain 203d3b2).
    public var handleExpiresAt: Int64 = 0
    public var handleExpiresFor: String = ""
    /// The caretaker split is held but its record did not carry it (restored from a state record).
    public var caretakerSplitUnknown: Bool = false
    /// The newest handle / caretaker state record applied (note position; nil: none).
    public var handleRecordPos: UInt64?
    public var caretakerRecordPos: UInt64?
    /// Heights of this wallet's txs that failed in their block: their state records are void.
    public var voidRecordHeights: Set<UInt64> = []
    /// Moves in flight, either way.
    public var pendingMoves: [PendingMove] = []
    /// The store id of the wallet a switch moves to, fixed by its first move.
    public var switchTarget: String = ""
    /// Undelegations whose payout has not arrived yet.
    public var pendingUnbonds: [PendingUnbond] = []
    /// Next unused Groundworks owner-tag counter (PrivacyKeys.otagSalt).
    public var nextOtagCounter: UInt32 = 0
    /// The highest owner-tag counter of a position this wallet closed, from its unlock memos (nil: none).
    public var closedOtagMax: UInt32?
    /// Every stake vote cast: (proposal, vote nullifier).
    public var stakeVotes: [StakeVoteRecord] = []
    /// The stake tree's stream cursors and this wallet's stake notes.
    public var stakeNext: UInt64 = 0
    public var stakeHeight: UInt64 = 0
    public var stakeNullifiersNext: UInt64 = 0
    public var stakeNotes: [OwnedStakeNote] = []
    /// The chain's slash label window (Query/DebtTree window_seconds) as last
    /// read: when a moved stake's exposure may leave its note, for display
    /// between reads (0: never read).
    public var labelWindowSeconds: UInt64 = 0
    /// Every denom seen in a public amount: resolves the asset ids ciphertexts carry.
    public var denoms: Set<String> = []
    /// A uniform sample of identity row heights (registration blocks): a record's LCD cover set is drawn from it.
    public var identityHeights: [UInt64] = []
    public var identityRowsSeen: UInt64 = 0

    public init() {}

    enum CodingKeys: String, CodingKey {
        case chainID, genesis, notesNext, notesHeight, nullifiersNext, identityNext, zeroedNext, notes, identity, pendingRegistration,
             regRecords, rootsVerified, rootsError, claimedDays, caretakerCastAt, caretakerSplit, caretakerExpiresAt, caretakerMovedOut, handle, handleMovedOut,
             pendingUnbonds, nextOtagCounter, stakeNext, stakeHeight, stakeNullifiersNext, stakeNotes, denoms, closedOtagMax,
             syncGeneration, verifiedGeneration, verifiedHeight, stakeVotes, identityHeights, identityRowsSeen,
             handleSetAt, caretakerSplitUnknown, handleRecordPos, caretakerRecordPos, voidRecordHeights, pendingMoves, switchTarget,
             handleExpiresAt, handleExpiresFor, labelWindowSeconds
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
        caretakerExpiresAt = try v(.caretakerExpiresAt, 0); caretakerMovedOut = try v(.caretakerMovedOut, false)
        handle = try v(.handle, ""); handleMovedOut = try v(.handleMovedOut, false); pendingUnbonds = try v(.pendingUnbonds, [])
        nextOtagCounter = try v(.nextOtagCounter, 0); stakeNext = try v(.stakeNext, 0); stakeHeight = try v(.stakeHeight, 0)
        stakeNullifiersNext = try v(.stakeNullifiersNext, 0); stakeNotes = try v(.stakeNotes, []); denoms = try v(.denoms, [])
        closedOtagMax = try c.decodeIfPresent(UInt32.self, forKey: .closedOtagMax)
        stakeVotes = try v(.stakeVotes, [])
        syncGeneration = try v(.syncGeneration, 0)
        verifiedGeneration = try c.decodeIfPresent(UInt64.self, forKey: .verifiedGeneration)
        verifiedHeight = try v(.verifiedHeight, 0)
        identityHeights = try v(.identityHeights, []); identityRowsSeen = try v(.identityRowsSeen, 0)
        handleSetAt = try v(.handleSetAt, 0); caretakerSplitUnknown = try v(.caretakerSplitUnknown, false)
        handleRecordPos = try c.decodeIfPresent(UInt64.self, forKey: .handleRecordPos)
        caretakerRecordPos = try c.decodeIfPresent(UInt64.self, forKey: .caretakerRecordPos)
        voidRecordHeights = try v(.voidRecordHeights, []); pendingMoves = try v(.pendingMoves, []); switchTarget = try v(.switchTarget, "")
        handleExpiresAt = try v(.handleExpiresAt, 0); handleExpiresFor = try v(.handleExpiresFor, "")
        labelWindowSeconds = try v(.labelWindowSeconds, 0)
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

    /// state.json exists but does not parse: shown as an error, never silently replaced by an empty state.
    public struct CorruptState: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// A save failed (the state file, or a tree's files): surfaced, never silent.
    public struct SaveFailed: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    private let fileStores: [FileNodeStore]

    private init(dir: URL?) throws {
        self.dir = dir
        let files = dir.map { d in ["notes", "identity", "stake"].map { FileNodeStore(directory: d.appendingPathComponent($0)) } }
        fileStores = files ?? []
        let noteNodes: NodeStore = files?[0] ?? MemNodeStore()
        let identityNodes: NodeStore = files?[1] ?? MemNodeStore()
        let stakeNodes: NodeStore = files?[2] ?? MemNodeStore()
        var loaded: PrivacyState?
        if let dir {
            let url = dir.appendingPathComponent(Self.stateFile)
            if FileManager.default.fileExists(atPath: url.path) {
                do {
                    loaded = try JSONDecoder().decode(PrivacyState.self, from: Data(contentsOf: url))
                } catch {
                    throw CorruptState(message: "this wallet's private data (\(Self.stateFile)) is unreadable: \(error.localizedDescription)")
                }
            }
        }
        state = loaded ?? PrivacyState()
        noteTree = MerkleTree(store: noteNodes, size: state.notesNext)
        identityTree = MerkleTree(store: identityNodes, size: state.identityNext)
        stakeTree = MerkleTree(store: stakeNodes, size: state.stakeNext)
    }

    public static func memory() -> PrivacyStore { try! PrivacyStore(dir: nil) } // no file: nothing to fail

    nonisolated(unsafe) private static var sharedStores: [String: PrivacyStore] = [:]
    private static let sharedLock = NSLock()

    /// The process's one store for a wallet's directory: two
    /// instances on one directory each save their whole state over the
    /// other's. Every app path opens stores through this.
    public static func shared(root: URL, walletID: String) throws -> PrivacyStore {
        let key = root.appendingPathComponent("privacy").appendingPathComponent(walletID).standardizedFileURL.path
        sharedLock.lock(); defer { sharedLock.unlock() }
        if let s = sharedStores[key] { return s }
        let s = try open(root: root, walletID: walletID)
        sharedStores[key] = s
        return s
    }

    public static func open(root: URL, walletID: String) throws -> PrivacyStore {
        let top = root.appendingPathComponent("privacy")
        let d = top.appendingPathComponent(walletID)
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        // The notes, trees and registration are derivable from the
        // mnemonic and the chain; a device backup (iCloud, iTunes/Finder)
        // would only carry this wallet's private history off the device.
        excludeFromBackup(top)
        excludeFromBackup(d)
        return try PrivacyStore(dir: d)
    }

    /// Deletes a wallet's private data (notes, identity, records, trees) when
    /// the wallet is forgotten: every file is overwritten with
    /// zeros and synced before it is unlinked (best effort on flash; the
    /// app's sandbox and Data Protection are the real boundary). `walletID`
    /// nil: every wallet's.
    public static func delete(root: URL, walletID: String? = nil) throws {
        let top = root.appendingPathComponent("privacy")
        let target = walletID.map { top.appendingPathComponent($0) } ?? top
        sharedLock.lock()
        let prefix = target.standardizedFileURL.path
        sharedStores = sharedStores.filter { $0.key != prefix && !$0.key.hasPrefix(prefix + "/") }
        sharedLock.unlock()
        let fm = FileManager.default
        guard fm.fileExists(atPath: target.path) else { return }
        if let e = fm.enumerator(at: target, includingPropertiesForKeys: [.isRegularFileKey]) {
            for case let url as URL in e where (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true {
                if let h = try? FileHandle(forUpdating: url) {
                    let n = (try? h.seekToEnd()) ?? 0
                    try? h.seek(toOffset: 0)
                    var left = n
                    let zeros = Data(count: 64 * 1024)
                    while left > 0 { let k = Int(min(left, UInt64(zeros.count))); try? h.write(contentsOf: zeros.prefix(k)); left -= UInt64(k) }
                    try? h.synchronize()
                    try? h.close()
                }
            }
        }
        try fm.removeItem(at: target)
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
    /// state behind (and resyncs) rather than ahead. The state is written
    /// atomically (a temp file renamed over it); any failure, the trees'
    /// included, throws (never silent).
    public func save() throws {
        noteTree.flush(); identityTree.flush(); stakeTree.flush()
        for f in fileStores { if let e = f.takeError() { throw SaveFailed(message: "could not save this wallet's private data: \(e)") } }
        guard let dir else { return }
        do {
            let data = try JSONEncoder().encode(state)
            try data.write(to: dir.appendingPathComponent(Self.stateFile), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            throw SaveFailed(message: "could not save this wallet's private data: \(error.localizedDescription)")
        }
    }

    /// Forgets the synced data. On the same chain (an inconsistent sync, a
    /// root mismatch) it keeps the owner-tag counter, the registration (its
    /// leaf only when it was matched against a verified tree; or
    /// the one pending) and what the wallet itself cast (claims, caretaker
    /// split, handle, the moves, its stake votes); a different chain or genesis (a relaunch under the same chain
    /// id) keeps only the owner-tag counter.
    public func reset(chainID: String?) throws { try reset(chainID: chainID, genesis: state.genesis) }

    public func reset(chainID: String?, genesis: String?) throws {
        noteTree.clear()
        identityTree.clear()
        stakeTree.clear()
        let old = state
        var s = PrivacyState()
        s.chainID = chainID
        s.genesis = genesis
        s.nextOtagCounter = old.nextOtagCounter
        s.closedOtagMax = old.closedOtagMax
        if old.chainID == chainID && old.genesis == genesis {
            s.pendingUnbonds = old.pendingUnbonds
            s.identity = old.identity?.verified == true ? old.identity : nil
            s.pendingRegistration = old.pendingRegistration
            s.stakeVotes = old.stakeVotes
            s.labelWindowSeconds = old.labelWindowSeconds
            s.claimedDays = old.claimedDays
            s.caretakerCastAt = old.caretakerCastAt; s.caretakerSplit = old.caretakerSplit
            s.caretakerExpiresAt = old.caretakerExpiresAt; s.caretakerMovedOut = old.caretakerMovedOut
            s.handle = old.handle; s.handleMovedOut = old.handleMovedOut
            Self.keepHandleState(old, &s)
        } else if old.chainID == nil {
            // Never synced: what a switch moved to this identity was
            // recorded for the chain the app follows (PrivacyWallet.recordIncoming).
            s.caretakerCastAt = old.caretakerCastAt; s.caretakerSplit = old.caretakerSplit
            s.caretakerExpiresAt = old.caretakerExpiresAt
            s.handle = old.handle
            Self.keepHandleState(old, &s)
        }
        state = s
        try save()
    }

    /// What a reset keeps of the moves and state records: the records already
    /// applied are not applied again over what the wallet did since (a resync
    /// reads them from the start), and moves in flight stay in flight.
    private static func keepHandleState(_ old: PrivacyState, _ s: inout PrivacyState) {
        s.handleSetAt = old.handleSetAt
        s.handleExpiresAt = old.handleExpiresAt; s.handleExpiresFor = old.handleExpiresFor
        s.caretakerSplitUnknown = old.caretakerSplitUnknown
        s.handleRecordPos = old.handleRecordPos; s.caretakerRecordPos = old.caretakerRecordPos
        s.voidRecordHeights = old.voidRecordHeights
        s.pendingMoves = old.pendingMoves
        s.switchTarget = old.switchTarget
        s.verifiedHeight = old.verifiedHeight
    }

    /// A relaunch of the same chain id under a new genesis, confirmed by the
    /// LCD: the synced data goes, but the registration stays (the
    /// identity record, its passport nullifier, a pending registration) and
    /// so do the owner-tag counters; the old chain's bookkeeping does not.
    public func switchGenesis(_ genesis: String) throws {
        noteTree.clear()
        identityTree.clear()
        stakeTree.clear()
        let old = state
        var s = PrivacyState()
        s.chainID = old.chainID
        s.genesis = genesis
        s.nextOtagCounter = old.nextOtagCounter
        s.closedOtagMax = old.closedOtagMax
        s.identity = old.identity
        s.pendingRegistration = old.pendingRegistration
        s.pendingRegistration?.failure = nil
        state = s
        try save()
    }
}

