package network.erth.wallet.chain

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** R6-E-7: the activity refresh's lookups are bounded, retried, and remembered. */
class TxLookupTest {
    private fun hash(i: Int) = "%02X".format(i % 256).repeat(32)

    private fun tx(h: String, height: Long) =
        Explorer.Tx(h, height, true, "", emptyList(), emptyList())

    @Before
    fun reset() = Explorer.forgetLookups()

    @Test
    fun atMostFourInFlight() = runBlocking {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val hashes = (0 until 50).map(::hash)
        val got = Explorer.lookupAll(hashes, fetch = { h ->
            val n = inFlight.incrementAndGet()
            most.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            delay(5)
            inFlight.decrementAndGet()
            Explorer.Lookup.Found(tx(h, 10))
        }, sleep = {})
        assertEquals(50, got.size)
        assertTrue("${most.get()} lookups at once", most.get() <= Explorer.LOOKUP_CONCURRENCY)
    }

    @Test
    fun refusedLookupIsRetriedNotDropped() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val sleeps = mutableListOf<Long>()
        val got = Explorer.lookupAll(listOf(hash(1), hash(2), hash(3)), fetch = { h ->
            val n = calls.getOrPut(h) { AtomicInteger() }.incrementAndGet()
            when (h) {
                hash(1) -> if (n < 3) Explorer.Lookup.Unavailable else Explorer.Lookup.Found(tx(h, 7))
                hash(2) -> Explorer.Lookup.NotFound
                else -> Explorer.Lookup.Unavailable
            }
        }, sleep = { synchronized(sleeps) { sleeps.add(it) } })
        assertEquals(listOf(hash(1)), got.map { it.hash })
        assertEquals(3, calls[hash(1)]!!.get())
        assertEquals(1, calls[hash(2)]!!.get()) // "no such tx" is an answer: not asked again
        assertEquals(Explorer.LOOKUP_ATTEMPTS, calls[hash(3)]!!.get())
        assertTrue(sleeps.all { it >= 1000 })
    }

    @Test
    fun committedTxIsNotAskedAgain() = runBlocking {
        val calls = AtomicInteger()
        val fetch: suspend (String) -> Explorer.Lookup = { h -> calls.incrementAndGet(); Explorer.Lookup.Found(tx(h, 9)) }
        Explorer.lookupAll(listOf(hash(4)), fetch, sleep = {})
        val again = Explorer.lookupAll(listOf(hash(4).lowercase()), fetch, sleep = {})
        assertEquals(1, calls.get())
        assertEquals(listOf(hash(4)), again.map { it.hash })
        Explorer.forgetLookups()
        Explorer.lookupAll(listOf(hash(4)), fetch, sleep = {})
        assertEquals(2, calls.get())
    }

    @Test
    fun classifiesAnswers() {
        val body = """{"tx":{"body":{"messages":[]}},"tx_response":{"txhash":"${hash(5)}","height":"3","code":0}}"""
        assertTrue(Explorer.classify(200, body) is Explorer.Lookup.Found)
        assertEquals(Explorer.Lookup.NotFound, Explorer.classify(404, """{"code":5,"message":"tx not found"}"""))
        assertEquals(Explorer.Lookup.NotFound, Explorer.classify(200, "{}"))
        for (code in listOf(0, 408, 429, 500, 502, 503, 504)) {
            assertEquals("HTTP $code", Explorer.Lookup.Unavailable, Explorer.classify(code, "busy"))
        }
        assertEquals(Explorer.Lookup.Unavailable, Explorer.classify(200, "<html>portal</html>"))
    }
}
