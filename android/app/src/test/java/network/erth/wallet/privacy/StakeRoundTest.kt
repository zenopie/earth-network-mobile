package network.erth.wallet.privacy

import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.note.StakeLabel
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Stake screen's stake lines, countdowns and standings (as StakeRoundTests.swift). */
class StakeRoundTest {
    private fun note(pos: Long, height: Long, amount: Long, spent: Long? = null, label: StakeLabel? = null, denom: String = "derth/v") =
        OwnedStakeNote(pos, height, denom, amount, Fr.of(pos), Fr.ONE, Fr.of(pos), Fr.of(pos + 1000), spentHeight = spent, label = label)

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
    // earth-1, 2026-10-07: a first stake (as StakeRoundTests.swift).
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

    /** The MsgDelegate at 2395, as a stake note: valued at the live rate. */
    @Test
    fun aStakeIsValuedAtTheRate() {
        val rates = liveRates()
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(rates.getValue(op)))
        val ns = listOf(note(0, 2395, staked, denom = denom))
        val lines = StakeRound.lines(ns) { rates[it] ?: java.math.BigDecimal.ONE }
        assertEquals(1, lines.size)
        assertEquals(op, lines[0].validator)
        assertEquals(staked, lines[0].value)
        // Spent notes do not count; several notes add up, at the rate.
        assertTrue(StakeRound.lines(listOf(note(0, 2395, staked, spent = 2541, denom = denom))) { rates[it] ?: java.math.BigDecimal.ONE }.isEmpty())
        val two = listOf(note(0, 2395, 1_000_000, denom = denom), note(1, 2400, 2_000_000, denom = denom))
        val l = StakeRound.lines(two) { java.math.BigDecimal("1.5") }.single()
        assertEquals(3_000_000L, l.derth)
        assertEquals(4_500_000L, l.value)
    }
}
