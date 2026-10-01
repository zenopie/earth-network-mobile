package network.erth.wallet.privacy

import network.erth.wallet.chain.math.SwapMath
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * SwapMath against x/dex/keeper/amm.go's own output (dex_amm.json, written by
 * tools/privacyvectors/gen.sh from the chain's unexported swap functions):
 * every hop's output and fee, and two-hop routes through the hub.
 */
class SwapMathTest {
    private val json by lazy { JSONObject(Vectors.resource("privacy/dex_amm.json").decodeToString()) }

    @Test
    fun hopsMatchTheChain() {
        val hops = json.getJSONArray("hops")
        for (i in 0 until hops.length()) {
            val h = hops.getJSONObject(i)
            val fee = BigDecimal(h.getString("fee"))
            val re = BigInteger(h.getString("reserve_erth"))
            val rt = BigInteger(h.getString("reserve_token"))
            val inp = BigInteger(h.getString("amount_in"))
            val q = if (h.getString("dir") == "hub_for_token") SwapMath.hubForToken(re, rt, inp, fee) else SwapMath.tokenForHub(re, rt, inp, fee)
            assertEquals("$h out", BigInteger(h.getString("amount_out")), q!!.amountOut)
            assertEquals("$h fee", BigInteger(h.getString("fee_erth")), q.feeErth)
        }
    }

    @Test
    fun twoHopRoutesMatchTheChain() {
        val twos = json.getJSONArray("two_hop")
        for (i in 0 until twos.length()) {
            val t = twos.getJSONObject(i)
            val pools = mapOf(
                "ua" to SwapMath.Reserves(BigInteger(t.getString("in_pool_erth")), BigInteger(t.getString("in_pool_token"))),
                "ub" to SwapMath.Reserves(BigInteger(t.getString("out_pool_erth")), BigInteger(t.getString("out_pool_token"))),
            )
            val q = SwapMath.route(pools, "uerth", "ua", BigInteger(t.getString("amount_in")), "ub", BigDecimal(t.getString("fee")))
            val want = BigInteger(t.getString("amount_out"))
            if (want.signum() == 0) assertNull(q) else assertEquals(want, q!!.amountOut)
        }
    }

    @Test
    fun slippageFloorTruncates() {
        assertEquals(BigInteger.valueOf(98_999), SwapMath.withSlippage(BigInteger.valueOf(99_999), 100))
        assertEquals(BigInteger.ZERO, SwapMath.withSlippage(BigInteger.ONE, 50))
    }

    /**
     * x/dex SimulateSwapExactIn's REST body (grpc-gateway, snake_case) and
     * the chain figure replacing the local quote's output and fee; anything
     * else leaves the local quote standing.
     */
    @Test
    fun chainSimulationReplacesLocalMaths() {
        val body = """{"token_out":{"denom":"uanml","amount":"9871"},"fee":{"denom":"uerth","amount":"30"},"erth_burned":"15"}"""
        val sim = network.erth.wallet.chain.Dex.parseSimulated(body)!!
        assertEquals(BigInteger.valueOf(9871), sim.amountOut)
        assertEquals(BigInteger.valueOf(30), sim.feeErth)
        assertNull(network.erth.wallet.chain.Dex.parseSimulated("""{"code":12,"message":"Not Implemented"}"""))
        assertNull(network.erth.wallet.chain.Dex.parseSimulated("""{"token_out":{"denom":"uanml","amount":"0"},"fee":{"denom":"uerth","amount":"0"}}"""))

        val local = SwapMath.hubForToken(BigInteger.valueOf(1_000_000), BigInteger.valueOf(1_000_000), BigInteger.valueOf(10_000), BigDecimal("0.3"))!!
        val merged = SwapMath.withChain(local, sim.amountOut, sim.feeErth)!!
        assertEquals(sim.amountOut, merged.amountOut)
        assertEquals(sim.feeErth, merged.feeErth)
        assertEquals(local.priceImpact, merged.priceImpact, 0.0)
        assertEquals(local, SwapMath.withChain(local, null, null))
        assertNull(SwapMath.withChain(null, null, null))
    }
}
