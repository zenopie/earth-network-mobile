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
 * Switching identity: moves of a handle and a caretaker split are recorded
 * in both wallets before the broadcast, settled by the chain's word, undone
 * when they did not happen, and the switch target is fixed only by a
 * confirmed move.
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
    fun aRefusedMoveDoesNotFixTheSwitchTarget() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "602")
        a.bindHandle("alice"); a.sync()
        val bKeys = PrivacyKeys.fromMnemonic(bob)
        val bStore = PrivacyStore.memory()
        chain.rejectNext = 1
        runCatching { a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, CheckingRecorder(bStore, { chain.now }, "b")) }
        assertEquals("", a.store.state.switchTarget)
        assertEquals("", bStore.state.handle)
        // A target that already holds a handle is refused before anything is laid out.
        val cStore = PrivacyStore.memory().also { it.state.handle = "taken" }
        val sent = chain.txs.size
        val e = runCatching { a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, CheckingRecorder(cStore, { chain.now }, "c")) }.exceptionOrNull()
        assertTrue("$e", e is IllegalStateException && e.message!!.contains("already holds"))
        assertEquals(sent, chain.txs.size)
        // A target fixed by the old code with no move behind it is freed.
        a.store.state.switchTarget = "stale"
        a.resolvePendingMoves()
        assertEquals("", a.store.state.switchTarget)
        // Confirmed: fixed to that target.
        a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, CheckingRecorder(bStore, { chain.now }, "b"))
        assertEquals("b", a.store.state.switchTarget)
    }
}
