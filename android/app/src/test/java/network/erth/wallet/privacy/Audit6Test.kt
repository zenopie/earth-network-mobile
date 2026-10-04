package network.erth.wallet.privacy

import network.erth.wallet.chain.math.SwapMath
import network.erth.wallet.privacy.handles.Handles
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.AssetDenoms
import network.erth.wallet.privacy.note.Denoms
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import network.erth.wallet.privacy.sync.PendingMove
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Audit round 6 (mobile): indexer denoms (validated, learned only from our
 * own notes or the chain's asset list, never "asset/"), the send tip bounded
 * by the verified sync height, the switch target fixed only by a confirmed
 * move, handles adopted only by owner, renew-only binds, one store per
 * wallet, public add-liquidity's min_shares.
 */
class Audit6Test {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(chain.caretakerLease, 3_600, chain.handleLease, chain.handleRenewal)
        override fun leaseBounds() = chain.leaseBounds()
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

    private fun register(chain: FakeChain, w: PrivacyWallet, passport: String) {
        val prep = w.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), passport, Fr.of(77).toBigInteger().toString())
        w.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
    }

    /** A v2 mint of [value] [denom] to [w] (what every chain mint is), one block. */
    private fun mintTo(chain: FakeChain, w: PrivacyWallet, denom: String, value: Long): Long {
        val n = NotePlaintext.fresh(denom, value)
        val pos = chain.notes.size.toLong()
        chain.shield(denom, value, n.pc(w.keys.ownerPk), NoteCipher.encryptBlind(n, w.keys.address))
        return pos
    }

    private class Recorder(val store: PrivacyStore, val now: () -> Long, override val targetId: String = "target") : PrivacyWallet.MoveRecorder {
        override fun record(move: PendingMove) = PrivacyWallet.recordIncoming(store, move, now())
        override fun rollback(move: PendingMove) = PrivacyWallet.rollbackIncoming(store, move, now())
        override fun refusal(move: PendingMove): String? = PrivacyWallet.targetRefusal(store.state, move.kind, now())
    }

    // ---- M2, M3: indexer denoms ---------------------------------------------

    @Test
    fun denomsFollowTheSdkRuleAndNeverTheInternalName() {
        assertTrue(Denoms.valid("uerth"))
        assertTrue(Denoms.valid("derth/earthvaloper1abc"))
        assertTrue(Denoms.valid("unbond/earthvaloper1abc/42"))
        assertFalse(Denoms.valid("asset/0a"))
        assertFalse(Denoms.valid("ab"))
        assertFalse(Denoms.valid("1abc"))
        assertFalse(Denoms.valid("a b c"))
        assertFalse(Denoms.valid("a".repeat(129)))
        val d = AssetDenoms()
        assertFalse(d.learn("asset/" + Privacy.assetId("uerth").toHex()))
        // An id the chain states for a denom is learned only if it is the denom's own.
        assertFalse(d.learn("ufoo", Privacy.assetId("ubar")))
        assertTrue(d.learn("ufoo", Privacy.assetId("ufoo")))
        assertEquals("ufoo", d.resolve(Privacy.assetId("ufoo")))
    }

    @Test
    fun anAssetSentinelOrMalformedRowAmountIsRefusedNotStoredAndNeverDuplicates() {
        val chain = FakeChain()
        val w = wallet(chain, alice)
        val good = mintTo(chain, w, "uerth", 1_000)
        val relabeled = mintTo(chain, w, "uerth", 1_000_000)
        val broken = mintTo(chain, w, "uerth", 2_000)
        // A hostile indexer relabels a mint as the internal "asset/<hex>" name of uerth's own id,
        // and serves another with a non-hex sentinel (which threw part way through a page before).
        chain.notes[relabeled.toInt()] = chain.notes[relabeled.toInt()].copy(amount = "1000000asset/" + Privacy.assetId("uerth").toHex())
        chain.notes[broken.toInt()] = chain.notes[broken.toInt()].copy(amount = "2000asset/zz")
        w.sync()
        assertTrue(w.notes.none { it.note.denom.startsWith(NotePlaintext.UNRESOLVED_PREFIX) })
        assertEquals(listOf(good), w.notes.map { it.position })
        w.sync()
        assertEquals(1, w.notes.size)
        assertEquals(chain.notes.size.toLong(), w.store.state.notesNext)
    }

    @Test
    fun aStoredSentinelNoteIsRenamedBack() {
        val chain = FakeChain()
        val w = wallet(chain, alice)
        mintTo(chain, w, "uerth", 5_000)
        w.sync()
        // As a store the old code let an indexer relabel (audit 6, M3).
        val n = w.store.state.notes[0]
        w.store.state.notes[0] = n.copy(note = n.note.copy(denom = NotePlaintext.UNRESOLVED_PREFIX + Privacy.assetId("uerth").toHex()))
        w.sync()
        assertEquals("uerth", w.store.state.notes[0].note.denom)
        assertEquals(5_000L, w.balances()["uerth"])
    }

    @Test
    fun denomsAreLearnedOnlyFromOwnNotesOrTheChainsAssetList() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        register(chain, a, "601")
        // Rows not ours, each with a junk denom in the amount column.
        repeat(40) { i ->
            val n = NotePlaintext.fresh("uerth", 7)
            val pos = chain.notes.size
            chain.shield("uerth", 7, n.pc(Fr.of(1000L + i)), NoteCipher.encryptBlind(n, b.keys.address))
            chain.notes[pos] = chain.notes[pos].copy(amount = "7junk$i")
        }
        mintTo(chain, a, "ufoo", 9_000)
        a.sync()
        assertTrue(a.store.state.denoms.none { it.startsWith("junk") })
        assertTrue("ufoo" in a.store.state.denoms)
        // b learns ufoo from a v1 note only through the chain's asset list (id checked).
        b.sync()
        a.send(b.keys.address, "ufoo", 4_000)
        chain.assetList = listOf("ufoo" to Privacy.assetId("ubar"))
        b.sync()
        assertEquals(listOf(NotePlaintext.UNRESOLVED_PREFIX + Privacy.assetId("ufoo").toHex()), b.notes.map { it.note.denom })
        chain.assetList = listOf("ufoo" to Privacy.assetId("ufoo"))
        b.sync()
        assertEquals(listOf("ufoo"), b.notes.map { it.note.denom })
        assertEquals(4_000L, b.balances()["ufoo"])
    }

    // ---- M4: the send tip ---------------------------------------------------

    @Test
    fun aTipFarPastTheVerifiedHeightIsRefusedBeforeAnythingIsSent() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        mintTo(chain, a, "uerth", 500_000)
        a.sync()
        val sent = chain.txs.size
        chain.sendTipAhead = 1_000_000_000_000L
        val e = runCatching { a.send(b.keys.address, "uerth", 1_000) }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.SyncFirst)
        assertEquals(sent, chain.txs.size)
        assertTrue(a.notes.all { it.pendingAt == null })
        // An honest tip sends.
        chain.sendTipAhead = 0
        a.send(b.keys.address, "uerth", 1_000)
        assertTrue(PrivateTxEngine.tipSane(chain.tipHeight(), a.store.state.verifiedHeight))
    }

    @Test
    fun anOutsizedPendingTimeoutIsSettledByTheTxStatus() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        mintTo(chain, a, "uerth", 500_000)
        a.sync()
        // As a mark the old code made against an inflated tip, for a tx the node never relayed.
        val n = a.store.state.notes[0]
        a.store.state.notes[0] = n.copy(pendingAt = chain.now, pendingUntil = 1_000_000_000_000_050L, pendingTx = "AB".repeat(32))
        a.sync()
        assertTrue(a.store.state.notes[0].pendingAt != null)
        chain.now += WalletSync.PENDING_TIMEOUT_S + 1
        a.sync()
        assertEquals(null, a.store.state.notes[0].pendingAt)
        assertFalse(PrivateTxEngine.timeoutSane(1_000_000_000_000_050L, a.store.state.verifiedHeight))
        assertTrue(PrivateTxEngine.timeoutSane(a.store.state.verifiedHeight + PrivateTxEngine.TIMEOUT_BLOCKS, a.store.state.verifiedHeight))
    }

    // ---- M5: the switch target ----------------------------------------------

    @Test
    fun aRefusedMoveDoesNotFixTheSwitchTarget() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, "602")
        a.bindHandle("alice"); a.sync()
        val bKeys = PrivacyKeys.fromMnemonic(bob)
        val bStore = PrivacyStore.memory()
        chain.rejectNext = 1
        runCatching { a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, Recorder(bStore, { chain.now }, "b")) }
        assertEquals("", a.store.state.switchTarget)
        assertEquals("", bStore.state.handle)
        // A target that already holds a handle is refused before anything is laid out.
        val cStore = PrivacyStore.memory().also { it.state.handle = "taken" }
        val sent = chain.txs.size
        val e = runCatching { a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, Recorder(cStore, { chain.now }, "c")) }.exceptionOrNull()
        assertTrue("$e", e is IllegalStateException && e.message!!.contains("already holds"))
        assertEquals(sent, chain.txs.size)
        // A target fixed by the old code with no move behind it is freed.
        a.store.state.switchTarget = "stale"
        a.resolvePendingMoves()
        assertEquals("", a.store.state.switchTarget)
        // Confirmed: fixed to that target.
        a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()), bKeys, Recorder(bStore, { chain.now }, "b"))
        assertEquals("b", a.store.state.switchTarget)
    }

    // ---- M6, M7: handles ----------------------------------------------------

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

    // ---- M8: one store per wallet -------------------------------------------

    @Test
    fun theProcessHoldsOneStorePerWalletDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("a6").toFile()
        try {
            val s1 = PrivacyStore.shared(dir, "w")
            assertSame(s1, PrivacyStore.shared(dir, "w"))
            s1.state.stakeVoteRun = network.erth.wallet.privacy.sync.StakeVoteRun(1, emptyList(), setOf(42L), 1)
            s1.save()
            assertEquals(setOf(42L), PrivacyStore.shared(dir, "w").state.stakeVoteRun?.votedPositions)
            PrivacyStore.delete(dir, "w")
            assertTrue(PrivacyStore.shared(dir, "w") !== s1)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- M9: public add-liquidity -------------------------------------------

    @Test
    fun publicAddLiquidityCarriesMinShares() {
        // min(e*S/Re, t*S/Rt) = min(1000*500/2000, 300*500/1000) = 150, less 1%: 148.
        assertEquals("148", SwapMath.minShares(BigInteger.valueOf(1000), BigInteger.valueOf(300), BigInteger.valueOf(2000), BigInteger.valueOf(1000), BigInteger.valueOf(500), 100))
        assertEquals("", SwapMath.minShares(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 100))
        val any = network.erth.wallet.chain.Dex.msgAddLiquidity("earth1creator", 2, "uerth", "1000", "uusd", "300", "148")
        assertEquals("/earth.dex.v1.MsgAddLiquidity", any.typeUrl)
        // Golden shared with iOS (Audit6Tests): fields 1-5.
        assertEquals(
            "0a0d65617274683163726561746f7210021a0d0a057565727468120431303030" +
                "220b0a047575736412033330302a03313438",
            any.value.toByteArray().joinToString("") { "%02x".format(it) },
        )
    }
}
