package network.erth.wallet.privacy

import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles

/**
 * What the wallet reminds its owner to do, in place of doing it unasked: the
 * day's ANML claim, the caretaker vote's refresh before it lapses, each
 * Groundworks position's split before its lease ends, and the handle's
 * renewal before (and through) its renewal period. Each costs a fee, so each
 * is the owner's decision; nothing here broadcasts anything.
 *
 * [due] is pure (unit-tested); the app reads its inputs after a sync.
 */
object Reminders {
    /** How long before a caretaker vote or handle lapses the reminder starts. */
    const val LEAD_SECONDS = Handles.REMINDER_LEAD_SECONDS

    /** How long after a caretaker vote or a position's split lapsed the reminder to cast it again stays. */
    const val LAPSED_SECONDS = 30L * 86_400

    sealed interface Reminder {
        /** Today's ANML can be claimed. */
        data object AnmlReady : Reminder

        /** The caretaker vote lapses at [expiresAt] (lapsed when in the past): cast it again to keep it counted. */
        data class CaretakerExpiring(val expiresAt: Long, val lapsed: Boolean) : Reminder

        /** Position [positionId]'s Groundworks split lapses at [expiresAt] ([lapsed]: it has): renew or re-cast it. */
        data class GroundworksExpiring(val positionId: Long, val expiresAt: Long, val lapsed: Boolean) : Reminder

        /**
         * The handle's lease ends at [expiresAt]; until [renewalUntil] only
         * its owner may renew it ([inRenewal]: it no longer resolves).
         */
        data class HandleExpiring(val handle: String, val expiresAt: Long, val renewalUntil: Long, val inRenewal: Boolean) : Reminder

        /**
         * The live handle this identity holds pays another address (a
         * handle moved here keeps the old wallet's): renewing it from this
         * wallet points it here.
         */
        data class HandlePaysElsewhere(val handle: String) : Reminder

        /**
         * After a switch: the time the wallet suggested for bringing the
         * predecessor's handle and caretaker vote over ([at]) has come.
         * The move itself is the user's to make.
         */
        data class MoveSuggested(val at: Long) : Reminder
    }

    /**
     * One of this wallet's positions as its Groundworks split's lease stands,
     * from the wallet's own position read and what it last saw
     * (PrivacyWallet.groundworksLeases).
     */
    data class GroundworksLease(
        val positionId: Long,
        /** When the split stops (or stopped) counting; 0 unknown (a node before leases, a lapse never seen). */
        val expiresAt: Long,
        /** The chain still holds the split (it clears it at the lapse). */
        val held: Boolean,
        /** The split a renewal casts again: the chain's, else the last one seen (empty: unknown). */
        val split: Map<Long, Long>,
    ) {
        /** The split no longer counts: cleared, or past its lease end and not yet cleared. */
        fun lapsed(now: Long): Boolean = !held || expiresAt in 1..now

        /** The reminder window before the lease end has opened, and the split still counts. */
        fun renewalDue(now: Long): Boolean = !lapsed(now) && expiresAt > 0 && now >= Handles.satSub(expiresAt, LEAD_SECONDS)
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
        /**
         * Directory entries naming this wallet's own address
         * (a handle held but not in the store, after a restore): reminded
         * like the held one.
         */
        val addressed: List<HandleEntry> = emptyList(),
        /** This wallet's shielded address ("" unknown): a held handle paying another is pointed out. */
        val ownAddress: String = "",
        /** This wallet's Groundworks positions' leases (stake, so not tied to a live identity). */
        val groundworks: List<GroundworksLease> = emptyList(),
        /** PrivacyWallet.moveSuggestionDue: the suggested move time once it has come (0: none). */
        val moveSuggestedAt: Long = 0,
    )

    fun due(i: Inputs): List<Reminder> {
        val out = ArrayList<Reminder>()
        if (i.identityLive && !i.claimedToday && i.claimOpensAt == 0L) out.add(Reminder.AnmlReady)
        // Clamped throughout: a hostile time saturates, never wraps or traps.
        val c = i.caretakerExpiresAt
        if (i.identityLive && c > 0 && i.now >= Handles.satSub(c, LEAD_SECONDS) && i.now < Handles.satAdd(c, LAPSED_SECONDS)) {
            out.add(Reminder.CaretakerExpiring(c, lapsed = i.now >= c))
        }
        i.groundworks.mapNotNullTo(out) { groundworks(it, i.now) }
        if (!i.identityLive) return out
        if (i.moveSuggestedAt in 1..i.now) out.add(Reminder.MoveSuggested(i.moveSuggestedAt))
        val held = i.handleEntry?.takeIf { i.handle.isNotEmpty() && it.handle == i.handle }
        val entries = (listOfNotNull(held) + i.addressed).distinctBy { it.handle }
        for (e in entries) {
            val st = e.statusAt(i.now)
            if (st != HandleEntry.FREE && i.now >= Handles.satSub(e.expiresAt, LEAD_SECONDS) && i.now < e.renewalUntil) {
                out.add(Reminder.HandleExpiring(e.handle, e.expiresAt, e.renewalUntil, inRenewal = st == HandleEntry.RENEWAL))
            }
        }
        if (held != null && i.ownAddress.isNotEmpty() && held.address != i.ownAddress && held.statusAt(i.now) == HandleEntry.LIVE) {
            out.add(Reminder.HandlePaysElsewhere(held.handle))
        }
        return out
    }

    /**
     * [g]'s reminder, as the caretaker vote's: from [LEAD_SECONDS] before the
     * lease end until [LAPSED_SECONDS] after it. None while the lease end is
     * unknown.
     */
    fun groundworks(g: GroundworksLease, now: Long): Reminder.GroundworksExpiring? {
        val e = g.expiresAt
        if (e <= 0) return null
        val lapsed = g.lapsed(now)
        val due = if (lapsed) now < Handles.satAdd(e, LAPSED_SECONDS) else g.renewalDue(now)
        return if (due) Reminder.GroundworksExpiring(g.positionId, e, lapsed) else null
    }

    /** The reminder's line for a banner. */
    fun text(r: Reminder, now: Long): String = when (r) {
        Reminder.AnmlReady -> "Your ANML is ready to claim today."
        is Reminder.CaretakerExpiring ->
            if (r.lapsed) "Your caretaker vote has lapsed and no longer counts. Cast it again to keep directing emissions."
            else "Your caretaker vote expires in ${days(Handles.satSub(r.expiresAt, now))}. Renew it to keep it counted."
        is Reminder.GroundworksExpiring ->
            if (r.lapsed) "Your Groundworks split on position #${r.positionId} has lapsed and no longer counts. Choose a split again to keep directing emissions."
            else "Your Groundworks split on position #${r.positionId} expires in ${days(Handles.satSub(r.expiresAt, now))}. Renew it to keep it counted."
        is Reminder.HandleExpiring ->
            if (r.inRenewal) "@${r.handle} has expired and no longer receives payments. Renew it within ${days(Handles.satSub(r.renewalUntil, now))} or anyone may claim it."
            else "@${r.handle} expires in ${days(Handles.satSub(r.expiresAt, now))}. Renew it to keep it."
        is Reminder.HandlePaysElsewhere ->
            "@${r.handle} still pays the wallet it moved from. Renew it here to point it at this wallet."
        is Reminder.MoveSuggested ->
            "The suggested time to bring your handle and caretaker vote from your previous identity has come. Open Identity to move them."
    }

    private fun days(seconds: Long): String {
        val d = maxOf(0L, Handles.satAdd(seconds, 86_399) / 86_400)
        return if (d == 1L) "1 day" else "$d days"
    }
}
