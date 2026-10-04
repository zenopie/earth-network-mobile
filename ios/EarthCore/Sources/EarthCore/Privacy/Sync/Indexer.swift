import Foundation

/// The privacy indexer's streams (backend routers/privacy.py). Ports
/// `privacy/sync/Indexer.kt`. Full ranges only: every request is addressed
/// by a position, index or height, never by anything derived from this
/// wallet's keys, so the server learns nothing about which notes or leaf are
/// ours.
///
/// Streams live under a base keyed by the chain (`/privacy/<chain_id>/<genesis>`)
/// that `status` names; a stream call naming another chain fails with
/// `IndexerBaseMoved` (HTTP 404), and the caller reads `status` again.
public protocol PrivacyIndexer: Sendable {
    /// `/privacy/status`: the chain the index holds, its base, whether it halted. Also (re)selects the base.
    func status() async throws -> IndexerStatus
    func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage
    func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr>
    func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage
    func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64>
    func rootsLatest() async throws -> LatestRoots
    func rates(epoch: UInt64?) async throws -> [RateRow]
    /// x/shieldedstaking's stake note tree, by position.
    func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage
    /// Its spent nullifiers, by height.
    func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr>
    /// The stake nullifier tree's values by leaf index, in insertion order
    /// (leaf 1 is the first value; leaf 0, the sentinel, is never a row):
    /// what a stake vote rebuilds the snapshot's nullifier tree from.
    func stakeNullifierLeaves(fromIndex: UInt64, limit: Int?) async throws -> StakeNfLeavesPage
    /// Every proposal snapshot (what stake votes prove against), by height.
    func stakeSnapshots(fromHeight: UInt64, limit: Int?) async throws -> StakeSnapshotsPage
    /// The handle directory, whole (there is no endpoint for one handle): a
    /// snapshot of the chain's Handles query at one height, paged by place
    /// from `fromIndex` (aligned).
    func handles(fromIndex: Int64, limit: Int) async throws -> HandleDirectory.StreamPage
    /// The slash debt tree, whole, by leaf index (rows from leaf 1, each with
    /// its latest retained), its size and root as the indexer synced them.
    /// The wallet checks the rebuilt root against the
    /// chain's own Query/DebtTree before using a row.
    func debtRows(fromIndex: UInt64, limit: Int) async throws -> DebtRowsPage
}

public extension PrivacyIndexer {
    func handles(fromIndex: Int64, limit: Int) async throws -> HandleDirectory.StreamPage {
        throw PrivacyError("this indexer serves no handle directory")
    }

    func debtRows(fromIndex: UInt64, limit: Int) async throws -> DebtRowsPage {
        throw PrivacyError("this indexer serves no debt rows")
    }
}

/// /debt_rows: `rows` (leaf index, move key, retained), `size` the leaf count (sentinel included, 0 when empty), `root` at the synced height.
public struct DebtRowsPage: Sendable {
    public let rows: [(index: UInt64, key: Fr, retained: UInt64)]
    public let nextIndex: UInt64
    public let complete: Bool
    public let size: UInt64
    public let root: Fr?
    public init(rows: [(index: UInt64, key: Fr, retained: UInt64)], nextIndex: UInt64, complete: Bool, size: UInt64, root: Fr?) {
        self.rows = rows; self.nextIndex = nextIndex; self.complete = complete; self.size = size; self.root = root
    }
}

public struct IndexerStatus: Sendable {
    public let chainID: String?
    public let syncedHeight: UInt64
    public let syncedTime: Int64?
    public let notes: UInt64
    public let identityLeaves: UInt64
    public let halted: String?
    /// First 16 hex digits of the chain's first block hash: with chainID, which chain this is.
    public let genesis: String?
    /// `/privacy/<chain_id>/<genesis>`, nil until the indexer met its chain.
    public let base: String?
    public init(chainID: String?, syncedHeight: UInt64, syncedTime: Int64?, notes: UInt64, identityLeaves: UInt64, halted: String?,
                genesis: String? = nil, base: String? = nil) {
        self.chainID = chainID; self.syncedHeight = syncedHeight; self.syncedTime = syncedTime
        self.notes = notes; self.identityLeaves = identityLeaves; self.halted = halted
        self.genesis = genesis; self.base = base
    }
}

