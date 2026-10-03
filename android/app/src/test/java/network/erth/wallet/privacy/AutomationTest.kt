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
    private val now = day * 86_400 + 5 * 3600

    /**
     * Round 5 (user decision): nothing that spends a fee is automatic. The
     * day's claim, the caretaker vote and the handle are reminders; the one
     * automatic action is the end of an undelegation the user started.
     */
    @Test
    fun onlyMaturedUnbondingClaimsAreAutomatic() {
        assertTrue(PrivacyAutomation.decide(Inputs(now, emptyList())).isEmpty())
        val n = stake(3, "unbond/v/1")
        assertEquals(listOf<Action>(Action.ClaimUnbonding(n.denom)), PrivacyAutomation.decide(Inputs(now, listOf(n.denom))))
        // The only action kind there is (a sealed interface the JVM lists).
        assertEquals(listOf("ClaimUnbonding"), Action::class.java.permittedSubclasses.map { it.simpleName })
    }

    @Test
    fun remindersInsteadOfActions() {
        val base = Reminders.Inputs(now, identityLive = true, claimOpensAt = 0, claimedToday = false, caretakerExpiresAt = 0, handle = "", handleEntry = null)
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.AnmlReady), Reminders.due(base))
        assertTrue(Reminders.due(base.copy(claimedToday = true)).isEmpty())
        assertTrue(Reminders.due(base.copy(claimOpensAt = now + 100)).isEmpty())
        assertTrue(Reminders.due(base.copy(identityLive = false)).isEmpty())
        // The caretaker vote: from 30 days before it lapses, and for 30 days after.
        val q = base.copy(claimedToday = true)
        assertTrue(Reminders.due(q.copy(caretakerExpiresAt = now + Reminders.LEAD_SECONDS + 1)).isEmpty())
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.CaretakerExpiring(now + 86_400, false)), Reminders.due(q.copy(caretakerExpiresAt = now + 86_400)))
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.CaretakerExpiring(now - 86_400, true)), Reminders.due(q.copy(caretakerExpiresAt = now - 86_400)))
        assertTrue(Reminders.due(q.copy(caretakerExpiresAt = now - Reminders.LAPSED_SECONDS - 1)).isEmpty())
        // The handle: from 30 days before expiry, through the renewal period.
        val e = network.erth.wallet.privacy.handles.HandleEntry("alice", "erthz1x", "live", now + 10 * 86_400, now + 40 * 86_400)
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.HandleExpiring("alice", e.expiresAt, e.renewalUntil, false)), Reminders.due(q.copy(handle = "alice", handleEntry = e)))
        val r = e.copy(status = "renewal", expiresAt = now - 86_400, renewalUntil = now + 29 * 86_400)
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.HandleExpiring("alice", r.expiresAt, r.renewalUntil, true)), Reminders.due(q.copy(handle = "alice", handleEntry = r)))
        assertTrue(Reminders.due(q.copy(handle = "alice", handleEntry = e.copy(expiresAt = now + 200 * 86_400, renewalUntil = now + 230 * 86_400))).isEmpty())
        assertTrue(Reminders.due(q.copy(handle = "alice", handleEntry = r.copy(status = "free", renewalUntil = now - 1))).isEmpty())
        // A served "live" whose expiry passed by our clock is in its renewal period.
        assertEquals(true, (Reminders.due(q.copy(handle = "alice", handleEntry = e.copy(expiresAt = now - 5))).single() as Reminders.Reminder.HandleExpiring).inRenewal)
        assertTrue(Reminders.text(Reminders.Reminder.AnmlReady, now).contains("ANML"))
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

    private fun stake(pos: Long, denom: String) = OwnedStakeNote(pos, 1, denom, 5, Fr.ONE, Fr.ONE, Fr.ONE, Fr.ONE)
}
