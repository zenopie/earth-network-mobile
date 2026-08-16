package network.erth.wallet.chain

import android.util.Base64
import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.ByteString
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.crypto.secp256k1.PubKey
import cosmos.tx.v1beta1.Tx
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.TransactionSigner
import org.bitcoinj.core.ECKey
import org.json.JSONObject
import java.io.IOException

/**
 * EarthTx
 *
 * Core cosmos transaction plumbing for the earth chain: query the signer account,
 * assemble a SIGN_MODE_DIRECT tx from a list of messages, sign it with the app's
 * [TransactionSigner] (secp256k1), and broadcast the TxRaw. Feature modules
 * ([Bank], [Dex], [Allocation], [Staking], [Personhood]) only build the message `Any`s.
 */
object EarthTx {

    // cosmos.tx.signing.v1beta1.SignMode.SIGN_MODE_DIRECT (the app's stripped
    // tx.proto types ModeInfo.Single.mode as a plain uint32).
    private const val SIGN_MODE_DIRECT = 1

    data class Account(val number: Long, val sequence: Long)

    fun getAccount(address: String): Account {
        val (code, body) = EarthRest.get("/cosmos/auth/v1beta1/accounts/$address")
        if (code !in 200..299) throw IOException("account query failed ($code): $body")
        val acc = JSONObject(body).getJSONObject("account")
        val base = if (acc.has("account_number")) acc else acc.optJSONObject("base_account") ?: acc
        return Account(
            base.getString("account_number").toLong(),
            base.optString("sequence", "0").toLong(),
        )
    }

    /**
     * Signs and broadcasts the given messages from `key`'s account. Returns the tx
     * hash; throws on broadcast error or non-zero tx code.
     */
    fun broadcast(
        key: ECKey,
        msgs: List<ProtoAny>,
        gasLimit: Long = 400_000L,
        feeUerth: String = "2000",
    ): String {
        val signer = EarthWallet.address(key)
        val account = getAccount(signer)

        val bodyBuilder = Tx.TxBody.newBuilder()
        msgs.forEach { bodyBuilder.addMessages(it) }
        val body = bodyBuilder.build()

        val pubKeyMsg = PubKey.newBuilder()
            .setKey(ByteString.copyFrom(key.pubKeyPoint.getEncoded(true)))
            .build()
        val pubKeyAny = ProtoAny.newBuilder()
            .setTypeUrl("/cosmos.crypto.secp256k1.PubKey")
            .setValue(pubKeyMsg.toByteString())
            .build()

        val single = Tx.ModeInfo.Single.newBuilder().setMode(SIGN_MODE_DIRECT).build()
        val modeInfo = Tx.ModeInfo.newBuilder().setSingle(single).build()
        val signerInfo = Tx.SignerInfo.newBuilder()
            .setPublicKey(pubKeyAny)
            .setModeInfo(modeInfo)
            .setSequence(account.sequence)
            .build()

        val feeCoin = CoinOuterClass.Coin.newBuilder()
            .setDenom(Constants.UERTH_DENOM)
            .setAmount(feeUerth)
            .build()
        val fee = Tx.Fee.newBuilder().addAmount(feeCoin).setGasLimit(gasLimit).build()
        val authInfo = Tx.AuthInfo.newBuilder().addSignerInfos(signerInfo).setFee(fee).build()

        val signDoc = Tx.SignDoc.newBuilder()
            .setBodyBytes(body.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(Constants.EARTH_CHAIN_ID)
            .setAccountNumber(account.number)
            .build()

        val txBytes = TransactionSigner.signTransaction(signDoc, key)

        val payload = JSONObject()
            .put("tx_bytes", Base64.encodeToString(txBytes, Base64.NO_WRAP))
            .put("mode", "BROADCAST_MODE_SYNC")
            .toString()
        val (code, resp) = EarthRest.postJson("/cosmos/tx/v1beta1/txs", payload)
        if (code !in 200..299) throw IOException("broadcast failed ($code): $resp")
        val txResp = JSONObject(resp).getJSONObject("tx_response")
        // CheckTx result: rejects a malformed tx before it enters the mempool.
        val checkCode = txResp.optInt("code", 0)
        if (checkCode != 0) throw IOException("tx rejected (code $checkCode): ${txResp.optString("raw_log")}")
        val txHash = txResp.getString("txhash")
        // Wait for the tx to be included in a block and verify its execution result,
        // so callers that immediately re-query chain state see the committed effect.
        return awaitCommit(txHash)
    }

    /**
     * Polls for a broadcast tx until it is committed in a block, then returns its
     * hash (throwing if it executed with a non-zero code). Falls back to returning
     * the hash if it hasn't appeared within the timeout. Runs on the caller's
     * (IO) thread.
     */
    private fun awaitCommit(txHash: String, attempts: Int = 20, delayMs: Long = 800): String {
        for (i in 0 until attempts) {
            val (code, body) = EarthRest.get("/cosmos/tx/v1beta1/txs/$txHash")
            if (code in 200..299) {
                val tr = JSONObject(body).optJSONObject("tx_response")
                if (tr != null && tr.optString("txhash").isNotEmpty()) {
                    val execCode = tr.optInt("code", 0)
                    if (execCode != 0) throw IOException("tx failed (code $execCode): ${tr.optString("raw_log")}")
                    return txHash
                }
            }
            // Not in a block yet — wait and retry.
            try { Thread.sleep(delayMs) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return txHash
    }

    /** Wraps a proto message as an Any with the given type URL. */
    fun anyOf(typeUrl: String, msg: com.google.protobuf.MessageLite): ProtoAny =
        ProtoAny.newBuilder().setTypeUrl(typeUrl).setValue(msg.toByteString()).build()
}
