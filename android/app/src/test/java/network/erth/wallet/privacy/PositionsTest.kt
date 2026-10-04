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
}
