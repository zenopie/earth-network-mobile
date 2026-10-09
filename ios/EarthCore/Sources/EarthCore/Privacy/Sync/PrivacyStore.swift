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
    /// The identity generation it registers (PrivacyKeys): its leaf is matched with that generation's idc.
    public var generation: Int = 0

    public init(txHash: String, leafIndex: UInt64?, dscKey: Fr, passportNullifier: String, publicSignals: [String], activatedAt: UInt64?,
                countryHint: String, failure: String? = nil, generation: Int = 0) {
        self.txHash = txHash; self.leafIndex = leafIndex; self.dscKey = dscKey; self.passportNullifier = passportNullifier
        self.publicSignals = publicSignals; self.activatedAt = activatedAt; self.countryHint = countryHint; self.failure = failure
        self.generation = generation
    }

    enum CodingKeys: String, CodingKey { case txHash, leafIndex, dscKey, passportNullifier, publicSignals, activatedAt, countryHint, failure, generation }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        txHash = try c.decode(String.self, forKey: .txHash); leafIndex = try c.decodeIfPresent(UInt64.self, forKey: .leafIndex)
        dscKey = try c.decode(Fr.self, forKey: .dscKey); passportNullifier = try c.decode(String.self, forKey: .passportNullifier)
        publicSignals = try c.decode([String].self, forKey: .publicSignals); activatedAt = try c.decodeIfPresent(UInt64.self, forKey: .activatedAt)
        countryHint = try c.decode(String.self, forKey: .countryHint); failure = try c.decodeIfPresent(String.self, forKey: .failure)
        generation = PrivacyState.clampGeneration(try c.decodeIfPresent(Int.self, forKey: .generation) ?? 0)
    }
}

/// A registration record note found by sync (PRIVACY_FORMATS.md 6), its tag
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
    /// The identity generation the registration was for, from the record's tag (PRIVACY_FORMATS.md 6).
    public var generation: Int = 0

    public init(height: UInt64, position: UInt64, dscKey: Fr, country: String, builtAt: UInt64, generation: Int = 0) {
        self.height = height; self.position = position; self.dscKey = dscKey; self.country = country; self.builtAt = builtAt
        self.generation = generation
    }

    enum CodingKeys: String, CodingKey {
        case height, position, dscKey, country, builtAt, leaves, status, cursor, work, time, tried, cover, coverTries, chainTime, leavesTried, generation
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
        generation = PrivacyState.clampGeneration(try c.decodeIfPresent(Int.self, forKey: .generation) ?? 0)
    }
}

/// open: still searching; matched: the identity; exhausted: the bounded
/// fallback search is spent, or the chain's time did not match. Exhausted
/// never blocks a restore for good: an exact time not tried before (the
/// indexer's, the LCD's) is still tried, and a store reset finds the record
/// afresh.
public enum RecordStatus: String, Codable, Sendable { case open, matched, exhausted }

/// An undelegation of this wallet whose payout has not arrived
/// (ORCHARD_DESIGN 8.4): recorded when the node takes the tx
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

/// A stake vote this wallet cast (ORCHARD_DESIGN 8.5): its proposal and vote
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

/// A spend mark carried across a store reset, keyed by the note's nullifier:
/// a tx still in the mempool keeps its notes unspendable while the resync
/// finds them again (WalletSync applies it to the note it re-finds).
public struct CarriedMark: Codable, Equatable, Sendable {
    public var at: Int64
    public var until: UInt64?
    public var tx: String?
}

