package network.erth.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BusyRetryTest {

    /** Runs against a fake transport answering [codes] in turn (the last repeating); returns (final code, calls, sleeps). */
    private fun run(method: String, vararg codes: Int): Triple<Int, Int, List<Long>> {
        var calls = 0
        val sleeps = ArrayList<Long>()
        val code = BusyRetry.run(
            method,
            once = { codes[minOf(calls++, codes.size - 1)] },
            code = { it },
            sleep = { sleeps += it },
            jitter = { 0L },
        )
        return Triple(code, calls, sleeps)
    }

    @Test
    fun retriesA503UntilItClears() {
        val (code, calls, sleeps) = run("GET", 503, 429, 200)
        assertEquals(200, code)
        assertEquals(3, calls)
        assertEquals(listOf(250L, 500L), sleeps)
    }

    @Test
    fun givesUpAfterTheCap() {
        val (code, calls, _) = run("GET", 503)
        assertEquals(503, code)
        assertEquals(1 + BusyRetry.MAX_RETRIES, calls)
    }

    @Test
    fun doesNotRetryOtherErrors() {
        assertEquals(1, run("GET", 500, 200).second)
        assertEquals(1, run("GET", 404, 200).second)
    }

    @Test
    fun neverRetriesAPost() {
        assertEquals(1, run("POST", 503, 200).second)
        assertFalse(BusyRetry.shouldRetry("POST", 429, 1))
    }

    @Test
    fun jitterStaysInItsWindow() {
        assertEquals(750L, BusyRetry.delayMs(3, 0))
        assertEquals(1000L, BusyRetry.delayMs(3, 250))
    }
}
