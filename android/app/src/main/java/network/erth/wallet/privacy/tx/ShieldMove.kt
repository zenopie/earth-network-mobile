package network.erth.wallet.privacy.tx

import network.erth.wallet.privacy.note.OwnedNote

/**
 * Moving ERTH between this wallet's account (public) and its own notes
 * (private): the most each direction can move in one transaction.
 */
object ShieldMove {
    /** MsgShield is signed by the account, so its fee comes out of the same public balance the amount does. */
    fun maxShield(publicUerth: Long, fee: Long): Long = (publicUerth - fee).coerceAtLeast(0)

    /**
     * An unshield spends up to max_actions_per_bundle ERTH notes ([maxNotes]),
     * so its most is their sum: at Max the fee comes out of the amount
     * (PrivacyWallet.unshield feeFromAmount), the bundle releasing exactly
     * what the notes hold.
     */
    fun maxUnshield(notes: List<OwnedNote>, maxNotes: Int): Long = NoteSelection.maxSpendable(notes, "uerth", maxNotes)

    /**
     * Whether an unshield of [amount] pays its fee from the amount: when the
     * amount and an estimated [fee] would not both fit [spendable].
     */
    fun feeFromAmount(amount: Long, spendable: Long, fee: Long): Boolean = amount + fee > spendable

    /** A coin in Portfolio: its public (bank) and private (notes) amounts, in base units. */
    data class Coin(val denom: String, val public: Long, val private: Long)

    /**
     * Every coin held, public and private side by side: ERTH and ANML always
     * and first, the rest by denom. Stake (derth/) is not a coin: it is a
     * position, valued at its validator's rate.
     */
    fun coins(public: Map<String, Long>, shielded: Map<String, Long>): List<Coin> {
        val fixed = listOf(UERTH, UANML)
        val rest = (public.filterValues { it > 0 }.keys + shielded.filterValues { it > 0 }.keys)
            .filter { !it.startsWith("derth/") && it !in fixed }
            .toSortedSet()
        return (fixed + rest).map { Coin(it, public[it] ?: 0L, shielded[it] ?: 0L) }
    }

    /**
     * Why Shield is not offered for [denom], or null when it is. Only ERTH
     * moves: ANML exists only as notes, and the pool admits nothing else at
     * genesis (a later asset needs its own max and fee handling here first).
     */
    fun shieldBlocked(denom: String, public: Long, fee: Long): String? = when {
        denom == UANML -> "ANML is always private."
        denom != UERTH -> ONLY_ERTH
        public <= 0 -> "No public ERTH to shield."
        maxShield(public, fee) <= 0 -> "Public ERTH doesn't cover the shield fee."
        else -> null
    }

    /**
     * Why Unshield is not offered for [denom], or null when it is.
     * [spendable] is what one unshield can spend ([maxUnshield]); at Max it
     * must exceed the fee it pays.
     */
    fun unshieldBlocked(denom: String, private: Long, spendable: Long, fee: Long): String? = when {
        denom == UANML -> "ANML can't be made public."
        denom != UERTH -> ONLY_ERTH
        private <= 0 -> "No private ERTH to unshield."
        spendable <= fee -> "Private ERTH doesn't cover the unshield fee."
        else -> null
    }

    private const val UERTH = "uerth"
    private const val UANML = "uanml"
    private const val ONLY_ERTH = "Only ERTH moves between public and private."
}
