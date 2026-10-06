import CryptoKit
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
        /// Nil only for a built-in default without one; a node the user
        /// chooses always has one (`probe` refuses it otherwise).
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
        // A node saved before the RPC was required is dropped: its genesis was never checked.
        guard let rpcText = d.string(forKey: rpcKey), let rpc = normalize(rpcText), problem(rpc) == nil else { return defaultNode }
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

    /// The most /genesis_chunked chunks read (CometBFT's are 16 MiB each).
    public static let maxGenesisChunks = 8
    /// One chunk's response bound: 16 MiB as base64, and the JSON around it.
    static let genesisChunkBytes = 24 * 1024 * 1024

    /// Settings → Network says this under the RPC field (docs quote it).
    public static let rpcWhy = "Required. The wallet checks your node against the live chain's genesis, and only "
        + "the RPC serves it (/genesis_chunked). Every node keeps its genesis, state-synced or not. "
        + "The explorer also reads block ranges from it."
    static let rpcRequired = "RPC: required. The wallet checks the node's genesis, which only the RPC serves."
    static let notRPC = "That does not look like a CometBFT RPC."
    static let noGenesis = "The RPC did not serve its genesis (/genesis_chunked), so the wallet cannot tell "
        + "which earth-1 it follows. Check that the URL is the node's CometBFT RPC (port 26657)."
    static let otherGenesis = "That node follows another earth-1: its genesis is not the live chain's "
        + "(an earlier launch, or another network under the same name). Point it at the live chain."
    static let splitNodes = "The LCD and the RPC disagree about a recent block: they are not following "
        + "the same chain. Use the LCD and RPC of one node."

    /// Asks `node` which chain it follows and how far it is. Refused: a node
    /// that is not `Constants.chainID`; one whose genesis is not the live
    /// chain's (an earlier earth-1 genesis, or another chain under the same
    /// id): the sha256 of what its RPC's /genesis_chunked serves must be
    /// `Constants.genesisSHA256`; an LCD that does not hold the RPC's block
    /// at their common height (so both are one node, or nodes of one chain);
    /// and either one whose latest block is more than `maxLagSeconds` old.
    /// The RPC is required: only it serves the genesis, and every node keeps
    /// that, state-synced or pruned. Mirrors Android's NodeConfig.probe.
    public static func probe(_ node: Node) async throws -> Probe {
        if let p = problem(node.lcd) { throw ProbeError(message: "LCD: \(p)") }
        guard let rpcURL = node.rpc else { throw ProbeError(message: rpcRequired) }
        if let p = problem(rpcURL) { throw ProbeError(message: "RPC: \(p)") }
        let rest = EarthRest(lcd: node.lcd, rpc: rpcURL, allowLocal: true)
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

        let r: JSON
        do { r = try await rest.getRPC("/status") } catch {
            throw ProbeError(message: "Could not reach the RPC: \(error.localizedDescription)")
        }
        let status = r.result.exists ? r.result : r
        let info = status.node_info
        let network = info.network.string(default: "")
        guard info.exists else { throw ProbeError(message: notRPC) }
        guard network == Constants.chainID else { throw ProbeError(message: "The RPC follows \"\(network)\", not \(Constants.chainID).") }
        try checkLag("The RPC", RESTPrivateChain.parseTime(status.sync_info.latest_block_time.string(default: "")))
        guard let rpcHeight = status.sync_info.latest_block_height.uint64 else { throw ProbeError(message: "The RPC did not say its latest height.") }

        let genesis = try await genesisSHA256 { chunk in
            do { return try await rest.getRPC("/genesis_chunked?chunk=\(chunk)", maxBytes: genesisChunkBytes) } catch {
                throw ProbeError(message: noGenesis)
            }
        }
        guard genesis == Constants.genesisSHA256.lowercased() else { throw ProbeError(message: otherGenesis) }

        // The genesis is the RPC's; the LCD answers every query. Both must
        // hold the same block at a height both have.
        let common = min(height, rpcHeight)
        let lcdBlock = try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/\(common)")
        let lcdHash = lcdBlock?.block_id.hash.string.flatMap { Data(base64Encoded: $0) }.flatMap { $0.count == 32 ? $0.hexString : nil }
        let rpcBlock = try? await rest.getRPC("/block?height=\(common)")
        let rpcHash = rpcBlock.map { ($0.result.exists ? $0.result : $0).block_id.hash.string(default: "").lowercased() }
        guard let lcdHash, let rpcHash, !rpcHash.isEmpty else {
            throw ProbeError(message: "Could not read block \(common) from both the LCD and the RPC to compare them; try again.")
        }
        guard lcdHash == rpcHash else { throw ProbeError(message: splitNodes) }
        return Probe(chainID: chainID, height: height)
    }

    /// sha256 of the genesis a CometBFT RPC serves: /genesis_chunked's base64
    /// chunks, decoded, in order. `chunk` fetches one response. 64 lowercase
    /// hex digits; throws a `ProbeError` to show.
    static func genesisSHA256(_ chunk: (Int) async throws -> JSON) async throws -> String {
        var sha = SHA256()
        var total = 1
        var i = 0
        while i < total {
            let j = try await chunk(i)
            let r = j.result.exists ? j.result : j
            guard let n = r.total.int64.map({ Int($0) }) else { throw ProbeError(message: noGenesis) }
            if i == 0 {
                guard (1 ... maxGenesisChunks).contains(n) else { throw ProbeError(message: noGenesis) }
                total = n
            }
            guard n == total, r.chunk.int64 == Int64(i),
                  let data = r.data.string.flatMap({ Data(base64Encoded: $0) }), !data.isEmpty
            else { throw ProbeError(message: noGenesis) }
            sha.update(data: data)
            i += 1
        }
        return Data(sha.finalize()).hexString
    }

    private static func checkLag(_ what: String, _ time: Int64) throws {
        let now = Int64(Date().timeIntervalSince1970)
        guard time > 0 else { throw ProbeError(message: "\(what) did not say when its latest block was made.") }
        if now - time > maxLagSeconds {
            throw ProbeError(message: "\(what)'s latest block is \((now - time) / 60) minutes old: it is still syncing or has stopped. Try again once it has caught up.")
        }
    }
}
