import Foundation

/// Transactions, read back off the chain.
///
/// Ports the part of `chain/Explorer.kt` the wallet screen needs: what this
/// address has done lately. That is the transactions this wallet sent
/// (``SentTxLog``), each looked up by hash. Not a search by address: Earth's
/// public node refuses one, because CometBFT loads every match of a search
/// before it pages and cannot cancel it, so an address search is a scan of
/// that address's whole history on the only validator (round-5 R5-E-1); and
/// the node indexes no address events. A transfer someone else sent to this
/// address shows in its balance, not as a row.
public enum Explorer {

    public struct Tx: Sendable, Identifiable, Equatable {
        public let hash: String
        public let height: Int64
        /// A non-zero code means the transaction was included but failed.
        public let success: Bool
        public let timestamp: String
        /// Short message names, e.g. "MsgSwap" — enough for a list row.
        public let types: [String]
        /// The first message's fields, which is all a row needs.
        public let first: [String: Any]
        /// What the same lookup also says, for the detail sheet: the fee and
        /// who paid it (a fee granter, when one did), the chain's log when it
        /// failed, the memo and the gas.
        public var fee: [ActivityCoin] = []
        public var feeGranter = ""
        public var feePayer = ""
        public var code: Int64 = 0
        public var rawLog = ""
        public var memo = ""
        public var gasUsed: Int64 = 0
        public var gasWanted: Int64 = 0

        public var id: String { hash }

        public static func == (lhs: Tx, rhs: Tx) -> Bool { lhs.hash == rhs.hash }
    }

    public struct Status: Sendable, Equatable {
        public let chainID: String
        public let height: Int64
        public let time: String
    }

    public struct Block: Sendable, Equatable, Identifiable {
        public let height: Int64
        public let time: String
        public let txCount: Int

        public var id: Int64 { height }
    }
}

public extension EarthClient {

    func status() async -> Explorer.Status? {
        guard let json = try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/latest")
        else { return nil }
        let header = json.block.header
        guard let chainID = header.chain_id.string else { return nil }
        return Explorer.Status(
            chainID: chainID,
            height: header.height.int64(default: 0),
            time: header.time.string(default: "")
        )
    }

    /// The most recent blocks, newest first.
    ///
    /// Served by CometBFT's `/blockchain?minHeight=&maxHeight=` range query:
    /// one request for the whole page instead of one per block. The LCD has no
    /// equivalent, which is the only reason this knows about the RPC port at
    /// all — and if that port is unreachable, which a REST-only deployment
    /// makes normal, it falls back to fetching each height from the LCD.
    func recentBlocks(_ count: Int = 8) async -> [Explorer.Block] {
        // CometBFT refuses more than 20 block metas per call and silently
        // clamps the range, so asking for more would quietly return fewer
        // rather than erroring.
        let capped = min(count, 20)
        if let range = await blockRange(capped) { return range }
        return await blocksViaLCD(capped)
    }

    /// Returns nil rather than an empty list when the RPC cannot serve it, so
    /// the caller can tell "no blocks" from "no RPC" and fall back.
    private func blockRange(_ count: Int) async -> [Explorer.Block]? {
        guard let tip = await status()?.height else { return nil }
        let min = Swift.max(1, tip - Int64(count) + 1)
        guard let json = try? await rest.getRPC(
            "/blockchain?minHeight=\(min)&maxHeight=\(tip)"
        ) else { return nil }

        let metas = json.result.block_metas.array
        guard !metas.isEmpty else { return nil }
        return metas.map { meta in
            Explorer.Block(
                height: meta.header.height.int64(default: 0),
                time: meta.header.time.string(default: ""),
                txCount: Int(meta.num_txs.int64(default: 0))
            )
        }
    }

    private func blocksViaLCD(_ count: Int) async -> [Explorer.Block] {
        guard let tip = await status() else { return [] }
        let heights = stride(from: tip.height, through: Swift.max(1, tip.height - Int64(count) + 1), by: -1)

        return await withTaskGroup(of: Explorer.Block?.self) { group in
            for height in heights {
                group.addTask { await self.block(height) }
            }
            var blocks = [Explorer.Block]()
            for await block in group { if let block { blocks.append(block) } }
            return blocks.sorted { $0.height > $1.height }
        }
    }

    private func block(_ height: Int64) async -> Explorer.Block? {
        guard let json = try? await rest.get("/cosmos/base/tendermint/v1beta1/blocks/\(height)")
        else { return nil }
        let header = json.block.header
        return Explorer.Block(
            height: header.height.int64(default: 0),
            time: header.time.string(default: ""),
            txCount: json.block.data.txs.array.count
        )
    }

    /// These transactions, newest first: each looked up by hash (a point
    /// read in the node's index). A hash the node does not know (not yet
    /// indexed, or dropped from the mempool) is left out.
    ///
    /// Gentle on the node (round-6 R6-E-7): a tx found in a block never
    /// changes, so it is kept in memory and not asked for again; at most
    /// ``Explorer/lookupConcurrency`` lookups are in flight; and a lookup the
    /// node could not answer (busy, rate-limited, unreachable) is retried
    /// with a backoff rather than taken for "no such tx".
    func transactions(hashes: [String]) async -> [Explorer.Tx] {
        let rest = self.rest
        return await Explorer.lookupAll(hashes, fetch: { hash in
            do {
                return Explorer.lookup(answer: try await rest.get("/cosmos/tx/v1beta1/txs/\(hash)"))
            } catch {
                return Explorer.lookup(error: error)
            }
        }, sleep: { seconds in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        })
    }
}

