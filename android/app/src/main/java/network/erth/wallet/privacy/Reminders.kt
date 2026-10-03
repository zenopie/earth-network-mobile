package network.erth.wallet.privacy

import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles

/**
 * What the wallet reminds its owner to do, in place of doing it unasked: the
 * day's ANML claim, the caretaker vote's refresh before it lapses, and the
 * handle's renewal before (and through) its renewal period. Each costs a
 * fee, so each is the owner's decision; nothing here broadcasts anything.
 *
 * [due] is pure (unit-tested); the app reads its inputs after a sync.
 */
object Reminders {
    /** How long before a caretaker vote or handle lapses the reminder starts. */
    const val LEAD_SECONDS = Handles.REMINDER_LEAD_SECONDS

    /** How long after a caretaker vote lapsed the reminder to cast it again stays. */
    const val LAPSED_SECONDS = 30L * 86_400

    sealed interface Reminder {
        /** Today's ANML can be claimed. */
        data object AnmlReady : Reminder

        /** The caretaker vote lapses at [expiresAt] (lapsed when in the past): cast it again to keep it counted. */
        data class CaretakerExpiring(val expiresAt: Long, val lapsed: Boolean) : Reminder

        /**
         * The handle's lease ends at [expiresAt]; until [renewalUntil] only
         * its owner may renew it ([inRenewal]: it no longer resolves).
         */
        data class HandleExpiring(val handle: String, val expiresAt: Long, val renewalUntil: Long, val inRenewal: Boolean) : Reminder
    }

    data class Inputs(
        val now: Long,
        val identityLive: Boolean,
        /** PrivacyWallet.claimOpensAt: 0 for now, null without a live registration. */
        val claimOpensAt: Long?,
        val claimedToday: Boolean,
        /** When the caretaker vote lapses (0: none cast). */
        val caretakerExpiresAt: Long,
        /** This identity's handle ("" for none) and its directory entry (null: not found). */
        val handle: String,
        val handleEntry: HandleEntry?,
    )

    fun due(i: Inputs): List<Reminder> {
        val out = ArrayList<Reminder>()
        if (i.identityLive && !i.claimedToday && i.claimOpensAt == 0L) out.add(Reminder.AnmlReady)
        val c = i.caretakerExpiresAt
        if (i.identityLive && c > 0 && i.now >= c - LEAD_SECONDS && i.now < c + LAPSED_SECONDS) {
            out.add(Reminder.CaretakerExpiring(c, lapsed = i.now >= c))
        }
        val e = i.handleEntry
        if (i.identityLive && i.handle.isNotEmpty() && e != null && e.handle == i.handle) {
            val st = e.statusAt(i.now)
            if (st != HandleEntry.FREE && i.now >= e.expiresAt - LEAD_SECONDS && i.now < e.renewalUntil) {
                out.add(Reminder.HandleExpiring(e.handle, e.expiresAt, e.renewalUntil, inRenewal = st == HandleEntry.RENEWAL))
            }
        }
        return out
    }

    /** The reminder's line for a banner. */
    fun text(r: Reminder, now: Long): String = when (r) {
        Reminder.AnmlReady -> "Your ANML is ready to claim today."
        is Reminder.CaretakerExpiring ->
            if (r.lapsed) "Your caretaker vote has lapsed and no longer counts. Cast it again to keep directing emissions."
            else "Your caretaker vote expires in ${days(r.expiresAt - now)}. Renew it to keep it counted."
        is Reminder.HandleExpiring ->
            if (r.inRenewal) "@${r.handle} has expired and no longer receives payments. Renew it within ${days(r.renewalUntil - now)} or anyone may claim it."
            else "@${r.handle} expires in ${days(r.expiresAt - now)}. Renew it to keep it."
    }

    private fun days(seconds: Long): String {
        val d = maxOf(0L, (seconds + 86_399) / 86_400)
        return if (d == 1L) "1 day" else "$d days"
    }
}
