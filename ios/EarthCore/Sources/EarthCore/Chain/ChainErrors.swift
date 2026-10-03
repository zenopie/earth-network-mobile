import Foundation

/// The chain errors a wallet action can meet that deserve a sentence of
/// their own. Ports `chain/ChainErrors.kt`. A code means nothing without its
/// codespace (x/personhood and x/dex both register 1120), so both are
/// matched; a simulate answers with the registered text instead, which is
/// matched too.
public enum ChainErrors {
    private struct Known {
        let codespace: String
        let code: Int
        let text: String
        let explain: String
    }

    private static let known: [Known] = [
        Known(codespace: "personhood", code: 1121, text: "affiliate_handle is not a live handle",
              explain: "That referrer's handle is not live any more (unclaimed, expired or released). Register without it, or ask them to renew it."),
        Known(codespace: "personhood", code: 1122, text: "handle is held by another human",
              explain: "That handle is taken. Choose another."),
        Known(codespace: "personhood", code: 1125, text: "this identity moved its handle away",
              explain: "This identity moved its handle to another identity and can never claim one again."),
        Known(codespace: "personhood", code: 1126, text: "this identity moved its caretaker split away",
              explain: "This identity moved its caretaker vote to another identity and can never cast one again."),
        Known(codespace: "dex", code: 1120, text: "amount exceeds the pool cap",
              explain: "That amount is past the pool's cap (2^120 units). Use a smaller amount."),
    ]

    /// A plain sentence for a refused tx, or nil when it is not one of these.
    public static func explain(code: Int, codespace: String, log: String = "") -> String? {
        known.first { $0.code == code && $0.codespace == codespace }?.explain ?? explain(text: log)
    }

    /// The same, from an error's text alone (a simulate's message).
    public static func explain(text: String) -> String? { known.first { text.contains($0.text) }?.explain }

    /// The sentence for any error, if it is one of these.
    public static func explain(_ error: Swift.Error) -> String? {
        if let r = error as? UnsignedTx.TxRejected { return explain(code: r.code, codespace: r.codespace, log: r.log) }
        return explain(text: (error as? LocalizedError)?.errorDescription ?? "\(error)")
    }
}
