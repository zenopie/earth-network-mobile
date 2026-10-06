package network.erth.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** The custom-node rules: https anywhere, plain http only to this phone or a private network. */
class NodeConfigTest {

    @Test
    fun normalizes() {
        assertEquals("https://node.example.com", NodeConfig.normalize("  https://node.example.com/ "))
        assertEquals("https://node.example.com/lcd", NodeConfig.normalize("https://node.example.com/lcd//"))
        assertNull(NodeConfig.normalize("node.example.com"))
        assertNull(NodeConfig.normalize("ftp://node.example.com"))
        assertNull(NodeConfig.normalize("https://user:pw@node.example.com"))
        assertNull(NodeConfig.normalize("https://node.example.com/?a=1"))
        assertNull(NodeConfig.normalize("https://node.example.com/#x"))
    }

    @Test
    fun localAddresses() {
        for (h in listOf("localhost", "127.0.0.1", "10.0.2.2", "192.168.1.20", "172.16.0.5", "169.254.1.1", "::1", "fd00::1", "mynode.local")) {
            assertTrue(h, NodeConfig.isLocal(h))
        }
        for (h in listOf("8.8.8.8", "172.32.0.1", "lcd.erth.network", "2001:4860::8888", "cafe.bad", "")) {
            assertFalse(h, NodeConfig.isLocal(h))
        }
    }

    /** R2-MC-03: the rules iOS NodeSettings.isLocal applies, case for case. */
    @Test
    fun localRulesMatchIos() {
        for (h in listOf("::ffff:192.168.1.5", "[::ffff:10.0.0.1]", "fec0::1", "fe80::1", "fc00::1")) assertTrue(h, NodeConfig.isLocal(h))
        for (h in listOf("::ffff:8.8.8.8", "300.1.1.1", "192.168.1.256", "2001:db8::1", "::2")) assertFalse(h, NodeConfig.isLocal(h))
    }

    @Test
    fun cleartextOnlyLocal() {
        assertNull(NodeConfig.problem("https://node.example.com"))
        assertNull(NodeConfig.problem("http://192.168.1.20:1317"))
        assertNull(NodeConfig.problem("http://[fd00::1]:1317"))
        assertNotNull(NodeConfig.problem("http://node.example.com"))
        assertNotNull(NodeConfig.problem("http://8.8.8.8:1317"))
    }

    @Test
    fun builtInsMustBeHttps() {
        // With the default node, no http:// base is allowed, local or not.
        assertTrue(NodeConfig.allowed("https://lcd.erth.network"))
        assertFalse(NodeConfig.allowed("http://127.0.0.1:1317"))
    }

    /** The genesis hash is over the decoded chunks in order, whatever their split. */
    @Test
    fun genesisHashOverChunks() {
        val genesis = "{\"genesis_time\":\"2026-10-02T12:00:00Z\",\"chain_id\":\"earth-1\"}".toByteArray()
        val want = MessageDigest.getInstance("SHA-256").digest(genesis).joinToString("") { "%02x".format(it) }
        fun served(parts: List<ByteArray>): (Int) -> String = { i ->
            """{"jsonrpc":"2.0","id":-1,"result":{"chunk":"$i","total":"${parts.size}","data":"${Base64.getEncoder().encodeToString(parts[i])}"}}"""
        }
        assertEquals(want, NodeConfig.genesisSha256(served(listOf(genesis))))
        assertEquals(want, NodeConfig.genesisSha256(served(listOf(genesis.copyOfRange(0, 10), genesis.copyOfRange(10, 30), genesis.copyOfRange(30, genesis.size)))))
        // A chunk out of order, a changing total, too many chunks, an error: refused.
        assertThrows(IllegalStateException::class.java) { NodeConfig.genesisSha256 { """{"result":{"chunk":"1","total":"2","data":"YQ=="}}""" } }
        assertThrows(IllegalStateException::class.java) {
            NodeConfig.genesisSha256 { i -> """{"result":{"chunk":"$i","total":"${i + 2}","data":"YQ=="}}""" }
        }
        assertThrows(IllegalStateException::class.java) { NodeConfig.genesisSha256 { i -> """{"result":{"chunk":"$i","total":"99","data":"YQ=="}}""" } }
        assertThrows(IllegalStateException::class.java) { NodeConfig.genesisSha256 { """{"jsonrpc":"2.0","error":{"code":-32603}}""" } }
    }

    /** The pin is 64 lowercase hex digits (set at the ceremony; iOS holds the same). */
    @Test
    fun genesisPinIsASha256() {
        assertTrue(Regex("[0-9a-f]{64}").matches(network.erth.wallet.Constants.EARTH_GENESIS_SHA256))
    }

    /** A node without an RPC is refused before anything is asked of it: only the RPC serves the genesis. */
    @Test
    fun rpcIsRequired() {
        val e = assertThrows(IllegalArgumentException::class.java) { NodeConfig.probe(NodeConfig.Node("https://node.example.com", "")) }
        assertTrue(e.message!!.contains("RPC"))
    }
}