/// What one identity generation of the wallet holds (PrivacyKeys: one
/// phrase, an identity secret per generation): its registration, handle,
/// caretaker split and moves. The wallet acts as `PrivacyState.generation`;
/// an earlier one keeps what it holds until moved on or lapsed. Ports
/// IdentitySlot in `privacy/sync/PrivacyStore.kt`.
public struct IdentitySlot: Codable, Equatable, Sendable {
    public var identity: IdentityRecord?
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
    /// bounded like a claim and a move is refused.
    public var handleExpiresAt: Int64 = 0
    public var handleExpiresFor: String = ""
    /// The caretaker split is held but its record did not carry it (restored from a state record).
    public var caretakerSplitUnknown: Bool = false
    /// The newest handle / caretaker state record applied (note position; nil: none).
    public var handleRecordPos: UInt64?
    public var caretakerRecordPos: UInt64?
    /// Moves in flight, either way.
    public var pendingMoves: [PendingMove] = []
    /// The store id of the wallet a switch moves to, fixed by its first move.
    public var switchTarget: String = ""
    /// After a switch to this identity: when the wallet suggests bringing the
    /// predecessor's handle and split over (a random delay after the switch,
    /// so a move does not link them by timing; 0: none drawn, -1: nothing
    /// left to move), and the leaf of the registration it was drawn for. A
    /// suggestion only: nothing moves unasked. Android's move_suggested_at.
    public var moveSuggestedAt: Int64 = 0
    public var moveSuggestedLeaf: Int64 = -1
    /// The earliest lease end of what the predecessor still holds to move
    /// here, as Identity last read it (0: none known): past it the old
    /// identity can neither move nor renew it. The suggestion is capped
    /// before it, and the reminder grows urgent as it nears. As Android's move_deadline.
    public var moveDeadline: Int64 = 0

    public init() {}

    /// Whether it holds or did nothing (such a slot is not written).
    public var empty: Bool { self == IdentitySlot() }

    enum CodingKeys: String, CodingKey {
        case identity, caretakerCastAt, caretakerSplit, caretakerExpiresAt, caretakerMovedOut, handle, handleMovedOut, handleSetAt,
             handleExpiresAt, handleExpiresFor, caretakerSplitUnknown, handleRecordPos, caretakerRecordPos, pendingMoves, switchTarget,
             moveSuggestedAt, moveSuggestedLeaf, moveDeadline
    }

    /// From a slot, or (a state file from before generations) the state's own top-level keys, which are the same.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func v<T: Decodable>(_ k: CodingKeys, _ d: T) throws -> T { try c.decodeIfPresent(T.self, forKey: k) ?? d }
        identity = try c.decodeIfPresent(IdentityRecord.self, forKey: .identity)
        caretakerCastAt = try v(.caretakerCastAt, 0); caretakerSplit = try v(.caretakerSplit, [:])
        caretakerExpiresAt = try v(.caretakerExpiresAt, 0); caretakerMovedOut = try v(.caretakerMovedOut, false)
        handle = try v(.handle, ""); handleMovedOut = try v(.handleMovedOut, false)
        handleSetAt = try v(.handleSetAt, 0); caretakerSplitUnknown = try v(.caretakerSplitUnknown, false)
        handleRecordPos = try c.decodeIfPresent(UInt64.self, forKey: .handleRecordPos)
        caretakerRecordPos = try c.decodeIfPresent(UInt64.self, forKey: .caretakerRecordPos)
        pendingMoves = try v(.pendingMoves, []); switchTarget = try v(.switchTarget, "")
        moveSuggestedAt = try v(.moveSuggestedAt, 0); moveSuggestedLeaf = try v(.moveSuggestedLeaf, -1)
        moveDeadline = try v(.moveDeadline, 0)
        handleExpiresAt = try v(.handleExpiresAt, 0); handleExpiresFor = try v(.handleExpiresFor, "")
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
    /// The identity generation the wallet acts as (PrivacyKeys): the one its
    /// registration, handle and split below are. It moves up when a
    /// registration of a later generation lands, or a restore finds one.
    public var generation: Int = 0
    /// The lowest generation a registration may use: raised past one the chain refused as used (1130).
    public var generationFloor: Int = 0
    /// Every generation's slot that holds anything (by generation).
    public var identities: [Int: IdentitySlot] = [:]
    /// A committed registration not yet matched to its leaf.
    public var pendingRegistration: PendingRegistration?
    /// Until when (unix seconds) a registration this wallet broadcast can
    /// still land: its proof's current_date plus the chain's 48 h skew. A
    /// registration that failed or was refused is public and may be replayed
    /// until then, so this wallet's identity (its recovery phrase) must be
    /// kept and counted as possibly registered. 0: none.
    public var registrationKeepUntil: Int64 = 0
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
    /// Heights of this wallet's txs that failed in their block: their state records are void.
    public var voidRecordHeights: Set<UInt64> = []
    /// Undelegations whose payout has not arrived yet.
    public var pendingUnbonds: [PendingUnbond] = []
    /// The Groundworks split this wallet votes with (empty: none): every
    /// stake tx carries it to the note it makes (PrivacyWallet.castGroundworks).
    public var groundworksSplit: [UInt64: UInt64] = [:]
    /// The split was chosen here (cast, changed or stopped): never replaced
    /// by one read off the chain's votes (PrivacyWallet.adopt).
    public var groundworksChosen = false
    /// The latest lease end seen on this wallet's own votes (0: none seen): a
    /// lapsed vote is deleted on chain, so only this says when it lapsed.
    public var groundworksExpiresAt: Int64 = 0
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
    /// Pending marks a reset carried, by nullifier hex, until the resync re-finds their notes.
    public var carriedMarks: [String: CarriedMark] = [:]
    /// The earliest note position holding a value-0 note of ours with a
    /// record's magic whose tag matched no generation tried (nil: none), and
    /// the smallest record window such a note was tried with: once the
    /// window grows past it, WalletSync tries those notes again. As Android.
    public var unmatchedRecordFrom: UInt64?
    public var unmatchedRecordWindow: Int = 0
    /// The private activity list's own data (PrivateActivity): what this wallet sent, and what it expects minted to it.
    public var activity = ActivityLog()

