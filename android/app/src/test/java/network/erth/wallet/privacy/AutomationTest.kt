package network.erth.wallet.privacy

import network.erth.wallet.privacy.PrivacyAutomation.Action
import network.erth.wallet.privacy.PrivacyAutomation.Inputs
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.note.OwnedNote
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
        val n = OwnedNote(3, 1, NotePlaintext("unbond/v/1", 5, Fr.ONE, Fr.ONE), Fr.ONE, Fr.ONE)
        val acts = PrivacyAutomation.decide(base.copy(claimedToday = true, caretakerDue = true, hasFeeErth = false, maturedUnbonds = listOf(n)))
        // No fee note: the caretaker refresh waits, the unbonding claim pays from its output.
        assertEquals(listOf<Action>(Action.ClaimUnbonding(n)), acts)
        assertEquals(listOf<Action>(Action.RefreshCaretaker), PrivacyAutomation.decide(base.copy(claimedToday = true, caretakerDue = true)))
    }

    @Test
    fun offsetIsWithinTheWindowAndStableForADay() {
        val a = PrivacyAutomation.claimOffset(day * 86_400 + 10)
        assertEquals(a, PrivacyAutomation.claimOffset(day * 86_400 + 80_000))
        assertTrue(a in 0 until PrivacyAutomation.CLAIM_WINDOW_S)
    }
}
