package network.erth.wallet.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {
    private val day = 20_000L
    private val now = day * 86_400 + 5 * 3600

    /**
     * Round 5, and chain 48b631c (user decision): the wallet sends nothing on
     * its own. The day's claim, the caretaker vote and the handle are
     * reminders; an undelegation pays out by itself (nothing to claim).
     */
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
    fun payoutTimeComesFromEpochTimingAlone() {
        val day = 86_400L
        val unbonding = 21 * day
        val m = PrivacyWallet.PAYOUT_MARGIN_S
        // Epoch 10 began at t and ends at t + day.
        val t = 1_800_000_000L
        // Booked in the epoch in progress: it ends with it, then unbonds.
        assertEquals(t + day + unbonding + m, PrivacyWallet.unbondDueBy(10, 10, t, t + day, day, unbonding))
        // A late epoch end (the end time passed): at least one epoch from its start.
        assertEquals(t + day + unbonding + m, PrivacyWallet.unbondDueBy(10, 10, t, t, day, unbonding))
        // An ended epoch: 9 ended at t, 7 at least two epochs earlier.
        assertEquals(t + unbonding + m, PrivacyWallet.unbondDueBy(9, 10, t, t + day, day, unbonding))
        assertEquals(t - 2 * day + unbonding + m, PrivacyWallet.unbondDueBy(7, 10, t, t + day, day, unbonding))
        // Chain-supplied numbers never wrap.
        assertEquals(null, PrivacyWallet.unbondDueBy(0, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, 0))
        assertEquals(null, PrivacyWallet.unbondDueBy(1, 1, Long.MAX_VALUE, Long.MAX_VALUE, 1, Long.MAX_VALUE))
        assertEquals(null, PrivacyWallet.unbondDueBy(-1, 3, 0, 1, 1, 1))
        assertEquals(null, PrivacyWallet.unbondDueBy(1, 3, 0, 1, 0, 1))
    }
}
