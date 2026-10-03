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
        /// The code is a generic one; only its text names this case.
        var byText = false
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
        // Chain 203d3b2 (audit 5): x/dex refuses at start a withdrawal whose note leg is above 16 notes' worth.
        Known(codespace: "dex", code: 1101, text: "the most one withdrawal pays as notes",
              explain: "That withdrawal is too large to be paid as private notes in one go. Withdraw in smaller parts.", byText: true),
        // MsgShield refuses a denom the bank has send-disabled.
        Known(codespace: "bank", code: 5, text: "send transactions are disabled",
              explain: "Transfers of this token are switched off on the chain, so it cannot be shielded now.", byText: true),
        // MsgMoveHandle moves only a live handle.
        Known(codespace: "personhood", code: 1116, text: "renew it before moving it",
              explain: "That handle is past its expiry (in its renewal period), and only a live handle can be moved. Renew it first, then move it.",
              byText: true),
        // CheckTx refuses an anchor lapsing within 120 s.
        Known(codespace: "shielded", code: 1103, text: "pick a newer anchor",
              explain: "This wallet's notes were anchored to a note tree the chain is about to stop accepting. Sync and try again.", byText: true),
    ]

    /// A plain sentence for a refused tx, or nil when it is not one of these.
    public static func explain(code: Int, codespace: String, log: String = "") -> String? {
        known.first { $0.code == code && $0.codespace == codespace && (!$0.byText || log.contains($0.text)) }?.explain ?? explain(text: log)
    }

    /// The same, from an error's text alone (a simulate's message).
    public static func explain(text: String) -> String? { known.first { text.contains($0.text) }?.explain }

    /// The sentence for any error, if it is one of these.
    public static func explain(_ error: Swift.Error) -> String? {
        if let r = error as? UnsignedTx.TxRejected { return explain(code: r.code, codespace: r.codespace, log: r.log) }
        return explain(text: (error as? LocalizedError)?.errorDescription ?? "\(error)")
    }
}
