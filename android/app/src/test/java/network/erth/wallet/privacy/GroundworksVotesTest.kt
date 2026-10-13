package network.erth.wallet.privacy

import network.erth.wallet.privacy.sync.PrivacyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Groundworks votes by stake note: the split rides every stake tx onto the
 * note it makes, a spend cancels the vote, and (v1.3.0) a vote has no lease:
 * it ends with its vintage, and a check-in carries it on (as
 * GroundworksVoteTests.swift).
 */
class GroundworksVotesTest : WalletTest() {
    private val vA = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private val vB: String = Vectors.json.getString("validator2")
    private val day = 20_000L
    private val now = day * 86_400 + 5 * 3600

    private fun staked(chain: FakeChain): PrivacyWallet {
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 3_000_000) }
        a.sync()
        a.delegate(vA, 2_000_000); a.sync()
        return a
    }

    private fun held(a: PrivacyWallet, v: String): Long =
        a.stakeNotes.filter { it.spendable && it.denom == PrivacyWallet.derthDenom(v) }.sumOf { it.amount }

    /**
     * Casting votes all our stake in one tx per validator; every later stake
     * tx carries the split onto the note it makes, so the vote follows the
     * stake: a top-up adds to it, an unstake shrinks it, a move leaves the
     * change voting. Stopping cancels it.
     */
    @Test
    fun voteFollowsTheStake() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val a = staked(chain)
        assertTrue(chain.gwVotes.isEmpty())

        a.castGroundworks(mapOf(2L to 60L, 3L to 40L))
        var mine = a.groundworksVotes()
        assertEquals(1, mine.size)
        assertEquals(vA, mine[0].validator)
        assertEquals(held(a, vA), mine[0].derth)
        assertEquals(mapOf(2L to 60L, 3L to 40L), mine[0].split)
        // No lease: the vote is filed under the note's vintage and ends with it.
        assertEquals(chain.baseVintage, mine[0].vintage)
        assertEquals(0L, chain.gwVotes.values.single().splitExpiresAt)

        // A top-up: the merged note votes it all, one vote.
        a.delegate(vA, 1_000_000); a.sync()
        mine = a.groundworksVotes()
        assertEquals(1, chain.gwVotes.size)
        assertEquals(held(a, vA), mine[0].derth)

        // An unstake: the change votes.
        a.undelegate(vA, 500_000); a.sync()
        mine = a.groundworksVotes()
        assertEquals(1, chain.gwVotes.size)
        assertEquals(held(a, vA), mine[0].derth)

        // A move: the change at A votes; the credit at B votes too, pending
        // (exposed until its move's window closes), and then counts by
        // itself: nothing to re-cast.
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        mine = a.groundworksVotes()
        assertEquals(setOf(vA, vB), mine.map { it.validator }.toSet())
        assertEquals(held(a, vA), mine.first { it.validator == vA }.derth)
        val atB = mine.first { it.validator == vB }
        assertEquals(0L, atB.derth)
        assertEquals(held(a, vB), atB.pending)
        assertTrue(atB.maturesAt > chain.now)
        val txs = chain.txs.size
        chain.now = atB.maturesAt
        chain.matureVotes()
        val matured = a.groundworksVotes().first { it.validator == vB }
        assertEquals(held(a, vB), matured.derth)
        assertEquals(0L, matured.pending)
        assertEquals("the chain counts it; the wallet sent nothing", txs, chain.txs.size)

        // Stop: the split cleared, the vote cancelled.
        a.castGroundworks(emptyMap())
        assertTrue(chain.gwVotes.isEmpty())
        assertTrue(a.groundworksSplit.isEmpty())
        // And a later stake tx votes nothing.
        a.delegate(vA, 1_000_000); a.sync()
        assertTrue(chain.gwVotes.isEmpty())
    }

    /** A vote changed while moved stake is still exposed keeps its pending part: the restake keeps the label and publishes it (lane A's pending). */
    @Test
    fun changingTheVoteKeepsThePendingPart() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val a = staked(chain)
        a.castGroundworks(mapOf(2L to 100L))
        a.redelegate(a.quoteMove(vA, vB, 500_000)); a.sync()
        a.castGroundworks(mapOf(3L to 100L))
        val atB = a.groundworksVotes().first { it.validator == vB }
        assertEquals(mapOf(3L to 100L), atB.split)
        assertEquals(held(a, vB), atB.pending)
        assertTrue("lane A published the kept label's exposure", chain.prover.allStakes.any { it.pEx > 0 })
        chain.now = atB.maturesAt
        chain.matureVotes()
        assertEquals(held(a, vB), a.groundworksVotes().first { it.validator == vB }.derth)
    }

    /** Notes made apart (another device; delegations without a sync between) merge as the vote is cast, until all of the stake votes as one note. */
    @Test
    fun castingMergesEveryNote() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val a = wallet(chain)
        repeat(6) { funded(chain, a, 3_000_000) }
        a.sync()
        repeat(3) { a.delegate(vA, 1_000_000) }
        a.sync()
        assertEquals(3, a.stakeNotes.count { it.spendable })
        val rs = a.castGroundworks(mapOf(2L to 100L))
        assertEquals(2, rs.size)
        assertEquals(1, a.stakeNotes.count { it.spendable })
        assertEquals(listOf(held(a, vA)), a.groundworksVotes().map { it.derth })
    }

    /** A split naming a removed option: stake moves go on without the vote; a new split votes again. */
    @Test
    fun aRemovedOptionStopsTheVoteNotTheStake() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val a = staked(chain)
        a.castGroundworks(mapOf(2L to 60L, 3L to 40L))
        assertEquals(1, chain.gwVotes.size)
        chain.gwOptions = setOf(2L)
        assertEquals(false, a.groundworksSplitLive(mapOf(2L to 60L, 3L to 40L)))
        a.delegate(vA, 1_000_000); a.sync()
        assertTrue(chain.gwVotes.isEmpty())
        // Casting (or renewing) it again is refused up front: nothing sent.
        val txs = chain.txs.size
        org.junit.Assert.assertThrows(Exception::class.java) { a.castGroundworks(mapOf(2L to 60L, 3L to 40L)) }
        assertEquals(txs, chain.txs.size)
        // A new split votes again.
        a.castGroundworks(mapOf(2L to 100L))
        assertEquals(1, chain.gwVotes.size)
    }

    /** A read the vote needs failing refuses the tx: never sent without the vote (its input's tag would cancel it). */
    @Test
    fun aFailedReadRefusesRatherThanCancels() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val a = staked(chain)
        a.castGroundworks(mapOf(2L to 100L))
        val txs = chain.txs.size
        chain.failMinVoteRead = true
        org.junit.Assert.assertThrows(Exception::class.java) { a.delegate(vA, 1_000_000) }
        assertEquals(txs, chain.txs.size)
        assertEquals(1, chain.gwVotes.size)
        chain.failMinVoteRead = false
        a.delegate(vA, 1_000_000); a.sync()
        assertEquals(1, chain.gwVotes.size)
    }

    /** Stake below the least vote weight cannot vote (the chain refuses less): the cast is refused, nothing sent. */
    @Test
    fun belowTheMinimumDoesNotVote() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000_000 }
        val a = staked(chain)
        // Refused up front: nothing could vote, so nothing is sent.
        val txs = chain.txs.size
        org.junit.Assert.assertThrows(Exception::class.java) { a.castGroundworks(mapOf(2L to 100L)) }
        assertEquals(txs, chain.txs.size)
        assertTrue(chain.gwVotes.isEmpty())
    }

    /**
     * No lease (v1.3.0): the vote counts until its vintage retires, with
     * nothing to renew; a check-in carries it into the current month (both
     * outputs vote with the split), and only then does it outlive the old
     * vintage. Nothing is sent unless the wallet is asked.
     */
    @Test
    fun voteEndsWithItsVintageAndCheckInCarriesIt() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000 }
        val a = staked(chain)
        a.castGroundworks(mapOf(2L to 100L))
        val base = chain.baseVintage
        assertEquals(listOf(base), a.groundworksVotes().map { it.vintage })

        // Eleven months on: still counting, nothing sent by itself.
        chain.now = Vintages.start(Vintages.addMonths(base, 11)) + 86_400
        val txs = chain.txs.size
        assertEquals(1, a.groundworksVotes().size)
        assertEquals(txs, chain.txs.size)

        // The check-in moves the stake into this month and the vote with it.
        a.sync()
        val r = a.checkInAll()
        assertEquals(1, r.size)
        val m = chain.checkIns.single()
        assertEquals(mapOf(2L to 100L), m.groundworksSplitList.associate { it.optionId to it.percent })
        val month = chain.currentVintage()
        val votes = a.groundworksVotes()
        assertEquals(listOf(month), votes.map { it.vintage })
        assertEquals(mapOf(2L to 100L), votes.single().split)

        // Past the old vintage's retirement the vote still counts: it is in the new one.
        chain.now = Vintages.retiresAt(base) + 3_600
        assertEquals(1, a.groundworksVotes().size)

        // Without a check-in a vintage's vote dies with it.
        chain.now = Vintages.retiresAt(month) + 3_600
        chain.retireDue()
        assertTrue(a.groundworksVotes().isEmpty())
        // The store carries no lease: only the split.
        val decoded = PrivacyState.fromJson(a.store.state.toJson())
        assertEquals(mapOf(2L to 100L), decoded.groundworksSplit)
        assertEquals(base, decoded.baseVintage)
    }
}