    public init() {}

    static func clampGeneration(_ g: Int) -> Int { min(max(g, 0), PrivacyKeys.maxGeneration) }

    /// The slot of `generation` (empty until it holds anything).
    public func slot(_ generation: Int) -> IdentitySlot { identities[generation] ?? IdentitySlot() }
    /// What the identity the wallet acts as holds.
    public var current: IdentitySlot {
        get { slot(generation) }
        set { identities[generation] = newValue }
    }
    /// Changes `generation`'s slot in place.
    public mutating func withSlot<T>(_ generation: Int, _ body: (inout IdentitySlot) throws -> T) rethrows -> T {
        var t = slot(generation)
        defer { identities[generation] = t }
        return try body(&t)
    }

    // What the identity the wallet acts as holds: its slot's (IdentitySlot).
    public var identity: IdentityRecord? { get { current.identity } set { current.identity = newValue } }
    public var caretakerCastAt: Int64 { get { current.caretakerCastAt } set { current.caretakerCastAt = newValue } }
    public var caretakerSplit: [UInt64: UInt64] { get { current.caretakerSplit } set { current.caretakerSplit = newValue } }
    public var caretakerExpiresAt: Int64 { get { current.caretakerExpiresAt } set { current.caretakerExpiresAt = newValue } }
    public var caretakerMovedOut: Bool { get { current.caretakerMovedOut } set { current.caretakerMovedOut = newValue } }
    public var handle: String { get { current.handle } set { current.handle = newValue } }
    public var handleMovedOut: Bool { get { current.handleMovedOut } set { current.handleMovedOut = newValue } }
    public var handleSetAt: Int64 { get { current.handleSetAt } set { current.handleSetAt = newValue } }
    public var handleExpiresAt: Int64 { get { current.handleExpiresAt } set { current.handleExpiresAt = newValue } }
    public var handleExpiresFor: String { get { current.handleExpiresFor } set { current.handleExpiresFor = newValue } }
    public var caretakerSplitUnknown: Bool { get { current.caretakerSplitUnknown } set { current.caretakerSplitUnknown = newValue } }
    public var handleRecordPos: UInt64? { get { current.handleRecordPos } set { current.handleRecordPos = newValue } }
    public var caretakerRecordPos: UInt64? { get { current.caretakerRecordPos } set { current.caretakerRecordPos = newValue } }
    public var pendingMoves: [PendingMove] { get { current.pendingMoves } set { current.pendingMoves = newValue } }
    public var switchTarget: String { get { current.switchTarget } set { current.switchTarget = newValue } }
    public var moveSuggestedAt: Int64 { get { current.moveSuggestedAt } set { current.moveSuggestedAt = newValue } }
    public var moveSuggestedLeaf: Int64 { get { current.moveSuggestedLeaf } set { current.moveSuggestedLeaf = newValue } }
    public var moveDeadline: Int64 { get { current.moveDeadline } set { current.moveDeadline = newValue } }

