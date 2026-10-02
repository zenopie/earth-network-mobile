import BigInt

/// Moving ERTH between this wallet's account (public) and its own notes
/// (private): the most each direction can move in one transaction.
public enum ShieldMove {
    /// MsgShield is signed by the account, so its fee comes out of the same
    /// public balance the amount does.
    public static func maxShield(public balance: BigInt, fee: BigInt) -> BigInt {
        max(0, balance - fee)
    }

    /// An ERTH unshield spends at most three notes, the fee paid from the same
    /// notes as the amount (PrivacyWallet.unshield). `fee` is the estimate the
    /// confirm sheet shows; the simulated fee is at most that.
    public static func maxUnshield(_ notes: [OwnedNote], fee: UInt64) -> UInt64 {
        let spendable = NoteSelection.maxSpendable(notes, denom: "uerth", maxNotes: 3)
        return spendable > fee ? spendable - fee : 0
    }
}
