package network.erth.wallet.chain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import kotlin.random.Random

/** The settled ERTH reserve recovered from a token -> ERTH simulation (x/dex swapTokenForHub). */
class DexSettledReserveTest {

    @Test
    fun recoversTheReserveWithinTwo() {
        val rnd = Random(7)
        repeat(20_000) {
            val re = BigInteger.valueOf(rnd.nextLong(1, Long.MAX_VALUE / 4))
            val rt = BigInteger.valueOf(rnd.nextLong(1, Long.MAX_VALUE / 4))
            // What the chain's swap of rt computes before the fee split.
            val gross = re * rt / (rt + rt)
            val got = Dex.settledReserveFrom(gross, rt, rt)
            assertTrue("re=$re rt=$rt got=$got", got >= re && got - re <= BigInteger.TWO)
            assertTrue(got * rt / (rt + rt) == gross)
        }
    }
}
