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
    // earth-1, 2026-10-07: a first stake before the first round ended (as StakeRoundTests.swift).
    private val live = """
        {"validators":[{"validator":"earthvaloper1n6amvkgfrrgy6ulhurewnm0endkgye69vpfk7m","delegatable":true,
          "book":{"pending_delegation":"117607253770","pending_undelegation":"0","epoch_rate":"1.000000000000000000",
                  "derth_supply":"117607253770","checkpoint_seq":"0","supply_height":"2395","supply_at_block_start":"0","slash_debt":"0"},
          "backing":"117607253770","supply":"117607253770","rate":"1.000000000000000000","delegation":"0","rewards":"0","redelegations":[]}],
         "pagination":{"next_key":null,"total":"0"},"height":"8158"}
    """
    private val op = "earthvaloper1n6amvkgfrrgy6ulhurewnm0endkgye69vpfk7m"
    private val staked = 117_607_253_770L
    private val denom = PrivacyWallet.derthDenom(op)

    private fun liveRates(): Map<String, java.math.BigDecimal> =
        network.erth.wallet.privacy.chain.ValidatorPages.parse(org.json.JSONObject(live)).validators.associate { it.validator to it.rate }

    @Test
    fun aFirstStakeBeforeTheRoundEndsIsValuedAndWaiting() {
        val rates = liveRates()
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(rates.getValue(op)))
        val ns = listOf(note(0, 2395, staked, denom = denom))
        val lines = StakeRound.lines(ns, emptyList(), { rates[it] ?: java.math.BigDecimal.ONE }, 1)
        assertEquals(1, lines.size)
        assertEquals(op, lines[0].validator)
        assertEquals(staked, lines[0].value)
        assertEquals(staked, lines[0].joiningValue)
        assertEquals(0L, StakeRound.lines(ns, emptyList(), { rates[it] ?: java.math.BigDecimal.ONE }, 2395)[0].joiningValue)
    }

    /** The chain now: the note locked whole into position 0 at 2541; notes alone gave 0 (the Stake tab's zero). */
    @Test
    fun stakeLockedInAPositionStillCounts() {
        val rates = liveRates()
        val ns = listOf(note(0, 2395, staked, spent = 2541, denom = denom))
        val ps = listOf(StakeRound.Locked(op, staked, 2541))
        assertTrue(StakeRound.lines(ns, emptyList(), { rates[it] ?: java.math.BigDecimal.ONE }, 1).isEmpty())
        val l = StakeRound.lines(ns, ps, { rates[it] ?: java.math.BigDecimal.ONE }, 1).single()
        assertEquals(0L, l.notes)
        assertEquals(staked, l.locked)
        assertEquals(staked, l.value)
        assertEquals(staked, l.lockedValue)
        assertEquals(staked, l.joiningValue)
        assertEquals(0L, StakeRound.joining(ns, denom, 2400, ps))
        val part = listOf(note(0, 2395, staked, spent = 2541, denom = denom), note(1, 2541, staked - 1_000_000, denom = denom))
        val one = listOf(StakeRound.Locked(op, 1_000_000, 2541))
        val p = StakeRound.lines(part, one, { java.math.BigDecimal("1.5") }, 1).single()
        assertEquals(staked, p.derth)
        assertEquals(staked / 2 * 3, p.value)
        assertEquals(staked / 2 * 3, p.joiningValue)
    }
}
