import Foundation
@testable import EarthCore

// Indexers and HTTP endpoints the suites put between a wallet and FakeChain.

/// An indexer delegating to another; a test overrides what it needs.
final class WrappedIndexer: PrivacyIndexer, @unchecked Sendable {
    let inner: PrivacyIndexer
    var statuses = 0
    var statusOverride: (() throws -> IndexerStatus)?
    /// A page to serve instead (nil: the inner indexer's).
    var notesOverride: ((UInt64, Int?) throws -> NotesPage?)?
    var rootsOverride: ((LatestRoots) -> LatestRoots)?
    var identityOverride: ((UInt64, Int?) throws -> IdentityPage?)?
    var nullifiersOverride: ((HeightPage<Fr>) -> HeightPage<Fr>)?
    /// A nullifier page to serve instead, by its from height (nil: the inner indexer's).
    var nullifiersFromOverride: ((UInt64) -> HeightPage<Fr>?)?
    var stakeNullifiersFromOverride: ((UInt64) -> HeightPage<Fr>?)?
    /// Rewrites every identity page served.
    var identityMap: ((IdentityPage) -> IdentityPage)?

    init(_ inner: PrivacyIndexer) { self.inner = inner }

    func status() async throws -> IndexerStatus {
        statuses += 1
        if let o = statusOverride { return try o() }
        return try await inner.status()
    }

    func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
        if let o = notesOverride, let p = try o(fromPos, limit) { return p }
        return try await inner.notes(fromPos: fromPos, limit: limit)
    }

    func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        if let o = nullifiersFromOverride, let p = o(fromHeight) { return p }
        let p = try await inner.nullifiers(fromHeight: fromHeight, limit: limit)
        return nullifiersOverride?(p) ?? p
    }
    func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage {
        if let o = identityOverride, let p = try o(fromIndex, limit) { return p }
        let p = try await inner.identity(fromIndex: fromIndex, limit: limit)
        return identityMap?(p) ?? p
    }
    var identityZeroedOverride: ((UInt64) -> HeightPage<UInt64>?)?
    func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        if let o = identityZeroedOverride, let p = o(fromHeight) { return p }
        return try await inner.identityZeroed(fromHeight: fromHeight, limit: limit)
    }

    func rootsLatest() async throws -> LatestRoots {
        let r = try await inner.rootsLatest()
        return rootsOverride?(r) ?? r
    }

    func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage { try await inner.stakeNotes(fromPos: fromPos, limit: limit) }
    func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        if let o = stakeNullifiersFromOverride, let p = o(fromHeight) { return p }
        return try await inner.stakeNullifiers(fromHeight: fromHeight, limit: limit)
    }
    /// Rewrites every stake nullifier tree page served.
    var stakeNfLeavesMap: ((StakeNfLeavesPage) -> StakeNfLeavesPage)?
    func stakeNullifierLeaves(fromIndex: UInt64, limit: Int?) async throws -> StakeNfLeavesPage {
        nfLeafAsks.append((fromIndex, limit))
        let p = try await inner.stakeNullifierLeaves(fromIndex: fromIndex, limit: limit)
        return stakeNfLeavesMap?(p) ?? p
    }
    /// Rewrites every snapshot page served.
    var stakeSnapshotsMap: ((StakeSnapshotsPage) -> StakeSnapshotsPage)?
    /// Every stake nullifier tree page asked: (from_index, limit).
    var nfLeafAsks: [(UInt64, Int?)] = []
    func stakeSnapshots(fromHeight: UInt64, limit: Int?) async throws -> StakeSnapshotsPage {
        let p = try await inner.stakeSnapshots(fromHeight: fromHeight, limit: limit)
        return stakeSnapshotsMap?(p) ?? p
    }
}

