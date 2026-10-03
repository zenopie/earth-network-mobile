import Foundation

/// The privacy indexer's streams (backend routers/privacy.py). Ports
/// `privacy/sync/Indexer.kt`. Full ranges only: every request is addressed
/// by a position, index or height, never by anything derived from this
/// wallet's keys, so the server learns nothing about which notes or leaf are
/// ours.
public protocol PrivacyIndexer: Sendable {
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
    public init(chainID: String?, syncedHeight: UInt64, syncedTime: Int64?, notes: UInt64, identityLeaves: UInt64, halted: String?) {
        self.chainID = chainID; self.syncedHeight = syncedHeight; self.syncedTime = syncedTime
        self.notes = notes; self.identityLeaves = identityLeaves; self.halted = halted
    }
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
/// `amount` and stake pc `spc` and no ciphertext; a note a stake proof
/// created carries a ciphertext and none of the three.
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

/// `PrivacyIndexer` over HTTP.
public struct HTTPPrivacyIndexer: PrivacyIndexer {
    public let base: URL
    private let session: URLSession

    public init(base: URL = Constants.backendBaseURL) {
        self.base = base
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 120
        // URLSession inflates gzip itself; asking for it keeps a full sync small.
        config.httpAdditionalHeaders = ["Accept-Encoding": "gzip"]
        session = URLSession(configuration: config)
    }

    public enum Error: Swift.Error { case http(Int, String), notJSON }

    private func get(_ path: String) async throws -> JSON {
        let url = URL(string: base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path)!
        let (data, response) = try await session.data(from: url)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200 ... 299).contains(code) else { throw Error.http(code, String(decoding: data, as: UTF8.self)) }
        guard let o = try? JSONSerialization.jsonObject(with: data) else { throw Error.notJSON }
        return JSON(o)
    }

    private func q(_ name: String, _ v: Int?) -> String { v.map { "&\(name)=\($0)" } ?? "" }

    public func status() async throws -> IndexerStatus {
        let j = try await get("/privacy/status")
        return IndexerStatus(
            chainID: j.chain_id.string.flatMap { $0.isEmpty ? nil : $0 },
            syncedHeight: j.synced_height.uint64(default: 0),
            syncedTime: j.synced_time.int64,
            notes: j.notes.uint64(default: 0),
            identityLeaves: j.identity_leaves.uint64(default: 0),
            halted: j.halted.string
        )
    }

    public func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
        try Self.parseNotes(await get("/privacy/notes?from_pos=\(fromPos)\(q("limit", limit))"))
    }

    public func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        try Self.parseHeights(await get("/privacy/nullifiers?from_height=\(fromHeight)\(q("limit", limit))")) { try Fr(hex: $0.string ?? "") }
    }

    public func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage {
        try Self.parseIdentity(await get("/privacy/identity?from_index=\(fromIndex)\(q("limit", limit))"))
    }

    public func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        try Self.parseHeights(await get("/privacy/identity/zeroed?from_height=\(fromHeight)\(q("limit", limit))")) { $0.uint64(default: 0) }
    }

    public func rootsLatest() async throws -> LatestRoots { try Self.parseRoots(await get("/privacy/roots/latest")) }

    public func rates(epoch: UInt64?) async throws -> [RateRow] {
        let j = try await get("/privacy/rates" + (epoch.map { "?epoch=\($0)" } ?? ""))
        return j.rates.array.map { r in
            RateRow(validator: r[0].string(default: ""), rate: r[1].string(default: "0"), supply: r[2].string(default: "0"),
                    epoch: r[3].uint64, height: r[4].uint64(default: 0))
        }
    }

    public func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage {
        try Self.parseStakeNotes(await get("/privacy/stake/notes?from_pos=\(fromPos)\(q("limit", limit))"))
    }

    public func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        try Self.parseHeights(await get("/privacy/stake/nullifiers?from_height=\(fromHeight)\(q("limit", limit))")) { try Fr(hex: $0.string ?? "") }
    }

    static func parseStakeNotes(_ j: JSON) throws -> StakeNotesPage {
        let rows = try j.notes.array.map { r in
            StakeNoteRow(position: r[0].uint64(default: 0), height: r[1].uint64(default: 0), cm: try Fr(hex: r[2].string ?? ""),
                         ciphertext: Data(base64Encoded: r[3].string ?? "") ?? Data(), denom: r[4].string, amount: r[5].uint64,
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
