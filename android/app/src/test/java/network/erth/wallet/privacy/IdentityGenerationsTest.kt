package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.IdentitySlot
import network.erth.wallet.privacy.sync.PrivacyState
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.sync.WalletSync.Companion.StateRecord
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity generations (PRIVACY_FORMATS.md §2): the chain registers each idc
 * once (1130), so a re-entry after a lapse or a fresh identity proves with
 * the wallet's next identity secret, derived from the same phrase. The
 * wallet picks the lowest generation that never registered, from its own
 * records and leaves; a restore finds the live (or last) one; moves between
 * generations need only the one phrase.
 */
class IdentityGenerationsTest : WalletTest() {
    /** [w]'s generation [g] lapses (its leaf zeroed by the chain's sweep); [w] syncs past it. */
    private fun lapse(chain: FakeChain, w: PrivacyWallet, g: Int) {
        chain.lapse(w.keys.idc(g))
        funded(chain, w)
        w.sync()
    }

    /** Pinned, and cross-checked with an independent Python derivation (BIP-39/32 + HMAC-SHA512). Generation 0 is the identity as before. */
    @Test
    fun generationsArePinnedAndGenerationZeroIsUnchanged() {
        val k = PrivacyKeys.fromMnemonic(alice)
        assertEquals("059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba", k.idSecret(0).toHex())
        assertEquals(k.idSecret, k.idSecret(0))
        assertEquals(k.idc, k.idc(0))
        assertEquals("1578439fcb7abad1387e5fe1f7a46ad6ed3300186b8f4e0c783ec7a39fc7de87", k.idSecret(1).toHex())
        assertEquals("11b2df44a02f56ef93dbe19933e65939c426c7a58ef8d021c14809b2cce04164", k.idSecret(2).toHex())
        assertEquals("0aa6bcc31d78717aab7c87ba4b8d88f785842444a48cc229ef673233400bd084", k.idSecret(3).toHex())
        val b = PrivacyKeys.fromMnemonic(bob)
        assertEquals("155ada956f52baa40ef19cde4bac90f0196a56b1167aaf995d4c1b06f6faf3ea", b.idSecret(1).toHex())
        assertEquals(Privacy.idc(k.idSecret(2)), k.idc(2))
        assertNotEquals(k.idc(1), k.idc(2))
        // nk and ek (notes, the address) are the same for every generation.
        assertEquals("0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81", k.nk.toHex())
    }

    @Test
    fun recordsCarryTheirGenerationInTheTag() {
        val k = PrivacyKeys.fromMnemonic(alice)
        // Generation 0's record is byte for byte the record as before (the golden in RestoreTest).
        assertTrue(WalletSync.regMemo(k.nk, Fr.of(77), "FR", 1_790_000_000L).contentEquals(WalletSync.regMemo(k.nk, Fr.of(77), "FR", 1_790_000_000L, 0)))
        val m = WalletSync.regMemo(k.nk, Fr.of(77), "FR", 1_790_000_000L, 3)
        assertEquals(WalletSync.Companion.RegMemo(Fr.of(77), "FR", 1_790_000_000L, 3), WalletSync.parseRegMemo(k.nk, m, 5))
        // Only generations up to the one asked are tried.
        assertNull(WalletSync.parseRegMemo(k.nk, m, 2))
        assertNull(WalletSync.parseRegMemo(Fr.of(5), m, 5))
        val h = WalletSync.handleMemo(k.nk, WalletSync.RECORD_HOLDS, "alice", 2)
        assertEquals(StateRecord.Handle(WalletSync.RECORD_HOLDS, "alice", 2), WalletSync.parseStateMemo(k.nk, h, 4))
        assertNull(WalletSync.parseStateMemo(k.nk, h, 1))
        val c = WalletSync.caretakerMemo(k.nk, WalletSync.RECORD_MOVED_OUT, generation = 1)
        assertEquals(StateRecord.Caretaker(WalletSync.RECORD_MOVED_OUT, 0, emptyMap(), 1), WalletSync.parseStateMemo(k.nk, c, 1))
    }

    /** A registration lapses; renewing registers the next generation with no new phrase, and its handle and split move over. */
    @Test
    fun reEntryAfterALapseUsesTheNextGenerationAndMovesWithinTheWallet() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        assertEquals(0, a.nextGeneration())
        register(chain, a, "999")
        assertEquals(0, a.generation)
        assertEquals(1, a.nextGeneration())
        a.bindHandle("alice"); a.sync()
        a.setCaretaker(mapOf(1L to 100L)); a.sync()

        lapse(chain, a, 0)
        assertEquals(WalletSync.IdentityStatus.ZEROED, a.identityStatus())
        assertTrue(a.registeredBefore())
        assertEquals(1, a.nextGeneration())

