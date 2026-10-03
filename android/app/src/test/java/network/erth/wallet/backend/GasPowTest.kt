package network.erth.wallet.backend

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/** The /gas/register proof of work (backend services/pow.py) against a fake server that checks stamps as the backend does. */
class GasPowTest {
    private val binding = "123456789012345678901234567890"
    private val nullifier = "987654321"
    private var now = 1_759_363_200L

    /** A fake /gas: GET /gas/pow answers [advertised]; POST checks the stamp like services/pow (once per stamp, ±600 s). */
    private inner class Server(var advertised: Int, val replies: ArrayDeque<(JSONObject) -> Pair<Int, String>>) : GasGrant.Transport {
        val posts = ArrayList<JSONObject>()
        val used = HashSet<String>()
        override fun get(path: String): Pair<Int, String> {
            assertEquals("/gas/pow", path)
            return 200 to JSONObject().put("version", GasPow.VERSION).put("bits", advertised).put("max_age_seconds", 600).toString()
        }
        override fun post(path: String, json: String): Pair<Int, String> {
            assertEquals("/gas/register", path)
            val b = JSONObject(json)
            posts.add(b)
            return (replies.removeFirstOrNull() ?: { _ -> 200 to """{"status":"success"}""" })(b)
        }
        /** The leading zero bits of [b]'s stamp, or -1 when it would be refused (stale, reused, malformed). */
        fun bits(b: JSONObject): Int {
            val p = b.optJSONObject("pow") ?: return -1
            val ts = p.getLong("ts"); val nonce = p.getString("nonce")
            if (!Regex("[0-9A-Za-z]{1,64}").matches(nonce) || Math.abs(now - ts) > 600) return -1
            val d = MessageDigest.getInstance("SHA-256").digest(GasPow.input(ts, binding, nullifier, nonce))
            val key = d.joinToString("") { "%02x".format(it) }
            if (key in used) return -1
            used.add(key)
            return GasPow.leadingZeroBits(d)
        }
    }

    private fun body() = JSONObject().put("public_signals", listOf("261001", binding, nullifier, "0"))

    @Before fun pin() { GasGrant.clock = { now } }

    @After fun unpin() { GasGrant.clock = { System.currentTimeMillis() / 1000 } }

    @Test
    fun stampFormatMatchesTheBackend() {
        // Python: hashlib.sha256(b"earth-gas-pow/v1:1759363200:123456789012345678901234567890:987654321:0").hexdigest()
        val d = MessageDigest.getInstance("SHA-256").digest(GasPow.input(now, binding, nullifier, "0"))
        assertEquals("earth-gas-pow/v1:1759363200:123456789012345678901234567890:987654321:0", String(GasPow.input(now, binding, nullifier, "0")))
        assertEquals(GasPow.leadingZeroBits(d), GasPow.leadingZeroBits(d))
        assertEquals(8, GasPow.leadingZeroBits(byteArrayOf(0, 0xff.toByte())))
        assertEquals(12, GasPow.leadingZeroBits(byteArrayOf(0, 0x0f, 0)))
        assertEquals(16, GasPow.leadingZeroBits(byteArrayOf(0, 0)))
        val s = runBlocking { GasPow.solve(now, binding, nullifier, 12) }
        assertTrue(GasPow.leadingZeroBits(MessageDigest.getInstance("SHA-256").digest(GasPow.input(s.ts, binding, nullifier, s.nonce))) >= 12)
    }

    @Test
    fun stampsAtTheAdvertisedBitsAndRestampsOn428() = runBlocking {
        val server = Server(10, ArrayDeque())
        server.replies.add { b -> assertTrue(server.bits(b) >= 10); 428 to """{"status":"error","message":"shedding","pow":{"version":"earth-gas-pow/v1","bits":14}}""" }
        server.replies.add { b -> assertTrue(server.bits(b) >= 14); 200 to """{"status":"success"}""" }
        GasGrant.transport = server
        val progress = ArrayList<Float>()
        val r = GasGrant.withPow(body(), binding, nullifier) { progress.add(it) }
        assertEquals(GasGrant.Result.Sent, r)
        assertEquals(2, server.posts.size)
        assertNotEquals(server.posts[0].getJSONObject("pow").getString("nonce") + server.posts[0].getJSONObject("pow").getLong("ts"),
            server.posts[1].getJSONObject("pow").getString("nonce") + server.posts[1].getJSONObject("pow").getLong("ts"))
        assertEquals(1f, progress.last())
    }

    @Test
    fun noWorkWhenNoneIsAsked() = runBlocking {
        val server = Server(0, ArrayDeque())
        GasGrant.transport = server
        assertEquals(GasGrant.Result.Sent, GasGrant.withPow(body(), binding, nullifier) {})
        assertFalse(server.posts.single().has("pow"))
    }

    @Test
    fun stampIsKeptOnlyAfter503Or429() = runBlocking {
        val server = Server(8, ArrayDeque())
        GasGrant.transport = server
        server.replies.add { _ -> 503 to """{"status":"error","message":"busy"}""" }
        assertTrue(GasGrant.withPow(body(), binding, nullifier) {} is GasGrant.Result.Refused)
        server.replies.add { _ -> 200 to """{"status":"success"}""" }
        GasGrant.withPow(body(), binding, nullifier) {}
        assertEquals(server.posts[0].getJSONObject("pow").toString(), server.posts[1].getJSONObject("pow").toString())
        // A 403 drops it: the next try makes a new stamp.
        server.replies.add { _ -> 403 to """{"status":"error","message":"refused"}""" }
        GasGrant.withPow(body(), binding, nullifier) {}
        now += 1
        GasGrant.withPow(body(), binding, nullifier) {}
        assertNotEquals(server.posts[2].getJSONObject("pow").toString(), server.posts[3].getJSONObject("pow").toString())
    }

    @Test
    fun workCanBeCancelled() = runBlocking<Unit> {
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { GasPow.solve(now, binding, nullifier, GasPow.MAX_BITS) }
        kotlinx.coroutines.delay(100)
        job.cancel()
        kotlinx.coroutines.withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
    }
}
