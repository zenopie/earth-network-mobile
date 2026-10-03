package network.erth.wallet.privacy.tx

import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.MessageLite
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.tx.v1beta1.Tx
import network.erth.wallet.Constants
import network.erth.wallet.chain.EarthRest
import network.erth.wallet.chain.EarthTx
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException

/**
 * A private tx: exactly one private msg, no signer infos, no signatures, a fee
 * of exactly the msg's total fee in uerth, no payer, granter, extension,
 * timeout_timestamp or unordered flag (x/shielded/ante NewRouter and
 * ValidateTxDecorator). The memo, timeout_height and gas limit are bound by
 * the msg's sighash (PrivateMsgs.TxFields), so they are fixed before proving. The
 * SDK's CLI cannot build one (`--gas auto` signs), so the wallet encodes the
 * raw bytes itself and the REST broadcast takes them as they are.
 */
object UnsignedTx {

    fun build(msg: MessageLite, gasLimit: Long, memo: String = "", timeoutHeight: Long = 0): ByteArray {
        val body = Tx.TxBody.newBuilder()
            .addMessages(ProtoAny.newBuilder().setTypeUrl(PrivateMsgs.typeUrl(msg)).setValue(msg.toByteString()))
            .setMemo(memo)
            .setTimeoutHeight(timeoutHeight)
            .build()
        val coin = CoinOuterClass.Coin.newBuilder()
            .setDenom(Constants.UERTH_DENOM)
            .setAmount(PrivateMsgs.totalFee(msg).toString())
        val authInfo = Tx.AuthInfo.newBuilder()
            .setFee(Tx.Fee.newBuilder().addAmount(coin).setGasLimit(gasLimit))
            .build()
        return Tx.TxRaw.newBuilder()
            .setBodyBytes(body.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .build()
            .toByteArray()
    }

    fun build(msg: MessageLite, tx: PrivateMsgs.TxFields): ByteArray = build(msg, tx.gasLimit, tx.memo, tx.timeoutHeight)

    /**
     * Gas the chain charges this tx. In simulate mode the private ante charges
     * every proof's fixed gas but does not verify it, and still runs every
     * state check (anchor, nullifiers, the action's), so a tx carrying
     * placeholder proofs over its real nullifiers and roots measures what the
     * proven tx will cost and fails here for the same reasons it would.
     */
    fun simulate(txBytes: ByteArray): Long {
        val payload = JSONObject().put("tx_bytes", txBytes.toByteString().base64()).toString()
        val (code, resp) = EarthRest.postJson("/cosmos/tx/v1beta1/simulate", payload)
        if (code !in 200..299) throw IOException("simulate failed ($code): ${message(resp)}")
        return JSONObject(resp).getJSONObject("gas_info").getString("gas_used").toLong()
    }

    /** Broadcasts and waits for the block. Returns the tx hash; throws on any non-zero code. */
    fun broadcast(txBytes: ByteArray): String {
        val payload = JSONObject()
            .put("tx_bytes", txBytes.toByteString().base64())
            .put("mode", "BROADCAST_MODE_SYNC")
            .toString()
        val (code, resp) = EarthRest.postJson("/cosmos/tx/v1beta1/txs", payload)
        if (code !in 200..299) throw IOException("broadcast failed ($code): ${message(resp)}")
        val txResp = JSONObject(resp).getJSONObject("tx_response")
        val checkCode = txResp.optInt("code", 0)
        if (checkCode != 0) throw IOException("tx rejected (code $checkCode): ${txResp.optString("raw_log")}")
        return EarthTx.awaitCommit(txResp.getString("txhash"))
    }

    private fun message(resp: String): String =
        runCatching { JSONObject(resp).optString("message") }.getOrNull()?.ifEmpty { null } ?: resp
}
