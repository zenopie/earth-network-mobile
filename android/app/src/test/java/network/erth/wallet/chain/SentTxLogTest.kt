package network.erth.wallet.chain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentTxLogTest {
    private fun hash(i: Int) = "%02X".format(i % 256).repeat(32)

    @Test
    fun newestFirstDedupedCapped() {
        var list = emptyList<String>()
        for (i in 0 until 60) list = SentTxLog.add(list, hash(i))
        list = SentTxLog.add(list, hash(10).lowercase()) // again, any case: to the front
        assertEquals(SentTxLog.LIMIT, list.size)
        assertEquals(hash(10), list[0])
        assertEquals(hash(59), list[1])
        assertEquals(list.size, list.toSet().size)
        assertEquals(list, SentTxLog.decode(list.joinToString(",")))
    }

    @Test
    fun refusesWhatIsNotAHash() {
        assertEquals(emptyList<String>(), SentTxLog.add(emptyList(), "../../bank/v1beta1/supply"))
        assertFalse(SentTxLog.isHash("G".repeat(64)))
        assertFalse(SentTxLog.isHash("AB"))
        assertTrue(SentTxLog.isHash(hash(1)))
        assertEquals(emptyList<String>(), SentTxLog.decode("x,,AB"))
        assertEquals(emptyList<String>(), SentTxLog.decode(null))
    }

    /** The by-hash lookup's answer becomes a row's data. */
    @Test
    fun fromLookup() {
        val json = JSONObject(
            """{"tx":{"body":{"messages":[{"@type":"/cosmos.bank.v1beta1.MsgSend","from_address":"earth1a","to_address":"earth1b"}]}},
               "tx_response":{"txhash":"${hash(3)}","height":"42","code":0,"timestamp":"2026-10-06T00:00:00Z"}}""",
        )
        val tx = Explorer.fromLookup(json)!!
        assertEquals(hash(3), tx.hash)
        assertEquals(42L, tx.height)
        assertTrue(tx.success)
        assertEquals(listOf("MsgSend"), tx.types)
        assertEquals("earth1b", tx.messages[0].optString("to_address"))
        assertNull(Explorer.fromLookup(JSONObject("""{"code":5}""")))
    }
}
