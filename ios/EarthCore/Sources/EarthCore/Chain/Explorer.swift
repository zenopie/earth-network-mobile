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
}

public extension EarthClient {

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
