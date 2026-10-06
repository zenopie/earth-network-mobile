package network.erth.wallet.chain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A saved node is used only on a full check this build ran: at launch, then
 * whenever the last pass is old; one that fails is set aside for Earth's
 * node with a notice; one that cannot be reached is left as it is.
 */
class NodeRecheckTest {
    private class MapStore : NodeConfig.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(values: Map<String, String?>) = values.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
    }

    private val own = NodeConfig.Node("https://node.example.com", "https://rpc.example.com")
    private var now = 1_800_000_000L
    private var checks = 0
    private var answer: (NodeConfig.Node) -> NodeConfig.Probe = { checks++; NodeConfig.Probe("earth-1", 10) }

    @Before fun pin() {
        NodeConfig.clock = { now }
        NodeConfig.fullCheck = { answer(it) }
    }

    @After fun unpin() {
        NodeConfig.clock = { System.currentTimeMillis() / 1000 }
        NodeConfig.fullCheck = NodeConfig::probe
        NodeConfig.load(MapStore())
    }

    private fun saved() = MapStore().apply { m["lcd"] = own.lcd; m["rpc"] = own.rpc }

    /** A node an earlier build saved (no pass recorded) waits on Earth's node until its check passes. */
    @Test
    fun aNodeFromAnEarlierBuildIsCheckedBeforeUse() {
        val s = saved()
        NodeConfig.load(s)
        assertTrue(NodeConfig.current.isDefault)
        assertNotNull(NodeConfig.notice.value)
        NodeConfig.recheck(s, force = true)
        assertEquals(own, NodeConfig.current)
        assertNull(NodeConfig.notice.value)
        // A relaunch uses it at once, and checks it again.
        NodeConfig.load(s)
        assertEquals(own, NodeConfig.current)
        NodeConfig.recheck(s, force = true)
        assertEquals(2, checks)
    }

    /** A recheck that finds another genesis sets the node aside, for good until the person checks it again. */
    @Test
    fun aFailedRecheckFallsBackToEarthsNode() {
        val s = saved()
        NodeConfig.load(s)
        NodeConfig.recheck(s, force = true)
        answer = { checks++; throw IllegalStateException("That node follows another earth-1.") }
        NodeConfig.recheck(s, force = true)
        assertTrue(NodeConfig.current.isDefault)
        assertTrue(NodeConfig.notice.value!!.contains("another earth-1"))
        // Set aside across a relaunch, and not checked again on its own.
        NodeConfig.load(s)
        assertTrue(NodeConfig.current.isDefault)
        assertNotNull(NodeConfig.notice.value)
        val before = checks
        NodeConfig.recheck(s, force = true)
        assertEquals(before, checks)
    }

    /** An LCD that does not answer at all read nothing: the node stays, and is tried again. */
    @Test
    fun anUnreachableNodeIsLeftAsItIs() {
        val s = saved()
        NodeConfig.load(s)
        NodeConfig.recheck(s, force = true)
        answer = { checks++; throw NodeConfig.LcdUnreachable("Could not reach the LCD") }
        NodeConfig.recheck(s, force = true)
        assertEquals(own, NodeConfig.current)
        assertNull(s.get("suspended"))
    }

    /** A pass stands for RECHECK_SECONDS: the heavy check is not rerun before (unless forced), and is after. */
    @Test
    fun aPassIsCachedPerNode() {
        val s = saved()
        NodeConfig.load(s)
        NodeConfig.recheck(s, force = true)
        now += NodeConfig.RECHECK_SECONDS - 1
        NodeConfig.recheck(s, force = false)
        assertEquals(1, checks)
        now += 1
        NodeConfig.recheck(s, force = false)
        assertEquals(2, checks)
    }

    /** A pass recorded against another genesis pin (an earlier build's) does not count. */
    @Test
    fun aPassAgainstAnotherGenesisDoesNotCount() {
        val s = saved()
        s.m["verified"] = """{"${own.lcd}\n${own.rpc}":{"genesis":"${"0".repeat(64)}","at":$now}}"""
        NodeConfig.load(s)
        assertTrue(NodeConfig.current.isDefault)
    }
}
