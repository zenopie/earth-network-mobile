package network.erth.wallet.privacy

import network.erth.wallet.privacy.sync.PrivacyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Groundworks votes by stake note: the split rides every stake tx onto the
 * note it makes, a spend cancels the vote, and a vote lapses after its lease
 * (as GroundworksVoteTests.swift).
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

    private fun lease(a: PrivacyWallet, read: Boolean = true): Reminders.GroundworksLease =
        a.groundworksLease(if (read) a.groundworksVotes() else emptyList())!!

    private fun held(a: PrivacyWallet, v: String): Long =
        a.stakeNotes.filter { it.spendable && it.denom == PrivacyWallet.derthDenom(v) }.sumOf { it.amount }

    /**
     * Reminded as the caretaker vote is (from 30 days before its lease end,
     * for 30 days after), and never while the lease end is unknown.
     */
    @Test
    fun groundworksLeaseReminders() {
        val q = Reminders.Inputs(now, identityLive = false, claimOpensAt = null, claimedToday = true, caretakerExpiresAt = 0, handle = "", handleEntry = null)
        fun due(g: Reminders.GroundworksLease?) = Reminders.due(q.copy(groundworks = g))
        fun lease(e: Long, held: Boolean, split: Map<Long, Long> = mapOf(2L to 100L)) = Reminders.GroundworksLease(e, held, split)
        fun expiring(e: Long, lapsed: Boolean) = listOf<Reminders.Reminder>(Reminders.Reminder.GroundworksExpiring(e, lapsed))
        assertTrue(due(null).isEmpty())
        assertTrue(due(lease(now + Reminders.LEAD_SECONDS + 1, true)).isEmpty())
        assertEquals(expiring(now + 7 * 86_400, false), due(lease(now + 7 * 86_400, true)))
        assertEquals(expiring(now + Reminders.LEAD_SECONDS, false), due(lease(now + Reminders.LEAD_SECONDS, true)))
        // Past its end, deleted by the chain or not yet: lapsed.
        assertEquals(expiring(now, true), due(lease(now, true)))
        assertEquals(expiring(now - 86_400, true), due(lease(now - 86_400, false)))
        assertTrue(due(lease(now - Reminders.LAPSED_SECONDS - 1, false)).isEmpty())
        // Unknown lease end: no reminder.
        assertTrue(due(lease(0, true)).isEmpty())
        assertTrue(lease(0, false).lapsed(now))
        assertFalse(lease(0, true).lapsed(now))
        // Hostile times saturate.
        Reminders.due(q.copy(now = Long.MIN_VALUE, groundworks = lease(Long.MAX_VALUE, true)))
        Reminders.text(Reminders.Reminder.GroundworksExpiring(Long.MAX_VALUE, false), Long.MIN_VALUE)
        assertTrue(Reminders.text(Reminders.Reminder.GroundworksExpiring(now + 7 * 86_400, false), now).contains("expires in 7 days"))
        assertTrue(Reminders.text(Reminders.Reminder.GroundworksExpiring(now - 1, true), now).contains("Vote again"))
    }

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
        assertEquals(chain.now + chain.groundworksLease, mine[0].expiresAt)

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
     * The lease: renewed by casting again, still known after the chain
     * deletes a lapsed vote, with the split to cast again. Nothing is sent
     * unless the wallet is asked.
     */
    @Test
    fun leaseRenewsAndLapses() {
        val chain = FakeChain().apply { minGroundworksVote = 100_000; groundworksLease = 40L * 86_400 }
        val a = staked(chain)
        a.castGroundworks(mapOf(2L to 100L))
        val l0 = lease(a)
        assertEquals(chain.now + chain.groundworksLease, l0.expiresAt)
        assertTrue(l0.held && !l0.lapsed(chain.now) && !l0.renewalDue(chain.now))

        // A week before the end: due, and nothing was sent on its own.
        chain.now = l0.expiresAt - 7 * 86_400
        val txs = chain.txs.size
        val l1 = lease(a)
        assertTrue(l1.renewalDue(chain.now))
        assertEquals(txs, chain.txs.size)
        // Renew: the same split, a new lease from now.
        a.castGroundworks(l1.split)
        val l2 = lease(a)
        assertEquals(chain.now + chain.groundworksLease, l2.expiresAt)
        assertEquals(mapOf(2L to 100L), l2.split)

        // The lapse: the chain deletes the vote; the wallet still knows when, and what it was.
        chain.now = l2.expiresAt
        chain.lapseSplits()
        assertTrue(a.groundworksVotes().isEmpty())
        val l3 = lease(a, read = false)
        assertTrue(!l3.held && l3.lapsed(chain.now))
        assertEquals(l2.expiresAt, l3.expiresAt)
        assertEquals(mapOf(2L to 100L), l3.split)
        assertEquals(Reminders.Reminder.GroundworksExpiring(l2.expiresAt, true), Reminders.groundworks(l3, chain.now))
        // Survives an encode/decode of the store, and a re-cast counts again.
        val decoded = PrivacyState.fromJson(a.store.state.toJson())
        assertEquals(l2.expiresAt, decoded.groundworksExpiresAt)
        assertEquals(mapOf(2L to 100L), decoded.groundworksSplit)
        a.castGroundworks(l3.split)
        val l4 = lease(a)
        assertTrue(l4.held && !l4.lapsed(chain.now))
        // Stopped: no lease.
        a.castGroundworks(emptyMap())
        assertNull(a.groundworksLease(emptyList()))
    }
}
