package network.erth.wallet.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reminders: what the wallet asks the user to do instead of doing it. */
class RemindersTest {
    private val day = 20_000L
    private val now = day * 86_400 + 5 * 3600

    /**
     * The wallet sends nothing on its own: the day's claim, the caretaker vote
     * and the handle are reminders; an undelegation pays out by itself
     * (nothing to claim).
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

    /** The move reminder grows urgent as the deadline nears. */
    @Test
    fun theMoveReminderEscalates() {
        val far = Reminders.text(Reminders.Reminder.MoveSuggested(now - 1, now + 20 * 86_400), now)
        assertTrue(far, far.contains("within 20 days"))
        val near = Reminders.text(Reminders.Reminder.MoveSuggested(now - 1, now + 2 * 86_400), now)
        assertTrue(near, near.startsWith("Move your handle and caretaker vote from your previous identity now"))
        assertTrue(Reminders.text(Reminders.Reminder.MoveSuggested(now - 1), now).contains("suggested time"))
    }

    /**
     * Before the registration's year ends: a handle or caretaker vote whose
     * lease ends within RENEW_FIRST_WINDOW_SECONDS after it is to be renewed
     * first (the live identity can; after the lapse it cannot).
     */
    @Test
    fun renewBeforeTheRegistrationLapses() {
        val end = now + 10 * 86_400
        val base = Reminders.Inputs(now, identityLive = true, claimOpensAt = null, claimedToday = true, caretakerExpiresAt = 0,
            handle = "alice", handleEntry = null, registrationEndsAt = end, handleExpiresAt = end + 5 * 86_400)
        val r = Reminders.due(base).filterIsInstance<Reminders.Reminder.RenewBeforeLapse>().single()
        assertEquals("alice", r.handle)
        assertTrue(Reminders.text(r, now).startsWith("Your registration ends in 10 days. Renew @alice before then"))
        // A lease that outlasts the window, or a registration far from its end: nothing.
        assertTrue(Reminders.due(base.copy(handleExpiresAt = end + PrivacyWallet.RENEW_FIRST_WINDOW_SECONDS)).none { it is Reminders.Reminder.RenewBeforeLapse })
        assertTrue(Reminders.due(base.copy(registrationEndsAt = now + Reminders.LEAD_SECONDS + 1)).none { it is Reminders.Reminder.RenewBeforeLapse })
        // The vote too.
        val v = Reminders.due(base.copy(handle = "", caretakerExpiresAt = end + 86_400)).filterIsInstance<Reminders.Reminder.RenewBeforeLapse>().single()
        assertTrue(Reminders.text(v, now).contains("Renew your caretaker vote before then"))
    }
}
