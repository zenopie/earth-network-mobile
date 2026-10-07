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

/// Portfolio's coins, and why a coin's Shield or Unshield is not offered.
public extension ShieldMove {
    struct Coin: Equatable, Sendable {
        public let denom: String
        public let publicAmount: BigInt
        public let privateAmount: BigInt

        public init(denom: String, publicAmount: BigInt, privateAmount: BigInt) {
            self.denom = denom
            self.publicAmount = publicAmount
            self.privateAmount = privateAmount
        }
    }

    /// Every coin held, public (bank) and private (notes) side by side: ERTH
    /// and ANML always and first, the rest by denom. Stake (derth/) is not a
    /// coin: it is a position, valued at its validator's rate.
    static func coins(public balances: [String: BigInt], shielded: [String: UInt64]) -> [Coin] {
        let fixed = [Constants.gasDenom, "uanml"]
        var denoms = Set(balances.filter { $0.value > 0 }.keys)
        denoms.formUnion(shielded.filter { $0.value > 0 }.keys)
        denoms = denoms.filter { !$0.hasPrefix("derth/") && !fixed.contains($0) }
        return (fixed + denoms.sorted()).map {
            Coin(denom: $0, publicAmount: balances[$0] ?? 0, privateAmount: BigInt(shielded[$0] ?? 0))
        }
    }

    /// Why Shield is not offered for `denom`, or nil when it is. Only ERTH
    /// moves: ANML exists only as notes, and the pool admits nothing else at
    /// genesis (a later asset needs its own max and fee handling here first).
    static func shieldBlocked(denom: String, public balance: BigInt, fee: BigInt) -> String? {
        if denom == "uanml" { return "ANML is always private." }
        if denom != Constants.gasDenom { return onlyErth }
        if balance <= 0 { return "No public ERTH to shield." }
        if maxShield(public: balance, fee: fee) <= 0 { return "Public ERTH doesn't cover the shield fee." }
        return nil
    }

    /// Why Unshield is not offered for `denom`, or nil when it is.
    /// `spendable` is what one unshield can spend (maxUnshield); at Max it
    /// must exceed the fee it pays.
    static func unshieldBlocked(denom: String, private balance: BigInt, spendable: UInt64, fee: UInt64) -> String? {
        if denom == "uanml" { return "ANML can't be made public." }
        if denom != Constants.gasDenom { return onlyErth }
        if balance <= 0 { return "No private ERTH to unshield." }
        if spendable <= fee { return "Private ERTH doesn't cover the unshield fee." }
        return nil
    }

    private static let onlyErth = "Only ERTH moves between public and private."
}
