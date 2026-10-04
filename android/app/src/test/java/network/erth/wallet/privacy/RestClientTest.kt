package network.erth.wallet.privacy

import java.net.ServerSocket
import kotlin.concurrent.thread
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostile node and indexer responses: deep JSON refused before parsing, redirects never followed. */
class RestClientTest : WalletTest() {
    private val status = JSONObject().put("chain_id", "earth-1").put("genesis", "0123456789abcdef")
        .put("base", "/privacy/earth-1/0123456789abcdef").put("synced_height", 1).put("halted", JSONObject.NULL).toString().toByteArray()

    private fun serve(handler: (String) -> Pair<String, ByteArray>): ServerSocket {
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { c ->
                    val r = c.getInputStream().bufferedReader()
                    val line = r.readLine() ?: return@use
                    while (!r.readLine().isNullOrEmpty()) { }
                    val (head, body) = handler(line.split(" ")[1])
                    c.getOutputStream().apply { write("$head\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()); write(body); flush() }
                }
            }
        }
        return server.also { servers.add(it) }
    }

    private val servers = ArrayList<ServerSocket>()

    @After fun stop() = servers.forEach { it.close() }


    /** A deeply nested body is refused before org.json sees it. */
    @Test
    fun deepJsonIsRefusedBeforeParsing() {
        val deep = ("{\"notes\":" + "[".repeat(500_000)).toByteArray()
        val s = serve { p -> "HTTP/1.0 200 OK\r\nContent-Type: application/json" to (if (p == "/privacy/status") status else deep) }
        val idx = HttpPrivacyIndexer("http://127.0.0.1:${s.localPort}", "earth-1")
        val e = assertThrows(java.io.IOException::class.java) { idx.notes(0) }
        assertTrue(e.message!!, "nests deeper" in e.message!!)
        // Within the bound, strings with brackets in them included, parses.
        network.erth.wallet.chain.EarthRest.checkJsonDepth("""{"a":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"]","b":[[1]]}""")
    }

    /** A redirect is never followed; the other origin is never asked. */
    @Test
    fun redirectsAreNotFollowed() {
        val hits = java.util.Collections.synchronizedList(ArrayList<String>())
        val other = serve { p -> hits.add(p); "HTTP/1.0 200 OK\r\nContent-Type: application/json" to
            """{"format":2,"fields":["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"],"notes":[],"next_pos":0,"complete":false,"synced_height":1}""".toByteArray() }
        val s = serve { p ->
            if (p == "/privacy/status") "HTTP/1.0 200 OK\r\nContent-Type: application/json" to status
            else "HTTP/1.0 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/elsewhere$p" to ByteArray(0)
        }
        val idx = HttpPrivacyIndexer("http://127.0.0.1:${s.localPort}", "earth-1")
        assertThrows(java.io.IOException::class.java) { idx.notes(0) }
        assertTrue(hits.isEmpty())
    }
}
