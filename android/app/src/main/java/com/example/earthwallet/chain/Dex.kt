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
        /**
         * 14-day-weighted swap volume, in real uerth.
         *
         * The chain does the weighting and hands over a plain number: a trade a
         * week ago counts (13/14)^7 of one made today. Nothing here has to age
         * it, and nothing here should try — an earlier version decayed this
         * client-side against a mechanism the chain had already replaced, and
         * the resulting APR drifted further out every day.
         *
         * At a steady trading rate it settles at about fourteen times the daily
         * volume, which is how an APR estimate works back to a daily figure.
         */
        val volumeErth: String = "0",
        /** Day index this pool last traded (block time / 86400). */
        val lastTradedDay: Long = 0,
    )

    /** Days in the rolling volume window. Mirrors types.VolumeDecayWindowDays. */
    const val VOLUME_WINDOW_DAYS = 14

    /** The LP share denom for a pool. Mirrors types.LPShareDenom. */
    fun shareDenom(poolId: Long): String = "dexlp/$poolId"

    /** How long withdrawn shares are escrowed before they pay out. */
    fun lpUnbondingSeconds(): Long {
        val (code, body) = EarthRest.get("/earth/dex/v1/params")
        if (code !in 200..299) return 0
        return JSONObject(body).getJSONObject("params")
            .optString("lp_unbonding_seconds", "0").toLongOrNull() ?: 0
    }

    private fun coin(denom: String, amount: String) =
        CoinOuterClass.Coin.newBuilder().setDenom(denom).setAmount(amount).build()

    // --- queries ---

    fun pools(): List<Pool> {
        val (code, body) = EarthRest.get("/earth/dex/v1/pool")
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
                    volumeErth = p.optString("volume_erth", "0"),
                    lastTradedDay = p.optString("last_traded_day", "0").toLongOrNull() ?: 0L,
                )
            )
        }
        return out
    }

    /** A withdrawal waiting out its escrow. */
    data class Unbonding(
        val poolId: Long,
        val shares: String,
        /** Unix seconds at which it pays out on its own. */
        val completionTime: Long,
    )

    /**
     * Withdrawals this address has waiting.
     *
     * Between submitting one and it landing there is nothing in the balance to
     * show for it — the shares have left and the assets have not arrived — so
     * without this the week looks like the funds went nowhere.
     */
    fun unbondings(address: String): List<Unbonding> {
        val (code, body) = EarthRest.get("/earth/dex/v1/unbondings/$address")
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONArray("unbondings") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            Unbonding(
                poolId = u.optString("pool_id", "0").toLongOrNull() ?: 0L,
                shares = u.optJSONObject("shares")?.optString("amount", "0") ?: "0",
                completionTime = u.optString("completion_time", "0").toLongOrNull() ?: 0L,
            )
        }
    }

    /** Pool that pairs ERTH with the given spoke token denom, if any. */
    fun poolForToken(tokenDenom: String): Pool? = pools().find { it.tokenDenom == tokenDenom }

    /** Swap fee as a percent string (e.g. "0.3"). */
    fun swapFeePercent(): String {
        val (code, body) = EarthRest.get("/earth/dex/v1/params")
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
