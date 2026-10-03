package network.erth.wallet.privacy

import network.erth.wallet.privacy.PrivacyAutomation.Action
import network.erth.wallet.privacy.PrivacyAutomation.Inputs
import network.erth.wallet.privacy.note.OwnedStakeNote
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {
    private val day = 20_000L
    private val base = Inputs(
        now = day * 86_400 + 5 * 3600, identityLive = true, claimOpensAt = 0, claimedToday = false,
        claimOffset = 4 * 3600, caretakerDue = false, hasFeeErth = true, maturedUnbonds = emptyList(),
    )

    @Test
    fun claimsOnceTheDaysOffsetHasPassed() {
        assertEquals(listOf(Action.ClaimAnml(day)), PrivacyAutomation.decide(base))
        assertTrue(PrivacyAutomation.decide(base.copy(claimOffset = 6 * 3600)).isEmpty())
        assertTrue(PrivacyAutomation.decide(base.copy(claimedToday = true)).isEmpty())
        assertTrue(PrivacyAutomation.decide(base.copy(claimOpensAt = 123)).isEmpty())
        assertTrue(PrivacyAutomation.decide(base.copy(identityLive = false)).isEmpty())
        assertTrue(PrivacyAutomation.decide(base.copy(hasFeeErth = false)).isEmpty())
    }

    @Test
    fun refreshesCaretakerAndClaimsUnbonding() {
        val n = stake(3, "unbond/v/1")
        val acts = PrivacyAutomation.decide(base.copy(claimedToday = true, caretakerDue = true, hasFeeErth = false, maturedUnbonds = listOf(n.denom)))
        // No fee note: the caretaker refresh waits, the unbonding claim pays from its output.
        assertEquals(listOf<Action>(Action.ClaimUnbonding(n.denom)), acts)
        assertEquals(listOf<Action>(Action.RefreshCaretaker), PrivacyAutomation.decide(base.copy(claimedToday = true, caretakerDue = true)))
    }

    @Test
    fun offsetIsWithinTheWindowAndStableForADay() {
        val st = network.erth.wallet.privacy.sync.PrivacyState()
        val a = PrivacyAutomation.claimOffset(st, day * 86_400 + 10)
        assertEquals(a, PrivacyAutomation.claimOffset(st, day * 86_400 + 80_000))
        assertTrue(a in 0 until PrivacyAutomation.CLAIM_WINDOW_S)
        // Audit 4: persisted with the wallet, so a restart (the state read back) keeps the day's draw.
        val back = network.erth.wallet.privacy.sync.PrivacyState.fromJson(st.toJson())
        var saved = 0
        assertEquals(a, PrivacyAutomation.claimOffset(back, day * 86_400 + 50_000) { saved++ })
        assertEquals(0, saved)
        PrivacyAutomation.claimOffset(back, (day + 1) * 86_400 + 5) { saved++ }
        assertEquals(1, saved)
        assertEquals(day + 1, back.claimOffsetDay)
    }

    @Test
    fun maturityComesFromEpochTimingAlone() {
        val day = 86_400L
        val unbonding = 21 * day
        // Epoch 10 began at t; epoch 9 ended exactly then, epoch 7 at least two epochs earlier.
        val t = 1_800_000_000L
        assertEquals(t + unbonding + PrivacyAutomation.MATURITY_MARGIN_S, PrivacyAutomation.maturesBy(9, 10, t, day, unbonding))
        assertEquals(t - 2 * day + unbonding + PrivacyAutomation.MATURITY_MARGIN_S, PrivacyAutomation.maturesBy(7, 10, t, day, unbonding))
        // The epoch in progress has not been undelegated yet.
        assertEquals(null, PrivacyAutomation.maturesBy(10, 10, t, day, unbonding))

        val n9 = stake(1, "unbond/v/9")
        val n10 = stake(2, "unbond/v/10")
        val by9 = PrivacyAutomation.maturesBy(9, 10, t, day, unbonding)!!
        assertTrue(PrivacyAutomation.matured(listOf(n9, n10), by9 - 1, 10, t, day, unbonding, emptyMap()).isEmpty())
        assertEquals(listOf(n9.denom), PrivacyAutomation.matured(listOf(n9, n10), by9, 10, t, day, unbonding, emptyMap()))
        // A claim the chain refused waits out its retry.
        assertTrue(PrivacyAutomation.matured(listOf(n9), by9, 10, t, day, unbonding, mapOf("unbond/v/9" to by9 + 1)).isEmpty())
        // Spent or pending notes are never claimed twice.
        assertTrue(PrivacyAutomation.matured(listOf(n9.copy(pendingAt = 1)), by9, 10, t, day, unbonding, emptyMap()).isEmpty())
    }

    @Test
    fun refreshesTheReferrerBinding() {
        assertEquals(listOf<Action>(Action.RefreshReferrer), PrivacyAutomation.decide(base.copy(claimedToday = true, referrerDue = true)))
        assertTrue(PrivacyAutomation.decide(base.copy(claimedToday = true, referrerDue = true, hasFeeErth = false)).isEmpty())
    }

    private fun stake(pos: Long, denom: String) = OwnedStakeNote(pos, 1, denom, 5, Fr.ONE, Fr.ONE, Fr.ONE, Fr.ONE)
}
