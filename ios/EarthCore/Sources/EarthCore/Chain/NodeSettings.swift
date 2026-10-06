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
    /// link-local or RFC 1918 / unique-local / site-local (fec0::/10)
    /// address, or a .local name. An IPv4-mapped IPv6 address
    /// (::ffff:a.b.c.d) is judged by its IPv4 address, where the socket
    /// goes. Literal addresses only: a public name that resolves to a
    /// private address is still a public name. Same rules as Android
    /// NodeConfig.isLocal.
    public static func isLocal(_ host: String?) -> Bool {
        guard var h = host?.lowercased(), !h.isEmpty else { return false }
        h = h.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h == "localhost" || h.hasSuffix(".local") { return true }
        var v4 = in_addr()
        if inet_pton(AF_INET, h, &v4) == 1 { return localBytes(withUnsafeBytes(of: v4.s_addr) { Array($0) }) }
        var v6 = in6_addr()
        if inet_pton(AF_INET6, h, &v6) == 1 { return localBytes(withUnsafeBytes(of: v6) { Array($0) }) }
        return false
    }

    /// `isLocal` on an address's bytes (4 or 16).
    static func localBytes(_ b: [UInt8]) -> Bool {
        if b.count == 4 {
            switch (b[0], b[1]) {
            case (127, _), (10, _), (192, 168), (169, 254): return true
            case (172, let x) where (16 ... 31).contains(x): return true
            default: return false
            }
        }
        guard b.count == 16 else { return false }
        if b[0 ..< 10].allSatisfy({ $0 == 0 }) && b[10] == 0xff && b[11] == 0xff { return localBytes(Array(b[12 ..< 16])) } // ::ffff:a.b.c.d
        if b[0 ..< 15].allSatisfy({ $0 == 0 }) && b[15] == 1 { return true } // ::1
        if b[0] & 0xfe == 0xfc { return true } // fc00::/7
        if b[0] == 0xfe && (b[1] & 0xc0 == 0x80 || b[1] & 0xc0 == 0xc0) { return true } // fe80::/10, fec0::/10
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

    /// How far behind the wall clock a node's latest block may be (it is syncing or stalled past that).
    public static let maxLagSeconds: Int64 = 10 * 60

    static let noBlockOne = "That node does not serve block 1 (it was state-synced or prunes old blocks), so the wallet "
        + "cannot tell which earth-1 it follows. Use a node that keeps the chain from its first block."
    static let otherGenesis = "That node follows another earth-1: its first block is not the live chain's "
        + "(an earlier launch, or a fork). Point it at the live chain."

    /// Asks `node` which chain it follows and how far it is. Refused: a node
    /// that is not `Constants.chainID`; one whose block 1 is not the live
    /// chain's (an earlier earth-1 genesis or a fork under the same id; the
    /// hash is `Constants.genesisBlockHash`, or Earth's own node's block 1
    /// until that is set); one whose latest block is more than
    /// `maxLagSeconds` old. The RPC, when given, is checked the same way.
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
        try checkLag("The LCD", RESTPrivateChain.parseTime(header.time.string(default: "")))
        let expected = try await expectedGenesis()
        guard let lcdGenesis = await blockOneHash(rest) else { throw ProbeError(message: noBlockOne) }
        guard lcdGenesis == expected else { throw ProbeError(message: otherGenesis) }
        if node.rpc != nil {
            let r: JSON
            do { r = try await rest.getRPC("/status") } catch {
                throw ProbeError(message: "Could not reach the RPC: \(error.localizedDescription)")
            }
            let status = r.result.exists ? r.result : r
            let info = status.node_info
            let network = info.network.string(default: "")
            guard info.exists else { throw ProbeError(message: "That does not look like a CometBFT RPC.") }
            guard network == Constants.chainID else { throw ProbeError(message: "The RPC follows \"\(network)\", not \(Constants.chainID).") }
            try checkLag("The RPC", RESTPrivateChain.parseTime(status.sync_info.latest_block_time.string(default: "")))
            let b = try? await rest.getRPC("/block?height=1")
            let hash = b.map { ($0.result.exists ? $0.result : $0).block_id.hash.string(default: "").lowercased() } ?? ""
            guard hash.count == 64, hash.allSatisfy({ $0.isHexDigit }) else { throw ProbeError(message: noBlockOne) }
            guard hash == expected else { throw ProbeError(message: otherGenesis) }
        }
        return Probe(chainID: chainID, height: height)
    }

    private static func checkLag(_ what: String, _ time: Int64) throws {
        let now = Int64(Date().timeIntervalSince1970)
        guard time > 0 else { throw ProbeError(message: "\(what) did not say when its latest block was made.") }
        if now - time > maxLagSeconds {
            throw ProbeError(message: "\(what)'s latest block is \((now - time) / 60) minutes old: it is still syncing or has stopped. Try again once it has caught up.")
        }
    }

    /// The LCD's block 1 hash as 64 lowercase hex digits; nil when it cannot say.
    private static func blockOneHash(_ rest: EarthRest) async -> String? {
        guard let j = try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/1"),
              let d = j.block_id.hash.string.flatMap({ Data(base64Encoded: $0) }), d.count == 32 else { return nil }
        return d.hexString
    }

    /// The live chain's block 1 hash: pinned, or (until the pin is set) Earth's own node's.
    private static func expectedGenesis() async throws -> String {
        if !Constants.genesisBlockHash.isEmpty { return Constants.genesisBlockHash.lowercased() }
        guard let h = await blockOneHash(EarthRest(lcd: defaultNode.lcd, rpc: nil)) else {
            throw ProbeError(message: "Could not read the live chain's first block from Earth's node to compare with; try again.")
        }
        return h
    }
}