public extension Explorer {
    /// What one by-hash lookup said.
    enum Lookup: Sendable {
        case found(Tx)
        /// The node answered: it has no such tx (yet).
        case notFound
        /// The node could not be asked, or did not answer: ask again.
        case unavailable
    }

    static let lookupConcurrency = 4
    static let lookupAttempts = 3
    static let lookupBackoff: Double = 1

    /// Drops the remembered lookups (the wallets were forgotten).
    static func forgetLookups() { found.removeAll() }

    /// A 2xx answer as a lookup result.
    static func lookup(answer json: JSON) -> Lookup {
        tx(lookup: json).map(Lookup.found) ?? .notFound
    }

    /// A failed request as a lookup result. 404 is the node's "no such tx"
    /// (the gateway's NotFound); 400 a hash it will never know. Anything
    /// else (a busy edge's 503, Cloudflare's 429, a 5xx, a timeout, no
    /// connection, a body that is not JSON) says nothing about the tx.
    static func lookup(error: Swift.Error) -> Lookup {
        if case let EarthRest.Error.http(status, _) = error, status == 404 || status == 400 {
            return .notFound
        }
        return .unavailable
    }

    /// The lookups behind ``EarthClient/transactions(hashes:)``, with the
    /// request and the wait passed in (tests).
    static func lookupAll(
        _ hashes: [String],
        fetch: @escaping @Sendable (String) async -> Lookup,
        sleep: @escaping @Sendable (Double) async -> Void
    ) async -> [Tx] {
        var seen = Set<String>()
        let wanted = hashes.filter(SentTxLog.isHash).map { $0.uppercased() }.filter { seen.insert($0).inserted }
        var out: [Tx] = []
        var ask: [String] = []
        for hash in wanted {
            if let tx = found.get(hash) { out.append(tx) } else { ask.append(hash) }
        }
        let one: @Sendable (String) async -> Tx? = { hash in
            for attempt in 1 ... lookupAttempts {
                switch await fetch(hash) {
                case let .found(tx):
                    if tx.height > 0 { found.set(hash, tx) }
                    return tx
                case .notFound:
                    return nil
                case .unavailable:
                    if attempt < lookupAttempts { await sleep(lookupBackoff * Double(1 << (attempt - 1))) }
                }
            }
            return nil
        }
        await withTaskGroup(of: Tx?.self) { group in
            var next = ask.makeIterator()
            for _ in 0 ..< lookupConcurrency {
                guard let hash = next.next() else { break }
                group.addTask { await one(hash) }
            }
            while let result = await group.next() {
                if let result { out.append(result) }
                if let hash = next.next() { group.addTask { await one(hash) } }
            }
        }
        return out.sorted { $0.height > $1.height }
    }

    /// Txs found in a block, by upper-case hash: they never change.
    internal static let found = FoundTxs(limit: 512)

    internal final class FoundTxs: @unchecked Sendable {
        private var byHash: [String: Tx] = [:]
        private var order: [String] = []
        private let limit: Int
        private let lock = NSLock()

        init(limit: Int) { self.limit = limit }

        func get(_ hash: String) -> Tx? {
            lock.lock(); defer { lock.unlock() }
            return byHash[hash]
        }

        func set(_ hash: String, _ tx: Tx) {
            lock.lock(); defer { lock.unlock() }
            if byHash.updateValue(tx, forKey: hash) == nil { order.append(hash) }
            while order.count > limit { byHash.removeValue(forKey: order.removeFirst()) }
        }

        func removeAll() {
            lock.lock(); defer { lock.unlock() }
            byHash = [:]
            order = []
        }
    }
}

public extension Explorer {
    /// A `GET /cosmos/tx/v1beta1/txs/{hash}` answer as a row's data, or nil
    /// when it names no tx.
    static func tx(lookup json: JSON) -> Tx? {
        let response = json.tx_response
        guard let hash = response.txhash.string, !hash.isEmpty else { return nil }
        let messages = json.tx.body.messages.array
        return Tx(
            hash: hash,
            height: response.height.int64(default: 0),
            success: response.code.int64(default: 0) == 0,
            timestamp: response.timestamp.string(default: ""),
            // "/cosmos.bank.v1beta1.MsgSend" -> "MsgSend".
            types: messages.compactMap { $0["@type"].string?.components(separatedBy: ".").last },
            first: (messages.first?.raw as? [String: Any]) ?? [:],
            fee: json.tx.auth_info.fee.amount.array.compactMap { c in
                guard let d = c.denom.string, let a = Int64(c.amount.string ?? "") else { return nil }
                return ActivityCoin(d, a)
            },
            feeGranter: json.tx.auth_info.fee.granter.string ?? "",
            feePayer: json.tx.auth_info.fee.payer.string ?? "",
            code: response.code.int64(default: 0),
            rawLog: response.raw_log.string ?? "",
            memo: json.tx.body.memo.string ?? "",
            gasUsed: response.gas_used.int64(default: 0),
            gasWanted: response.gas_wanted.int64(default: 0)
        )
    }
}