/// A stream request named a chain the indexer no longer holds (404): re-read `PrivacyIndexer.status`.
public struct IndexerBaseMoved: Swift.Error, LocalizedError {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/// The indexer refuses to serve trees it cannot vouch for (`/privacy/status` halted).
public struct IndexerHalted: Swift.Error, LocalizedError {
    public let reason: String
    public init(_ reason: String) { self.reason = reason }
    public var errorDescription: String? { "the privacy indexer has halted: \(reason)" }
}

/// A note tree leaf. A note the chain minted with an opening it chose
/// (MintOpenNote: the referral note a registration pays its referrer handle)
/// has no ciphertext and carries the opening from its `shielded_mint` event,
/// `ownerPK`, `rho` and `rcm`; every other row has them nil.
public struct NoteRow: Sendable {
    public let position: UInt64
    public let height: UInt64
    public let cm: Fr
    public let ciphertext: Data
    /// The public amount ("<value><denom>") of a shield or mint; nil for a transfer output.
    public let amount: String?
    public let ownerPK: Fr?
    public let rho: Fr?
    public let rcm: Fr?
    public init(position: UInt64, height: UInt64, cm: Fr, ciphertext: Data, amount: String?, ownerPK: Fr? = nil, rho: Fr? = nil, rcm: Fr? = nil) {
        self.position = position; self.height = height; self.cm = cm; self.ciphertext = ciphertext; self.amount = amount
        self.ownerPK = ownerPK; self.rho = rho; self.rcm = rcm
    }
}

public struct NotesPage: Sendable {
    public let rows: [NoteRow]
    public let nextPos: UInt64
    public let complete: Bool
    public let syncedHeight: UInt64
    public init(rows: [NoteRow], nextPos: UInt64, complete: Bool, syncedHeight: UInt64) {
        self.rows = rows; self.nextPos = nextPos; self.complete = complete; self.syncedHeight = syncedHeight
    }
}

public struct HeightPage<T: Sendable>: Sendable {
    public let blocks: [(height: UInt64, items: [T])]
    public let nextHeight: UInt64
    public let complete: Bool
    public let syncedHeight: UInt64
    public init(blocks: [(height: UInt64, items: [T])], nextHeight: UInt64, complete: Bool, syncedHeight: UInt64) {
        self.blocks = blocks; self.nextHeight = nextHeight; self.complete = complete; self.syncedHeight = syncedHeight
    }
}

/// An identity leaf; `time` is its block's time (unix seconds) when the indexer serves it (a fifth column), nil otherwise.
public struct IdentityRow: Sendable {
    public let index: UInt64
    public let height: UInt64
    public let leaf: Fr
    public let zeroedHeight: UInt64?
    public let time: UInt64?
    public init(index: UInt64, height: UInt64, leaf: Fr, zeroedHeight: UInt64?, time: UInt64? = nil) {
        self.index = index; self.height = height; self.leaf = leaf; self.zeroedHeight = zeroedHeight; self.time = time
    }