/// Serves a body of `size` bytes in 64 KiB chunks.
final class BigBodyProtocol: URLProtocol {
    nonisolated(unsafe) static var size = 0

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let resp = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        var left = Self.size
        let chunk = Data(repeating: 0x20, count: 64 * 1024)
        while left > 0 {
            let n = min(left, chunk.count)
            client?.urlProtocol(self, didLoad: chunk.prefix(n))
            left -= n
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// A frozen view of the chain: what it served when `freeze` was called.
final class FrozenIndexer: PrivacyIndexer, @unchecked Sendable {
    let chain: FakeChain
    var notes = -1
    var height: UInt64 = 0
    var roots: LatestRoots?
    init(_ chain: FakeChain) { self.chain = chain }
    func freeze() async throws { notes = chain.notes.count; height = chain.height - 1; roots = try await chain.rootsLatest() }
    func thaw() { notes = -1; roots = nil }
    func status() async throws -> IndexerStatus { try await chain.status() }
    func notes(fromPos: UInt64, limit: Int?) async throws -> NotesPage {
        if notes < 0 { return try await chain.notes(fromPos: fromPos, limit: limit) }
        let rows = Array(chain.notes.prefix(notes).dropFirst(Int(fromPos)))
        return NotesPage(rows: rows, nextPos: fromPos + UInt64(rows.count), complete: false, syncedHeight: height)
    }
    func nullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        let p = try await chain.nullifiers(fromHeight: fromHeight, limit: limit)
        if notes < 0 { return p }
        return HeightPage(blocks: p.blocks.filter { $0.height <= height }, nextHeight: height + 1, complete: false, syncedHeight: height)
    }
    func identity(fromIndex: UInt64, limit: Int?) async throws -> IdentityPage { try await chain.identity(fromIndex: fromIndex, limit: limit) }
    func identityZeroed(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<UInt64> {
        try await chain.identityZeroed(fromHeight: fromHeight, limit: limit)
    }
    func rootsLatest() async throws -> LatestRoots { if let roots { return roots }; return try await chain.rootsLatest() }
    func stakeNotes(fromPos: UInt64, limit: Int?) async throws -> StakeNotesPage { try await chain.stakeNotes(fromPos: fromPos, limit: limit) }
    func stakeNullifiers(fromHeight: UInt64, limit: Int?) async throws -> HeightPage<Fr> {
        try await chain.stakeNullifiers(fromHeight: fromHeight, limit: limit)
    }
    func stakeNullifierLeaves(fromIndex: UInt64, limit: Int?) async throws -> StakeNfLeavesPage {
        try await chain.stakeNullifierLeaves(fromIndex: fromIndex, limit: limit)
    }
    func stakeSnapshots(fromHeight: UInt64, limit: Int?) async throws -> StakeSnapshotsPage {
        try await chain.stakeSnapshots(fromHeight: fromHeight, limit: limit)
    }
}

/// Serves the indexer: /privacy/status, then per the script (busy, redirect) a notes page.
final class ScriptedProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) static var busy = 0
    nonisolated(unsafe) static var busyCode = 503
    nonisolated(unsafe) static var redirect = false
    nonisolated(unsafe) static var sleeps: [UInt64] = []
    nonisolated(unsafe) static var hosts: [String] = []

    static func reset() { lock.lock(); busy = 0; busyCode = 503; redirect = false; sleeps = []; hosts = []; lock.unlock() }
    static func slept(_ ms: UInt64) { lock.lock(); sleeps.append(ms); lock.unlock() }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        Self.lock.lock()
        Self.hosts.append(url.host ?? "")
        let busy = Self.busy > 0 && url.path != "/privacy/status"
        if busy { Self.busy -= 1 }
        let code = Self.busyCode, redirect = Self.redirect
        Self.lock.unlock()
        func send(_ status: Int, _ headers: [String: String], _ body: Data) {
            let resp = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }
        if url.path == "/privacy/status" {
            send(200, ["Content-Type": "application/json"], Data(#"{"chain_id":"earth-1","genesis":"0123456789abcdef","base":"/privacy/earth-1/0123456789abcdef","synced_height":1,"halted":null}"#.utf8))
        } else if busy {
            send(code, code == 503 ? ["Retry-After": "3"] : [:], Data())
        } else if redirect {
            let to = URL(string: "https://elsewhere.test" + url.path)!
            let resp = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": to.absoluteString])!
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: to), redirectResponse: resp)
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        } else {
            send(200, ["Content-Type": "application/json"], Data(#"{"format":2,"fields":["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"],"notes":[],"next_pos":0,"complete":false,"synced_height":1}"#.utf8))
        }
    }

    override func stopLoading() {}
}

/// Serves /privacy/status with `base`, and an empty notes page for anything else; records every path asked.
final class StatusProtocol: URLProtocol {
    nonisolated(unsafe) static var base = ""
    nonisolated(unsafe) static var paths: [String] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let path = url.path + (url.query.map { "?" + $0 } ?? "")
        Self.paths.append(path)
        let body: [String: Any] = url.path == "/privacy/status"
            ? ["chain_id": "earth-1", "genesis": "0123456789abcdef", "base": Self.base, "synced_height": 1]
            : ["format": 2, "fields": ["position", "height", "cm", "ciphertext", "amount", "owner_pk", "rho", "rcm"],
               "notes": [], "next_pos": 0, "complete": true, "synced_height": 1]
        let resp = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
