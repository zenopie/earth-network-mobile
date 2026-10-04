import Foundation

/// The LCD (cosmos gRPC-gateway) client. All chain I/O goes through here.
///
/// Mirrors `EarthRest.kt`, with one deliberate difference: a non-2xx is thrown
/// rather than returned. Kotlin's callers each re-implemented the same
/// `code !in 200..299` guard; Swift can put it in one place and let `try?` at
/// the call site express the same "return the empty result" behaviour.
public struct EarthRest: Sendable {

    public enum Error: Swift.Error {
        case http(status: Int, body: String)
        case notJSON(String)
        case missing(String)
        /// The RPC base is optional — a deployment may expose only the LCD.
        case rpcUnavailable
        /// The response ran past `maxBodyBytes`.
        case tooLarge(Int)
    }

    /// The most any response is read to: a node or proxy streaming without
    /// end (or a hostile one) cannot exhaust memory. The largest legitimate
    /// answers (a page of positions or validators, a 5000-row indexer page)
    /// are far below it.
    public static let maxBodyBytes = 8 * 1024 * 1024

    /// The deepest JSON nesting any response may have, checked before parsing.
    public static let maxJSONDepth = 64

    /// Refuses `data` when its JSON arrays/objects nest deeper than `max`
    /// (counted outside strings), before any parser sees it.
    public static func checkJSONDepth(_ data: Data, max: Int = maxJSONDepth) throws {
        var depth = 0, inString = false, escaped = false
        for b in data {
            if inString {
                if escaped { escaped = false } else if b == 0x5c { escaped = true } else if b == 0x22 { inString = false }
                continue
            }
            switch b {
            case 0x22: inString = true
            case 0x5b, 0x7b: depth += 1; if depth > max { throw Error.notJSON("response nests deeper than \(max)") }
            case 0x5d, 0x7d: depth -= 1
            default: break
            }
        }
    }

    /// JSON of `data`, its nesting bounded first.
    static func parseJSON(_ data: Data) throws -> Any {
        try checkJSONDepth(data)
        guard let o = try? JSONSerialization.jsonObject(with: data) else { throw Error.notJSON(String(decoding: data.prefix(200), as: UTF8.self)) }
        return o
    }

    /// A session that never follows a redirect: a 3xx comes back as
    /// the response (a non-2xx, an error), never a request to another origin.
    public static func session(_ config: URLSessionConfiguration) -> URLSession {
        URLSession(configuration: config, delegate: NoRedirects.shared, delegateQueue: nil)
    }

    final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        static let shared = NoRedirects()
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest) async -> URLRequest? { nil }
    }

    /// Reads `request`'s response, refusing more than `max` bytes (after
    /// URLSession's own gzip decoding). Returns the body and HTTP status.
    static func boundedData(_ session: URLSession, _ request: URLRequest, max: Int = maxBodyBytes) async throws -> (Data, Int) {
        let (d, s, _) = try await boundedResponse(session, request, max: max)
        return (d, s)
    }

    /// `boundedData` with the response's headers.
    static func boundedResponse(_ session: URLSession, _ request: URLRequest, max: Int = maxBodyBytes) async throws -> (Data, Int, HTTPURLResponse?) {
        let (bytes, response) = try await session.bytes(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if response.expectedContentLength > Int64(max) { throw Error.tooLarge(max) }
        var out: [UInt8] = []
        out.reserveCapacity(Swift.min(Swift.max(Int(response.expectedContentLength), 16 * 1024), max))
        for try await b in bytes {
            out.append(b)
            if out.count > max { throw Error.tooLarge(max) }
        }
        return (Data(out), status, response as? HTTPURLResponse)
    }

    public let lcd: URL
    public let rpc: URL?
    private let session: URLSession

    public init(lcd: URL = Constants.lcdURL, rpc: URL? = Constants.rpcURL) {
        self.lcd = lcd
        self.rpc = rpc
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        self.session = Self.session(config)
    }

    public func get(_ path: String) async throws -> JSON {
        try await request(URLRequest(url: try lcd.appendingPath(path)))
    }

    /// `get` of the state at block `height` (the gRPC gateway's
    /// `x-cosmos-block-height` header); a pruned height answers an error.
    public func get(_ path: String, height: UInt64) async throws -> JSON {
        var r = URLRequest(url: try lcd.appendingPath(path))
        r.setValue(String(height), forHTTPHeaderField: "x-cosmos-block-height")
        return try await request(r)
    }

    /// `get(_:height:)` with the height the node says it answered at (its
    /// `x-cosmos-block-height` response header; nil when absent). A caller
    /// pinning state to a height checks the two agree.
    public func getEcho(_ path: String, height: UInt64) async throws -> (JSON, UInt64?) {
        guard let url = lcd.appendingPathChecked(path) else { throw Error.missing(path) }
        var r = URLRequest(url: url)
        r.setValue(String(height), forHTTPHeaderField: "x-cosmos-block-height")
        let (data, status, resp) = try await Self.boundedResponse(session, r)
        guard (200 ... 299).contains(status) else { throw Error.http(status: status, body: String(decoding: data, as: UTF8.self)) }
        let object = try Self.parseJSON(data)
        let echo = (resp?.value(forHTTPHeaderField: "x-cosmos-block-height")).flatMap { UInt64($0.trimmingCharacters(in: .whitespaces)) }
        return (JSON(object), echo)
    }

    /// The CometBFT RPC, which serves the one thing the LCD cannot: a *range*
    /// of blocks in a single request. Callers must tolerate it being absent.
    public func getRPC(_ path: String) async throws -> JSON {
        guard let rpc else { throw Error.rpcUnavailable }
        return try await request(URLRequest(url: try rpc.appendingPath(path)))
    }

    public func postJSON(_ path: String, body: [String: Any]) async throws -> JSON {
        var request = URLRequest(url: try lcd.appendingPath(path))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await self.request(request)
    }

    private func request(_ request: URLRequest) async throws -> JSON {
        let (data, status) = try await Self.boundedData(session, request)
        guard (200 ... 299).contains(status) else {
            throw Error.http(status: status, body: String(decoding: data, as: UTF8.self))
        }
        return JSON(try Self.parseJSON(data))
    }
}

private extension URL {
    /// `appendingPathComponent` escapes the separators in a multi-segment path
    /// and drops any query string, so paths are joined textually instead. A
    /// path that does not parse (a tx hash or denom the node or user supplied)
    /// throws rather than traps.
    func appendingPath(_ path: String) throws -> URL {
        guard let u = URL(string: absoluteString.trimmingTrailingSlash + path) else { throw EarthRest.Error.missing(path) }
        return u
    }

    /// `appendingPath` that answers nil instead of trapping on an unparsable path.
    func appendingPathChecked(_ path: String) -> URL? {
        URL(string: absoluteString.trimmingTrailingSlash + path)
    }
}

private extension String {
    var trimmingTrailingSlash: String {
        hasSuffix("/") ? String(dropLast()) : self
    }
}