    public func with(time: UInt64?) -> IdentityRow { IdentityRow(index: index, height: height, leaf: leaf, zeroedHeight: zeroedHeight, time: time) }
}

public struct IdentityPage: Sendable {
    public let rows: [IdentityRow]
    public let nextIndex: UInt64
    public let size: UInt64
    public let syncedHeight: UInt64
    public init(rows: [IdentityRow], nextIndex: UInt64, size: UInt64, syncedHeight: UInt64) {
        self.rows = rows; self.nextIndex = nextIndex; self.size = size; self.syncedHeight = syncedHeight
    }
}

public struct RootRecord: Sendable {
    public let root: Fr
    public let treeSize: UInt64
    public let height: UInt64
    public let time: Int64
    public init(root: Fr, treeSize: UInt64, height: UInt64, time: Int64) {
        self.root = root; self.treeSize = treeSize; self.height = height; self.time = time
    }
}

public struct LatestRoots: Sendable {
    public let note: RootRecord?
    public let identity: RootRecord?
    public let syncedHeight: UInt64
    public let stake: RootRecord?
    public init(note: RootRecord?, identity: RootRecord?, syncedHeight: UInt64, stake: RootRecord? = nil) {
        self.note = note; self.identity = identity; self.syncedHeight = syncedHeight; self.stake = stake
    }
}

/// A stake tree leaf: every one a stake proof's output with its 201-byte
/// wallet stake ciphertext (the chain mints no stake note, so
/// no row has a public denom, amount or pc any more).
public struct StakeNoteRow: Sendable {
    public let position: UInt64
    public let height: UInt64
    public let cm: Fr
    public let ciphertext: Data
    public init(position: UInt64, height: UInt64, cm: Fr, ciphertext: Data) {
        self.position = position; self.height = height; self.cm = cm; self.ciphertext = ciphertext
    }
}

public struct StakeNotesPage: Sendable {
    public let rows: [StakeNoteRow]
    public let nextPos: UInt64
    public let complete: Bool
    public let syncedHeight: UInt64
    public init(rows: [StakeNoteRow], nextPos: UInt64, complete: Bool, syncedHeight: UInt64) {
        self.rows = rows; self.nextPos = nextPos; self.complete = complete; self.syncedHeight = syncedHeight
    }
}

/// `leaves`: (leaf index, value); `size` the tree's leaf count as the chain counts it (sentinel included, 0 when empty).
public struct StakeNfLeavesPage: Sendable {
    public let leaves: [(index: UInt64, value: Fr)]
    public let nextIndex: UInt64
    public let complete: Bool
    public let size: UInt64
    public let syncedHeight: UInt64
    public init(leaves: [(index: UInt64, value: Fr)], nextIndex: UInt64, complete: Bool, size: UInt64, syncedHeight: UInt64) {
        self.leaves = leaves; self.nextIndex = nextIndex; self.complete = complete; self.size = size; self.syncedHeight = syncedHeight
    }
}

/// A proposal snapshot: the stake note tree's root and size and the stake nullifier tree's (nil roots: none recorded).
public struct StakeSnapshotRow: Sendable, Equatable {
    public let height: UInt64
    public let proposalID: UInt64
    public let root: Fr?
    public let treeSize: UInt64
    public let nfRoot: Fr?
    public let nfSize: UInt64
    public init(height: UInt64, proposalID: UInt64, root: Fr?, treeSize: UInt64, nfRoot: Fr?, nfSize: UInt64) {
        self.height = height; self.proposalID = proposalID; self.root = root; self.treeSize = treeSize; self.nfRoot = nfRoot; self.nfSize = nfSize
    }
}

public struct StakeSnapshotsPage: Sendable {
    public let rows: [StakeSnapshotRow]
    public let nextHeight: UInt64
    public let complete: Bool
    public let syncedHeight: UInt64
    public init(rows: [StakeSnapshotRow], nextHeight: UInt64, complete: Bool, syncedHeight: UInt64) {
        self.rows = rows; self.nextHeight = nextHeight; self.complete = complete; self.syncedHeight = syncedHeight
    }
}

public struct RateRow: Sendable {
    public let validator: String
    public let rate: String
    public let supply: String
    public let epoch: UInt64?
    public let height: UInt64
}

/// `PrivacyIndexer` over HTTP. Only ever talks to `host`: the status's
/// `base` is accepted only as exactly `/privacy/<chain_id>/<genesis>` for
/// `chainID`, so a hostile status cannot point the stream requests
/// anywhere else, and no path ever traps building its URL.
public final class HTTPPrivacyIndexer: PrivacyIndexer, @unchecked Sendable {
    public let host: URL
    public let chainID: String
    private let session: URLSession
    private let lock = NSLock()
    private var _base: String?