        // Renew: the same wallet, generation 1. The chain would refuse generation 0 (1130).
        register(chain, a, "999")
        assertEquals(1, a.generation)
        assertEquals(2, a.nextGeneration())
        assertTrue(chain.usedIdcs.contains(a.keys.idc(1)))
        assertEquals(a.keys.idc(1), a.idc)
        // The re-entry appended the succession from generation 0, right after the new leaf.
        val id = a.store.state.identity!!
        assertEquals(id.leafIndex + 1, a.successionIndex(a.keys.idc(0), a.keys.idc(1), id.leafIndex + 1, nearOnly = true))
        // What generation 0 held is still its own, not the new identity's.
        assertEquals("", a.store.state.handle)
        assertEquals("alice", a.store.state.slot(0).handle)
        assertTrue(a.caretakerLive(0))
        assertFalse(a.caretakerLive())

        // Moved within the wallet: one phrase holds both secrets; this wallet pays.
        a.moveHandleWithin(0, expected = "alice"); a.sync()
        a.moveCaretakerWithin(0); a.sync()
        assertEquals("alice", a.store.state.handle)
        assertEquals(mapOf(1L to 100L), a.store.state.caretakerSplit)
        assertTrue(a.store.state.slot(0).handleMovedOut && a.store.state.slot(0).caretakerMovedOut)
        assertTrue(a.store.state.slots.values.all { s -> s.pendingMoves.isEmpty() })
        val w = chain.prover.allMoves.last()
        assertEquals(a.keys.idSecret(0), w.oldSecret)
        assertEquals(a.keys.idSecret(1), w.newSecret)
        // The new identity renews and votes with no predecessor wait.
        a.bindHandle("alice", renewOnly = true)
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        assertEquals(Privacy.scopeNullifier(a.keys.idSecret(1), Privacy.handleScope()).toHex().lowercase(), a.handleOwner())

