import CryptoKit
import Foundation

/// Assembles and signs a SIGN_MODE_DIRECT transaction.
///
/// Mirrors `EarthTx.broadcast` on Android, minus the network half — building
/// and signing is pure, so it is testable without a chain. See `EarthClient`
/// for the query-account / broadcast / await-commit sequence around it.
public enum TransactionSigner {

    /// Android's defaults, kept identical so a transaction costs the same on
    /// both platforms.
    public static let defaultGasLimit: UInt64 = 400_000

    /// The fee for ``defaultGasLimit``, derived rather than flat — see ``Fees``.
    /// A caller that raises the gas limit must raise the fee with it, or the
    /// node rejects the transaction for insufficient fee.
    public static var defaultFeeUerth: String { Fees.forGas(defaultGasLimit) }

    /// What a "max" button leaves behind so the account can still act.
    ///
    /// Subtracting one minimum fee is not enough. Staking the maximum used to
    /// leave exactly ``defaultFeeUerth`` — one 400,000-gas transaction and no
    /// more — so the very next thing a staker wants, claiming rewards, needed
    /// 2,750 against a 2,000 balance and was unaffordable the moment it was
    /// offered. The position was staked and the account was stranded.
    ///
    /// A million gas covers the realistic follow-ups: claim across a few
    /// validators, then unstake. 5,000 uerth at 0.005uerth.
    ///
    /// Deliberately NOT applied to sending: emptying an account on purpose
    /// should be allowed to empty it, less the fee.
    public static var gasReserveUerth: String { Fees.forGas(1_000_000) }

    public struct SignedTx {
        public let signDoc: SignDoc
        public let signature: Data
        /// Ready to base64 and post to `/cosmos/tx/v1beta1/txs`.
        public let txBytes: Data
    }

    public static func sign(
        messages: [ProtoAny],
        key: EarthKey,
        accountNumber: UInt64,
        sequence: UInt64,
        chainID: String = Constants.chainID,
        gasLimit: UInt64 = defaultGasLimit,
        feeUerth: String = defaultFeeUerth,
        memo: String = ""
    ) throws -> SignedTx {
        let body = TxBody(messages: messages, memo: memo)

        let signerInfo = SignerInfo(
            publicKey: Secp256k1PubKey(key: key.publicKey).asAny(typeURL: Secp256k1PubKey.typeURL),
            sequence: sequence
        )
        let authInfo = AuthInfo(
            signerInfos: [signerInfo],
            fee: Fee(amount: [Coin(denom: Constants.gasDenom, amount: feeUerth)], gasLimit: gasLimit)
        )

        let signDoc = SignDoc(
            bodyBytes: body.encoded(),
            authInfoBytes: authInfo.encoded(),
            chainID: chainID,
            accountNumber: accountNumber
        )

        // Cosmos signs SHA256 of the SignDoc bytes. The secp256k1 library
        // hashes what it is given, so it is handed the digest's preimage — not
        // the digest — and told nothing else.
        let signature = try key.sign(signDoc.encoded())

        let raw = TxRaw(
            bodyBytes: signDoc.bodyBytes,
            authInfoBytes: signDoc.authInfoBytes,
            signatures: [signature]
        )
        return SignedTx(signDoc: signDoc, signature: signature, txBytes: raw.encoded())
    }
}
