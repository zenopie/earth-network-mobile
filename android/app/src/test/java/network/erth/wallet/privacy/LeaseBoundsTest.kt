package network.erth.wallet.privacy

import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predecessor bounds on handle and caretaker msgs: from the chain's
 * lease bounds (checked), the longest lease ever in force, a renewal period
 * bounded like a claim, and a lapsed split as a new one.
 */
class LeaseBoundsTest : WalletTest() {
    private fun entry(chain: FakeChain, h: String): HandleEntry? = chain.handleDirectory().lookup(h)

    /** [w] registers [passport] after another wallet did: a switch, its leaf naming a predecessor. Funded for fees. */
    private fun switched(chain: FakeChain, w: PrivacyWallet, passport: String) {
        register(chain, w, passport)
        w.shieldOutput("uerth", 5_000_000).let { chain.shield("uerth", 5_000_000, it.pc, it.ciphertext) }
        w.sync()
        assertTrue(w.store.state.identity!!.predecessorAt > 0)
    }

    @Test
    fun claimBoundUsesTheLongestLeaseNotParams() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, passport = "801")
        chain.now += 2 * 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "801")
        val pred = c.store.state.identity!!.predecessorAt
        // Governance cut the lease to 30 days; the chain still bounds claims by the year ever in force.
        chain.handleLeaseMax = chain.handleLease
        chain.handleLease = 30L * 86_400
        chain.now = pred + 30L * 86_400 + 86_400 + 2 * 3_600 + 600
        c.sync()
        val reads0 = chain.leaseBoundsReads
        val e = runCatching { c.bindHandle("carol") }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.NotYet && e.waitSeconds > 300L * 86_400)
        assertTrue(chain.leaseBoundsReads > reads0)
        assertNull(entry(chain, "carol"))
        // Past the longest lease: a claim, bounded by it.
        chain.now = pred + chain.handleLeaseMax + 86_400 + 2 * 3_600 + 600
        c.sync()
        c.bindHandle("carol")
        assertNotNull(entry(chain, "carol"))
        val mp = chain.prover.allMemberships.last().maxPredecessor
        assertTrue(mp < chain.now - chain.handleLeaseMax - 86_400 && mp % 3_600 == 0L && mp >= pred)
        // The caretaker bound likewise: a held longer lease after a cut keeps bounding splits.
        chain.caretakerLeaseHold = 400L * 86_400
        val v = runCatching { c.setCaretaker(mapOf(1L to 100L)) }.exceptionOrNull()
        assertTrue("$v", v is PrivacyWallet.NotYet)
        dumpWitnesses(chain, "fix6LeaseBounds")
    }

    @Test
    fun leaseBoundsThatDoNotAddUpAreRefused() {
        val chain = FakeChain()
        val a = wallet(chain, alice, r = reads(chain, bounds = { chain.leaseBounds().let { it.copy(handleClaimBound = it.handleClaimBound + 1) } }))
        register(chain, a, passport = "901")
        val e = runCatching { a.bindHandle("alice") }.exceptionOrNull()
        assertTrue("$e", e is java.io.IOException && e.message!!.contains("do not add up"))
        val z = wallet(chain, bob, r = reads(chain, bounds = { chain.leaseBounds().copy(handleLeaseSeconds = 0) }))
        register(chain, z, passport = "902")
        assertTrue(runCatching { z.bindHandle("bob") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun handleInItsRenewalPeriodIsBoundedLikeAClaim() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, passport = "501")
        a.bindHandle("alice")
        assertEquals(entry(chain, "alice")!!.expiresAt, a.handleExpiresAt())
        // Lapsed into its renewal period: a fresh identity meets the claim bound and renews.
        chain.now += chain.handleLease + 10
        a.sync()
        assertEquals(HandleEntry.RENEWAL, entry(chain, "alice")!!.status)
        a.bindHandle("alice")
        assertEquals(HandleEntry.LIVE, entry(chain, "alice")!!.status)
        assertTrue(chain.prover.allMemberships.last().maxPredecessor < Privacy.NO_BOUND)

        // A switched identity whose own bound has not passed holds a handle (moved to it), lets it
        // lapse: renewing now is a claim it cannot make. Refused before anything is sent.
        val b = wallet(chain, bob)
        register(chain, b, passport = "502")
        b.bindHandle("bobby")
        b.sync()
        chain.now += 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "502")
        // (as MsgMoveHandle after the switch would; here it is recorded as moved in)
        val nfC = Privacy.scopeNullifier(c.keys.idSecret, Privacy.handleScope())
        chain.handles["bobby"] = chain.handles.getValue("bobby").copy(nullifier = nfC)
        c.adoptMoved("bobby", null, 0)
        c.reconcileHandle(mapOf("bobby" to entry(chain, "bobby")!!), chain.now + 1)
        assertTrue(c.handleExpiresAt() > chain.now)
        // Live: renewed with no bound (it holds it).
        c.bindHandle("bobby")
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        c.sync()
        chain.now += chain.handleLease + 10
        c.sync()
        c.reconcileHandle(mapOf("bobby" to entry(chain, "bobby")!!), chain.now + 1)
        val sent = chain.txs.size
        val e = runCatching { c.bindHandle("bobby") }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.HandleNotLive && e.message!!.contains("renewal period"))
        assertEquals(sent, chain.txs.size)
        // ... and it cannot be moved either.
        val m = runCatching { c.moveHandle(PrivacyWallet.Successor(PrivacyKeys.fromMnemonic(alice), c.store.state.identity!!)) }.exceptionOrNull()
        assertTrue("$m", m is PrivacyWallet.HandleNotMovable)
        assertEquals(sent, chain.txs.size)
        dumpWitnesses(chain, "fix6HandleRenewal")
    }

    @Test
    fun aHandleWhoseExpiryTheWalletLacksIsTriedAndTheChainDecides() {
        val chain = FakeChain()
        val b = wallet(chain, bob)
        register(chain, b, passport = "602")
        b.bindHandle("bobby")
        chain.now += 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "602")
        chain.handles["bobby"] = chain.handles.getValue("bobby").copy(nullifier = Privacy.scopeNullifier(c.keys.idSecret, Privacy.handleScope()))
        c.adoptMoved("bobby", null, 0)
        assertEquals(0L, c.handleExpiresAt())
        chain.now += chain.handleLease + 10
        c.sync()
        // No expiry known: a no-bound attempt; the chain refuses it in its ante (no fee): NotHeld.
        val e = runCatching { c.bindHandle("bobby") }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.NotHeld)
    }

    @Test
    fun aLapsedCaretakerSplitIsANewSplit() {
        val chain = FakeChain()
        val b = wallet(chain, bob)
        register(chain, b, passport = "702")
        b.setCaretaker(mapOf(1L to 100L))
        b.sync()
        chain.now += 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "702")
        // The split moved to c (as MsgMoveCaretaker would), then lapses unswept.
        val exp = chain.now + 3 * 86_400
        val nfB = Privacy.scopeNullifier(b.keys.idSecret, Privacy.caretakerScope())
        val nfC = Privacy.scopeNullifier(c.keys.idSecret, Privacy.caretakerScope())
        chain.caretakerVotes[nfC] = chain.caretakerVotes.remove(nfB)!!
        chain.caretakerExpiry.remove(nfB); chain.caretakerExpiry[nfC] = exp
        c.adoptMoved(null, mapOf(1L to 100L), exp)
        // Live: refreshed with no bound.
        c.setCaretaker(mapOf(1L to 100L))
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        c.sync()
        chain.now = c.caretakerExpiresAt() + 10
        c.sync()
        val sent = chain.txs.size
        val e = runCatching { c.setCaretaker(mapOf(1L to 100L)) }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.CaretakerLapsed)
        assertEquals(sent, chain.txs.size)
        // Clearing needs no bound.
        c.setCaretaker(emptyMap())
    }
}
