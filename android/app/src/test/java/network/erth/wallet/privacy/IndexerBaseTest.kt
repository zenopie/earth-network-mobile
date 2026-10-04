package network.erth.wallet.privacy

import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The status's base is taken only as exactly /privacy/<chain_id>/<genesis>, never as a host. */
class IndexerBaseTest {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val paths = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile private var status = JSONObject()

    init {
        // A one-request-per-connection HTTP/1.0 server: enough for HttpURLConnection.
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { c ->
                    val r = c.getInputStream().bufferedReader()
                    val line = r.readLine() ?: return@use
                    while (!r.readLine().isNullOrEmpty()) { /* headers */ }
                    val target = line.split(" ")[1]
                    paths.add(target)
                    val body = (if (target == "/privacy/status") status.toString()
                    else """{"format":2,"fields":["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"],"notes":[],"next_pos":0,"complete":true,"synced_height":1}""").toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun indexer() = HttpPrivacyIndexer("http://127.0.0.1:${server.localPort}", "earth-1")

    private fun status(chainId: Any?, genesis: Any?, base: Any?) {
        status = JSONObject().put("chain_id", chainId ?: JSONObject.NULL).put("genesis", genesis ?: JSONObject.NULL)
            .put("base", base ?: JSONObject.NULL).put("synced_height", 1).put("halted", JSONObject.NULL)
    }

    @Test
    fun validBaseIsUsedOnThisHost() {
        status("earth-1", "0123456789abcdef", "/privacy/earth-1/0123456789abcdef")
        indexer().notes(0)
        assertEquals(listOf("/privacy/status", "/privacy/earth-1/0123456789abcdef/notes?from_pos=0"), paths)
    }

    @Test
    fun hostileBasesAreRefused() {
        val bad = listOf(
            "@evil.example/privacy/earth-1/0123456789abcdef",
            "//evil.example/privacy/earth-1/0123456789abcdef",
            "https://evil.example/privacy/earth-1/0123456789abcdef",
            "/privacy/earth-1/0123456789abcdef/../../x",
            "/privacy/earth-1/0123456789abcdef?x=",
            "/privacy/earth-2/0123456789abcdef",
            "/privacy/earth-1/0123456789ABCDEF",
            "privacy/earth-1/0123456789abcdef",
        )
        for (b in bad) {
            status("earth-1", "0123456789abcdef", b)
            paths.clear()
            assertThrows(b, IOException::class.java) { indexer().notes(0) }
            assertEquals(b, listOf("/privacy/status"), paths)
        }
    }

    @Test
    fun nullOrOddChainIdIsRefused() {
        assertFalse(HttpPrivacyIndexer.validBase("/privacy/null/0123456789abcdef", "earth-1", null, "0123456789abcdef"))
        assertFalse(HttpPrivacyIndexer.validBase("/privacy/earth 1/0123456789abcdef", "earth 1", "earth 1", "0123456789abcdef"))
        assertFalse(HttpPrivacyIndexer.validBase("/privacy/earth-1/null", "earth-1", "earth-1", null))
        assertTrue(HttpPrivacyIndexer.validBase("/privacy/earth-1/0123456789abcdef", "earth-1", "earth-1", "0123456789abcdef"))
        status(null, "0123456789abcdef", "/privacy/null/0123456789abcdef")
        assertThrows(IOException::class.java) { indexer().status() }
    }
}
