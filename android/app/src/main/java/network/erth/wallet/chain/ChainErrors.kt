package network.erth.wallet.chain

/**
 * The chain errors a wallet action can meet that deserve a sentence of their
 * own. A code means nothing without its codespace (x/personhood and x/dex
 * both register 1120), so both are matched; a simulate answers with the
 * registered text instead, which is matched too.
 */
object ChainErrors {
    /** [byText]: the code is a generic one; only its text names this case. */
    private data class Known(val codespace: String, val code: Int, val text: String, val explain: String, val byText: Boolean = false)

    private val KNOWN = listOf(
        Known("personhood", 1121, "affiliate_handle is not a live handle",
            "That referrer's handle is not live any more (unclaimed, expired or released). Register without it, or ask them to renew it."),
        Known("personhood", 1122, "handle is held by another human",
            "That handle is taken. Choose another."),
        Known("personhood", 1125, "this identity moved its handle away",
            "This identity moved its handle to another identity and can never claim one again."),
        Known("personhood", 1126, "this identity moved its caretaker split away",
            "This identity moved its caretaker vote to another identity and can never cast one again."),
        Known("dex", 1120, "amount exceeds the pool cap",
            "That amount is past the pool's cap (2^120 units). Use a smaller amount."),
        // Chain 203d3b2 (audit 5): x/dex refuses at start a withdrawal whose note leg is above 16 notes' worth.
        Known("dex", 1101, "the most one withdrawal pays as notes",
            "That withdrawal is too large to be paid as private notes in one go. Withdraw in smaller parts.", byText = true),
        // MsgShield refuses a denom the bank has send-disabled.
        Known("bank", 5, "send transactions are disabled",
            "Transfers of this token are switched off on the chain, so it cannot be shielded now.", byText = true),
        // MsgMoveHandle moves only a live handle.
        Known("personhood", 1116, "renew it before moving it",
            "That handle is past its expiry (in its renewal period), and only a live handle can be moved. Renew it first, then move it.", byText = true),
        // CheckTx refuses an anchor lapsing within 120 s.
        Known("shielded", 1103, "pick a newer anchor",
            "This wallet's notes were anchored to a note tree the chain is about to stop accepting. Sync and try again.", byText = true),
    )

    /** A plain sentence for a refused tx, or null when it is not one of these. */
    fun explain(code: Int, codespace: String, log: String = ""): String? =
        KNOWN.firstOrNull { it.code == code && it.codespace == codespace && (!it.byText || log.contains(it.text)) }?.explain ?: explain(log)

    /** The same, from an error's text alone (a simulate's message). */
    fun explain(text: String): String? = KNOWN.firstOrNull { text.contains(it.text) }?.explain
}