    /// The highest generation whose idc may have been registered, -1 for
    /// none: one with a registration record (a registration of it reached a
    /// block, whether or not it succeeded: the record lands with the fee),
    /// a registration matched, one a node said committed (its leaf not yet
    /// matched), and every one below the floor. Skipping a generation whose
    /// registration failed costs nothing. As Android.
    public func usedThrough() -> Int { max(generationFloor - 1, usedThroughRecorded(), unmatchedThrough()) }

    /// The highest generation with a registration on chain to show for it
    /// (a record, a matched leaf), -1 for none: what a restore can find
    /// again. The floor is not counted, nor a pending registration whose
    /// leaf has not matched: a committed tx is only the node's word, and
    /// from an http own node anyone's on its network. As Android.
    public func usedThroughRecorded() -> Int {
        var m = -1
        for r in regRecords { m = max(m, r.generation) }
        for (g, t) in identities where t.identity != nil { m = max(m, g) }
        return m
    }

    /// The generation of a pending registration a node said committed whose
    /// leaf has not matched (a matched one is cleared into its slot), -1 for
    /// none. Its idc is not reused, but it advances the next generation no
    /// further than `generationLookahead` past the highest recorded one: a
    /// forged "committed" repeated across retries cannot push the live
    /// identity past a restore's reach. At that bound the generation is
    /// tried again; if it is used after all, CheckTx refuses it (1130) at no
    /// cost. As Android.
    public func unmatchedThrough() -> Int {
        guard let p = pendingRegistration, p.leafIndex != nil else { return -1 }
        return min(p.generation, usedThroughRecorded() + WalletSync.generationLookahead - 1)
    }

    /// The generation the next registration (a first one, a re-entry, a
    /// fresh identity) uses: the lowest above every one that may have been
    /// registered. A sent registration that has not landed keeps its own:
    /// retrying it is the same identity.
    public func nextGeneration() -> Int {
        let pending = pendingRegistration.flatMap { $0.leafIndex == nil && $0.failure == nil ? $0.generation : nil } ?? -1
        return max(usedThrough() + 1, generationFloor, pending, 0)
    }

    /// A registration of `g` landed or was found: the wallet acts as it from now on (never back to an earlier one).
    public mutating func actAs(_ g: Int) {
        if g > generation { generation = g }
    }