    public convenience init(host: URL = Constants.backendBaseURL, chainID: String = Constants.chainID) {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 120
        // URLSession inflates gzip itself; asking for it keeps a full sync small.
        config.httpAdditionalHeaders = ["Accept-Encoding": "gzip"]
        self.init(host: host, chainID: chainID, configuration: config)
    }

    /// Waits between retries of a busy indexer (tests pass their own).
    private let sleep: @Sendable (UInt64) async throws -> Void

    init(host: URL, chainID: String, configuration: URLSessionConfiguration,
         sleep: @escaping @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0 * 1_000_000) }) {
        self.host = host
        self.chainID = chainID
        self.sleep = sleep
        session = EarthRest.session(configuration)
    }

    /// Retries of a busy (503/429) indexer before the request fails.
    public static let maxBusyRetries = 4

    /// Retry-After (seconds, when sent) or 2^(attempt-1) s, whichever is longer, at most 30 s; in milliseconds.
    public static func backoffMs(retryAfter: UInt64?, attempt: Int) -> UInt64 {
        Swift.min(Swift.max(Swift.min(retryAfter ?? 0, 30), UInt64(1) << UInt64(Swift.min(attempt - 1, 5))), 30) * 1000
    }

    public enum Error: Swift.Error { case http(Int, String), notJSON, noBase, badBase(String), badPath(String), busy(Int), badLimit(Int) }

    /// `base` is exactly `/privacy/<chain_id>/<genesis>` for the chain
    /// the wallet follows (`expected`), as the same status names it, with a
    /// chain id of [A-Za-z0-9._-] (at most 64, first alphanumeric) and a
    /// genesis of 16 lowercase hex digits. A nil chain id is refused.
    public static func validBase(_ base: String, expected: String, chainID: String?, genesis: String?) -> Bool {
        guard let chainID, let genesis, chainID == expected else { return false }
        let idChars = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
        let alnum = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        guard (1 ... 64).contains(chainID.count), chainID.allSatisfy(idChars.contains), alnum.contains(chainID.first!) else { return false }
        guard genesis.count == 16, genesis.allSatisfy(Set("0123456789abcdef").contains) else { return false }
        return base == "/privacy/\(chainID)/\(genesis)"
    }

    private var base: String? {
        get { lock.lock(); defer { lock.unlock() }; return _base }
        set { lock.lock(); _base = newValue; lock.unlock() }
    }

    /// `getOnce`, backing off while the indexer sheds load (/privacy
    /// answers 503 with Retry-After past its in-flight cap, 429 past a
    /// client's rate): Retry-After or 1, 2, 4, 8 s (at most 30), then an error.
    private func get(_ path: String) async throws -> JSON {
        var attempt = 0
        while true {
            do {
                return try await getOnce(path)
            } catch let Busy.shed(code, retryAfter) {
                attempt += 1
                if attempt > Self.maxBusyRetries { throw Error.busy(code) }
                try await sleep(Self.backoffMs(retryAfter: retryAfter, attempt: attempt))
            }
        }
    }

    private enum Busy: Swift.Error { case shed(Int, UInt64?) }

    private func getOnce(_ path: String) async throws -> JSON {
        guard path.hasPrefix("/"), !path.hasPrefix("//"),
              let url = URL(string: host.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path),
              url.scheme == host.scheme, url.host == host.host, url.port == host.port, url.user == nil else {
            throw Error.badPath(String(path.prefix(80)))
        }
        let (data, code, resp) = try await EarthRest.boundedResponse(session, URLRequest(url: url))
        if code == 404 { throw IndexerBaseMoved("indexer \(path): 404 \(String(decoding: data.prefix(200), as: UTF8.self))") }
        if code == 503 || code == 429 {
            throw Busy.shed(code, resp?.value(forHTTPHeaderField: "Retry-After").flatMap { UInt64($0.trimmingCharacters(in: .whitespaces)) })
        }
        guard (200 ... 299).contains(code) else { throw Error.http(code, String(decoding: data.prefix(200), as: UTF8.self)) }
        guard let o = try? EarthRest.parseJSON(data) else { throw Error.notJSON }
        return JSON(o)
    }

    /// A stream path under the current base (from `status`, read first when unknown).
    private func stream(_ path: String) async throws -> JSON {
        var b = base
        if b == nil { b = try await status().base }
        guard let b else { throw Error.noBase }
        return try await get(b + path)
    }

    private func q(_ name: String, _ v: Int?) throws -> String {
        // The backend serves only its fixed page sizes (its paging rule); anything else is a 400.
        if let v, !WalletSync.pageSizes.contains(v) { throw Error.badLimit(v) }
        return v.map { "&\(name)=\($0)" } ?? ""
    }

    public func status() async throws -> IndexerStatus {
        let j = try await get("/privacy/status")
        func str(_ x: JSON) -> String? { x.string.flatMap { $0.isEmpty ? nil : $0 } }
        let st = IndexerStatus(
            chainID: str(j.chain_id),
            syncedHeight: j.synced_height.uint64(default: 0),
            syncedTime: j.synced_time.int64,
            notes: j.notes.uint64(default: 0),
            identityLeaves: j.identity_leaves.uint64(default: 0),
            halted: str(j.halted),
            genesis: str(j.genesis),
            base: str(j.base)
        )
        // Refused before it is ever used: a base that is not exactly this
        // chain's (another chain, a host, a scheme, '..', '@', '?').
        if let b = st.base, !Self.validBase(b, expected: chainID, chainID: st.chainID, genesis: st.genesis) {
            throw Error.badBase(String(b.prefix(80)))
        }
        base = st.base
        return st
    }

    public func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
        try Self.parseNotes(await stream("/notes?from_pos=\(fromPos)\(try q("limit", limit))"))
    }

    public func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        try Self.parseHeights(await stream("/nullifiers?from_height=\(fromHeight)\(try q("limit", limit))")) { try Fr(hex: $0.string ?? "") }
    }

    public func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage {
        try Self.parseIdentity(await stream("/identity?from_index=\(fromIndex)\(try q("limit", limit))"))
    }

    public func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        try Self.parseHeights(await stream("/identity/zeroed?from_height=\(fromHeight)\(try q("limit", limit))")) { $0.uint64(default: 0) }
    }

    public func rootsLatest() async throws -> LatestRoots { try Self.parseRoots(await stream("/roots/latest")) }

    public func rates(epoch: UInt64?) async throws -> [RateRow] {
        let j = try await stream("/rates" + (epoch.map { "?epoch=\($0)" } ?? ""))
        return j.rates.array.map { r in
            RateRow(validator: r[0].string(default: ""), rate: r[1].string(default: "0"), supply: r[2].string(default: "0"),
                    epoch: r[3].uint64, height: r[4].uint64(default: 0))
        }
    }

    public func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage {
        try Self.parseStakeNotes(await stream("/stake/notes?from_pos=\(fromPos)\(try q("limit", limit))"))
    }

    public func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        try Self.parseHeights(await stream("/stake/nullifiers?from_height=\(fromHeight)\(try q("limit", limit))")) { try Fr(hex: $0.string ?? "") }
    }

    public func stakeNullifierLeaves(fromIndex: UInt64, limit: Int?) async throws -> StakeNfLeavesPage {
        try Self.parseStakeNfLeaves(await stream("/stake/nullifier-tree?from_index=\(fromIndex)\(try q("limit", limit))"))
    }

    public func stakeSnapshots(fromHeight: UInt64, limit: Int?) async throws -> StakeSnapshotsPage {
        try Self.parseStakeSnapshots(await stream("/stake/snapshots?from_height=\(fromHeight)\(try q("limit", limit))"))
    }

    public func handles(fromIndex: Int64, limit: Int) async throws -> HandleDirectory.StreamPage {
        Self.parseHandles(try await stream("/handles?from_index=\(fromIndex)\(try q("limit", limit))"))
    }

    public func debtRows(fromIndex: UInt64, limit: Int) async throws -> DebtRowsPage {
        try Self.parseDebtRows(await stream("/debt_rows?from_index=\(fromIndex)\(try q("limit", limit))"))
    }

    /// /debt_rows: rows [index, key (hex), retained, height, updated_height]; size, root (hex).
    static func parseDebtRows(_ j: JSON) throws -> DebtRowsPage {
        let rows = try j.rows.array.map { r -> (index: UInt64, key: Fr, retained: UInt64) in
            guard let i = r[0].uint64, let ret = r[2].uint64, ret <= UInt64(Int64.max) else { throw PrivacyError("a debt row without its index or retained") }
            return (i, try Fr(hex: r[1].string ?? ""), ret)
        }
        let rootHex = j.root.string ?? ""
        return DebtRowsPage(rows: rows, nextIndex: j.next_index.uint64(default: 0), complete: j.complete.bool(default: false),
                            size: j.size.uint64(default: 0), root: rootHex.count == 64 ? try Fr(hex: rootHex) : nil)
    }

    /// /handles: rows [handle, address, status, expires_at, renewal_until, owner], the
    /// snapshot's height and size. owner (64 hex, the chain's
    /// HandleEntry.owner) is optional: a row without it says no owner.
    public static func parseHandles(_ j: JSON) -> HandleDirectory.StreamPage {
        let rows = j.handles.array.map { r in
            HandleEntry(handle: r[0].string ?? "", address: r[1].string ?? "", status: r[2].string ?? "",
                        expiresAt: r[3].int64 ?? 0, renewalUntil: r[4].int64 ?? 0, owner: Handles.owner(r[5].string))
        }
        return HandleDirectory.StreamPage(handles: rows, height: j.height.int64, size: j.size.int64(default: 0),
                                          fromIndex: j.from_index.int64(default: 0), lastPage: j.last_page.bool(default: false))
    }

    /// /stake/nullifier-tree: rows [index, nullifier (hex), height].
    static func parseStakeNfLeaves(_ j: JSON) throws -> StakeNfLeavesPage {
        let leaves = try j.nullifiers.array.map { r -> (index: UInt64, value: Fr) in
            guard let i = r[0].uint64 else { throw PrivacyError("a stake nullifier row without its index") }
            return (i, try Fr(hex: r[1].string ?? ""))
        }
        return StakeNfLeavesPage(leaves: leaves, nextIndex: j.next_index.uint64(default: 0), complete: j.complete.bool(default: false),
                                 size: j.size.uint64(default: 0), syncedHeight: j.synced_height.uint64(default: 0))
    }

    /// /stake/snapshots: rows [height, proposal_id, root, tree_size, nf_root, nf_size] ("" for a root not recorded).
    static func parseStakeSnapshots(_ j: JSON) throws -> StakeSnapshotsPage {
        func opt(_ x: JSON) throws -> Fr? { let h = x.string ?? ""; return h.isEmpty ? nil : try Fr(hex: h) }
        let rows = try j.snapshots.array.map { r in
            StakeSnapshotRow(height: r[0].uint64(default: 0), proposalID: r[1].uint64(default: 0), root: try opt(r[2]),
                             treeSize: r[3].uint64(default: 0), nfRoot: try opt(r[4]), nfSize: r[5].uint64(default: 0))
        }
        return StakeSnapshotsPage(rows: rows, nextHeight: j.next_height.uint64(default: 0), complete: j.complete.bool(default: false),
                                  syncedHeight: j.synced_height.uint64(default: 0))
    }

    static func parseStakeNotes(_ j: JSON) throws -> StakeNotesPage {
        let rows = try j.notes.array.map { r in
            StakeNoteRow(position: r[0].uint64(default: 0), height: r[1].uint64(default: 0), cm: try Fr(hex: r[2].string ?? ""),
                         ciphertext: Data(base64Encoded: r[3].string ?? "") ?? Data())
        }
        return StakeNotesPage(rows: rows, nextPos: j.next_pos.uint64(default: 0), complete: j.complete.bool(default: false),
                              syncedHeight: j.synced_height.uint64(default: 0))
    }

    /// The notes stream format this wallet reads (backend README "Note stream format 2").
    public static let noteFormat = 2
    static let noteFields = ["position", "height", "cm", "ciphertext", "amount", "owner_pk", "rho", "rcm"]

    /// /notes, format 2: columns by name from `fields` (position, height, cm,
    /// ciphertext, amount, owner_pk, rho, rcm). `ciphertext` is null exactly
    /// for an open note, whose owner_pk, rho and rcm (hex) are then all set;
    /// they are null on every other row. A page of another format (an old
    /// backend) or one missing a column is refused.
    static func parseNotes(_ j: JSON) throws -> NotesPage {
        let format = j.format.int64 ?? 1
        guard format == Int64(noteFormat) else { throw PrivacyError("the privacy indexer serves notes format \(format), not \(noteFormat); it needs an update") }
        let fields = j.fields.array.compactMap(\.string)
        var col: [String: Int] = [:]
        for name in noteFields {
            guard let i = fields.firstIndex(of: name) else { throw PrivacyError("the notes page has no \(name) column") }
            col[name] = i
        }
        let rows = try j.notes.array.map { r -> NoteRow in
            func v(_ name: String) -> JSON { r[col[name]!] }
            func hex(_ name: String) throws -> Fr? { v(name).exists ? try Fr(hex: v(name).string ?? "") : nil }
            let opening = [try hex("owner_pk"), try hex("rho"), try hex("rcm")]
            let open = !v("ciphertext").exists
            if opening.contains(where: { $0 == nil }), opening.contains(where: { $0 != nil }) { throw PrivacyError("a note row carries part of an opening") }
            if open != (opening[0] != nil) {
                throw PrivacyError("a note row has \(open ? "neither a ciphertext nor an opening" : "both a ciphertext and an opening")")
            }
            if open, !v("amount").exists { throw PrivacyError("an open note row has no amount") }
            return NoteRow(position: v("position").uint64(default: 0), height: v("height").uint64(default: 0), cm: try Fr(hex: v("cm").string ?? ""),
                           ciphertext: open ? Data() : Data(base64Encoded: v("ciphertext").string ?? "") ?? Data(), amount: v("amount").string,
                           ownerPK: opening[0], rho: opening[1], rcm: opening[2])
        }
        return NotesPage(rows: rows, nextPos: j.next_pos.uint64(default: 0), complete: j.complete.bool(default: false),
                         syncedHeight: j.synced_height.uint64(default: 0))
    }

    static func parseHeights<T>(_ j: JSON, _ item: (JSON) throws -> T) throws -> HeightPage<T> {
        let blocks = try j.blocks.array.map { b in (height: b[0].uint64(default: 0), items: try b[1].array.map(item)) }
        return HeightPage(blocks: blocks, nextHeight: j.next_height.uint64(default: 0), complete: j.complete.bool(default: false),
                          syncedHeight: j.synced_height.uint64(default: 0))
    }

    static func parseIdentity(_ j: JSON) throws -> IdentityPage {
        let rows = try j.leaves.array.map { r in
            IdentityRow(index: r[0].uint64(default: 0), height: r[1].uint64(default: 0), leaf: try Fr(hex: r[2].string ?? ""), zeroedHeight: r[3].uint64,
                        time: r.array.count > 4 ? r[4].uint64 : nil)
        }
        return IdentityPage(rows: rows, nextIndex: j.next_index.uint64(default: 0), size: j.size.uint64(default: 0),
                            syncedHeight: j.synced_height.uint64(default: 0))
    }

    static func parseRoots(_ j: JSON) throws -> LatestRoots {
        func root(_ r: JSON) throws -> RootRecord? {
            guard r.exists else { return nil }
            return RootRecord(root: try Fr(hex: r.root.string ?? ""), treeSize: r.tree_size.uint64(default: 0),
                              height: r.height.uint64(default: 0), time: r.time.int64(default: 0))
        }
        return LatestRoots(note: try root(j.note), identity: try root(j.identity), syncedHeight: j.synced_height.uint64(default: 0),
                           stake: try root(j.stake))
    }
}
