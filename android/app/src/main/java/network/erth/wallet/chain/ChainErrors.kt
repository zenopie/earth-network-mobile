package network.erth.wallet.chain

/**
 * The chain errors a wallet action can meet that deserve a sentence of their
 * own. A code means nothing without its codespace (x/personhood and x/dex
 * both register 1120), so both are matched; a simulate answers with the
 * registered text instead, which is matched too.
 */
object ChainErrors {
    private data class Known(val codespace: String, val code: Int, val text: String, val explain: String)

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
    )

    /** A plain sentence for a refused tx, or null when it is not one of these. */
    fun explain(code: Int, codespace: String, log: String = ""): String? =
        KNOWN.firstOrNull { it.code == code && it.codespace == codespace }?.explain ?: explain(log)

    /** The same, from an error's text alone (a simulate's message). */
    fun explain(text: String): String? = KNOWN.firstOrNull { text.contains(it.text) }?.explain
}
