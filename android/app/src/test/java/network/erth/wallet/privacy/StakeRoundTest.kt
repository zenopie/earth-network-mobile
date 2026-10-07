package network.erth.wallet.privacy

import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.note.StakeLabel
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Stake screen's round: what is still joining, the round's block, standings (as StakeRoundTests.swift). */
class StakeRoundTest {
    private fun note(pos: Long, height: Long, amount: Long, spent: Long? = null, label: StakeLabel? = null, denom: String = "derth/v") =
        OwnedStakeNote(pos, height, denom, amount, Fr.of(pos), Fr.ONE, Fr.of(pos), Fr.of(pos + 1000), spentHeight = spent, label = label)

    @Test
    fun joiningCountsDelegationsAfterTheRoundOnly() {
        val ns = listOf(note(1, 40, 100, spent = 60), note(2, 60, 140, spent = 70), note(3, 70, 150))
        assertEquals(50, StakeRound.joining(ns, "derth/v", 50))
        assertEquals(10, StakeRound.joining(ns, "derth/v", 60))
        assertEquals(0, StakeRound.joining(ns, "derth/v", 70))
        assertEquals(0, StakeRound.joining(ns, "derth/w", 0))
        assertEquals(30, StakeRound.joining(listOf(note(1, 60, 30)), "derth/v", 50))
    }

    @Test
    fun unstakeMergeAndMoveInAreNotJoining() {
        assertEquals(0, StakeRound.joining(listOf(note(1, 40, 100, spent = 60), note(2, 60, 70)), "derth/v", 50))
        assertEquals(0, StakeRound.joining(listOf(note(1, 40, 10, spent = 60), note(2, 45, 20, spent = 60), note(3, 60, 30)), "derth/v", 50))
        val l = StakeLabel(Fr.of(77), 1_000, 25)
        assertEquals(0, StakeRound.joining(listOf(note(1, 40, 10, spent = 60), note(2, 60, 35, label = l)), "derth/v", 50))
        assertEquals(10, StakeRound.joining(listOf(note(1, 40, 35, spent = 60, label = l), note(2, 60, 45, label = l)), "derth/v", 50))
        assertEquals(5, StakeRound.joining(listOf(note(1, 60, 50, spent = 70), note(2, 70, 5)), "derth/v", 50))
    }

    private fun time(h: Long): Long = 1_000 + h * 6 + h % 3

    @Test
    fun firstHeightFindsTheRoundsBlock() {
        val tip = 20_000L
        for (target in listOf(1L, 2L, 777L, 14_400L, 19_999L, 20_000L)) {
            var asked = 0
            val h = StakeRound.firstHeight(time(target), tip, time(tip)) { asked++; time(it) }
            assertEquals("target $target", target, h)
            assertTrue(asked <= 40)
        }
        assertEquals(501L, StakeRound.firstHeight(time(500) + 1, tip, time(tip)) { time(it) })
        assertNull(StakeRound.firstHeight(time(500), tip, time(tip)) { null })
        assertNull(StakeRound.firstHeight(time(tip) + 1, tip, time(tip)) { time(it) })
    }

    @Test
    fun countdown() {
        assertEquals("now", StakeRound.countdown(0))
        assertEquals("under a minute", StakeRound.countdown(30))
        assertEquals("45m", StakeRound.countdown(45 * 60))
        assertEquals("20h 15m", StakeRound.countdown(20 * 3600 + 15 * 60))
        assertEquals("3h", StakeRound.countdown(3 * 3600))
        assertEquals("5 days", StakeRound.countdown(5 * 86_400))
    }

    @Test
    fun standing() {
        fun q(status: String, jailed: Boolean = false, tomb: Boolean = false, ok: Boolean = true) =
            PrivacyChainReads.ValidatorQuote("v", java.math.BigInteger.ZERO, java.math.BigInteger.ZERO,
                status = status, jailed = jailed, tombstoned = tomb, delegatable = ok)
        assertEquals(StakeRound.Standing.ACTIVE, StakeRound.Standing.of(q(PrivacyChainReads.BOND_STATUS_BONDED)))
        assertEquals(StakeRound.Standing.CLOSED, StakeRound.Standing.of(q(PrivacyChainReads.BOND_STATUS_BONDED, ok = false)))
        assertEquals(StakeRound.Standing.INACTIVE, StakeRound.Standing.of(q("BOND_STATUS_UNBONDED")))
        assertEquals(StakeRound.Standing.JAILED, StakeRound.Standing.of(q("BOND_STATUS_UNBONDED", jailed = true, ok = false)))
        assertEquals(StakeRound.Standing.TOMBSTONED, StakeRound.Standing.of(q("BOND_STATUS_UNBONDED", jailed = true, tomb = true, ok = false)))
        assertEquals(StakeRound.Standing.REMOVED, StakeRound.Standing.of(q("")))
        assertNull(StakeRound.Standing.ACTIVE.reason)
    }
}
