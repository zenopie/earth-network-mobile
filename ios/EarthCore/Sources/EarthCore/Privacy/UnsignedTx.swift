import Foundation

/// A private tx: exactly one private msg, no signer infos, no signatures, a
/// fee of exactly the msg's total fee in uerth, no payer, granter, memo,
/// extension or unordered flag (x/shielded/ante NewRouter and
/// ValidateTxDecorator). Ports `privacy/tx/UnsignedTx.kt`. The SDK's CLI
/// cannot build one (`--gas auto` signs), so the wallet encodes the raw bytes
/// itself and the REST broadcast takes them as they are.
public enum UnsignedTx {
    public static func build(_ msg: any PrivateMsg, gasLimit: UInt64) -> Data {
        let body = TxBody(messages: [msg.asAny()])
        let authInfo = AuthInfo(
            signerInfos: [],
            fee: Fee(amount: [Coin(denom: Constants.gasDenom, amount: String(msg.totalFee))], gasLimit: gasLimit)
        )
        return TxRaw(bodyBytes: body.encoded(), authInfoBytes: authInfo.encoded(), signatures: []).encoded()
    }

    /// The private msg a TxRaw carries, with its declared fee and gas limit.
    public struct Decoded {
        public let msg: any PrivateMsg
        public let signatures: Int
        public let signerInfos: Int
        public let feeCoins: [(denom: String, amount: String)]
        public let gasLimit: UInt64
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
                       feeCoins: coins, gasLimit: fee.uint64(2))
    }
}
