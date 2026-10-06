package network.erth.wallet.privacy

import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.ui.govern.positionSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Groundworks positions: split rounding, and a position's vote recorded once the node takes it. */
class PositionsTest : WalletTest() {
    private fun p(derth: Long, splits: Map<Long, Long>) = PrivacyChainReads.Position(1, "v", derth, Fr.ZERO, splits)

    private val vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    @Test
    fun weighsEachPositionByItsStakeAndSumsTo100() {
        assertEquals(emptyMap<Long, Long>(), positionSplit(emptyList()))
        assertEquals(mapOf(2L to 100L), positionSplit(listOf(p(5, mapOf(2L to 100L)))))
        // 3:1 stake, 100% to option 1 vs 100% to option 2.
        assertEquals(mapOf(1L to 75L, 2L to 25L), positionSplit(listOf(p(3_000, mapOf(1L to 100L)), p(1_000, mapOf(2L to 100L)))))
        // Thirds: largest remainder keeps the total at 100.
        val thirds = positionSplit(listOf(p(1, mapOf(1L to 100L)), p(1, mapOf(2L to 100L)), p(1, mapOf(3L to 100L))))
        assertEquals(100L, thirds.values.sum())
    }

    @Test
    fun positionVoteReportsAcceptanceBeforeItsBlock() {
        val chain = FakeChain()
        val a = wallet(chain)
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        a.lockPosition(vB, 100_000, mapOf(2L to 100L)); a.sync()
        chain.openProposal(12)
        a.sync()
        val pos = a.stakeVoteItems(12).filterIsInstance<PrivacyWallet.StakeVoteItem.Position>().single()
        var acceptedHash: String? = null
        chain.unconfirmedNext = 1
        val (p, c) = a.positions().single { it.first.id == pos.id }
        assertThrows(Exception::class.java) { a.positionVote(p, c, 12, yes) { acceptedHash = it } }
        assertNotNull(acceptedHash)
        assertTrue(acceptedHash in chain.txs)
        assertEquals(1, chain.positionVotes.size)
    }

    /**
     * A split's lease: read from the wallet's own position read, renewed by
     * casting the same split again (MsgUpdatePosition), and still known after
     * the chain clears a lapsed split, with the split to cast again. Nothing
     * is sent unless the wallet is asked.
     */
    @Test
    fun splitLeaseRenewsAndLapses() {
        val chain = FakeChain().apply { groundworksLease = 40L * 86_400 }
        val a = wallet(chain)
        repeat(4) { funded(chain, a, 2_000_000) }
        a.sync()
        a.delegate(vB, 1_000_000); a.sync()
        a.lockPosition(vB, 100_000, mapOf(2L to 100L)); a.sync()
        val (p0, c0) = a.positions().single()
        val l0 = a.groundworksLeases(listOf(p0)).single()
        assertEquals(chain.now + chain.groundworksLease, l0.expiresAt)
        assertTrue(l0.held && !l0.lapsed(chain.now) && !l0.renewalDue(chain.now))

        // A week before the end: due, and nothing was sent on its own.
        chain.now = l0.expiresAt - 7 * 86_400
        val txs = chain.txs.size
        val l1 = a.groundworksLeases(a.positions().map { it.first }).single()
        assertTrue(l1.renewalDue(chain.now))
        assertEquals(txs, chain.txs.size)
        // Renew: the same split, a new lease from now.
        a.updatePosition(p0, c0, l1.split); a.sync()
        val l2 = a.groundworksLeases(a.positions().map { it.first }).single()
        assertEquals(chain.now + chain.groundworksLease, l2.expiresAt)
        assertEquals(mapOf(2L to 100L), l2.split)

        // The lapse: the chain clears the split; the wallet still knows when, and what it was.
        chain.now = l2.expiresAt
        chain.lapseSplits()
        val (p3, c3) = a.positions().single()
        assertTrue(p3.splits.isEmpty())
        val l3 = a.groundworksLeases(listOf(p3)).single()
        assertTrue(!l3.held && l3.lapsed(chain.now))
        assertEquals(l2.expiresAt, l3.expiresAt)
        assertEquals(mapOf(2L to 100L), l3.split)
        assertEquals(Reminders.Reminder.GroundworksExpiring(p3.id, l2.expiresAt, true), Reminders.groundworks(l3, chain.now))
        // Survives a reload of the store, and a re-cast counts again.
        assertEquals(l2.expiresAt, a.store.state.positionLeases.getValue(p3.id).expiresAt)
        assertEquals(l2.expiresAt, network.erth.wallet.privacy.sync.PrivacyState.fromJson(a.store.state.toJson()).positionLeases.getValue(p3.id).expiresAt)
        a.updatePosition(p3, c3, l3.split); a.sync()
        val l4 = a.groundworksLeases(a.positions().map { it.first }).single()
        assertTrue(l4.held && !l4.lapsed(chain.now))
        // Closed: forgotten.
        a.unlockPosition(a.positions().single().first, c3); a.sync()
        assertTrue(a.positions().isEmpty())
        assertTrue(a.store.state.positionLeases.isEmpty())
    }
}
