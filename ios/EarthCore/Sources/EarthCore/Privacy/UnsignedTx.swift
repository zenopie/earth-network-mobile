import CryptoKit
import Foundation

/// A private tx: exactly one private msg, no signer infos, no signatures, a
/// fee of exactly the msg's total fee in uerth, no payer, granter,
/// extension, timeout_timestamp or unordered flag (x/shielded/ante NewRouter
/// and ValidateTxDecorator). The memo, timeout_height and gas limit are bound
/// by the msg's sighash (PrivateMsgs.TxFields), so they are fixed before
/// proving. Ports `privacy/tx/UnsignedTx.kt`. The SDK's CLI
/// cannot build one (`--gas auto` signs), so the wallet encodes the raw bytes
/// itself and the REST broadcast takes them as they are.
public enum UnsignedTx {
    public static func build(_ msg: any PrivateMsg, gasLimit: UInt64, memo: String = "", timeoutHeight: UInt64 = 0) -> Data {
        let body = TxBody(messages: [msg.asAny()], memo: memo, timeoutHeight: timeoutHeight)
        let authInfo = AuthInfo(
            signerInfos: [],
            fee: Fee(amount: [Coin(denom: Constants.gasDenom, amount: String(msg.totalFee))], gasLimit: gasLimit)
        )
        return TxRaw(bodyBytes: body.encoded(), authInfoBytes: authInfo.encoded(), signatures: []).encoded()
    }

    public static func build(_ msg: any PrivateMsg, tx: PrivateMsgs.TxFields) -> Data {
        build(msg, gasLimit: tx.gasLimit, memo: tx.memo, timeoutHeight: tx.timeoutHeight)
    }

    /// The tx hash the chain will name `txBytes` by: SHA-256 of the raw bytes, uppercase hex (audit 4: known before broadcast).
    public static func hash(_ txBytes: Data) -> String {
        SHA256.hash(data: txBytes).map { String(format: "%02X", $0) }.joined()
    }

    /// CheckTx refused the tx (a non-zero code): it never entered the mempool and never lands.
    public struct TxRejected: Swift.Error, LocalizedError, Sendable {
        public let code: Int
        public let log: String
        public let codespace: String
        public init(code: Int, log: String, codespace: String = "") { self.code = code; self.log = log; self.codespace = codespace }
        public var errorDescription: String? { "tx rejected (code \(code)\(codespace.isEmpty ? "" : ", \(codespace)")): \(log)" }
    }

    /// The private msg a TxRaw carries, with its declared fee, gas limit and body fields.
    public struct Decoded {
        public let msg: any PrivateMsg
        public let signatures: Int
        public let signerInfos: Int
        public let feeCoins: [(denom: String, amount: String)]
        public let gasLimit: UInt64
        public let memo: String
        public let timeoutHeight: UInt64

        /// The tx fields its sighash binds.
        public var txFields: PrivateMsgs.TxFields { .init(memo: memo, timeoutHeight: timeoutHeight, gasLimit: gasLimit) }
    }

    public static func decode(_ raw: Data) throws -> Decoded {
        let tx = try ProtoFields(raw)
        let body = try ProtoFields(tx.bytes(1))
        let anys = body.repeatedBytes(1)
        guard anys.count == 1 else { throw PrivateMsgs.Error.shape("a private tx carries exactly one msg") }
        let any = try ProtoFields(anys[0])
        let msg = try PrivateMsgs.decode(typeURL: any.string(1), value: any.bytes(2))
        let auth = try ProtoFields(tx.bytes(2))
        let fee = try ProtoFields(auth.bytes(2))
        let coins = try fee.repeatedBytes(1).map { try ProtoFields($0) }.map { (denom: $0.string(1), amount: $0.string(2)) }
        return Decoded(msg: msg, signatures: tx.repeatedBytes(3).count, signerInfos: auth.repeatedBytes(1).count,
                       feeCoins: coins, gasLimit: fee.uint64(2), memo: body.string(2), timeoutHeight: body.uint64(3))
    }
}
