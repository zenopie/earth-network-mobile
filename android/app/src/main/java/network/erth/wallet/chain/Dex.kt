package network.erth.wallet.chain

import com.google.protobuf.Any as ProtoAny
import cosmos.base.v1beta1.CoinOuterClass
import network.erth.earth.proto.dex.MsgAddLiquidity
import network.erth.earth.proto.dex.MsgRemoveLiquidity
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

    /** Swap fee as a percent string (e.g. "0.3"). */
    fun swapFeePercent(): String {
        val (code, body) = EarthRest.get("/earth/dex/v1/params")
        if (code !in 200..299) return "0"
        return JSONObject(body).getJSONObject("params").optString("swap_fee", "0")
    }

    /** The chain's own price for a swap: what it pays, and its uerth fee over every hop. */
    data class Simulated(val amountOut: java.math.BigInteger, val feeErth: java.math.BigInteger)

    /**
     * x/dex SimulateSwapExactIn: the swap itself run at the current state
     * (pending LP rewards settled into the reserves first) and discarded.
     * Null when the node does not serve it (an older chain) or refuses the
     * swap; callers fall back to SwapMath over the pool reserves.
     */
    fun simulateSwapExactIn(offerDenom: String, offerAmount: java.math.BigInteger, askDenom: String): Simulated? {
        val q = "offer_denom=${enc(offerDenom)}&offer_amount=$offerAmount&ask_denom=${enc(askDenom)}"
        val (code, body) = EarthRest.get("/earth/dex/v1/simulate_swap_exact_in?$q")
        if (code !in 200..299) return null
        return parseSimulated(body)
    }

    /** The REST response body of SimulateSwapExactIn, or null if it is not one. */
    fun parseSimulated(body: String): Simulated? = runCatching {
        val o = JSONObject(body)
        Simulated(
            o.getJSONObject("token_out").getString("amount").toBigInteger(),
            o.getJSONObject("fee").getString("amount").toBigInteger(),
        )
    }.getOrNull()?.takeIf { it.amountOut.signum() > 0 }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // --- messages ---

    /** The token only shields hold: its pool's legs are note paths (MsgNoteSwap, MsgAddLiquidityShielded). */
    const val SHIELDED_ONLY = "uanml"

    /**
     * [minShares]: the fewest shares the deposit accepts (field 5),
     * computed as the shielded deposit's; "" only for an empty pool.
     */
    fun msgAddLiquidity(creator: String, poolId: Long, denomA: String, amtA: String, denomB: String, amtB: String, minShares: String): ProtoAny {
        val msg = MsgAddLiquidity.newBuilder()
            .setCreator(creator).setPoolId(poolId)
            .setAmountA(coin(denomA, amtA)).setAmountB(coin(denomB, amtB))
            .setMinShares(minShares)
            .build()
        return EarthTx.anyOf("/earth.dex.v1.MsgAddLiquidity", msg)
    }

    /**
     * [pc] receives the token leg as a shielded note, [ciphertext] its
     * 177-byte blind ciphertext: required for a pool whose token is
     * shielded-only (ANML, pool 1), refused for any other.
     */
    fun msgRemoveLiquidity(
        creator: String, poolId: Long, sharesDenom: String, sharesAmount: String,
        pc: ByteArray? = null, ciphertext: ByteArray? = null,
    ): ProtoAny {
        val b = MsgRemoveLiquidity.newBuilder()
            .setCreator(creator).setPoolId(poolId)
            .setShares(coin(sharesDenom, sharesAmount))
        // The note's amount-blind (v2) ciphertext is required with the pc.
        if (pc != null) b.setPc(com.google.protobuf.ByteString.copyFrom(pc))
        if (ciphertext != null) b.setCiphertext(com.google.protobuf.ByteString.copyFrom(ciphertext))
        val msg = b.build()
        return EarthTx.anyOf("/earth.dex.v1.MsgRemoveLiquidity", msg)
    }
}
