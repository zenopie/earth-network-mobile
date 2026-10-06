package network.erth.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