    enum CodingKeys: String, CodingKey {
        case chainID, genesis, notesNext, notesHeight, nullifiersNext, identityNext, zeroedNext, notes, pendingRegistration,
             regRecords, rootsVerified, rootsError, claimedDays,
             pendingUnbonds, groundworksSplit, groundworksChosen, stakeNext, stakeHeight, stakeNullifiersNext, stakeNotes, denoms, groundworksExpiresAt,
             syncGeneration, verifiedGeneration, verifiedHeight, stakeVotes, identityHeights, identityRowsSeen,
             voidRecordHeights, labelWindowSeconds, carriedMarks, registrationKeepUntil,
             generation, generationFloor, identities, unmatchedRecordFrom, unmatchedRecordWindow, activity
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(chainID, forKey: .chainID); try c.encodeIfPresent(genesis, forKey: .genesis)
        try c.encode(notesNext, forKey: .notesNext); try c.encode(notesHeight, forKey: .notesHeight)
        try c.encode(nullifiersNext, forKey: .nullifiersNext); try c.encode(identityNext, forKey: .identityNext)
        try c.encode(zeroedNext, forKey: .zeroedNext); try c.encode(notes, forKey: .notes)
        try c.encodeIfPresent(pendingRegistration, forKey: .pendingRegistration); try c.encode(regRecords, forKey: .regRecords)
        try c.encode(rootsVerified, forKey: .rootsVerified); try c.encodeIfPresent(rootsError, forKey: .rootsError)
        try c.encode(claimedDays, forKey: .claimedDays); try c.encode(pendingUnbonds, forKey: .pendingUnbonds)
        try c.encode(groundworksSplit, forKey: .groundworksSplit); try c.encode(groundworksChosen, forKey: .groundworksChosen)
        try c.encode(stakeNext, forKey: .stakeNext)
        try c.encode(stakeHeight, forKey: .stakeHeight); try c.encode(stakeNullifiersNext, forKey: .stakeNullifiersNext)
        try c.encode(stakeNotes, forKey: .stakeNotes); try c.encode(denoms, forKey: .denoms)
        try c.encode(groundworksExpiresAt, forKey: .groundworksExpiresAt); try c.encode(syncGeneration, forKey: .syncGeneration)
        try c.encodeIfPresent(verifiedGeneration, forKey: .verifiedGeneration); try c.encode(verifiedHeight, forKey: .verifiedHeight)
        try c.encode(stakeVotes, forKey: .stakeVotes); try c.encode(identityHeights, forKey: .identityHeights)
        try c.encode(identityRowsSeen, forKey: .identityRowsSeen); try c.encode(voidRecordHeights, forKey: .voidRecordHeights)
        try c.encode(labelWindowSeconds, forKey: .labelWindowSeconds); try c.encode(carriedMarks, forKey: .carriedMarks)
        try c.encode(registrationKeepUntil, forKey: .registrationKeepUntil)
        try c.encode(generation, forKey: .generation); try c.encode(generationFloor, forKey: .generationFloor)
        try c.encodeIfPresent(unmatchedRecordFrom, forKey: .unmatchedRecordFrom); try c.encode(unmatchedRecordWindow, forKey: .unmatchedRecordWindow)
        try c.encode(activity, forKey: .activity)
        // String keys: a JSON object, not an alternating array.
        try c.encode(Dictionary(uniqueKeysWithValues: identities.filter { $0.key == generation || !$0.value.empty }.map { (String($0.key), $0.value) }),
                     forKey: .identities)
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
        generation = Self.clampGeneration(try v(.generation, 0)); generationFloor = Self.clampGeneration(try v(.generationFloor, 0))
        if let ids = try c.decodeIfPresent([String: IdentitySlot].self, forKey: .identities) {
            for (k, t) in ids { if let g = Int(k), g == Self.clampGeneration(g) { identities[g] = t } }
        } else {
            // A state file from before generations: its one identity is generation 0.
            identities[0] = try IdentitySlot(from: decoder)
        }
        claimedDays = try v(.claimedDays, []); pendingUnbonds = try v(.pendingUnbonds, [])
        groundworksSplit = try v(.groundworksSplit, [:]); groundworksChosen = try v(.groundworksChosen, false); stakeNext = try v(.stakeNext, 0); stakeHeight = try v(.stakeHeight, 0)
        stakeNullifiersNext = try v(.stakeNullifiersNext, 0); stakeNotes = try v(.stakeNotes, []); denoms = try v(.denoms, [])
        groundworksExpiresAt = try v(.groundworksExpiresAt, 0)
        stakeVotes = try v(.stakeVotes, [])
        syncGeneration = try v(.syncGeneration, 0)
        verifiedGeneration = try c.decodeIfPresent(UInt64.self, forKey: .verifiedGeneration)
        verifiedHeight = try v(.verifiedHeight, 0)
        identityHeights = try v(.identityHeights, []); identityRowsSeen = try v(.identityRowsSeen, 0)
        voidRecordHeights = try v(.voidRecordHeights, [])
        labelWindowSeconds = try v(.labelWindowSeconds, 0)
        carriedMarks = try v(.carriedMarks, [:])
        registrationKeepUntil = try v(.registrationKeepUntil, 0)
        unmatchedRecordFrom = try c.decodeIfPresent(UInt64.self, forKey: .unmatchedRecordFrom)
        unmatchedRecordWindow = try v(.unmatchedRecordWindow, 0)
        activity = try v(.activity, ActivityLog())
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
    /// The install's data key (StateSeal); nil only for a store with no file.
    private let key: Data?
    private let walletID: String
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

