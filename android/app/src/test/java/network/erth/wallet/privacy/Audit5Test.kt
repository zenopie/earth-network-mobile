package network.erth.wallet.privacy

import network.erth.wallet.privacy.handles.HandleDirectory
import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PendingMove
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.sync.WalletSync.Companion.StateRecord
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import network.erth.wallet.referral.Referral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round 5 (mobile): restore of a held handle and caretaker split (state
 * records, the directory scan, renewals the chain checks at no cost), moves
 * that stay pending until the chain says (recorded in the new wallet before
 * the broadcast), hostile handle and caretaker times, referral links.
 */
class Audit5Test {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(chain.caretakerLease, 3_600, chain.handleLease, chain.handleRenewal)
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
    }

    private fun wallet(chain: FakeChain, words: String, store: PrivacyStore = PrivacyStore.memory()) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(words), store, chain, chain, reads(chain), chain.prover, chain.chainId, chain, now = { chain.now },
    )

    private fun bal(w: PrivacyWallet, d: String = "uerth") = w.balances()[d] ?: 0L

    private fun register(chain: FakeChain, w: PrivacyWallet, passport: String) {
        val prep = w.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), passport, Fr.of(77).toBigInteger().toString())
        w.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
    }

    /** Records moves into [store] as the app does for another wallet on the phone; [failFirst] makes the first write fail. */
    private class Recorder(val store: PrivacyStore, val now: () -> Long, var failFirst: Boolean = false) : PrivacyWallet.MoveRecorder {
        override val targetId = "target"
        override fun record(move: PendingMove) {
            if (failFirst) { failFirst = false; throw java.io.IOException("disk full (test)") }
            PrivacyWallet.recordIncoming(store, move, now())
        }
        override fun rollback(move: PendingMove) = PrivacyWallet.rollbackIncoming(store, move, now())
    }

    @Test
    fun stateRecordsRoundTripAndRefuseForgeries() {
        val k = PrivacyKeys.fromMnemonic(alice)
        val other = PrivacyKeys.fromMnemonic(bob)
        val h = WalletSync.handleMemo(k.nk, WalletSync.RECORD_HOLDS, "alice")
        assertEquals(64, h.size)
        assertEquals(StateRecord.Handle(WalletSync.RECORD_HOLDS, "alice"), WalletSync.parseStateMemo(k.nk, h))
        assertEquals(StateRecord.Handle(WalletSync.RECORD_MOVED_OUT, ""), WalletSync.parseStateMemo(k.nk, WalletSync.handleMemo(k.nk, WalletSync.RECORD_MOVED_OUT)))
        // Only nk tags one: another wallet's record, or a byte changed, is nothing.
        assertNull(WalletSync.parseStateMemo(other.nk, h))
        assertNull(WalletSync.parseStateMemo(k.nk, h.copyOf().also { it[5] = 'x'.code.toByte() }))
        val split = mapOf(1L to 60L, 300L to 40L)
        val c = WalletSync.caretakerMemo(k.nk, WalletSync.RECORD_HOLDS, 1_900_000_000, split)
        assertEquals(StateRecord.Caretaker(WalletSync.RECORD_HOLDS, 1_900_000_000, split), WalletSync.parseStateMemo(k.nk, c))
        // Twenty options with large ids do not fit: held, the split not recorded.
        val big = (0 until 20).associate { (1L shl 40) + it to 5L }
        assertEquals(StateRecord.Caretaker(WalletSync.RECORD_HOLDS, 1_900_000_000, null),
            WalletSync.parseStateMemo(k.nk, WalletSync.caretakerMemo(k.nk, WalletSync.RECORD_HOLDS, 1_900_000_000, big)))
        assertEquals(StateRecord.Caretaker(WalletSync.RECORD_NONE, 0, emptyMap()), WalletSync.parseStateMemo(k.nk, WalletSync.caretakerMemo(k.nk, WalletSync.RECORD_NONE)))
        // Goldens, the same in Audit5Tests.swift (Android/iOS byte parity).
        assertEquals("45480101616c6963650000000000000000000000000000000000000000000000000000000000000000000000000000001d745226aa7b8d3ed9c363b444ef95bb", h.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        assertEquals("45430101713fb300013cac02280000000000000000000000000000000000000000000000000000000000000000000000035eda1d684c370b3c709013ca43fb93", c.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        // A registration record is not a state record, and the reverse.
        assertNull(WalletSync.parseStateMemo(k.nk, WalletSync.regMemo(k.nk, Fr.of(77), "FR", 1_790_000_000)))
        assertNull(WalletSync.parseRegMemo(k.nk, h))
    }

    @Test
    fun restoreFindsTheHandleAndCaretakerSplit() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "111")
        a.bindHandle("alice")
        a.sync()
        a.setCaretaker(mapOf(1L to 70L, 2L to 30L))
        a.sync()
        val exp = a.caretakerExpiresAt()
        // The same mnemonic on a new phone: everything from the chain.
        val a2 = wallet(chain, alice)
        a2.sync()
        assertEquals("alice", a2.store.state.handle)
        assertEquals(mapOf(1L to 70L, 2L to 30L), a2.store.state.caretakerSplit)
        assertTrue(a2.caretakerLive())
        // The record's estimate never runs past the chain's expiry.
        assertTrue(a2.caretakerExpiresAt() in (exp - 3_600)..exp)
        // A release and a cleared split are found too.
        a.releaseHandle(); a.sync()
        a.setCaretaker(emptyMap()); a.sync()
        val a3 = wallet(chain, alice)
        a3.sync()
        assertEquals("", a3.store.state.handle)
        assertFalse(a3.caretakerLive())
    }

    @Test
    fun restoredSwitchedIdentityRenewsWhatMovedToIt() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "999")
        a.bindHandle("alice"); a.sync()
        a.setCaretaker(mapOf(1L to 100L)); a.sync()
        val bKeys = PrivacyKeys.fromMnemonic(bob)
        val bStore = PrivacyStore.memory()
        val rec = Recorder(bStore, { chain.now })
        a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, rec); a.sync()
        a.moveCaretaker(a.newOwner(bKeys, Privacy.caretakerScope()), bKeys, rec); a.sync()
        assertTrue(a.outgoingMoves().isEmpty())
        val b = wallet(chain, bob, bStore)
        register(chain, b, "999")
        assertTrue(b.store.state.identity!!.predecessorAt > 0)

        // B restored from its phrase alone: the moved-in records say what it holds.
        val b2 = wallet(chain, bob)
        b2.sync()
        assertEquals("alice", b2.store.state.handle)
        assertEquals(mapOf(1L to 100L), b2.store.state.caretakerSplit)
        b2.bindHandle("alice")
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        assertEquals(b2.address.encode(), chain.handleDirectory().chainDirectory()["alice"]!!.address)
        b2.sync()
        b2.setCaretaker(mapOf(2L to 100L))
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)

        // A restored: it moved both away and knows it.
        val a2 = wallet(chain, alice)
        a2.sync()
        assertTrue(a2.store.state.handleMovedOut && a2.store.state.caretakerMovedOut)
        assertEquals("", a2.store.state.handle)
        dumpWitnesses(chain, "audit5Restore")
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
    fun theDirectoryScanAdoptsAHandleAtThisAddressAndDropsASweptOne() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "111")
        a.bindHandle("alice"); a.sync()
        // As a store that lost it (no record, e.g. a handle bound by an older app).
        a.store.state.handle = ""; a.store.state.handleSetAt = 0
        val dir = chain.handleDirectory()
        val addressed = a.reconcileHandle(dir.chainDirectory(), chain.now + 1)
        assertEquals(listOf("alice"), addressed.map { it.handle })
        assertEquals("alice", a.store.state.handle)
        // Reminded on, from the address match alone.
        chain.now += chain.handleLease - 86_400
        val e = chain.handleDirectory().chainDirectory()["alice"]!!
        val due = Reminders.due(Reminders.Inputs(chain.now, true, null, true, 0, "", null, addressed = listOf(e)))
        assertEquals(listOf("alice"), due.filterIsInstance<Reminders.Reminder.HandleExpiring>().map { it.handle })
        // Swept by the chain: dropped (audit 5, L11), so a claim is offered again.
        chain.now += 86_400 + chain.handleRenewal + 1
        a.reconcileHandle(chain.handleDirectory().chainDirectory(), chain.now + 1)
        assertEquals("", a.store.state.handle)
    }

    @Test
    fun anUnconfirmedMoveStaysPendingInBothWalletsUntilTheChainSays() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "999")
        a.bindHandle("alice"); a.sync()
        val bKeys = PrivacyKeys.fromMnemonic(bob)
        val bStore = PrivacyStore.memory()
        chain.unconfirmedNext = 1
        val e = runCatching { a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, Recorder(bStore, { chain.now })) }.exceptionOrNull()
        assertTrue("$e", e is network.erth.wallet.chain.TxUnconfirmedException)
        // Not shown as moved: the mover still holds it, pending; the new wallet already has it, pending.
        assertEquals("alice", a.store.state.handle)
        assertFalse(a.store.state.handleMovedOut)
        assertTrue(a.outgoingMoves().single().let { !it.confirmed && it.recorded })
        assertEquals("alice", bStore.state.handle)
        assertTrue(bStore.state.pendingMoves.single().incoming)
        // No second move, renewal or release while it is in doubt.
        assertTrue(runCatching { a.bindHandle("alice") }.exceptionOrNull() is IllegalStateException)
        // The chain says: committed.
        assertFalse(a.resolvePendingMoves())
        assertEquals("", a.store.state.handle)
        assertTrue(a.store.state.handleMovedOut)
        assertTrue(a.outgoingMoves().isEmpty())
        val b = wallet(chain, bob, bStore)
        assertFalse(b.resolvePendingMoves())
        assertEquals("alice", bStore.state.handle)
        assertTrue(bStore.state.pendingMoves.isEmpty())
    }

    @Test
    fun aMoveThatDidNotHappenIsUndoneInBothWallets() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "999")
        a.bindHandle("alice"); a.sync()
        val bKeys = PrivacyKeys.fromMnemonic(bob)
        val owner = a.newOwner(bKeys, Privacy.handleScope())
        for (mode in listOf("reject", "fail", "drop")) {
            // Fees of a tx that may have landed stay pending until its timeout: fresh funds each round.
            a.shieldOutput("uerth", 5_000_000).let { chain.shield("uerth", 5_000_000, it.pc, it.ciphertext) }
            a.sync()
            val bStore = PrivacyStore.memory()
            when (mode) { "reject" -> chain.rejectNext = 1; "fail" -> chain.failInBlockNext = 1; else -> chain.dropNext = 1 }
            assertTrue(runCatching { a.moveHandle(owner, bKeys, Recorder(bStore, { chain.now })) }.isFailure)
            val b = wallet(chain, bob, bStore)
            if (mode == "drop") chain.tipAhead = 100
            a.resolvePendingMoves(); b.resolvePendingMoves()
            chain.tipAhead = 0
            assertEquals(mode, "alice", a.store.state.handle)
            assertFalse(mode, a.store.state.handleMovedOut)
            assertTrue(mode, a.outgoingMoves().isEmpty())
            assertEquals(mode, "", bStore.state.handle)
            assertTrue(mode, bStore.state.pendingMoves.isEmpty())
            a.sync()
        }
        // And the handle still moves after all that.
        a.moveHandle(owner, bKeys, Recorder(PrivacyStore.memory(), { chain.now }))
        assertTrue(a.store.state.handleMovedOut)
    }

    @Test
    fun recordingInTheNewWalletIsRetryableAndItsSyncFindsTheMoveAnyway() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "999")
        a.bindHandle("alice"); a.sync()
        val bKeys = PrivacyKeys.fromMnemonic(bob)
        val bStore = PrivacyStore.memory()
        val rec = Recorder(bStore, { chain.now }, failFirst = true)
        a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, rec)
        // Moved (confirmed), not yet recorded there: kept for a retry.
        assertTrue(a.store.state.handleMovedOut)
        val p = a.outgoingMoves().single()
        assertTrue(p.confirmed && !p.recorded)
        assertEquals("", bStore.state.handle)
        rec.record(p.copy(incoming = true, target = "", recorded = true)); a.markRecorded(p.txHash)
        assertTrue(a.outgoingMoves().isEmpty())
        assertEquals("alice", bStore.state.handle)
        // A wallet that never got the write finds the move in its own notes.
        val b2 = wallet(chain, bob)
        b2.sync()
        assertEquals("alice", b2.store.state.handle)
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

    @Test
    fun referralLinksOnlyFromTheVerifiedHost() {
        assertEquals("alice", Referral.handleFromLink("https://erth.network/ref/alice"))
        assertEquals("a-b-c", Referral.handleFromLink("https://erth.network/ref/a-b-c/"))
        for (bad in listOf(
            "earth://ref/alice", "http://erth.network/ref/alice", "https://erth.network.evil.com/ref/alice",
            "https://evil.com/ref/alice", "https://erth.network/ref/alice/more", "https://erth.network/r/alice",
            "https://erth.network:8443/ref/alice", "https://user@erth.network/ref/alice", "https://erth.network/ref/Alice",
            "https://erth.network/ref/-x-", "https://erth.network/ref/", null,
        )) assertNull(bad, Referral.handleFromLink(bad))
    }
}
