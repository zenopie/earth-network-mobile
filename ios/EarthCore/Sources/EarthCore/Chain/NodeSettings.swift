import Foundation

/// Which node the wallet talks to: every chain query and every broadcast goes
/// to `current`'s LCD (and its RPC, for the explorer's block ranges). The
/// default is Earth's own node; Settings → Network can point the install at
/// the user's own, so no third party sees which account asks what or when.
/// The backend (privacy indexer, handle directory, gas grant, fetched
/// circuits) is not a node and stays at `Constants.backendBaseURL`.
/// Mirrors `chain/NodeConfig.kt`.
///
/// Per install, in defaults: a URL is not a secret.
///
/// HTTPS is required, except to the user's own node at a loopback or
/// private-network address, chosen with a warning. `EarthRest` refuses any
/// http:// base that is not that custom node.
public enum NodeSettings {

    public struct Node: Equatable, Sendable {
        public let lcd: URL
        /// Nil: no RPC (the explorer then reads blocks from the LCD).
        public let rpc: URL?
        public init(lcd: URL, rpc: URL?) { self.lcd = lcd; self.rpc = rpc }
        public var isDefault: Bool { self == NodeSettings.defaultNode }
    }

    public static let defaultNode = Node(lcd: Constants.lcdURL, rpc: Constants.rpcURL)

    private static let lcdKey = "node.lcd"
    private static let rpcKey = "node.rpc"
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: Node?

    public static var current: Node {
        lock.withLock {
            if let cached { return cached }
            let loaded = load()
            cached = loaded
            return loaded
        }
    }

    private static func load() -> Node {
        let d = UserDefaults.standard
        guard let lcdText = d.string(forKey: lcdKey), let lcd = normalize(lcdText), problem(lcd) == nil else { return defaultNode }
        let rpcText = d.string(forKey: rpcKey) ?? ""
        if rpcText.isEmpty { return Node(lcd: lcd, rpc: nil) }
        guard let rpc = normalize(rpcText), problem(rpc) == nil else { return defaultNode }
        return Node(lcd: lcd, rpc: rpc)
    }

    public static func save(_ node: Node) {
        let d = UserDefaults.standard
        d.set(node.lcd.absoluteString, forKey: lcdKey)
        d.set(node.rpc?.absoluteString ?? "", forKey: rpcKey)
        lock.withLock { cached = node }
    }

    public static func reset() {
        let d = UserDefaults.standard
        d.removeObject(forKey: lcdKey)
        d.removeObject(forKey: rpcKey)
        lock.withLock { cached = defaultNode }
    }

    /// A base URL as typed, trimmed of whitespace and trailing slashes; nil
    /// when it is not an http(s) URL with a host and nothing past its path.
    public static func normalize(_ input: String) -> URL? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        guard let c = URLComponents(string: s), let scheme = c.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = c.host, !host.isEmpty, c.user == nil, c.password == nil, c.query == nil, c.fragment == nil
        else { return nil }
        return URL(string: s)
    }

    /// Why `url` cannot be a node, or nil when it can.
    public static func problem(_ url: URL) -> String? {
        switch url.scheme?.lowercased() {
        case "https": nil
        case "http" where isLocal(url.host): nil
        default: "Use https://. Plain http:// is allowed only to this phone or your own local network."
        }
    }

    public static func isCleartext(_ url: URL) -> Bool { url.scheme?.lowercased() == "http" }

    /// The phone itself or a private network: localhost, a loopback,
    /// link-local or RFC 1918 / unique-local address, or a .local name.
    /// Literal addresses only: a public name that resolves to a private
    /// address is still a public name.
    public static func isLocal(_ host: String?) -> Bool {
        guard var h = host?.lowercased(), !h.isEmpty else { return false }
        h = h.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h == "localhost" || h.hasSuffix(".local") { return true }
        var v4 = in_addr()
        if inet_pton(AF_INET, h, &v4) == 1 {
            let b = withUnsafeBytes(of: v4.s_addr) { Array($0) }
            switch (b[0], b[1]) {
            case (127, _), (10, _), (192, 168), (169, 254): return true
            case (172, let x) where (16 ... 31).contains(x): return true
            default: return false
            }
        }
        var v6 = in6_addr()
        if inet_pton(AF_INET6, h, &v6) == 1 {
            let b = withUnsafeBytes(of: v6) { Array($0) }
            if b[0 ..< 15].allSatisfy({ $0 == 0 }) && b[15] == 1 { return true } // ::1
            if b[0] & 0xfe == 0xfc { return true } // fc00::/7
            if b[0] == 0xfe && b[1] & 0xc0 == 0x80 { return true } // fe80::/10
            return false
        }
        return false
    }

    /// Whether `EarthRest` may send to `base`: https, or the custom node's own local http.
    static func allowed(_ base: URL) -> Bool {
        if base.scheme?.lowercased() == "https" { return true }
        let node = current
        guard !node.isDefault, base == node.lcd || base == node.rpc else { return false }
        return problem(base) == nil
    }

    /// What a node said about itself.
    public struct Probe: Sendable {
        public let chainID: String
        public let height: UInt64
    }

    public struct ProbeError: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// Asks `node` which chain it follows and how far it is, and refuses one
    /// that is not `Constants.chainID`. The RPC, when given, is checked the
    /// same way.
    public static func probe(_ node: Node) async throws -> Probe {
        if let p = problem(node.lcd) { throw ProbeError(message: "LCD: \(p)") }
        if let rpc = node.rpc, let p = problem(rpc) { throw ProbeError(message: "RPC: \(p)") }
        let rest = EarthRest(lcd: node.lcd, rpc: node.rpc, allowLocal: true)
        let j: JSON
        do { j = try await rest.get("/cosmos/base/tendermint/v1beta1/blocks/latest") } catch {
            throw ProbeError(message: "Could not reach the LCD: \(error.localizedDescription)")
        }
        let header = j.sdk_block.header.exists ? j.sdk_block.header : j.block.header
        guard header.exists else { throw ProbeError(message: "That does not look like a Cosmos LCD.") }
        let chainID = header.chain_id.string(default: "")
        guard chainID == Constants.chainID else { throw ProbeError(message: "That node follows \"\(chainID)\", not \(Constants.chainID).") }
        guard let height = header.height.uint64 else { throw ProbeError(message: "The LCD did not say its latest height.") }
        if node.rpc != nil {
            let r: JSON
            do { r = try await rest.getRPC("/status") } catch {
                throw ProbeError(message: "Could not reach the RPC: \(error.localizedDescription)")
            }
            let info = r.result.node_info.exists ? r.result.node_info : r.node_info
            let network = info.network.string(default: "")
            guard info.exists else { throw ProbeError(message: "That does not look like a CometBFT RPC.") }
            guard network == Constants.chainID else { throw ProbeError(message: "The RPC follows \"\(network)\", not \(Constants.chainID).") }
        }
        return Probe(chainID: chainID, height: height)
    }
}
