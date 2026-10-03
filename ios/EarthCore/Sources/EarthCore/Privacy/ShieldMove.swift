import BigInt

/// Moving ERTH between this wallet's account (public) and its own notes
/// (private): the most each direction can move in one transaction.
public enum ShieldMove {
    /// MsgShield is signed by the account, so its fee comes out of the same
    /// public balance the amount does.
    public static func maxShield(public balance: BigInt, fee: BigInt) -> BigInt {
        max(0, balance - fee)
    }

    /// An unshield spends up to max_actions_per_bundle ERTH notes
    /// (`maxNotes`), so its most is their sum: at Max the fee comes out of
    /// the amount (PrivacyWallet.unshield feeFromAmount), the bundle
    /// releasing exactly what the notes hold.
    public static func maxUnshield(_ notes: [OwnedNote], maxNotes: Int) -> UInt64 {
        NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: maxNotes)
    }

    /// Whether an unshield of `amount` pays its fee from the amount: when the
    /// amount and an estimated `fee` would not both fit `spendable`.
    public static func feeFromAmount(amount: UInt64, spendable: UInt64, fee: UInt64) -> Bool {
        amount.addingReportingOverflow(fee).overflow || amount + fee > spendable
    }
}
