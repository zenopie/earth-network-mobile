package network.erth.wallet.privacy

import network.erth.wallet.privacy.note.OwnedStakeNote

/**
 * What the Stake screen says about private stake. iOS: `StakeRound.swift`.
 *
 * A delegation bonds with its validator in the block it lands in (v1.2.0),
 * so stake earns from then on: there is no wait for the round to end.
 */
object StakeRound {
    /** One validator's private stake: what the Stake screen's card and total show. */
    data class Line(
        val validator: String,
        /** derth in spendable stake notes. */
        val derth: Long,
        /** At the live rate, in uerth (floor(derth x rate), as the chain converts it). */
        val value: Long,
    )

    /** A line per validator where the wallet holds stake, in validator order. [rate] is rate_v (1 until read). */
    fun lines(notes: List<OwnedStakeNote>, rate: (String) -> java.math.BigDecimal): List<Line> {
        val prefix = PrivacyWallet.DERTH_PREFIX
        val held = java.util.TreeMap<String, Long>()
        for (n in notes) if (n.spendable && n.denom.startsWith(prefix)) {
            val v = n.denom.removePrefix(prefix)
            held[v] = Amounts.satAdd(held[v] ?: 0L, n.amount)
        }
        return held.map { (v, d) -> Line(v, d, PrivacyWallet.derthValue(d, rate(v))) }
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
