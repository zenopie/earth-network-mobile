package network.erth.wallet.chain

import com.google.protobuf.Any as ProtoAny
import cosmos.bank.v1beta1.MsgSend
import cosmos.base.v1beta1.CoinOuterClass
import org.json.JSONObject

/** x/bank queries + messages on the earth chain. */
object Bank {

    /** All balances for an address as denom -> amount (base units, as string). */
    fun balances(address: String): Map<String, String> {
        val (code, body) = EarthRest.get("/cosmos/bank/v1beta1/balances/$address")
        if (code !in 200..299) return emptyMap()
        val arr = JSONObject(body).optJSONArray("balances") ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            m[c.getString("denom")] = c.getString("amount")
        }
        return m
    }

    fun balance(address: String, denom: String): String = balances(address)[denom] ?: "0"

    /** Total supply of a single denom (base units, as string). Handles slashed denoms. */
    fun supply(denom: String): String {
        val (code, body) = EarthRest.get("/cosmos/bank/v1beta1/supply/by_denom?denom=$denom")
        if (code !in 200..299) return "0"
        return JSONObject(body).optJSONObject("amount")?.optString("amount", "0") ?: "0"
    }

    /** MsgSend of a single coin. */
    fun msgSend(from: String, to: String, denom: String, amount: String): ProtoAny {
        val coin = CoinOuterClass.Coin.newBuilder().setDenom(denom).setAmount(amount).build()
        val msg = MsgSend.newBuilder().setFromAddress(from).setToAddress(to).addAmount(coin).build()
        return EarthTx.anyOf("/cosmos.bank.v1beta1.MsgSend", msg)
    }
}
