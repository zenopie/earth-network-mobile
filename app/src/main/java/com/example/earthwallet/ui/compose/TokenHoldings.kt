package network.erth.wallet.ui.compose

import network.erth.wallet.R

/**
 * A denom this wallet holds, ready to display or send.
 *
 * The chain speaks in base units and micro-denoms; everything a person sees is
 * the display symbol and a decimal. Both live here so the conversion happens
 * once rather than at each call site.
 */
data class Holding(
    /** What the chain calls it: "uerth". */
    val denom: String,
    /** What a person calls it: "ERTH". */
    val symbol: String,
    /** Base units, as the chain returns them. */
    val amount: Long,
    /** Decimal places between the two. */
    val exponent: Int = 6,
    val icon: Int = R.drawable.ic_token_default,
) {
    /** The amount as a person reads it. */
    val display: String get() = formatUerth(amount)
}

object Tokens {

    /**
     * Everything the chain reports, as holdings.
     *
     * Unknown denoms are kept rather than filtered out, with their raw name
     * uppercased as the symbol. A wallet that hides balances it does not
     * recognise is a wallet that loses funds silently — an odd-looking row is
     * the correct outcome for a token this build has never heard of.
     */
    fun holdings(balances: Map<String, String>): List<Holding> =
        balances
            .map { (denom, amount) ->
                Holding(
                    denom = denom,
                    symbol = symbolOf(denom),
                    amount = amount.toLongOrNull() ?: 0L,
                    icon = iconOf(denom),
                )
            }
            // ERTH first — it pays the fee, so it is the one every screen needs
            // — then by size, so the rest sort themselves.
            .sortedWith(
                compareByDescending<Holding> { it.denom == "uerth" }
                    .thenByDescending { it.amount },
            )

    /** "uerth" -> "ERTH". Micro-denoms are the only convention this chain uses. */
    private fun symbolOf(denom: String): String =
        denom.removePrefix("u").uppercase()

    /** The mark for a denom, in its own colours. Public: sheets outside the
     *  holdings list need the same mapping, and two of them would drift. */
    fun iconOf(denom: String): Int = when (denom) {
        "uerth" -> R.drawable.ic_erth_logo
        "uanml" -> R.drawable.anml
        else -> R.drawable.ic_token_default
    }
}
