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

    /**
     * Deposits (audit 4, C2): the shares and the legs x/dex pulls, rounded
     * up, match the chain's own maths; a leg derived with depositLeg never
     * makes the other side the binding one and is never pulled past.
     */
    @Test
    fun depositsMatchTheChain() {
        val deps = json.getJSONArray("deposits")
        assertEquals(144, deps.length())
        for (i in 0 until deps.length()) {
            val d = deps.getJSONObject(i)
            fun b(k: String) = BigInteger(d.getString(k))
            val got = SwapMath.deposit(b("in_erth"), b("in_token"), b("reserve_erth"), b("reserve_token"), b("supply"))
            if (b("shares").signum() == 0) assertNull("$d", got)
            else {
                assertEquals("$d shares", b("shares"), got!!.first)
                assertEquals("$d erth", b("pull_erth"), got.second)
                assertEquals("$d token", b("pull_token"), got.third)
            }
            // From the ERTH side: the derived token leg buys at least the ERTH side's shares.
            val re = b("reserve_erth"); val rt = b("reserve_token"); val s = b("supply")
            val e = b("in_erth")
            val t = SwapMath.depositLeg(e, re, rt)
            val fromErth = e * s / re
            if (fromErth.signum() > 0) {
                val p = SwapMath.deposit(e, t, re, rt, s)!!
                assertEquals("$d from erth", fromErth, p.first)
                org.junit.Assert.assertTrue(p.second <= e && p.third <= t)
            }
        }
        assertEquals(BigInteger.valueOf(3), SwapMath.depositLeg(BigInteger.valueOf(5), BigInteger.valueOf(2), BigInteger.ONE))
        assertEquals(BigInteger.ZERO, SwapMath.depositLeg(BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE))
    }
}
