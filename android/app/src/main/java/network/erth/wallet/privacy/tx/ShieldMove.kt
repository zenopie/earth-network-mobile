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
     * An ERTH unshield spends at most three notes, the fee paid from the same
     * notes as the amount (PrivacyWallet.unshield). [fee] is the estimate the
     * confirm sheet shows; the simulated fee is at most that.
     */
    fun maxUnshield(notes: List<OwnedNote>, fee: Long): Long = maxUnshield(NoteSelection.maxSpendable(notes, "uerth", 3), fee)

    /** The same, from the three-note figure the wallet state already holds. */
    fun maxUnshield(spendableUerth: Long, fee: Long): Long = (spendableUerth - fee).coerceAtLeast(0)
}
