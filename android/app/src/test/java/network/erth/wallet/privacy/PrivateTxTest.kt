package network.erth.wallet.privacy

import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.Assembled
import network.erth.wallet.privacy.tx.NoteOut
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.tx.PrivateTxEngine
import network.erth.wallet.privacy.tx.TxResult
import network.erth.wallet.privacy.tx.UnsignedTx
import network.erth.wallet.privacy.tx.VoteWitnessSpec
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building and sending a private tx: its timeout_height and the tip it comes
 * from, spent notes pending until the chain's word, the fee cap and the
 * sheet's fee, quotes that show the node nothing of ours, fresh anchors,
 * gas, and destinations the chain refuses.
 */
class PrivateTxTest : WalletTest() {
    /** A v2 mint of [value] [denom] to [w] (what every chain mint is), one block. */
    private fun mintTo(chain: FakeChain, w: PrivacyWallet, denom: String, value: Long): Long {
        val n = NotePlaintext.fresh(denom, value)
        val pos = chain.notes.size.toLong()
        chain.shield(denom, value, n.pc(w.keys.ownerPk), NoteCipher.encryptBlind(n, w.keys.address))
        return pos
    }

    private fun staked(chain: FakeChain, due: (Long) -> Long? = { null }): PrivacyWallet {
        val a = wallet(chain, r = reads(chain, due = due))
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        return a
    }

    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    @Test
    fun privateTxsCarryATimeoutAndPendingWaitsForIt() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        val tip = chain.tipHeight()
        a.unshield(receiver, "uerth", 1000)
        assertEquals(tip + PrivateTxEngine.TIMEOUT_BLOCKS, chain.lastTimeoutHeight)
        a.sync()
        // A tx the node accepted and then dropped: its note stays pending until the chain is past the timeout.
        chain.dropNext = 1
        val t = chain.tipHeight() + PrivateTxEngine.TIMEOUT_BLOCKS
        assertThrows(java.io.IOException::class.java) { a.unshield(receiver, "uerth", 1000) }
        val held = a.notes.single { it.unspent }
        assertEquals(t, held.pendingUntil)
        chain.now += 24 * 3600
        while (chain.tipHeight() < t) chain.emptyBlock()
        a.sync()
        assertNotNull(a.notes.single { it.unspent }.pendingAt)
        assertThrows(Exception::class.java) { a.unshield(receiver, "uerth", 1000) }
        chain.emptyBlock()
        a.sync()
        assertNull(a.notes.single { it.unspent }.pendingAt)
        a.unshield(receiver, "uerth", 1000)
        // A tx past its timeout_height is refused by the chain.
        assertThrows(IllegalArgumentException::class.java) {
            chain.broadcast(network.erth.wallet.privacy.tx.UnsignedTx.build(network.erth.earth.proto.shielded.MsgSend.getDefaultInstance(), 1, "", 1))
        }
    }

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
        // A mark an older version made against an inflated tip, for a tx the node never relayed.
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

    /** The answer to the broadcast is lost after the node took it: the spent notes are pending all the same. */
    @Test
    fun spendsArePendingBeforeTheBroadcastAnswers() {
        val chain = FakeChain()
        val lost = object : PrivateChain by chain {
            override fun broadcast(tx: ByteArray, accepted: (hash: String) -> Unit): TxResult {
                chain.broadcast(tx) {}
                throw java.net.SocketTimeoutException("read timed out (test)")
            }
        }
        val a = wallet(chain, pc = lost)
        funded(chain, a)
        a.sync()
        assertThrows(java.net.SocketTimeoutException::class.java) { a.unshield(receiver, "uerth", 1000) }
        val n = a.notes.single { it.note.value == 1_000_000L }
        assertNotNull(n.pendingAt)
        assertEquals(chain.txs.keys.single(), n.pendingTx)
    }

    /** A refusal proving the tx is in no mempool (CheckTx's code) makes the notes spendable again at once. */
    @Test
    fun aRefusedBroadcastUnmarks() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        chain.rejectNext = 1
        assertThrows(UnsignedTx.TxRejected::class.java) { a.unshield(receiver, "uerth", 1000) }
        assertTrue(a.notes.all { it.pendingAt == null })
    }

    /**
     * Past the timeout, a pending note is released only when the chain says
     * the tx is missing; a tx in a block whose spend the indexer never
     * reported keeps it pending and the sync unverified.
     */
    @Test
    fun pendingIsReleasedOnlyOnTheChainsWord() {
        val chain = FakeChain()
        var hide: Fr? = null
        val idx = object : Wrapped(chain) {
            override fun nullifiers(fromHeight: Long, limit: Int?) = inner.nullifiers(fromHeight, limit).let { p ->
                p.copy(blocks = p.blocks.map { (h, nfs) -> h to nfs.filter { it != hide } })
            }
        }
        val a = wallet(chain, indexer = idx)
        funded(chain, a)
        a.sync()
        // Dropped from the mempool: missing once past its timeout, released.
        chain.dropNext = 1
        assertThrows(java.io.IOException::class.java) { a.unshield(receiver, "uerth", 1000) }
        while (chain.tipHeight() <= chain.lastTimeoutHeight) chain.emptyBlock()
        assertTrue(a.sync().verified)
        assertTrue(a.notes.all { it.pendingAt == null })
        // Committed, its spend hidden by the indexer: kept pending, the sync unverified.
        hide = a.notes.single().nf
        chain.unconfirmedNext = 1
        assertThrows(Exception::class.java) { a.unshield(receiver, "uerth", 1000) }
        while (chain.tipHeight() <= chain.lastTimeoutHeight) chain.emptyBlock()
        assertFalse(a.sync().verified)
        assertTrue("did not report its spend" in a.store.state.rootsError!!)
        assertNotNull(a.notes.single { it.nf == hide }.pendingAt)
        // Unknown (the node cannot say): kept.
        chain.txLookupBlind = true
        a.sync()
        assertNotNull(a.notes.single { it.nf == hide }.pendingAt)
    }

    @Test
    fun feeIsCappedAndReconfirmedAboveTheSheet() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a, 50_000_000)
        a.sync()
        chain.price = java.math.BigDecimal("10")
        val e = assertThrows(PrivateTxEngine.FeeAboveCap::class.java) { a.unshield(receiver, "uerth", 1000) }
        assertTrue(e.cap <= PrivateTxEngine.MAX_PRIVATE_FEE)
        assertEquals(0, chain.prover.actions.size)
        chain.price = java.math.BigDecimal("0.001")
        val before = chain.height
        val q = assertThrows(PrivateTxEngine.FeeAboveQuote::class.java) { PrivacyWallet.withShownFee(1) { a.unshield(receiver, "uerth", 1000) } }
        assertEquals(before, chain.height)
        PrivacyWallet.withShownFee(q.fee) { a.unshield(receiver, "uerth", 1000) }
        assertEquals(before + 1, chain.height)
        assertNull(PrivacyWallet.shownFee.get())
    }

    @Test
    fun quoteNeverShowsTheNodeOurNullifiers() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        funded(chain, a)
        a.sync()
        val q = a.quoteSend(a.address, "uerth", 1_500_000)
        assertTrue(q.fee > 0)
        val ours = a.notes.map { it.nf }.toSet()
        assertTrue(chain.simulatedNullifiers.isNotEmpty())
        assertTrue(chain.simulatedNullifiers.none { it in ours })
        assertEquals(0, chain.prover.actions.size)
    }

    @Test
    fun anAnchorAboutToLapseIsReplacedOrRefused() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        val n = NoteOut.mintToSelf(a.keys, "uerth")
        chain.shield("uerth", 3_000_000, n.pc, n.ciphertext)
        a.sync()
        val stale = a.store.noteTree.root()
        // The local root lapses in a minute (CheckTx would refuse it within 120 s).
        chain.rootExpiresAt = { r -> if (r == stale) chain.now + 60 else 0L }
        val sent = chain.txs.size
        val e = runCatching { a.send(b.address, "uerth", 1_000) }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.SyncFirst)
        assertEquals(sent, chain.txs.size)
        // A newer root exists on the chain: the wallet syncs to it and sends.
        chain.shield("uerth", 1, NoteOut.mintToSelf(b.keys, "uerth").pc, NoteOut.mintToSelf(b.keys, "uerth").ciphertext)
        a.send(b.address, "uerth", 1_000)
        assertTrue(a.store.noteTree.root() != stale)
        // With room to spare (> the margin), the root is used as it is.
        chain.rootExpiresAt = { chain.now + PrivacyWallet.ANCHOR_MARGIN + 60 }
        a.sync()
        a.send(b.address, "uerth", 1_000)
    }

    @Test
    fun everyPrivateTxDeclaresWithinFiveTimesItsGas() {
        val chain = FakeChain()
        val a = staked(chain)
        a.delegate(vB, 500_000); a.sync()
        // A second note (another device's) merged by a restake.
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(vB), 50_000); a.sync()
        a.restake(vB); a.sync()
        chain.openProposal(9); a.sync()
        a.stakeVote(9, vB, yes); a.sync()
        a.undelegate(vB, 100_000); a.sync()
        assertTrue(chain.gasRatios.isNotEmpty())
        // simulate + 10% (at least the fixed headroom): well inside the chain's 5x.
        assertTrue(chain.gasRatios.toString(), chain.gasRatios.all { it in 1.0..1.2 })
        dumpWitnesses(chain, "fix7Gas")
    }

    @Test
    fun voteGasEstimateCountsItsNotes() {
        fun spec(used: Int) = VoteWitnessSpec(List(2) { if (it < used) Fr.of(it + 1L) else Fr.ZERO }) { error("unused") }
        val msg = network.erth.earth.proto.shieldedstaking.MsgStakeVote.getDefaultInstance()
        val g1 = PrivateTxEngine.estimateGas(msg, Assembled(emptyList(), vote = spec(1)) { _, _, _ -> msg }, 0)
        val g2 = PrivateTxEngine.estimateGas(msg, Assembled(emptyList(), vote = spec(2)) { _, _, _ -> msg }, 0)
        assertEquals(PrivateTxEngine.NOTE_GAS, g2 - g1)
        assertEquals(PrivateTxEngine.BASE_GAS + PrivateTxEngine.BUNDLE_GAS + 250_000 + 2_000_000 + 2 * PrivateTxEngine.NOTE_GAS, g1)
    }

    /** B/F2: an unshield to any module account is refused before anything is proven. */
    @Test
    fun unshieldToAModuleAccountIsRefused() {
        val chain = FakeChain()
        val a = wallet(chain)
        funded(chain, a)
        a.sync()
        val staking = network.erth.wallet.crypto.Bech32.encode("earth",
            network.erth.wallet.crypto.Bech32.convertBits(network.erth.wallet.privacy.tx.PrivateMsgs.moduleAddress("shieldedstaking"), 8, 5, true))
        val e = assertThrows(IllegalArgumentException::class.java) { a.unshield(staking, "uerth", 1000) }
        assertTrue(e.message!!.contains("shieldedstaking"))
        assertEquals(0, chain.simulated)
        a.unshield(receiver, "uerth", 1000)
    }
}
