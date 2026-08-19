import Foundation

/// Transactions, read back off the chain.
///
/// Ports the part of `chain/Explorer.kt` the wallet screen needs: what this
/// address has done lately. The chain indexes by event, so "mine" is two
/// searches — what I signed, and what was sent to me — merged.
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

    /// This address's recent transactions, newest first.
    ///
    /// Two queries because the chain indexes the signer and the recipient
    /// under different events, and a received transfer was signed by someone
    /// else — searching only `message.sender` would show a wallet nothing it
    /// had ever been paid.
    func transactions(for address: String, limit: Int = 20) async -> [Explorer.Tx] {
        async let sent = searchTransactions("message.sender='\(address)'", limit: limit)
        async let received = searchTransactions("transfer.recipient='\(address)'", limit: limit)

        var byHash = [String: Explorer.Tx]()
        for tx in await sent + (await received) { byHash[tx.hash] = tx }
        return byHash.values.sorted { $0.height > $1.height }.prefix(limit).map { $0 }
    }

    private func searchTransactions(_ query: String, limit: Int) async -> [Explorer.Tx] {
        let escaped = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? query
        guard let json = try? await rest.get(
            "/cosmos/tx/v1beta1/txs?query=\(escaped)&order_by=ORDER_BY_DESC&limit=\(limit)"
        ) else { return [] }

        let responses = json.tx_responses.array
        let bodies = json.txs.array

        return responses.enumerated().compactMap { index, response in
            guard let hash = response.txhash.string else { return nil }
            let messages = bodies.indices.contains(index)
                ? bodies[index].body.messages.array
                : []
            return Explorer.Tx(
                hash: hash,
                height: response.height.int64(default: 0),
                success: response.code.int64(default: 0) == 0,
                timestamp: response.timestamp.string(default: ""),
                // "/cosmos.bank.v1beta1.MsgSend" -> "MsgSend".
                types: messages.compactMap { $0["@type"].string?.components(separatedBy: ".").last },
                first: (messages.first?.raw as? [String: Any]) ?? [:]
            )
        }
    }
}
