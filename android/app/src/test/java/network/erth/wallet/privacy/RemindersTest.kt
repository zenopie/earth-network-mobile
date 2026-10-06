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

    /**
     * A position's Groundworks split: reminded as the caretaker vote is (from
     * 30 days before its lease end, for 30 days after), whatever the
     * identity's state, and never while the lease end is unknown.
     */
    @Test
    fun groundworksLeaseReminders() {
        val q = Reminders.Inputs(now, identityLive = false, claimOpensAt = null, claimedToday = true, caretakerExpiresAt = 0, handle = "", handleEntry = null)
        fun due(vararg g: Reminders.GroundworksLease) = Reminders.due(q.copy(groundworks = g.toList()))
        val split = mapOf(2L to 100L)
        assertTrue(due(Reminders.GroundworksLease(3, now + Reminders.LEAD_SECONDS + 1, true, split)).isEmpty())
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.GroundworksExpiring(3, now + 7 * 86_400, false)), due(Reminders.GroundworksLease(3, now + 7 * 86_400, true, split)))
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.GroundworksExpiring(3, now + Reminders.LEAD_SECONDS, false)), due(Reminders.GroundworksLease(3, now + Reminders.LEAD_SECONDS, true, split)))
        // Past its end, cleared by the chain or not yet: lapsed.
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.GroundworksExpiring(3, now, true)), due(Reminders.GroundworksLease(3, now, true, split)))
        assertEquals(listOf<Reminders.Reminder>(Reminders.Reminder.GroundworksExpiring(3, now - 86_400, true)), due(Reminders.GroundworksLease(3, now - 86_400, false, split)))
        assertTrue(due(Reminders.GroundworksLease(3, now - Reminders.LAPSED_SECONDS - 1, false, split)).isEmpty())
        // Unknown lease end (a node before leases; a lapse never seen here): no reminder, though the card says lapsed.
        assertTrue(due(Reminders.GroundworksLease(3, 0, true, split), Reminders.GroundworksLease(4, 0, false, emptyMap())).isEmpty())
        assertTrue(Reminders.GroundworksLease(4, 0, false, emptyMap()).lapsed(now))
        assertTrue(!Reminders.GroundworksLease(3, 0, true, split).lapsed(now))
        // One per position.
        assertEquals(2, due(Reminders.GroundworksLease(3, now + 86_400, true, split), Reminders.GroundworksLease(5, now - 86_400, false, split)).size)
        // Hostile times saturate.
        Reminders.due(q.copy(now = Long.MIN_VALUE, groundworks = listOf(Reminders.GroundworksLease(1, Long.MAX_VALUE, true, split))))
        Reminders.text(Reminders.Reminder.GroundworksExpiring(1, Long.MAX_VALUE, false), Long.MIN_VALUE)
        assertTrue(Reminders.text(Reminders.Reminder.GroundworksExpiring(3, now + 7 * 86_400, false), now).contains("expires in 7 days"))
        assertTrue(Reminders.text(Reminders.Reminder.GroundworksExpiring(3, now - 1, true), now).contains("Choose a split again"))
    }
}
