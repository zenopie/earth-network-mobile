package network.erth.wallet.privacy

import network.erth.wallet.privacy.handles.HandleDirectory
import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.sync.WalletSync.Companion.StateRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which handle a wallet holds: the chain's directory adopted only by owner,
 * swept handles dropped, renew-only binds, renewals refused without a handle,
 * and hostile lease times clamped.
 */
class HandleOwnershipTest : WalletTest() {
    @Test
    fun anEntryIsAdoptedOnlyWhenItsOwnerIsThisIdentity() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        register(chain, a, "603")
        register(chain, b, "604")
        // b binds "bait" to a's address: the directory names a's address, owner b.
        b.bindHandle("bait", a.keys.address); b.sync()
        a.sync()
        val dir = chain.handleDirectory().chainDirectory()
        assertEquals(b.handleOwner(), dir.getValue("bait").owner)
        assertTrue(a.reconcileHandle(dir, chain.now + 1).isEmpty())
        assertEquals("", a.store.state.handle)
        // A directory without owners: nothing adopted, the entry shown unverified.
        chain.ownerHex = { "" }
        val addressed = a.reconcileHandle(chain.handleDirectory().chainDirectory(), chain.now + 1)
        assertEquals(listOf("bait"), addressed.map { it.handle })
        assertEquals("", a.store.state.handle)
        chain.ownerHex = null
        // a's own handle, lost by the store: adopted by owner.
        a.bindHandle("alice"); a.sync()
        a.store.state.handle = ""; a.store.state.handleSetAt = 0
        assertTrue(a.reconcileHandle(chain.handleDirectory().chainDirectory(), chain.now + 1).isEmpty())
        assertEquals("alice", a.store.state.handle)
        // A held handle whose entry names another owner is dropped.
        a.store.state.handle = "bait"; a.store.state.handleSetAt = 0
        a.reconcileHandle(chain.handleDirectory().chainDirectory(), chain.now + 1)
        assertEquals("alice", a.store.state.handle)
    }

    @Test
    fun ownersParseOnlyAs64Hex() {
        val h = "ab".repeat(32)
        assertEquals(h, Handles.owner(h.uppercase()))
        assertEquals("", Handles.owner(null))
        assertEquals("", Handles.owner("ab".repeat(31)))
        assertEquals("", Handles.owner("zz".repeat(32)))
        val withOwner = JSONObject("""{"handles":[["alice","erthz1x","live",10,20,"$h"],["bob","erthz1y","live",10,20]],"height":5,"size":2,"from_index":0,"last_page":true}""")
        val page = HttpPrivacyIndexer.parseHandles(withOwner)
        assertEquals(listOf(h, ""), page.handles.map { it.owner })
    }

    @Test
    fun aRenewOnlyBindNeverChangesTheHandleHeld() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "605")
        a.bindHandle("mine"); a.sync()
        val sent = chain.txs.size
        val e = runCatching { a.bindHandle("other", renewOnly = true) }.exceptionOrNull()
        assertTrue("$e", e is IllegalStateException && e.message!!.contains("free @mine"))
        assertEquals(sent, chain.txs.size)
        a.bindHandle("mine", renewOnly = true)
        assertEquals("mine", a.store.state.handle)
    }

    @Test
    fun theDirectoryScanAdoptsAHandleAtThisAddressAndDropsASweptOne() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "111")
        a.bindHandle("alice"); a.sync()
        // As a store that lost it (no record, e.g. a handle bound by an older app).
        a.store.state.handle = ""; a.store.state.handleSetAt = 0
        val dir = chain.handleDirectory()
        val addressed = a.reconcileHandle(dir.chainDirectory(), chain.now + 1)
        // Adopted by its owner; held, no other entry is offered.
        assertEquals(emptyList<String>(), addressed.map { it.handle })
        assertEquals("alice", a.store.state.handle)
        // Reminded on, from the address match alone.
        chain.now += chain.handleLease - 86_400
        val e = chain.handleDirectory().chainDirectory()["alice"]!!
        val due = Reminders.due(Reminders.Inputs(chain.now, true, null, true, 0, "", null, addressed = listOf(e)))
        assertEquals(listOf("alice"), due.filterIsInstance<Reminders.Reminder.HandleExpiring>().map { it.handle })
        // Swept by the chain: dropped, so a claim is offered again.
        chain.now += 86_400 + chain.handleRenewal + 1
        a.reconcileHandle(chain.handleDirectory().chainDirectory(), chain.now + 1)
        assertEquals("", a.store.state.handle)
    }

    @Test
    fun aNonHolderRenewalIsRefusedAtNoCost() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "777")
        chain.now += 2 * 86_400
        val c = wallet(chain, carol)
        register(chain, c, "777")
        c.shieldOutput("uerth", 5_000_000).let { chain.shield("uerth", 5_000_000, it.pc, it.ciphertext) }
        c.sync()
        val before = bal(c)
        // Not held, bound not met: sent with no bound, refused by the chain before any fee.
        val h = runCatching { c.bindHandle("carol") }.exceptionOrNull()
        assertTrue("$h", h is PrivacyWallet.NotHeld && h.waitSeconds > chain.handleLease)
        val v = runCatching { c.setCaretaker(mapOf(1L to 100L)) }.exceptionOrNull()
        assertTrue("$v", v is PrivacyWallet.NotHeld)
        c.sync()
        assertEquals(before, bal(c))
        assertTrue(c.notes.filter { it.unspent }.all { it.pendingAt == null })
        assertEquals("", c.store.state.handle)
    }

    @Test
    fun hostileHandleAndCaretakerTimesNeitherWrapNorTrap() {
        val now = 1_800_000_000L
        // Reminders on Int64 extremes (iOS trapped on these).
        val e = HandleEntry("alice", "erthz1x", "live", Long.MIN_VALUE, Long.MAX_VALUE)
        for (c in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 1L)) {
            val due = Reminders.due(Reminders.Inputs(now, true, 0, false, c, "alice", e, addressed = listOf(e.copy(handle = "bob")), ownAddress = "erthz1y"))
            due.forEach { Reminders.text(it, now) }
        }
        Reminders.text(Reminders.Reminder.HandleExpiring("alice", Long.MIN_VALUE, Long.MAX_VALUE, true), Long.MIN_VALUE)
        Reminders.text(Reminders.Reminder.CaretakerExpiring(Long.MAX_VALUE, false), Long.MIN_VALUE)
        assertEquals(Long.MAX_VALUE, Handles.satAdd(Long.MAX_VALUE, 1))
        assertEquals(Long.MIN_VALUE, Handles.satSub(Long.MIN_VALUE, 1))
        // The directory refuses an entry whose times no lease has.
        fun dir(x: HandleEntry) = HandleDirectory({ _, _ -> HandleDirectory.Page(listOf(x), "") }, now = { now })
        val ok = HandleEntry("alice", "erthz1x", "live", now + 86_400, now + 31 * 86_400)
        assertEquals(setOf("alice"), dir(ok).chainDirectory().keys)
        for (bad in listOf(
            ok.copy(expiresAt = Long.MIN_VALUE, renewalUntil = Long.MAX_VALUE),
            ok.copy(expiresAt = 0),
            ok.copy(renewalUntil = ok.expiresAt - 1),
            ok.copy(renewalUntil = now + Handles.MAX_AHEAD_SECONDS + 1),
        )) assertTrue("$bad", runCatching { dir(bad).chainDirectory() }.exceptionOrNull() is HandleDirectory.Inconsistent)
        // A node reporting a 2^63 caretaker expiry: bounded before it is stored.
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "111")
        chain.forgeCaretakerExpiry = Long.MAX_VALUE
        a.setCaretaker(mapOf(1L to 100L))
        assertTrue(a.caretakerExpiresAt() <= chain.now + Handles.MAX_AHEAD_SECONDS)
        assertEquals(chain.now + chain.caretakerLease, a.caretakerExpiresAt())
        // And a record carrying one cannot be stored past the bound either.
        val s = a.store.state
        WalletSync.applyStateRecord(s, Long.MAX_VALUE, 1, StateRecord.Caretaker(WalletSync.RECORD_HOLDS, 0xffffffffL, mapOf(1L to 100L)), chain.now)
        assertTrue(s.caretakerExpiresAt <= chain.now + Handles.MAX_AHEAD_SECONDS)
    }
}
