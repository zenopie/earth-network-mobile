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
        self.session = URLSession(configuration: config)
    }

    public func get(_ path: String) async throws -> JSON {
        try await request(URLRequest(url: lcd.appendingPath(path)))
    }

    /// The CometBFT RPC, which serves the one thing the LCD cannot: a *range*
    /// of blocks in a single request. Callers must tolerate it being absent.
    public func getRPC(_ path: String) async throws -> JSON {
        guard let rpc else { throw Error.rpcUnavailable }
        return try await request(URLRequest(url: rpc.appendingPath(path)))
    }

    public func postJSON(_ path: String, body: [String: Any]) async throws -> JSON {
        var request = URLRequest(url: lcd.appendingPath(path))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await self.request(request)
    }

    private func request(_ request: URLRequest) async throws -> JSON {
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200 ... 299).contains(status) else {
            throw Error.http(status: status, body: String(decoding: data, as: UTF8.self))
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) else {
            throw Error.notJSON(String(decoding: data, as: UTF8.self))
        }
        return JSON(object)
    }
}

private extension URL {
    /// `appendingPathComponent` escapes the separators in a multi-segment path
    /// and drops any query string, so paths are joined textually instead.
    func appendingPath(_ path: String) -> URL {
        URL(string: absoluteString.trimmingTrailingSlash + path)!
    }
}

private extension String {
    var trimmingTrailingSlash: String {
        hasSuffix("/") ? String(dropLast()) : self
    }
}
