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
        // A denom the bank has send-disabled is refused at every pool edge
        // (chain 48b631c, audit 6 A-L2): shield, unshield, a dex note swap,
        // a private delegation's ERTH, a module mint into the pool.
        Known("bank", 5, "send transactions are disabled",
            "Transfers of this token are switched off on the chain, so it can't enter or leave private notes right now: no shielding, unshielding, note swaps or private staking with it. Notes you already hold still move privately.", byText = true),
        // Chain 48b631c (audit 6, B6-1): a switch stays under its Document Signer.
        Known("personhood", 1127, "identity switch must be proven under the live registration's document signer",
            "This switch was refused. A switch must be proven with the same passport you registered with, and this one was signed by a different issuing key."),
        // The signer's (or country's) daily cap; a switch counts against its signer's.
        Known("personhood", 1113, "daily registration limit reached",
            "Today's limit for passports from this issuer has been reached. Try again tomorrow."),
        // MsgMoveHandle moves only a live handle.
        Known("personhood", 1116, "renew it before moving it",
            "That handle is past its expiry (in its renewal period), and only a live handle can be moved. Renew it first, then move it.", byText = true),
        // Chain dff3a9b: a credit (a delegation's derth, a move's arrival) is
        // quoted at the live rate with a margin; a rate that outran it is
        // refused in the ante, before anything is spent or paid.
        Known("shieldedstaking", 1103, "re-quote with a margin",
            "The validator's rate moved before this landed, so the chain turned it down. Nothing was spent and no fee was paid: try again for a fresh quote.", byText = true),
        // A move names the latest block's time; one that waited too long is refused, at no cost.
        Known("shieldedstaking", 1120, "s before the block time",
            "This move took too long to reach a block, so the chain turned it down. Nothing was spent and no fee was paid: try again.", byText = true),
        // A slash reached a moved-in stake between the proof and its block: the debt root changed.
        Known("shieldedstaking", 1113, "is not the current slash debt root",
            "A slash reached stake moved between validators just as this was sent, so the chain turned it down. Nothing was spent: try again.", byText = true),
        // Chain b46a4bb: every stake proof names the label window's clear_before as of the last hour.
        Known("shieldedstaking", 1113, "name the label window's current clear_before",
            "This took too long between its quote and its block, so the chain turned it down. Nothing was spent and no fee was paid: try again.", byText = true),
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
