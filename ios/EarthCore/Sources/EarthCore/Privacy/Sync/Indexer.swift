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

public struct NoteRow: Sendable {
    public let position: UInt64
    public let height: UInt64
    public let cm: Fr
    public let ciphertext: Data
    /// The public amount ("<value><denom>") of a shield or mint; nil for a transfer output.
    public let amount: String?
    public init(position: UInt64, height: UInt64, cm: Fr, ciphertext: Data, amount: String?) {
        self.position = position; self.height = height; self.cm = cm; self.ciphertext = ciphertext; self.amount = amount
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

public struct IdentityRow: Sendable {
    public let index: UInt64
    public let height: UInt64
    public let leaf: Fr
    public let zeroedHeight: UInt64?
    public init(index: UInt64, height: UInt64, leaf: Fr, zeroedHeight: UInt64?) {
        self.index = index; self.height = height; self.leaf = leaf; self.zeroedHeight = zeroedHeight
    }
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

/// A stake tree leaf. A note the chain minted carries its public `denom`,
/// `amount` and stake pc `spc` and its blind stake ciphertext (177 bytes); a
/// note a stake proof created carries the wallet stake ciphertext and none of
/// the three.
public struct StakeNoteRow: Sendable {
    public let position: UInt64
    public let height: UInt64
    public let cm: Fr
    public let ciphertext: Data
    public let denom: String?
    public let amount: UInt64?
    public let spc: Fr?
    public init(position: UInt64, height: UInt64, cm: Fr, ciphertext: Data, denom: String?, amount: UInt64?, spc: Fr?) {
        self.position = position; self.height = height; self.cm = cm; self.ciphertext = ciphertext
        self.denom = denom; self.amount = amount; self.spc = spc
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

public struct RateRow: Sendable {
    public let validator: String
    public let rate: String
    public let supply: String
    public let epoch: UInt64?
    public let height: UInt64
}

/// `PrivacyIndexer` over HTTP. Only ever talks to `host`: the status's
/// `base` is accepted only as exactly `/privacy/<chain_id>/<genesis>` for
/// `chainID` (K10), so a hostile status cannot point the stream requests
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

    init(host: URL, chainID: String, configuration: URLSessionConfiguration) {
        self.host = host
        self.chainID = chainID
        session = URLSession(configuration: configuration)
    }

    public enum Error: Swift.Error { case http(Int, String), notJSON, noBase, badBase(String), badPath(String) }

    /// K10: `base` is exactly `/privacy/<chain_id>/<genesis>` for the chain
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

    private func get(_ path: String) async throws -> JSON {
        guard path.hasPrefix("/"), !path.hasPrefix("//"),
              let url = URL(string: host.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path),
              url.scheme == host.scheme, url.host == host.host, url.port == host.port, url.user == nil else {
            throw Error.badPath(String(path.prefix(80)))
        }
        let (data, code) = try await EarthRest.boundedData(session, URLRequest(url: url))
        if code == 404 { throw IndexerBaseMoved("indexer \(path): 404 \(String(decoding: data.prefix(200), as: UTF8.self))") }
        guard (200 ... 299).contains(code) else { throw Error.http(code, String(decoding: data.prefix(200), as: UTF8.self)) }
        guard let o = try? JSONSerialization.jsonObject(with: data) else { throw Error.notJSON }
        return JSON(o)
    }

    /// A stream path under the current base (from `status`, read first when unknown).
    private func stream(_ path: String) async throws -> JSON {
        var b = base
        if b == nil { b = try await status().base }
        guard let b else { throw Error.noBase }
        return try await get(b + path)
    }

    private func q(_ name: String, _ v: Int?) -> String { v.map { "&\(name)=\($0)" } ?? "" }

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
        try Self.parseNotes(await stream("/notes?from_pos=\(fromPos)\(q("limit", limit))"))
    }

    public func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        try Self.parseHeights(await stream("/nullifiers?from_height=\(fromHeight)\(q("limit", limit))")) { try Fr(hex: $0.string ?? "") }
    }

    public func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage {
        try Self.parseIdentity(await stream("/identity?from_index=\(fromIndex)\(q("limit", limit))"))
    }

    public func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        try Self.parseHeights(await stream("/identity/zeroed?from_height=\(fromHeight)\(q("limit", limit))")) { $0.uint64(default: 0) }
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
        try Self.parseStakeNotes(await stream("/stake/notes?from_pos=\(fromPos)\(q("limit", limit))"))
    }

    public func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        try Self.parseHeights(await stream("/stake/nullifiers?from_height=\(fromHeight)\(q("limit", limit))")) { try Fr(hex: $0.string ?? "") }
    }

    static func parseStakeNotes(_ j: JSON) throws -> StakeNotesPage {
        let rows = try j.notes.array.map { r in
            StakeNoteRow(position: r[0].uint64(default: 0), height: r[1].uint64(default: 0), cm: try Fr(hex: r[2].string ?? ""),
                         ciphertext: Data(base64Encoded: r[3].string ?? "") ?? Data(), denom: r[4].string,
                         amount: r[5].uint64.flatMap { $0 <= UInt64(Int64.max) ? $0 : nil },
                         spc: try r[6].string.map { try Fr(hex: $0) })
        }
        return StakeNotesPage(rows: rows, nextPos: j.next_pos.uint64(default: 0), complete: j.complete.bool(default: false),
                              syncedHeight: j.synced_height.uint64(default: 0))
    }

    static func parseNotes(_ j: JSON) throws -> NotesPage {
        let rows = try j.notes.array.map { r in
            NoteRow(position: r[0].uint64(default: 0), height: r[1].uint64(default: 0), cm: try Fr(hex: r[2].string ?? ""),
                    ciphertext: Data(base64Encoded: r[3].string ?? "") ?? Data(), amount: r[4].string)
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
            IdentityRow(index: r[0].uint64(default: 0), height: r[1].uint64(default: 0), leaf: try Fr(hex: r[2].string ?? ""), zeroedHeight: r[3].uint64)
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
