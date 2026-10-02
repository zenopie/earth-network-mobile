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
}
