package network.erth.wallet.chain

import network.erth.earth.proto.allocation.StreamId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class ClaimAllocationTest {

    @Test
    fun encodesAsTheChainDoes() {
        assertEquals("0a0d65617274683163726561746f7210011807", hex(StreamId.STREAM_ID_CARETAKER))
    }

    @Test
    fun namesTheOptionsOwnStream() {
        // Only the stream field (2) differs: GROUNDWORKS = 2.
        assertEquals("0a0d65617274683163726561746f7210021807", hex(StreamId.STREAM_ID_GROUNDWORKS))
    }

    private fun hex(stream: StreamId) =
        Allocation.claim("earth1creator", stream, 7).toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun option(accumulated: String, amount: String, last: String) =
        Allocation.OptionInfo(id = 1, description = "", kind = "ALLOCATION_KIND_ADDRESS", amountAllocated = amount, accumulated = accumulated, lastRewardIndex = last)

    @Test
    fun owedSettlesLikeTheChain() {
        // 3 * (index delta 2.5e18) / 1e18 = 7.5, floored: 7, on top of 10 accrued.
        val o = option("10", "3", "1000000000000000000")
        assertEquals(BigInteger.valueOf(17), o.owed("3500000000000000000"))
    }

    @Test
    fun owedIsTheAccrualWithNoWeightOrNoNewIndex() {
        assertEquals(BigInteger.valueOf(10), option("10", "0", "0").owed("5000000000000000000"))
        assertEquals(BigInteger.valueOf(10), option("10", "3", "5000000000000000000").owed("5000000000000000000"))
    }
}
