package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PendingMove
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switching identity: once the switch lands, the old identity's wallet moves
 * its handle and caretaker split to the new identity with move proofs (both
 * secrets on the phone). Each move is recorded in both wallets before the
 * broadcast, settled by the chain's word, undone when it did not happen, and
 * the switch target is fixed only by a confirmed move.
 */
class IdentitySwitchTest : WalletTest() {
    /** Records moves into [store] as the app does for another wallet on the phone; [failFirst] makes the first write fail. */
    private class Recorder(val store: PrivacyStore, val now: () -> Long, var failFirst: Boolean = false) : PrivacyWallet.MoveRecorder {
        override val targetId = "target"
        override fun record(move: PendingMove) {
            if (failFirst) { failFirst = false; throw java.io.IOException("disk full (test)") }
            PrivacyWallet.recordIncoming(store, move, now())
        }
        override fun rollback(move: PendingMove) = PrivacyWallet.rollbackIncoming(store, move, now())
    }

    private class CheckingRecorder(val store: PrivacyStore, val now: () -> Long, override val targetId: String = "target") : PrivacyWallet.MoveRecorder {
        override fun record(move: PendingMove) = PrivacyWallet.recordIncoming(store, move, now())
        override fun rollback(move: PendingMove) = PrivacyWallet.rollbackIncoming(store, move, now())
        override fun refusal(move: PendingMove): String? = PrivacyWallet.targetRefusal(store.state, move.kind, now())
    }

    /**
     * A registers [passport] and binds "alice"; B (on [bStore]) then switches to
     * the same passport. Returns A (synced past the switch, funded for fees),
     * B, and B as the successor a move names.
     */
    private fun switched(chain: FakeChain, passport: String, bStore: PrivacyStore = PrivacyStore.memory(), split: Boolean = false): Triple<PrivacyWallet, PrivacyWallet, PrivacyWallet.Successor> {
        val a = wallet(chain, alice)
        register(chain, a, passport)
        a.bindHandle("alice"); a.sync()
        if (split) { a.setCaretaker(mapOf(1L to 100L)); a.sync() }
        val b = wallet(chain, bob, bStore)
        register(chain, b, passport)
        a.sync()
        return Triple(a, b, PrivacyWallet.Successor(b.keys, b.store.state.identity!!))
    }

