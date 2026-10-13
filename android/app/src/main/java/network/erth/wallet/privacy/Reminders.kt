package network.erth.wallet.privacy

import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles

/**
 * What the wallet reminds its owner to do, in place of doing it unasked: the
 * day's ANML claim, the caretaker vote's refresh before it lapses, the stake
 * check-in before the oldest vintage holding stake retires (which also keeps
 * its Groundworks vote: stake votes have no lease since v1.3.0, they end
 * with their vintage), and the handle's renewal before (and through) its
 * renewal period. Each costs a fee, so each is the owner's decision; nothing
 * here broadcasts anything.
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
         * Stake of a vintage retiring at [retiresAt] (the oldest holding
         * this wallet's stake): check it in before then, or it stops earning
         * and voting and can only be withdrawn.
         */
        data class CheckInDue(val retiresAt: Long) : Reminder

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
         * [deadline] (0: unknown) is the earliest lease end of what is to
         * move: past it the old identity can neither move nor renew it, so
         * the reminder grows urgent as it nears. The move itself is the
         * user's to make.
         */
        data class MoveSuggested(val at: Long, val deadline: Long = 0) : Reminder

        /**
         * The registration ends at [registrationEndsAt] and this identity's
         * handle ([handle], "" none) or caretaker vote ends soon after
         * ([handleExpiresAt], [voteExpiresAt]; 0 when not at stake): once the
         * registration lapses this identity can renew neither, and after a
         * renewal they can be moved only until their leases end. Renewing
         * them now gives that move a full lease.
         */
        data class RenewBeforeLapse(val registrationEndsAt: Long, val handle: String, val handleExpiresAt: Long, val voteExpiresAt: Long) : Reminder
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
        /**
         * When the oldest live vintage holding this wallet's stake retires
         * (PrivacyWallet.checkInRetiresAt; 0: none, or unknown). Stake, so
         * not tied to a live identity.
         */
        val stakeRetiresAt: Long = 0,
        /** PrivacyWallet.moveSuggestionDue: the suggested move time once it has come (0: none). */
        val moveSuggestedAt: Long = 0,
        /** PrivacyWallet.moveDeadline: when what is to move stops being movable (0: unknown). */
        val moveDeadline: Long = 0,
        /** When the live registration ends: its activated_at plus registration_validity_seconds (0: unknown). */
        val registrationEndsAt: Long = 0,
        /** The held handle's lease end (0: unknown). */
        val handleExpiresAt: Long = 0,
    )

    fun due(i: Inputs): List<Reminder> {
        val out = ArrayList<Reminder>()
        if (i.identityLive && !i.claimedToday && i.claimOpensAt == 0L) out.add(Reminder.AnmlReady)
        // Clamped throughout: a hostile time saturates, never wraps or traps.
        val c = i.caretakerExpiresAt
        if (i.identityLive && c > 0 && i.now >= Handles.satSub(c, LEAD_SECONDS) && i.now < Handles.satAdd(c, LAPSED_SECONDS)) {
            out.add(Reminder.CaretakerExpiring(c, lapsed = i.now >= c))
        }
        checkIn(i.stakeRetiresAt, i.now)?.let { out.add(it) }
        if (!i.identityLive) return out
        // Until what is to move stops being movable; past that a move cannot bring it.
        if (i.moveSuggestedAt in 1..i.now && (i.moveDeadline <= 0 || i.now < i.moveDeadline)) out.add(Reminder.MoveSuggested(i.moveSuggestedAt, i.moveDeadline))
        renewBeforeLapse(i)?.let { out.add(it) }
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
     * From [LEAD_SECONDS] before the registration ends: the handle or
     * caretaker vote whose lease ends within
     * PrivacyWallet.RENEW_FIRST_WINDOW_SECONDS after it.
     */
    fun renewBeforeLapse(i: Inputs): Reminder.RenewBeforeLapse? {
        val end = i.registrationEndsAt
        if (!i.identityLive || end <= 0 || i.now < Handles.satSub(end, LEAD_SECONDS) || i.now >= end) return null
        val soon = Handles.satAdd(end, PrivacyWallet.RENEW_FIRST_WINDOW_SECONDS)
        val h = i.handleExpiresAt.takeIf { i.handle.isNotEmpty() && it in 1 until soon } ?: 0L
        val v = i.caretakerExpiresAt.takeIf { it in 1 until soon } ?: 0L
        if (h == 0L && v == 0L) return null
        return Reminder.RenewBeforeLapse(end, if (h > 0) i.handle else "", h, v)
    }

    /**
     * The check-in reminder: from [Vintages.CHECK_IN_LEAD_SECONDS] before
     * the oldest live vintage holding stake retires ([retiresAt]) until it
     * does. None without stake, or while the vintages are unknown.
     */
    fun checkIn(retiresAt: Long, now: Long): Reminder.CheckInDue? {
        if (retiresAt <= 0 || now >= retiresAt || now < Handles.satSub(retiresAt, Vintages.CHECK_IN_LEAD_SECONDS)) return null
        return Reminder.CheckInDue(retiresAt)
    }

    /** The reminder's line for a banner. */
    fun text(r: Reminder, now: Long): String = when (r) {
        Reminder.AnmlReady -> "Your ANML is ready to claim today."
        is Reminder.CaretakerExpiring ->
            if (r.lapsed) "Your caretaker vote has lapsed and no longer counts. Cast it again to keep directing emissions."
            else "Your caretaker vote expires in ${days(Handles.satSub(r.expiresAt, now))}. Renew it to keep it counted."
        is Reminder.CheckInDue ->
            "Check in your stake within ${days(Handles.satSub(r.retiresAt, now))} to keep it earning and voting."
        is Reminder.HandleExpiring ->
            if (r.inRenewal) "@${r.handle} has expired and no longer receives payments. Renew it within ${days(Handles.satSub(r.renewalUntil, now))} or anyone may claim it."
            else "@${r.handle} expires in ${days(Handles.satSub(r.expiresAt, now))}. Renew it to keep it."
        is Reminder.HandlePaysElsewhere ->
            "@${r.handle} still pays the wallet it moved from. Renew it here to point it at this wallet."
        is Reminder.MoveSuggested -> when {
            r.deadline <= 0 ->
                "The suggested time to bring your handle and caretaker vote from your previous identity has come. Open Identity to move them."
            Handles.satSub(r.deadline, now) <= PrivacyWallet.MOVE_DEADLINE_MARGIN_SECONDS ->
                "Move your handle and caretaker vote from your previous identity now: in ${days(Handles.satSub(r.deadline, now))} they can no longer be moved, and your previous identity cannot renew them. Open Identity to move them."
            else ->
                "Bring your handle and caretaker vote from your previous identity within ${days(Handles.satSub(r.deadline, now))}: after that they can no longer be moved. Open Identity to move them."
        }
        is Reminder.RenewBeforeLapse -> {
            val what = listOfNotNull(r.handle.takeIf { it.isNotEmpty() }?.let { "@$it" }, "your caretaker vote".takeIf { r.voteExpiresAt > 0 }).joinToString(" and ")
            "Your registration ends in ${days(Handles.satSub(r.registrationEndsAt, now))}. Renew $what before then: once it lapses, this identity can no longer renew them, and after you renew your registration they can be moved only until their leases end."
        }
    }

    private fun days(seconds: Long): String {
        val d = maxOf(0L, Handles.satAdd(seconds, 86_399) / 86_400)
        return if (d == 1L) "1 day" else "$d days"
    }
}