    private init(dir: URL?, key: Data? = nil, walletID: String = "") throws {
        self.dir = dir
        self.key = key
        self.walletID = walletID
        let files = dir.map { d in ["notes", "identity", "stake"].map { FileNodeStore(directory: d.appendingPathComponent($0)) } }
        fileStores = files ?? []
        let noteNodes: NodeStore = files?[0] ?? MemNodeStore()
        let identityNodes: NodeStore = files?[1] ?? MemNodeStore()
        let stakeNodes: NodeStore = files?[2] ?? MemNodeStore()
        var loaded: PrivacyState?
        var contents: StateSeal.Contents?
        if let dir {
            let url = dir.appendingPathComponent(Self.stateFile)
            if FileManager.default.fileExists(atPath: url.path) {
                do {
                    let c = try StateSeal.open(Data(contentsOf: url), key: key, walletID: walletID)
                    contents = c
                    switch c {
                    case let .legacy(json), let .opened(json): loaded = try JSONDecoder().decode(PrivacyState.self, from: json)
                    case .otherKey: loaded = nil
                    }
                } catch {
                    throw CorruptState(message: "this wallet's private data (\(Self.stateFile)) is unreadable: \(error.localizedDescription)")
                }
            }
        }
        state = loaded ?? PrivacyState()
        noteTree = MerkleTree(store: noteNodes, size: state.notesNext)
        identityTree = MerkleTree(store: identityNodes, size: state.identityNext)
        stakeTree = MerkleTree(store: stakeNodes, size: state.stakeNext)
        switch contents {
        // Sealed under an earlier install's key (the vault, and with it the
        // key, was made anew): unreadable, and everything in it is found again
        // by a sync from the mnemonic. Its trees go too.
        case .otherKey:
            noteTree.clear(); identityTree.clear(); stakeTree.clear()
            try save()
        // Plaintext from before sealing: sealed now, not at some later save.
        case .legacy where key != nil:
            try save()
        default:
            break
        }
    }

    public static func memory() -> PrivacyStore { try! PrivacyStore(dir: nil) } // no file: nothing to fail

    private final class WeakStore { weak var store: PrivacyStore?; init(_ s: PrivacyStore) { store = s } }

    /// Open stores by directory. `sharedStores` holds them while a session
    /// is open; `lockAll` lets go of it, and only `liveStores` (weak) still
    /// finds one a task finishing after the lock holds, so the next unlock
    /// reopens that same instance and never a second one beside it. One
    /// nothing holds is gone, key copy and decrypted state with it.
    nonisolated(unsafe) private static var sharedStores: [String: PrivacyStore] = [:]
    nonisolated(unsafe) private static var liveStores: [String: WeakStore] = [:]
    private static let sharedLock = NSLock()

    /// The process's one store for a wallet's directory: two
    /// instances on one directory each save their whole state over the
    /// other's. Every app path opens stores through this.
    public static func shared(root: URL, walletID: String, key dataKey: Data) throws -> PrivacyStore {
        let key = root.appendingPathComponent("privacy").appendingPathComponent(walletID).standardizedFileURL.path
        sharedLock.lock(); defer { sharedLock.unlock() }
        if let s = sharedStores[key] { return s }
        let s = try liveStores[key]?.store ?? open(root: root, walletID: walletID, key: dataKey)
        sharedStores[key] = s
        liveStores[key] = WeakStore(s)
        return s
    }

    /// At lock: the process stops holding any store (see `sharedStores`).
    public static func lockAll() {
        sharedLock.lock(); defer { sharedLock.unlock() }
        sharedStores = [:]
        liveStores = liveStores.filter { $0.value.store != nil }
    }