        // Restored from the phrase alone: the live generation, what it holds, what moved away.
        val r = wallet(chain, alice)
        r.sync()
        assertEquals(1, r.generation)
        assertEquals(WalletSync.IdentityStatus.LIVE, r.identityStatus())
        assertEquals(2, r.nextGeneration())
        assertEquals("alice", r.store.state.handle)
        assertTrue(r.store.state.slot(0).handleMovedOut && r.store.state.slot(0).caretakerMovedOut)
        dumpWitnesses(chain, "generationsReEntry")
    }

    /** A fresh identity while registered: the passport switches to the wallet's next generation (no new phrase). */
    @Test
    fun aFreshIdentityInTheSameWalletIsASwitchToTheNextGeneration() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "777")
        a.bindHandle("alice"); a.sync()
        register(chain, a, "777")
        assertEquals(1, a.generation)
        // The chain zeroed generation 0's leaf: a switch, and the move suggestion is the new identity's.
        assertTrue(chain.zeroed.isNotEmpty())
        assertTrue(a.store.state.identity!!.predecessorAt > 0)
        assertTrue(a.store.state.slot(1).moveSuggestedAt > 0)
        assertEquals(0L, a.store.state.slot(0).moveSuggestedAt)
        a.moveHandleWithin(0); a.sync()
        assertEquals("alice", a.store.state.handle)
        // A move goes only from an earlier generation to the one the wallet acts as.
        assertTrue(runCatching { a.moveHandleWithin(1) }.exceptionOrNull() is IllegalArgumentException)
    }

    /** The wallet's records missed a used identity: the chain refuses it (1130) and the next try uses the next generation. */
    @Test
    fun aRefusedIdentityMovesTheWalletToItsNextGeneration() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "555")
        lapse(chain, a, 0)
        // Generation 1 registered somewhere the wallet cannot see (no record of it here).
        chain.usedIdcs.add(a.keys.idc(1))
        val prep = a.prepareRegistration(null)
        assertEquals(1, prep.generation)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        a.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "555", Fr.of(77).toBigInteger().toString(), prep.idc.toBigInteger().toString())
        val e = runCatching { a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10)) }.exceptionOrNull()
        assertTrue("$e", e != null && PrivacyWallet.identityRefusal(e))
        assertEquals(2, a.nextGeneration())
        register(chain, a, "555")
        assertEquals(2, a.generation)
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        // A stale prep (its generation landed since) is refused before anything is sent.
        val stale = prep
        val sent = chain.txs.size
        assertTrue(runCatching { a.register(stale, ByteArray(14_656), signals, "lean_poa", ByteArray(10)) }.exceptionOrNull() is PrivacyWallet.IdentityUsed)
        assertEquals(sent, chain.txs.size)
    }

    /** A restore scans the records' generations: the live one, or (all lapsed) the last, and never offers a used one. */
    @Test
    fun aRestoreFindsTheLiveGenerationAndTheNextUnused() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "321")
        lapse(chain, a, 0)
        register(chain, a, "321")
        lapse(chain, a, 1)
        register(chain, a, "321")
        assertEquals(2, a.generation)

        val r = wallet(chain, alice)
        r.sync()
        assertEquals(2, r.generation)
        assertEquals(WalletSync.IdentityStatus.LIVE, r.identityStatus())
        assertEquals(3, r.nextGeneration())

        // All lapsed: nothing live to match, yet every used generation is known from its record.
        lapse(chain, a, 2)
        val r2 = wallet(chain, alice)
        r2.sync()
        assertTrue(r2.registeredBefore())
        assertEquals(3, r2.nextGeneration())
        register(chain, r2, "321")
        assertEquals(3, r2.generation)
    }

    /** Any wallet is a switch target: the target registers its own next generation, and the old one's moves reach it. */
    @Test
    fun aSwitchBackToAnEarlierWalletUsesItsNextGeneration() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "999")
        a.bindHandle("alice"); a.sync()
        fun recorder(w: PrivacyWallet) = object : PrivacyWallet.MoveRecorder {
            override val targetId = "w${w.keys.idc.toHex().take(6)}"
            override fun record(move: network.erth.wallet.privacy.sync.PendingMove) = PrivacyWallet.recordIncoming(w.store, move, chain.now)
            override fun rollback(move: network.erth.wallet.privacy.sync.PendingMove) = PrivacyWallet.rollbackIncoming(w.store, move, chain.now)
        }
        // To B, the handle moved along.
        val b = wallet(chain, bob)
        register(chain, b, "999")
        a.sync()
        a.moveHandle(PrivacyWallet.Successor(b.keys, b.store.state.identity!!, b.generation), recorder(b))
        b.sync()
        assertEquals("alice", b.store.state.handle)
        // Back to A: generation 1, since generation 0 registered.
        assertEquals(WalletSync.IdentityStatus.ZEROED, a.identityStatus())
        assertEquals(1, a.nextGeneration())
        register(chain, a, "999")
        assertEquals(1, a.generation)
        b.sync()
        assertEquals(WalletSync.IdentityStatus.ZEROED, b.identityStatus())
        // B's identity is A's generation 1's predecessor: the handle comes home.
        b.moveHandle(PrivacyWallet.Successor(a.keys, a.store.state.identity!!, a.generation), recorder(a))
        assertEquals("alice", a.store.state.handle)
        assertEquals(1, a.generation)
        assertEquals(a.keys.idSecret(1), chain.prover.allMoves.last().newSecret)
        assertEquals(setOf(a.keys.idc(0), b.keys.idc(0), a.keys.idc(1)), chain.usedIdcs)
        // Generation 0's slot keeps that it moved its handle away; the new identity holds it.
        a.sync()
        assertTrue(a.store.state.slot(0).handleMovedOut)
        assertEquals("alice", a.store.state.handle)
    }

    /** Slots round-trip; a store from before generations reads as generation 0. */
    @Test
    fun storeKeepsEveryGenerationsSlot() {
        val s = PrivacyState()
        s.slot(0).handle = "old"; s.slot(0).handleMovedOut = true
        s.generation = 1; s.generationFloor = 3
        s.handle = "new"; s.caretakerSplit = mapOf(2L to 100L)
        val back = PrivacyState.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(1, back.generation)
        assertEquals(3, back.generationFloor)
        assertEquals("new", back.handle)
        assertEquals(mapOf(2L to 100L), back.caretakerSplit)
        assertEquals("old", back.slot(0).handle)
        assertTrue(back.slot(0).handleMovedOut)
        assertEquals(3, back.nextGeneration())
        val legacy = JSONObject().put("handle", "kept").put("handle_moved_out", false).put("caretaker_cast_at", 5)
        val l = PrivacyState.fromJson(legacy)
        assertEquals(0, l.generation)
        assertEquals("kept", l.handle)
        assertEquals(5L, l.caretakerCastAt)
        assertTrue(IdentitySlot().empty)
        // A same-chain reset keeps every slot and the generation.
        val st = PrivacyStore.memory()
        st.state.chainId = "earth-1"
        st.state.slot(0).handle = "old"; st.state.generation = 1; st.state.handle = "new"
        st.reset("earth-1")
        assertEquals(1, st.state.generation)
        assertEquals("new", st.state.handle)
        assertEquals("old", st.state.slot(0).handle)
    }
}
