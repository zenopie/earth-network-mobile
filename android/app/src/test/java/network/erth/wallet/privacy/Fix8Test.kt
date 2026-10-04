package network.erth.wallet.privacy

import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgRedelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.chain.ChainErrors
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.DebtTree
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clients round 8 (chain dff3a9b, ORCHARD_DESIGN 20): one stake note per
 * validator (a delegation, an unlock and a move's credit merge into it; a
 * first delegation pads its input, a full exit creates a zero note), credits
 * quoted at the live rate with a margin (a refusal costs nothing), moving
 * stake (MsgRedelegate) with its slash label, the window that keeps moved-in
 * stake in place (refused up front, explained), the label cleared at what
 * the slash debt tree says it is worth, every stake proof naming the chain's
 * clear_before and debt root, and the debt tree read whole (the indexer's
 * stream, the chain's pages), never asked about one move.
 */
class Fix8Test {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val vB: String = Vectors.json.getString("validator2")
    private val vC = "earthvaloper1zyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszq"

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(chain.caretakerLease, 3_600, chain.handleLease, chain.handleRenewal)
        override fun leaseBounds() = chain.leaseBounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
        override fun debtTree(start: Long, limit: Int) = chain.debtTreeRead(start, limit)
        override fun validatorBook(valoper: String) = chain.validatorBookRead(valoper)
        override fun minDelegation() = chain.minDelegation
    }

    private fun wallet(chain: FakeChain, indexer: PrivacyIndexer = chain) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(alice), PrivacyStore.memory(), indexer, chain, reads(chain), chain.prover, chain.chainId, chain,
        now = { chain.now },
    )

    private fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long = 3_000_000) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    private fun staked(chain: FakeChain = FakeChain(), at: String = vA, amount: Long = 2_000_000): PrivacyWallet {
        val a = wallet(chain)
        repeat(6) { funded(chain, a) }
        a.sync()
        a.delegate(at, amount); a.sync()
        return a
    }

    private fun PrivacyWallet.at(v: String): List<OwnedStakeNote> = stakeNotes.filter { it.spendable && it.denom == PrivacyWallet.derthDenom(v) }

    private fun f(b: com.google.protobuf.ByteString) = Fr.fromBytes(b.toByteArray())

    /** Every proof names the chain's clear_before and debt root (circuit audit L-1). */
    private fun assertNamesTheDebt(chain: FakeChain, p: StakeProof) {
        assertEquals(chain.clearBefore(), p.clearBefore)
        assertEquals(chain.debtRoot(), f(p.debtRoot))
    }

    // ---- one note per validator ------------------------------------------------

    @Test
    fun aFirstDelegationPadsAndATopUpMerges() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(4) { funded(chain, a) }
        a.sync()
        val nfs = chain.stakeNfValues.size
        a.delegate(vA, 1_000_000); a.sync()
        val first = chain.lastMsg as MsgDelegate
        // A padding input publishes its own nullifier: a first delegation looks like a top-up.
        assertFalse(f(first.stake.getNullifiers(0)).isZero)
        assertTrue(f(first.stake.getNullifiers(1)).isZero)
        assertEquals(nfs + 1, chain.stakeNfValues.size)
        assertTrue(chain.prover.allStakes.last().ins[0].pad)
        assertEquals(first.derth, a.at(vA).single().amount)
        assertNamesTheDebt(chain, first.stake)
        // A second delegation spends the note and creates the merged one.
        val n = a.at(vA).single()
        a.delegate(vA, 500_000); a.sync()
        val second = chain.lastMsg as MsgDelegate
        assertEquals(n.nf, f(second.stake.getNullifiers(0)))
        val merged = a.at(vA).single()
        assertEquals(n.amount + second.derth, merged.amount)
        assertTrue(a.stakeMergeable().isEmpty())
        assertNamesTheDebt(chain, second.stake)
        dumpWitnesses(chain, "fix8Merge")
    }

    @Test
    fun aFullExitCreatesAZeroNote() {
        val chain = FakeChain()
        val a = staked(chain)
        val n = a.at(vA).single()
        val rows = chain.stakeRows.size
        a.undelegate(vA, n.amount); a.sync()
        val m = chain.lastMsg as MsgUndelegate
        // The change slot holds a zero note's commitment: a full exit looks like a partial one.
        assertFalse(f(m.stake.commitment).isZero)
        assertEquals(201, m.stake.ciphertext.size())
        assertEquals(0L, chain.prover.allStakes.last().outAmount)
        assertEquals(rows + 1, chain.stakeRows.size)
        assertTrue(a.at(vA).isEmpty())
        assertTrue(a.stakeBalances().isEmpty())
        dumpWitnesses(chain, "fix8FullExit")
    }

    /** A young chain (younger than the label window) names clear_before 0 and a zero debt root, as the chain requires. */
    @Test
    fun aYoungChainNamesNoClear() {
        val chain = FakeChain(now = 1_000_000)
        val a = staked(chain)
        val m = chain.lastMsg as MsgDelegate
        assertEquals(0L, m.stake.clearBefore)
        assertTrue(f(m.stake.debtRoot).isZero)
        a.undelegate(vA, 100_000)
        assertEquals(0L, (chain.lastMsg as MsgUndelegate).stake.clearBefore)
    }

    // ---- quotes ----------------------------------------------------------------

    @Test
    fun theQuoteIsWhatTheTxCredits() {
        val chain = FakeChain()
        val a = staked(chain)
        val q = a.quoteDelegate(vA, 1_000_000)
        // floor(amount x S / B) less the drift margin.
        assertEquals(900_000L - 9, q.derth)
        assertEquals(0L, q.haircut)
        a.delegate(q); a.sync()
        assertEquals(q.derth, (chain.lastMsg as MsgDelegate).derth)
    }

    @Test
    fun aRateThatOutranTheQuoteIsRefusedAtNoCost() {
        val chain = FakeChain()
        val a = staked(chain)
        val q = a.quoteDelegate(vA, 1_000_000)
        val txs = chain.txs.size
        val erth = a.balances().getValue("uerth")
        chain.rateDriftPpm = 50
        val e = assertThrows(Exception::class.java) { a.delegate(q) }
        val text = ChainErrors.explain(e.message.orEmpty())!!
        assertTrue(text, "try again" in text && "Nothing was spent" in text)
        assertEquals(txs, chain.txs.size)
        a.sync()
        assertEquals(erth, a.balances().getValue("uerth"))
        // Within the margin (the rate moved a little): it lands.
        chain.rateDriftPpm = 5
        a.delegate(a.quoteDelegate(vA, 1_000_000)); a.sync()
        assertEquals(txs + 1, chain.txs.size)
    }

    @Test
    fun belowTheMinimumIsRefusedUpFront() {
        val chain = FakeChain()
        chain.minDelegation = 1_000_000
        val a = staked(chain)
        val sims = chain.simulated
        assertThrows(IllegalArgumentException::class.java) { a.quoteDelegate(vA, 999_999) }
        // 1 ERTH buys 0.9 derth here: below the least derth a delegation may credit.
        assertThrows(IllegalArgumentException::class.java) { a.quoteDelegate(vA, 1_000_000) }
        assertEquals(sims, chain.simulated)
    }

    // ---- moving stake ------------------------------------------------------------

    @Test
    fun moveStakeLabelsTheCreditAndLeavesTheChange() {
        val chain = FakeChain()
        val a = staked(chain)
        val src = a.at(vA).single()
        val q = a.quoteMove(vA, vB, 1_000_000)
        assertEquals(1_000_000L * 10 / 9, q.value)
        assertFalse(q.merges)
        assertEquals(chain.labelWindow, q.windowSeconds)
        a.redelegate(q); a.sync()
        val m = chain.lastMsg as MsgRedelegate
        assertEquals(chain.now, m.moveTime)
        assertEquals(q.dstDerth, m.dstDerth)
        assertNamesTheDebt(chain, m.stake)
        // The change at vA; the credit at vB in a note of its own, labelled with this move.
        assertEquals(src.amount - 1_000_000, a.at(vA).single().amount)
        val dst = a.at(vB).single()
        assertEquals(q.dstDerth, dst.amount)
        val l = dst.label!!
        assertEquals(f(m.stake.creditNullifier), l.moveKey)
        assertEquals(m.moveTime to q.dstDerth, l.moveTime to l.exposed)
        assertTrue(l.moveKey in chain.moves)
        // The event names the move: credited, move key, move time.
        val ev = chain.txs.values.last().events.single { it.first == "shieldedstaking_redelegate" }.second
        assertEquals(listOf(q.dstDerth.toString(), l.moveKey.toHex(), m.moveTime.toString()), listOf(ev["credited"], ev["move_key"], ev["move_time"]))
        // A restored wallet finds the label in the note's ciphertext, and names its validators by the chain's list.
        val restored = wallet(chain)
        restored.sync()
        assertEquals(dst.label, restored.at(vB).single().label)
        assertEquals(a.stakeBalances(), restored.stakeBalances())
        dumpWitnesses(chain, "fix8Move")
    }

    @Test
    fun aMoveMergesIntoAnUnlabelledNoteAndBesideALabelledOne() {
        val chain = FakeChain()
        val a = staked(chain)
        a.delegate(vB, 1_000_000); a.sync()
        val old = a.at(vB).single()
        val q = a.quoteMove(vA, vB, 500_000)
        assertTrue(q.merges)
        a.redelegate(q)
        // The destination note the credit lane spent is pending until sync sees its nullifier.
        assertTrue(a.at(vB).isEmpty())
        a.sync()
        val m = chain.lastMsg as MsgRedelegate
        // The credit lane spent our unlabelled note: its nullifier is the move key.
        assertEquals(old.nf, f(m.stake.creditNullifier))
        val merged = a.at(vB).single()
        assertEquals(old.amount + q.dstDerth, merged.amount)
        assertEquals(q.dstDerth, merged.label!!.exposed)
        // Our note at vB is labelled now: a second move pads its lane and makes a second note there.
        val q2 = a.quoteMove(vA, vB, 300_000)
        assertFalse(q2.merges)
        a.redelegate(q2); a.sync()
        assertEquals(2, a.at(vB).size)
        assertTrue(a.at(vB).all { it.label != null })
        // Two labelled notes cannot merge until one's window closes.
        assertTrue(a.stakeMergeable().isEmpty())
        val h = a.stakeHoldings().single { it.validator == vB }
        assertEquals(2, h.notes)
        assertFalse(h.mergeable)
        assertEquals(merged.amount - q.dstDerth, h.free)
        dumpWitnesses(chain, "fix8MoveMerge")
    }

    @Test
    fun aRestakeMergesALabelledNoteWithAnUnlabelledOne() {
        val chain = FakeChain()
        val a = staked(chain)
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        val labelled = a.at(vB).single()
        // Another device's unlabelled note at vB.
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(vB), 70_000); a.sync()
        assertEquals(mapOf(PrivacyWallet.derthDenom(vB) to 2), a.stakeMergeable())
        a.restake(vB); a.sync()
        assertTrue(chain.lastMsg is MsgRestake)
        val one = a.at(vB).single()
        assertEquals(labelled.amount + 70_000, one.amount)
        assertEquals(labelled.label, one.label)
        dumpWitnesses(chain, "fix8RestakeLabelled")
    }

    // ---- the window ----------------------------------------------------------------

    @Test
    fun movedStakeStaysUntilItsWindowCloses() {
        val chain = FakeChain()
        val a = staked(chain)
        a.delegate(vB, 1_000_000); a.sync()
        val q = a.quoteMove(vA, vB, 500_000)
        a.redelegate(q); a.sync()
        val n = a.at(vB).single()
        val l = n.label!!
        val sims = chain.simulated
        // Refused up front, with the date it may move again.
        val until = PrivacyWallet.dateText(l.moveTime + chain.labelWindow)
        for (block in listOf<() -> Unit>(
            { a.undelegate(vB, n.amount) },
            { a.quoteMove(vB, vC, n.amount) },
            { a.lockPosition(vB, n.amount, mapOf(1L to 100L)) },
        )) {
            val e = assertThrows(NoteSelection.Insufficient::class.java) { block() }
            assertTrue(e.message!!, "moved stake can move again after $until" in e.message!!)
        }
        assertEquals(sims, chain.simulated)
        val h = a.stakeHoldings().single { it.validator == vB }
        assertEquals(n.amount - l.exposed, h.free)
        assertEquals(l.exposed, h.locked)
        assertEquals(l.moveTime + chain.labelWindow, h.lockedUntil)
        // The rest of the note moves freely, keeping the label on the change.
        a.undelegate(vB, n.amount - l.exposed); a.sync()
        val change = a.at(vB).single()
        assertEquals(l, change.label)
        assertEquals(l.exposed, change.amount)
        assertFalse(chain.prover.allStakes.last().clear)
        dumpWitnesses(chain, "fix8Window")
    }

    @Test
    fun theLabelClearsOnceTheWindowCloses() {
        val chain = FakeChain()
        val a = staked(chain)
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        val n = a.at(vB).single()
        chain.now = n.label!!.moveTime + chain.labelWindow + 1
        chain.emptyBlock(); a.sync()
        assertEquals(n.amount, a.stakeHoldings().single { it.validator == vB }.free)
        assertEquals(0L, a.leaveHaircut(vB, 100_000))
        a.undelegate(vB, 100_000); a.sync()
        val w = chain.prover.allStakes.last()
        assertTrue(w.clear)
        assertNull(w.outLabel)
        val left = a.at(vB).single()
        assertNull(left.label)
        assertEquals(n.amount - 100_000, left.amount)
        dumpWitnesses(chain, "fix8Clear")
    }

    @Test
    fun aSlashedMoveClearsAtWhatItRetains() {
        val chain = FakeChain()
        val a = staked(chain)
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        val n = a.at(vB).single()
        val l = n.label!!
        val retained = l.exposed * 7 / 10
        chain.slashMove(l.moveKey, retained)
        chain.now = l.moveTime + chain.labelWindow + 1
        chain.emptyBlock(); a.sync()
        // The rows come whole from the indexer's stream, checked against the chain's root.
        chain.debtAsks.clear()
        val cut = a.leaveHaircut(vB, 10_000)
        assertEquals(l.exposed - retained, cut)
        assertTrue(chain.debtAsks.toString(), "indexer:0" in chain.debtAsks)
        assertEquals(n.amount - cut, a.stakeHoldings().single { it.validator == vB }.free)
        // A sheet that showed no cut does not send one.
        assertThrows(PrivacyWallet.QuoteChanged::class.java) { a.undelegate(vB, 10_000, maxHaircut = 0) }
        a.undelegate(vB, 10_000, maxHaircut = cut); a.sync()
        assertEquals(n.amount - cut - 10_000, a.at(vB).single().amount)
        assertTrue(chain.prover.allStakes.last().clear)
        // Every read of the debt tree is a whole page from its start: nothing names this wallet's move.
        assertTrue(chain.debtAsks.all { it == "indexer:0" || it == "chain:0" })
        dumpWitnesses(chain, "fix8Slashed")
    }

    @Test
    fun aForgedOrMissingDebtStreamFallsBackToTheChain() {
        val chain = FakeChain()
        val a = staked(chain)
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        val l = a.at(vB).single().label!!
        chain.slashMove(l.moveKey, l.exposed / 2)
        chain.now = l.moveTime + chain.labelWindow + 1
        chain.emptyBlock(); a.sync()
        chain.forgeDebtRetained = l.exposed
        assertEquals(l.exposed - l.exposed / 2, a.leaveHaircut(vB, 10_000))
        chain.forgeDebtRetained = null
        chain.indexerDebtRows = false
        val b = wallet(chain)
        b.sync()
        assertEquals(l.exposed - l.exposed / 2, b.leaveHaircut(vB, 10_000))
        assertTrue(chain.debtAsks.toString(), chain.debtAsks.count { it.startsWith("chain:") } >= 2)
    }

    // ---- errors ----------------------------------------------------------------------

    @Test
    fun refusalsThatCostNothingSayTryAgain() {
        for (log in listOf(
            "the delegation buys 5 derth at the live rate 1.1, less than the 6 it credits (the rate moved since the proof: re-quote with a margin)",
            "move_time 1 is not within 600s before the block time 9000 (name a recent block's time)",
            "debt root 00 is not the current slash debt root 01 (a slash reached a redelegation since: re-prove)",
        )) {
            val t = ChainErrors.explain(log)
            assertNotNull(log, t)
            assertTrue(t!!, "try again" in t)
        }
        assertEquals(ChainErrors.explain("x re-quote with a margin"), ChainErrors.explain(1103, "shieldedstaking", "x re-quote with a margin"))
        assertNull(ChainErrors.explain(1103, "shieldedstaking", "amount converts to nothing"))
    }

    /** The proof's owner tag is fresh on every msg that is not a position's (circuit audit L-2). */
    @Test
    fun ownerTagsAreFreshOffPositions() {
        val chain = FakeChain()
        val a = staked(chain)
        a.delegate(vA, 500_000); a.sync()
        a.undelegate(vA, 100_000); a.sync()
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        val tags = chain.prover.allStakes.map { it.otag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun theMsgShapesAreTheChains() {
        assertEquals(PrivateMsgs.REDELEGATE, PrivateMsgs.typeUrl(MsgRedelegate.getDefaultInstance()))
        assertEquals(DebtTree.EMPTY_ROOT, FakeChain().debtRoot())
    }
}
