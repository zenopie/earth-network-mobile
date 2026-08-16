package network.erth.wallet.chain

import com.google.protobuf.Any as ProtoAny
import cosmos.base.v1beta1.CoinOuterClass
import network.erth.earth.proto.dex.MsgAddLiquidity
import network.erth.earth.proto.dex.MsgRemoveLiquidity
import network.erth.earth.proto.dex.MsgSwap
import org.json.JSONObject

/**
 * x/dex (spoke-and-wheel AMM hubbed on ERTH) queries + messages.
 */
object Dex {

    data class Pool(
        val id: Long,
        val erthReserve: String, // uerth
        val tokenDenom: String,
        val tokenReserve: String,
    )

    private fun coin(denom: String, amount: String) =
        CoinOuterClass.Coin.newBuilder().setDenom(denom).setAmount(amount).build()

    // --- queries ---

    fun pools(): List<Pool> {
        val (code, body) = EarthRest.get("/earth-network/earth/dex/v1/pool")
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("pool") ?: return emptyList()
        val out = ArrayList<Pool>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            out.add(
                Pool(
                    id = p.optString("pool_id", "0").toLong(),
                    erthReserve = p.getJSONObject("reserve_erth").getString("amount"),
                    tokenDenom = p.getJSONObject("reserve_token").getString("denom"),
                    tokenReserve = p.getJSONObject("reserve_token").getString("amount"),
                )
            )
        }
        return out
    }

    /** Pool that pairs ERTH with the given spoke token denom, if any. */
    fun poolForToken(tokenDenom: String): Pool? = pools().find { it.tokenDenom == tokenDenom }

    /** Swap fee as a percent string (e.g. "0.3"). */
    fun swapFeePercent(): String {
        val (code, body) = EarthRest.get("/earth-network/earth/dex/v1/params")
        if (code !in 200..299) return "0"
        return JSONObject(body).getJSONObject("params").optString("swap_fee", "0")
    }

    // --- messages ---

    fun msgSwap(creator: String, tokenInDenom: String, tokenInAmount: String, denomOut: String, minOut: String): ProtoAny {
        val msg = MsgSwap.newBuilder()
            .setCreator(creator)
            .setTokenIn(coin(tokenInDenom, tokenInAmount))
            .setDenomOut(denomOut)
            .setMinAmountOut(minOut)
            .build()
        return EarthTx.anyOf("/earth.dex.v1.MsgSwap", msg)
    }

    fun msgAddLiquidity(creator: String, poolId: Long, denomA: String, amtA: String, denomB: String, amtB: String): ProtoAny {
        val msg = MsgAddLiquidity.newBuilder()
            .setCreator(creator).setPoolId(poolId)
            .setAmountA(coin(denomA, amtA)).setAmountB(coin(denomB, amtB))
            .build()
        return EarthTx.anyOf("/earth.dex.v1.MsgAddLiquidity", msg)
    }

    fun msgRemoveLiquidity(creator: String, poolId: Long, sharesDenom: String, sharesAmount: String): ProtoAny {
        val msg = MsgRemoveLiquidity.newBuilder()
            .setCreator(creator).setPoolId(poolId)
            .setShares(coin(sharesDenom, sharesAmount))
            .build()
        return EarthTx.anyOf("/earth.dex.v1.MsgRemoveLiquidity", msg)
    }
}
