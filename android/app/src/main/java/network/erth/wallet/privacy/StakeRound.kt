package network.erth.wallet.privacy

import network.erth.wallet.privacy.note.OwnedStakeNote

/**
 * What the Stake screen says about private stake and the chain's daily round
 * (x/shieldedstaking's epoch). iOS: `StakeRound.swift`.
 *
 * A delegation's ERTH is queued (the book's P) and delegated to its validator
 * when the round ends; the derth it bought is credited at once, at the live
 * rate, and shares in the rate from then on. So "joining" is about the
 * validator, not about earning: the screen says when the stake joins, and that
 * it is earning already.
 */
object StakeRound {
    /**
     * The derth at [denom] this wallet's own delegations credited in blocks
     * after [height] (the block that ended the last round), at most what is
     * held there now. From the wallet's stake notes alone, spent ones
     * included: a block where the notes made outweigh the notes spent added
     * value. A note with a label no spent note carried is a move arriving
     * (bonded at once, not queued), so that block is not counted; an
     * undelegation, a merge or a lock adds nothing.
     */
    fun joining(notes: List<OwnedStakeNote>, denom: String, height: Long): Long {
        val ns = notes.filter { it.denom == denom }
        var credited = 0L
        for (h in ns.map { it.height }.filter { it > height }.toSet()) {
            val made = ns.filter { it.height == h }
            val spent = ns.filter { it.spentHeight == h }
            val carried = spent.mapNotNull { it.label?.moveKey }.toSet()
            if (made.any { n -> n.label?.let { it.moveKey !in carried } == true }) continue
            val m = Amounts.satSum(made) { it.amount }
            val s = Amounts.satSum(spent) { it.amount }
            if (m > s) credited = Amounts.satAdd(credited, m - s)
        }
        return minOf(credited, Amounts.satSum(ns.filter { it.unspent }) { it.amount })
    }

    /**
     * The first block whose time is at or after [time]: given the round's
     * start_time, the block that ended the last round (its EndBlocker
     * delegated everything queued up to and including that block).
     *
     * Found by probing block times, interpolating and bisecting in turn.
     * Every probe follows from the tip and [time] alone, both public and the
     * same for every wallet at that moment: nothing asked depends on this
     * wallet's notes. Null when a probe goes unanswered (a pruned node) or the
     * probes run out.
     */
    fun firstHeight(time: Long, tip: Long, tipTime: Long, maxProbes: Int = 40, blockTime: (Long) -> Long?): Long? {
        if (tip < 1 || tipTime < time) return null
        var hi = tip
        var hiT = tipTime
        var probes = 0
        fun probe(h: Long): Long? {
            if (probes >= maxProbes) return null
            probes++
            return blockTime(h)
        }
        // A block below [time]: back by the gap at 5 s a block, doubling.
        var lo: Long
        var loT: Long
        var step = maxOf(1L, (hiT - time) / 5 + 1)
        while (true) {
            if (hi <= 1) return 1
            val g = if (hi > step) hi - step else 1
            val t = probe(g) ?: return null
            if (t >= time) {
                hi = g; hiT = t
                if (step < Long.MAX_VALUE / 2) step *= 2
            } else {
                lo = g; loT = t
                break
            }
        }
        var bisect = false
        while (hi - lo > 1) {
            var g = if (bisect || hiT <= loT) {
                lo + (hi - lo) / 2
            } else {
                lo + Math.round((hi - lo).toDouble() * (time - loT).toDouble() / (hiT - loT).toDouble())
            }
            g = g.coerceIn(lo + 1, hi - 1)
            bisect = !bisect
            val t = probe(g) ?: return null
            if (t >= time) { hi = g; hiT = t } else { lo = g; loT = t }
        }
        return hi
    }

    /** "20h 15m", "45m", "under a minute"; "now" once due. */
    fun countdown(seconds: Long): String {
        if (seconds <= 0) return "now"
        if (seconds < 60) return "under a minute"
        val m = (seconds + 59) / 60
        if (m < 60) return "${m}m"
        val h = m / 60
        val rest = m % 60
        if (h < 48) return if (rest == 0L) "${h}h" else "${h}h ${rest}m"
        return "${h / 24} days"
    }

    /** A validator's standing, as the picker shows it. */
    enum class Standing(val label: String, val reason: String?) {
        /** In the active set and taking stake: earns. */
        ACTIVE("Active", null),
        /** In the active set but not taking stake now (a book settling). */
        CLOSED("Not taking stake", "Not taking new stake right now."),
        /** Outside the active set: it earns nothing while it is. */
        INACTIVE("Not in active set", "Outside the active set, so it earns nothing."),
        JAILED("Jailed", "Jailed for missing blocks: earns nothing until it returns."),
        TOMBSTONED("Tombstoned", "Permanently removed for double-signing."),
        /** x/staking removed it; its book winds down. */
        REMOVED("Removed", "No longer a validator."),
        ;

        /** Whether stake here earns now. */
        val earns: Boolean get() = this == ACTIVE || this == CLOSED

        companion object {
            fun of(v: PrivacyChainReads.ValidatorQuote): Standing = when {
                v.removed -> REMOVED
                v.tombstoned -> TOMBSTONED
                v.jailed -> JAILED
                !v.bonded -> INACTIVE
                !v.delegatable -> CLOSED
                else -> ACTIVE
            }
        }
    }
}