    @Test
    fun anUnconfirmedMoveStaysPendingInBothWalletsUntilTheChainSays() {
        val chain = FakeChain()
        val bStore = PrivacyStore.memory()
        val (a, _, to) = switched(chain, "999", bStore)
        chain.unconfirmedNext = 1
        val e = runCatching { a.moveHandle(to, Recorder(bStore, { chain.now })) }.exceptionOrNull()
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
        val (a, _, to) = switched(chain, "999")
        for (mode in listOf("reject", "fail", "drop")) {
            // Fees of a tx that may have landed stay pending until its timeout: fresh funds each round.
            a.shieldOutput("uerth", 5_000_000).let { chain.shield("uerth", 5_000_000, it.pc, it.ciphertext) }
            a.sync()
            val bStore = PrivacyStore.memory()
            when (mode) { "reject" -> chain.rejectNext = 1; "fail" -> chain.failInBlockNext = 1; else -> chain.dropNext = 1 }
            assertTrue(runCatching { a.moveHandle(to, Recorder(bStore, { chain.now })) }.isFailure)
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
        a.moveHandle(to, Recorder(PrivacyStore.memory(), { chain.now }))
        assertTrue(a.store.state.handleMovedOut)
    }

    @Test
    fun recordingInTheNewWalletIsRetryableAndItsSyncFindsTheMoveAnyway() {
        val chain = FakeChain()
        val bStore = PrivacyStore.memory()
        val (a, _, to) = switched(chain, "999", bStore)
        val rec = Recorder(bStore, { chain.now }, failFirst = true)
        a.moveHandle(to, rec)
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
    fun restoredSwitchedIdentityRenewsWhatMovedToIt() {
        val chain = FakeChain()
        val bStore = PrivacyStore.memory()
        val (a, b, to) = switched(chain, "999", bStore, split = true)
        assertTrue(b.store.state.identity!!.predecessorAt > 0)
        val rec = Recorder(bStore, { chain.now })
        a.moveHandle(to, rec); a.sync()
        a.moveCaretaker(to, rec); a.sync()
        assertTrue(a.outgoingMoves().isEmpty())
        // Recorded in B's store at once.
        assertEquals("alice", bStore.state.handle)
        assertEquals(mapOf(1L to 100L), bStore.state.caretakerSplit)

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
    fun aRefusedMoveDoesNotFixTheSwitchTarget() {
        val chain = FakeChain()
        val bStore = PrivacyStore.memory()
        val (a, _, to) = switched(chain, "602", bStore)
        chain.rejectNext = 1
        runCatching { a.moveHandle(to, CheckingRecorder(bStore, { chain.now }, "b")) }
        assertEquals("", a.store.state.switchTarget)
        assertEquals("", bStore.state.handle)
        // A target that already holds a handle is refused before anything is laid out.
        val cStore = PrivacyStore.memory().also { it.state.handle = "taken" }
        val sent = chain.txs.size
        val e = runCatching { a.moveHandle(to, CheckingRecorder(cStore, { chain.now }, "c")) }.exceptionOrNull()
        assertTrue("$e", e is IllegalStateException && e.message!!.contains("already holds"))
        assertEquals(sent, chain.txs.size)
        // A target fixed in an older store with no move behind it is freed.
        a.store.state.switchTarget = "stale"
        a.resolvePendingMoves()
        assertEquals("", a.store.state.switchTarget)
        // Confirmed: fixed to that target.
        a.moveHandle(to, CheckingRecorder(bStore, { chain.now }, "b"))
        assertEquals("b", a.store.state.switchTarget)
    }

    /**
     * An identity registers once (personhood 1130): the switched-away wallet
     * registers again only with its next generation, restored or not, and a
     * wallet that never registered starts at generation 0.
     */
    @Test
    fun aSwitchBackToAnEarlierWalletProvesWithItsNextIdentity() {
        val chain = FakeChain()
        val (a, b, _) = switched(chain, "999")
        assertTrue(a.registeredBefore())
        assertTrue(b.registeredBefore())
        assertEquals(1, a.prepareRegistration(null).generation)
        // Restored from the phrase: its registration record says generation 0 is spent.
        val restored = wallet(chain, alice)
        restored.sync()
        assertTrue(restored.registeredBefore())
        assertEquals(1, restored.nextGeneration())
        assertEquals(a.keys.idc(1), restored.prepareRegistration(null).idc)
        // A wallet that never registered starts at its first identity.
        val c = wallet(chain, carol)
        assertFalse(c.registeredBefore())
        assertEquals(0, c.nextGeneration())
        assertTrue(chain.usedIdcs.contains(a.keys.idc) && chain.usedIdcs.contains(b.keys.idc))
    }

    /** After a switch the wallet suggests a random time to move, drawn once; it only reminds, never moves. */
    @Test
    fun aSwitchSuggestsARandomDelayBeforeMoving() {
        val chain = FakeChain()
        val (a, b, _) = switched(chain, "999")
        val act = b.store.state.identity!!.activatedAt
        val at = b.suggestedMoveAt()
        assertTrue("$at", at in (act + PrivacyWallet.MOVE_DELAY_MIN_SECONDS)..(act + PrivacyWallet.MOVE_DELAY_MAX_SECONDS))
        assertEquals(at, b.suggestedMoveAt())
        assertEquals(0L, b.moveSuggestionDue())
        assertEquals(0L, a.moveSuggestionDue())
        chain.now = at
        assertEquals(at, b.moveSuggestionDue())
        assertTrue(Reminders.due(Reminders.Inputs(now = at, identityLive = true, claimOpensAt = null, claimedToday = false,
            caretakerExpiresAt = 0, handle = "", handleEntry = null, moveSuggestedAt = b.moveSuggestionDue())).contains(Reminders.Reminder.MoveSuggested(at)))
        // Nothing moved on its own: A still holds its handle.
        assertEquals("alice", a.store.state.handle)
        b.clearMoveSuggestion()
        assertEquals(0L, b.moveSuggestionDue())
    }

    /**
     * The suggestion is never later than MOVE_DEADLINE_MARGIN_SECONDS before
     * the earliest lease end of what is to move (a lapsed handle cannot
     * move, and the old identity cannot renew it); with less room than
     * that it is due at once, and the reminder says how long is left.
     */
    @Test
    fun theSuggestionIsCappedBeforeTheLeaseEnds() {
        val chain = FakeChain()
        val (_, b, _) = switched(chain, "999")
        val act = b.store.state.identity!!.activatedAt
        val drawn = b.suggestedMoveAt()
        // A lease ending a day after the registration: no room for the wait, due now.
        val soon = act + 86_400
        val capped = b.suggestedMoveAt(soon)
        assertEquals(soon - PrivacyWallet.MOVE_DEADLINE_MARGIN_SECONDS, capped)
        assertEquals(soon, b.moveDeadline())
        assertEquals(capped, b.moveSuggestionDue())
        val due = Reminders.due(Reminders.Inputs(now = chain.now, identityLive = true, claimOpensAt = null, claimedToday = false,
            caretakerExpiresAt = 0, handle = "", handleEntry = null, moveSuggestedAt = b.moveSuggestionDue(), moveDeadline = b.moveDeadline()))
        val r = due.filterIsInstance<Reminders.Reminder.MoveSuggested>().single()
        assertTrue(Reminders.text(r, chain.now).contains("now"))
        // Past the deadline nothing can move: no reminder.
        assertTrue(Reminders.due(Reminders.Inputs(now = soon, identityLive = true, claimOpensAt = null, claimedToday = false,
            caretakerExpiresAt = 0, handle = "", handleEntry = null, moveSuggestedAt = capped, moveDeadline = soon)).none { it is Reminders.Reminder.MoveSuggested })
        // A distant lease end leaves the drawn time as it was.
        assertEquals(drawn, b.suggestedMoveAt(act + 300L * 86_400))
        b.clearMoveSuggestion()
        assertEquals(0L, b.moveDeadline())
    }
}
