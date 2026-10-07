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
    /// The saved node failed a recheck: why, shown until the user checks a node again (Earth's node meanwhile).
    private static let suspendedKey = "node.suspended"
    /// The last full check each node passed (by its URLs), with the genesis pin it passed against.
    private static let verifiedKey = "node.verified"

    /// How long a passed full check stands. The heavy check (the whole
    /// genesis) runs at launch for a saved node, again once this old while the
    /// app runs, and when a node's light status probe fails; the Network
    /// screen otherwise shows the light probe. As Android.
    public static let recheckSeconds: Int64 = 6 * 3600
    /// How often a running app looks whether the saved node is due a recheck.
    public static let recheckTickSeconds: UInt64 = 15 * 60

    /// Where the node choice is kept: UserDefaults; tests use a dictionary.
    public protocol Store: Sendable {
        func get(_ key: String) -> String?
        /// Sets every key (nil removes it).
        func put(_ values: [String: String?])
    }

    struct Defaults: Store {
        func get(_ key: String) -> String? { UserDefaults.standard.string(forKey: key) }
        func put(_ values: [String: String?]) {
            for (k, v) in values { if let v { UserDefaults.standard.set(v, forKey: k) } else { UserDefaults.standard.removeObject(forKey: k) } }
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: Node?
    nonisolated(unsafe) private static var noticeValue: String?
    nonisolated(unsafe) private static var storeValue: Store = Defaults()
    /// Unix seconds; tests pin it.
    nonisolated(unsafe) static var clock: @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) }
    /// The full check; tests replace it.
    nonisolated(unsafe) static var fullCheck: @Sendable (Node) async throws -> Probe = { try await probe($0) }

    public static var current: Node {
        lock.withLock {
            if let cached { return cached }
            let loaded = load(storeValue)
            cached = loaded
            return loaded
        }
    }

    /// What the person should know about their node: it is waiting for its
    /// check, or it failed one and Earth's node is in use. Nil otherwise.
    public static var notice: String? {
        _ = current
        return lock.withLock { noticeValue }
    }

    /// Starts over from `store` (tests; the app keeps UserDefaults).
    static func reload(_ store: Store) {
        lock.withLock { storeValue = store; cached = nil; noticeValue = nil }
    }

    /// The saved node is used at once only if it passed this build's full
    /// check before (against the genesis pinned now); one saved by an earlier
    /// build, or that failed a recheck, is not: Earth's node answers until
    /// `recheck` passes it (a failed one waits for the person to check it
    /// again). A pass older than `recheckSeconds` waits too, for the forced
    /// launch recheck: the node may have been re-initialised on another
    /// genesis since. Under `lock`.
    private static func load(_ s: Store) -> Node {
        noticeValue = nil
        guard let saved = saved(s) else { return defaultNode }
        if let why = s.get(suspendedKey) { noticeValue = suspendedText(why); return defaultNode }
        if fresh(s, saved) { return saved }
        noticeValue = verifiedAt(s, saved) != nil ? rechecking : unchecked
        return defaultNode
    }

    /// Runs the full check on the saved node: at launch (`force`), then
    /// whenever the last pass is older than `recheckSeconds` (or it never
    /// passed). Passed: it is used (again). An LCD that cannot be reached
    /// leaves things as they are: nothing answers, so nothing wrong is read;
    /// the next tick tries again. Any other failure (another chain or
    /// genesis, an LCD and RPC that disagree, a node far behind, an RPC that
    /// does not serve the genesis) suspends it: Earth's node is used, and
    /// `notice` says why, until the person checks a node again. Returns
    /// whether the node in use changed. As Android's NodeConfig.recheck.
    @discardableResult
    public static func recheck(force: Bool) async -> Bool {
        let before = current
        let s = lock.withLock { storeValue }
        guard let saved = saved(s), s.get(suspendedKey) == nil else { return false }
        if !force && fresh(s, saved) { return false }
        _ = try? await check(s, saved)
        return current != before
    }

    /// The full check of `node`; a pass is recorded, and the saved node's failure suspends it.
    private static func check(_ s: Store, _ node: Node) async throws -> Probe {
        let p: Probe
        do { p = try await fullCheck(node) } catch {
            if node == saved(s), !(error is LCDUnreachable) { suspendSaved(s, error.localizedDescription) }
            throw error
        }
        markVerified(s, node)
        if node == saved(s) { lock.withLock { noticeValue = nil; cached = node } }
        return p
    }

    /// What the Network screen shows for `node`: the light probe (the LCD's
    /// latest block: chain id, height, age) while its last full check stands,
    /// else, or when the light probe fails, the full check (recorded when it
    /// passes; the saved node suspended when it fails).
    public static func status(_ node: Node) async throws -> Probe {
        let s = lock.withLock { storeValue }
        if fresh(s, node), let p = try? await lightProbe(node) { return p }
        return try await check(s, node)
    }

    public static func save(_ node: Node) {
        let s = lock.withLock { storeValue }
        s.put([lcdKey: node.lcd.absoluteString, rpcKey: node.rpc?.absoluteString ?? "", suspendedKey: nil])
        // Saved only after `probe` passed it.
        markVerified(s, node)
        lock.withLock { cached = node; noticeValue = nil }
    }

    public static func reset() {
        let s = lock.withLock { storeValue }
        s.put([lcdKey: nil, rpcKey: nil, suspendedKey: nil, verifiedKey: nil])
        lock.withLock { cached = defaultNode; noticeValue = nil }
    }

    /// The node the person saved (suspended or not), nil for none.
    public static var saved: Node? { saved(lock.withLock { storeValue }) }

    private static func saved(_ s: Store) -> Node? {
        guard let lcdText = s.get(lcdKey), let lcd = normalize(lcdText), problem(lcd) == nil else { return nil }
        // A node saved before the RPC was required is dropped: its genesis cannot be checked.
        guard let rpcText = s.get(rpcKey), let rpc = normalize(rpcText), problem(rpc) == nil else { return nil }
        return Node(lcd: lcd, rpc: rpc)
    }

    private static func suspendSaved(_ s: Store, _ reason: String) {
        s.put([suspendedKey: reason])
        if let n = saved(s) { forget(s, n) }
        lock.withLock { noticeValue = suspendedText(reason); cached = defaultNode }
    }

    private static func key(_ node: Node) -> String { node.lcd.absoluteString + "\n" + (node.rpc?.absoluteString ?? "") }

    /// Every recorded pass: node key to (genesis pin, unix seconds), oldest first. A few nodes at most.
    private static func verified(_ s: Store) -> [(key: String, genesis: String, at: Int64)] {
        guard let text = s.get(verifiedKey), let data = text.data(using: .utf8),
              let list = try? JSONDecoder().decode([VerifiedEntry].self, from: data) else { return [] }
        return list.map { ($0.key, $0.genesis, $0.at) }
    }

    private struct VerifiedEntry: Codable { let key: String; let genesis: String; let at: Int64 }

    private static func writeVerified(_ s: Store, _ all: [(key: String, genesis: String, at: Int64)]) {
        let list = all.suffix(maxVerified).map { VerifiedEntry(key: $0.key, genesis: $0.genesis, at: $0.at) }
        if let d = try? JSONEncoder().encode(list) { s.put([verifiedKey: String(decoding: d, as: UTF8.self)]) }
    }

    private static func markVerified(_ s: Store, _ node: Node) {
        var all = verified(s).filter { $0.key != key(node) }
        all.append((key(node), Constants.genesisSHA256.lowercased(), clock()))
        writeVerified(s, all)
    }

    private static func forget(_ s: Store, _ node: Node) {
        let all = verified(s)
        let kept = all.filter { $0.key != key(node) }
        if kept.count != all.count { writeVerified(s, kept) }
    }

    /// When `node` last passed the full check against the genesis pinned now; nil when it never did.
    private static func verifiedAt(_ s: Store, _ node: Node) -> Int64? {
        verified(s).last { $0.key == key(node) && $0.genesis == Constants.genesisSHA256.lowercased() }?.at
    }

    /// Its last pass stands: under `recheckSeconds` old (and not in the future).
    private static func fresh(_ s: Store, _ node: Node) -> Bool {
        guard let at = verifiedAt(s, node) else { return false }
        let age = clock() - at
        return age >= 0 && age < recheckSeconds
    }

    private static let maxVerified = 4

    private static func suspendedText(_ reason: String) -> String {
        "Your node no longer passes the wallet's check, so the wallet switched to Earth's node: \(reason) Check your node, then save it again in Settings → Network."
    }

    private static let unchecked = "Your node has not passed this version's check yet. Until it does, the wallet uses Earth's node. Settings → Network shows the result."

    private static let rechecking = "The wallet is checking your node again, as it does when the last check is a few hours old. Until it passes, the wallet uses Earth's node."

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
        let (chainID, height) = try await lcdLatest(rest)

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

    /// The light probe: the LCD's latest block, its chain id, height and age
    /// (what `probe` checks first). For a node whose full check stands.
    public static func lightProbe(_ node: Node) async throws -> Probe {
        if let p = problem(node.lcd) { throw ProbeError(message: "LCD: \(p)") }
        let (chainID, height) = try await lcdLatest(EarthRest(lcd: node.lcd, rpc: node.rpc, allowLocal: true))
        return Probe(chainID: chainID, height: height)
    }

    /// The LCD's latest block: chain id earth-1, its height, at most `maxLagSeconds` old.
    private static func lcdLatest(_ rest: EarthRest) async throws -> (String, UInt64) {
        let j: JSON
        let path = "/cosmos/base/tendermint/v1beta1/blocks/latest"
        do { j = try await rest.get(path) } catch { throw latestFailure(error, path) }
        let header = j.sdk_block.header.exists ? j.sdk_block.header : j.block.header
        guard header.exists else { throw ProbeError(message: "That does not look like a Cosmos LCD.") }
        let chainID = header.chain_id.string(default: "")
        guard chainID == Constants.chainID else { throw ProbeError(message: "That node follows \"\(chainID)\", not \(Constants.chainID).") }
        guard let height = header.height.uint64 else { throw ProbeError(message: "The LCD did not say its latest height.") }
        try checkLag("The LCD", RESTPrivateChain.parseTime(header.time.string(default: "")))
        return (chainID, height)
    }

    /// What a failed latest-block request means. Only a request that got no
    /// answer is `LCDUnreachable`; an HTTP error or a body that is not JSON is
    /// an answer, and fails the check (as Android's lcdGet).
    static func latestFailure(_ error: Swift.Error, _ path: String) -> Swift.Error {
        switch error {
        case EarthRest.Error.http(let status, _): ProbeError(message: "The LCD answered \(status) for \(path).")
        case EarthRest.Error.notJSON: ProbeError(message: "That does not look like a Cosmos LCD.")
        default: LCDUnreachable(message: "Could not reach the LCD: \(error.localizedDescription)")
        }
    }

    /// The node's LCD did not answer at all: nothing was read from it, so nothing wrong was.
    public struct LCDUnreachable: Swift.Error, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
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
