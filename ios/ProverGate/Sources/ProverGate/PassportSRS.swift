import CryptoKit
import ProverGateCore
import Foundation

/// The passport circuits' SRS as local files, one per size tier.
///
/// The privacy circuits' SRS ships in the app (32,769 points, 2 MiB), so a
/// private proof never fetches anything. The passport circuits need
/// 2^18 + 1 points (16 MiB, nearly every passport), 2^19 + 1 (32 MiB) or
/// 2^20 + 1 (64 MiB): too large to bundle. Each is the first points of
/// Aztec's transcript (crs.aztec.network/g1.dat, a byte range), checked
/// against the hash pinned in passport_variants.json, kept in Application
/// Support (excluded from backups). The 2^18 tier is fetched at launch; a
/// passport whose circuit needs a larger one fetches it before proving. A
/// larger file serves a smaller circuit. Ports `passport/PassportSrs.kt`.
public enum PassportSRS {
    /// One tier: a 2^log2 circuit's setup, `points` points of 64 bytes.
    public struct Tier: Sendable {
        public let log2: Int
        public let points: UInt64
        public let sha256: String
        public init(log2: Int, points: UInt64, sha256: String) {
            self.log2 = log2
            self.points = points
            self.sha256 = sha256
        }
    }

    public enum Failure: Error, LocalizedError {
        case noTier(Int)
        case download(String)
        public var errorDescription: String? {
            switch self {
            case let .noTier(n): "no proving setup covers a 2^\(n) circuit"
            case let .download(why): "the proving setup could not be fetched (\(why)); check the connection and try again"
            }
        }
    }

    static let source = URL(string: "https://crs.aztec.network/g1.dat")!

    static func file(_ tier: Tier) -> URL? {
        (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))?
            .appendingPathComponent("srs/bn254_g1_\(tier.points).dat")
    }

    static func complete(_ tier: Tier) -> String? {
        guard let f = file(tier), let size = (try? FileManager.default.attributesOfItem(atPath: f.path))?[.size] as? NSNumber,
              size.uint64Value == tier.points * 64 else { return nil }
        return f.path
    }

    /// The smallest complete local file covering a 2^log2 circuit, or nil.
    public static func path(covering log2: Int, tiers: [Tier]) -> String? {
        tiers.filter { $0.log2 >= log2 }.sorted { $0.log2 < $1.log2 }.lazy.compactMap(complete).first
    }

    /// Fetches `tier` if missing; best effort (launch prefetch).
    public static func prefetch(_ tier: Tier) async {
        _ = try? await fetch(tier)
    }

    /// The local file for a 2^log2 circuit, fetching the smallest tier that
    /// covers it when no local file does.
    public static func ensure(covering log2: Int, tiers: [Tier]) async throws -> String {
        if let p = path(covering: log2, tiers: tiers) { return p }
        guard let tier = tiers.filter({ $0.log2 >= log2 }).min(by: { $0.log2 < $1.log2 }) else { throw Failure.noTier(log2) }
        return try await fetch(tier)
    }

    /// Streamed to a staged file and hashed as it comes, never more than the
    /// range's bytes read (a server ignoring Range, or streaming without end,
    /// is cut off there); a redirect is not followed. A failure leaves
    /// nothing behind.
    static func fetch(_ tier: Tier) async throws -> String {
        if let p = complete(tier) { return p }
        guard let dest = file(tier) else { throw Failure.download("no Application Support directory") }
        let want = tier.points * 64
        var r = URLRequest(url: source)
        r.setValue("bytes=0-\(want - 1)", forHTTPHeaderField: "Range")
        r.timeoutInterval = 120
        let dir = dest.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var d = dir
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? d.setResourceValues(v)
        let staged = dir.appendingPathComponent(dest.lastPathComponent + ".part")
        try? FileManager.default.removeItem(at: staged)
        guard FileManager.default.createFile(atPath: staged.path, contents: nil), let out = try? FileHandle(forWritingTo: staged) else {
            throw Failure.download("cannot write")
        }
        var ok = false
        defer {
            try? out.close()
            if !ok { try? FileManager.default.removeItem(at: staged) }
        }
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirects(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (bytes, resp) = try await session.bytes(for: r)
        guard let status = (resp as? HTTPURLResponse)?.statusCode, status == 206 || status == 200 else {
            throw Failure.download("HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)")
        }
        var hasher = SHA256()
        var n: UInt64 = 0
        var buf = Data()
        buf.reserveCapacity(1 << 20)
        for try await b in bytes {
            buf.append(b)
            if buf.count == 1 << 20 || n + UInt64(buf.count) == want {
                hasher.update(data: buf); try out.write(contentsOf: buf)
                n += UInt64(buf.count); buf.removeAll(keepingCapacity: true)
                if n == want { break }
            }
        }
        guard n == want, hasher.finalize().map({ String(format: "%02x", $0) }).joined() == tier.sha256 else {
            throw Failure.download("it did not match its pinned hash")
        }
        try? out.synchronize()
        ok = true
        _ = try? FileManager.default.replaceItemAt(dest, withItemAt: staged)
        if !FileManager.default.fileExists(atPath: dest.path) { try? FileManager.default.moveItem(at: staged, to: dest) }
        guard let p = complete(tier) else { throw Failure.download("could not install it") }
        return p
    }

    final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest) async -> URLRequest? { nil }
    }
}

/// A passport variant's compiled circuit when the app does not bundle it:
/// fetched once from the manifest's download base (the backend's /circuits),
/// gzipped, inflated here, and kept only if it hashes to the variant's pinned
/// sha256. The server is trusted for availability only; the fetch names the
/// variant, which the registration makes public anyway. Ports
/// `passport/PassportCircuits.kt`.
public enum PassportCircuits {
    public enum Failure: Error, LocalizedError {
        case unavailable(String)
        public var errorDescription: String? {
            switch self {
            case let .unavailable(why): "this passport's circuit could not be fetched (\(why)); check the connection and try again"
            }
        }
    }

    static func file(_ id: String) -> URL? {
        (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))?
            .appendingPathComponent("circuits/\(id).json")
    }

    public static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// The cached circuit if it is there and intact, else nil.
    public static func cached(id: String, sha256 want: String) -> Data? {
        guard let f = file(id), let d = try? Data(contentsOf: f), sha256(d) == want else { return nil }
        return d
    }

    public static func load(id: String, sha256 want: String, base: String) async throws -> Data {
        if let d = cached(id: id, sha256: want) { return d }
        guard let url = URL(string: base + id + ".json.gz") else { throw Failure.unavailable("bad URL") }
        var r = URLRequest(url: url)
        r.timeoutInterval = 120
        let session = URLSession(configuration: .ephemeral, delegate: PassportSRS.NoRedirects(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (gz, resp) = try await session.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw Failure.unavailable("HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)")
        }
        guard let json = Gzip.inflate(gz) else { throw Failure.unavailable("not gzip") }
        guard sha256(json) == want else { throw Failure.unavailable("it did not match its pinned hash") }
        if let f = file(id) {
            try? FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? json.write(to: f, options: .atomic)
        }
        return json
    }
}