    /// Seals every wallet's plaintext state.json from before sealing under
    /// `key`, not just the stores the user opens again: a wallet never
    /// selected after the upgrade would otherwise keep its notes,
    /// registration and handle in plaintext. The store id (the directory
    /// name) is all the seal's AAD needs. A store open in this process is
    /// skipped (opening one seals it); the lock is held per directory, so
    /// none opens mid-seal. The plaintext is replaced atomically: its file
    /// is unlinked under its per-file Data Protection key, which is what
    /// erases it. Returns how many were sealed; one that fails is left for
    /// the next unlock.
    @discardableResult
    public static func sealLegacy(root: URL, key: Data) -> Int {
        let top = root.appendingPathComponent("privacy")
        let fm = FileManager.default
        guard let dirs = try? fm.contentsOfDirectory(at: top, includingPropertiesForKeys: [.isDirectoryKey]) else { return 0 }
        var sealed = 0
        for d in dirs where (try? d.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
            let url = d.appendingPathComponent(stateFile)
            guard fm.fileExists(atPath: url.path) else { continue }
            sharedLock.lock()
            defer { sharedLock.unlock() }
            let path = d.standardizedFileURL.path
            if sharedStores[path] != nil || liveStores[path]?.store != nil { continue }
            let id = d.lastPathComponent
            guard let plain = try? Data(contentsOf: url), case .legacy(_)? = (try? StateSeal.open(plain, key: key, walletID: id)),
                  let data = try? StateSeal.seal(plain, key: key, walletID: id),
                  (try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil
            else { continue }
            sealed += 1
        }
        return sealed
    }

    /// `key`: the install's data key (WalletStore.Opened.dataKey), which seals state.json.
    public static func open(root: URL, walletID: String, key: Data) throws -> PrivacyStore {
        let top = root.appendingPathComponent("privacy")
        let d = top.appendingPathComponent(walletID)
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        // The notes, trees and registration are derivable from the
        // mnemonic and the chain; a device backup (iCloud, iTunes/Finder)
        // would only carry this wallet's private history off the device.
        excludeFromBackup(top)
        excludeFromBackup(d)
        return try PrivacyStore(dir: d, key: key, walletID: walletID)
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
        liveStores = liveStores.filter { $0.key != prefix && !$0.key.hasPrefix(prefix + "/") }
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
        guard let key else { throw SaveFailed(message: "a stored wallet's private data needs the data key to be saved") }
        do {
            let data = try StateSeal.seal(try JSONEncoder().encode(state), key: key, walletID: walletID)
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
        s.groundworksSplit = old.groundworksSplit
        s.groundworksChosen = old.groundworksChosen
        if old.chainID == chainID && old.genesis == genesis {
            s.pendingUnbonds = old.pendingUnbonds
            // Every generation's slot: registrations (verified ones), handles, splits, moves.
            Self.keepSlots(old, &s) { $0.verified }
            s.pendingRegistration = old.pendingRegistration
            s.registrationKeepUntil = old.registrationKeepUntil
            s.stakeVotes = old.stakeVotes
            s.groundworksExpiresAt = old.groundworksExpiresAt
            s.labelWindowSeconds = old.labelWindowSeconds
            s.claimedDays = old.claimedDays
            // What it sent and expects: the resync finds the notes again and folds them in.
            s.activity = old.activity
            Self.keepHandleState(old, &s)
            // Notes a tx in flight spends stay unspendable through the resync.
            s.carriedMarks = old.carriedMarks
            for n in old.notes where n.unspent { if let at = n.pendingAt { s.carriedMarks[n.nf.hex] = CarriedMark(at: at, until: n.pendingUntil, tx: n.pendingTx) } }
            for n in old.stakeNotes where n.unspent { if let at = n.pendingAt { s.carriedMarks[n.nf.hex] = CarriedMark(at: at, until: n.pendingUntil, tx: n.pendingTx) } }
        } else if old.chainID == nil {
            // Never synced: what a switch moved to this identity was
            // recorded for the chain the app follows (PrivacyWallet.recordIncoming).
            Self.keepSlots(old, &s) { _ in false }
            Self.keepHandleState(old, &s)
            // A gas grant asked for before the first sync is still expected.
            s.activity = old.activity
        }
        state = s
        try save()
    }

    /// What a reset keeps of the moves and state records: the records already
    /// applied are not applied again over what the wallet did since (a resync
    /// reads them from the start), and moves in flight stay in flight.
    private static func keepHandleState(_ old: PrivacyState, _ s: inout PrivacyState) {
        s.voidRecordHeights = old.voidRecordHeights
        s.verifiedHeight = old.verifiedHeight
    }

    /// Every generation's slot (what each identity holds and its moves, the
    /// records applied), the generation the wallet acts as and its floor; a
    /// registration only where `keepIdentity`.
    private static func keepSlots(_ old: PrivacyState, _ s: inout PrivacyState, _ keepIdentity: (IdentityRecord) -> Bool) {
        s.generation = old.generation
        s.generationFloor = old.generationFloor
        for (g, t0) in old.identities {
            var t = t0
            if let id = t.identity, !keepIdentity(id) { t.identity = nil }
            s.identities[g] = t
        }
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
        s.groundworksSplit = old.groundworksSplit
        s.groundworksChosen = old.groundworksChosen
        // The registration stays: every generation's, and which the wallet acts as.
        s.generation = old.generation
        s.generationFloor = old.generationFloor
        for (g, t) in old.identities { if let id = t.identity { s.withSlot(g) { $0.identity = id } } }
        s.pendingRegistration = old.pendingRegistration
        s.pendingRegistration?.failure = nil
        s.registrationKeepUntil = old.registrationKeepUntil
        state = s
        try save()
    }
}

