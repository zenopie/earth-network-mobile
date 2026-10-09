package network.erth.wallet.privacy

import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgRedelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.chain.ChainErrors
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.tx.NoteSelection
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.DebtTree
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Private staking: one note per validator, quotes at the live rate, moving
 * stake (labels, the window, the slash debt), and the refusals that cost
 * nothing.
 */
class StakeTest : WalletTest() {
    private fun f(b: com.google.protobuf.ByteString) = Fr.fromBytes(b.toByteArray())

    /** Every proof names the chain's clear_before and debt root. */
    private fun assertNamesTheDebt(chain: FakeChain, p: StakeProof) {
        assertEquals(chain.clearBefore(), p.clearBefore)
        assertEquals(chain.debtRoot(), f(p.debtRoot))
    }

    private val vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    private fun staked(chain: FakeChain = FakeChain(), at: String = vA, amount: Long = 2_000_000): PrivacyWallet {
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 3_000_000) }
        a.sync()
        a.delegate(at, amount); a.sync()
        return a
    }

    private fun PrivacyWallet.at(v: String): List<OwnedStakeNote> = stakeNotes.filter { it.spendable && it.denom == PrivacyWallet.derthDenom(v) }

    private val vB: String = Vectors.json.getString("validator2")

    private val vC = "earthvaloper1zyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszq"

    @Test
    fun aFirstDelegationPadsAndATopUpMerges() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(4) { funded(chain, a, 3_000_000) }
        a.sync()
        val nfs = chain.stakeNfValues.size
        a.delegate(vA, 1_000_000); a.sync()
        val first = chain.lastMsg as MsgDelegate
        // A padding input publishes its own nullifier: a first delegation looks like a top-up,
        // and slot 1 is always spent or padded, so a merge looks like a single spend.
        assertFalse(f(first.stake.getNullifiers(0)).isZero)
        assertFalse(f(first.stake.getNullifiers(1)).isZero)
        assertNotEquals(f(first.stake.getNullifiers(0)), f(first.stake.getNullifiers(1)))
        assertEquals(nfs + 2, chain.stakeNfValues.size)
        assertTrue(chain.prover.allStakes.last().ins.all { it.pad })
        assertEquals(first.derth, a.at(vA).single().amount)
        assertNamesTheDebt(chain, first.stake)
        // A second delegation spends the note and creates the merged one.
        val n = a.at(vA).single()
        a.delegate(vA, 500_000); a.sync()
        val second = chain.lastMsg as MsgDelegate
        assertEquals(n.nf, f(second.stake.getNullifiers(0)))
        assertFalse(f(second.stake.getNullifiers(1)).isZero)
        assertTrue(chain.prover.allStakes.last().ins[1].pad)
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

    /** While the block time is below the label window (the chain says clear_before 0) a proof names 0 and a zero debt root, as the chain requires. */
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

    /**
     * A move arrives whole only out of an unbonded source's queue (chain
     * b46a4bb): from a bonded one it leaves pro rata, so the
     * quote takes u - 1001 even when the queue covers u, and lands.
     */
    @Test
    fun aMoveArrivesWholeOnlyFromAnUnbondedQueue() {
        val chain = FakeChain()
        val a = staked(chain)
        chain.queues[vA] = java.math.BigInteger.valueOf(10_000_000)
        val bonded = a.quoteMove(vA, vB, 500_000)
        chain.unbonded += vA
        val whole = a.quoteMove(vA, vB, 500_000)
        assertTrue(whole.dstDerth > bonded.dstDerth)
        // Quoted whole from a bonded source, the chain would refuse it.
        chain.unbonded -= vA
        assertThrows(Throwable::class.java) { a.redelegate(whole) }
        a.sync()
        a.redelegate(bonded); a.sync()
        assertEquals(bonded.dstDerth, a.at(vB).single().amount)
        chain.unbonded += vA
        val again = a.quoteMove(vA, vB, 500_000)
        a.redelegate(again); a.sync()
        assertEquals(bonded.dstDerth + again.dstDerth, a.at(vB).sumOf { it.amount })
    }

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
        val ev = chain.txs.values.flatMap { it.events }.single { it.first == "shieldedstaking_redelegate" }.second
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

    /** Every lane A input publishes its Groundworks tag, padding its own; with no split chosen, nothing votes. */
    @Test
    fun inputsPublishTheirGroundworksTags() {
        val chain = FakeChain()
        val a = staked(chain)
        a.delegate(vA, 500_000); a.sync()
        a.undelegate(vA, 100_000); a.sync()
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        for (w in chain.prover.allStakes) {
            assertEquals(w.ins.map { network.erth.wallet.privacy.zk.Privacy.stakeGw(w.nk, it.rho) }, w.gw)
            assertEquals(0L, w.wOut + w.crWOut)
        }
        assertTrue(chain.gwVotes.isEmpty())
    }

    /**
     * The value leaves the queue first, and arrives whole, wherever no slash
     * can reach src's stake: also a bonded validator whose bonded part D - U
     * is nothing; the queue counts the rewards a move withdraws into it.
     */
    @Test
    fun aMoveArrivesWholeWhereNoSlashReachesTheStake() {
        val chain = FakeChain()
        val a = staked(chain)
        val bonded = a.quoteMove(vA, vB, 500_000)
        // P + W covers u (555,555), D <= U: the queue goes first.
        chain.queues[vA] = java.math.BigInteger.valueOf(300_000)
        chain.rewards[vA] = java.math.BigInteger.valueOf(300_000)
        chain.delegated[vA] = java.math.BigInteger.valueOf(1_000)
        chain.undelegating[vA] = java.math.BigInteger.valueOf(1_000)
        val whole = a.quoteMove(vA, vB, 500_000)
        assertTrue(whole.dstDerth > bonded.dstDerth)
        // Without the rewards the queue no longer covers it.
        chain.rewards.remove(vA)
        assertEquals(bonded.dstDerth, a.quoteMove(vA, vB, 500_000).dstDerth)
        chain.rewards[vA] = java.math.BigInteger.valueOf(300_000)
        a.redelegate(whole); a.sync()
        assertEquals(whole.dstDerth, a.at(vB).single().amount)
    }

    /** A validator the list says takes no stake is refused before anything is laid out, with the chain's reason. */
    @Test
    fun aValidatorNotTakingStakeIsRefusedUpFront() {
        val chain = FakeChain()
        val a = staked(chain)
        chain.refusals[vB] = "$vB is jailed: validator cannot take delegations"
        val sims = chain.simulated
        val d = assertThrows(IllegalStateException::class.java) { a.quoteDelegate(vB, 1_000_000) }
        assertTrue(d.message!!, "jailed" in d.message!!)
        val m = assertThrows(IllegalStateException::class.java) { a.quoteMove(vA, vB, 500_000) }
        assertTrue(m.message!!, "jailed" in m.message!!)
        assertEquals(sims, chain.simulated)
        // Leaving it is not refused: undelegations and moves out do not depend on it.
        chain.refusals.clear(); chain.refusals[vA] = "$vA is jailed"
        assertTrue(a.quoteUndelegate(vA, 100_000).value > 0)
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        assertTrue(a.at(vB).isNotEmpty())
    }

    /** Every quote reads the whole list again, never one validator; an undelegation is quoted at the live rate. */
    @Test
    fun quotesReadTheWholeValidatorList() {
        val chain = FakeChain()
        val a = staked(chain)
        val before = chain.validatorsReads
        a.quoteDelegate(vA, 1_000_000)
        a.quoteMove(vA, vB, 500_000)
        val u = a.quoteUndelegate(vA, 900_000)
        assertEquals(before + 3, chain.validatorsReads)
        assertEquals(1_000_000L, u.value)
        assertEquals(0L, u.haircut)
        a.sync()
        assertEquals(before + 4, chain.validatorsReads)
    }

    /**
     * A move whose pair may reach its entry cap before it lands declares the
     * merge's gas on top of its simulation; one far from the cap, or already
     * at it (simulated), does not.
     */
    @Test
    fun aMoveNearThePairsEntryCapDeclaresTheMerge() {
        assertEquals(0L, PrivacyWallet.redelegateHeadroom(10, 10))
        assertEquals(0L, PrivacyWallet.redelegateHeadroom(1_100, 1_024))
        assertEquals(0L, PrivacyWallet.redelegateHeadroom(972, 972))
        assertEquals((973L + 51) * 2_500 + 128L * 20_000, PrivacyWallet.redelegateHeadroom(973, 973))
        val chain = FakeChain()
        val a = staked(chain)
        chain.redelegationLoads[vA to vB] = 1_000L to 1_000L
        val q = a.quoteMove(vA, vB, 500_000)
        assertEquals(1_000L to 1_000L, q.pairEntries to q.pairCounted)
        a.redelegate(q); a.sync()
        val m = chain.lastMsg as MsgRedelegate
        val gas = chain.gasOf(m)
        assertTrue(chain.lastGasLimit >= gas + gas / 10 + PrivacyWallet.redelegateHeadroom(1_000, 1_000))
    }

    @Test
    fun theMsgShapesAreTheChains() {
        assertEquals(PrivateMsgs.REDELEGATE, PrivateMsgs.typeUrl(MsgRedelegate.getDefaultInstance()))
        assertEquals(DebtTree.EMPTY_ROOT, FakeChain().debtRoot())
    }
}
