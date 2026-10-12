package network.erth.wallet.wallet

import org.json.JSONArray

/** The wallet list's own arithmetic, apart from the session that seals it. */
object WalletList {

    /**
     * [wallets] without the one at [index], and which to select after.
     *
     * The selection follows the wallet it named, so a wallet after the removed
     * one keeps being selected under its new index; removing the selected one
     * selects the first left. Returns an empty list and -1 for the last one.
     */
    fun remove(wallets: JSONArray, index: Int, selected: Int): Pair<JSONArray, Int> {
        require(index in 0 until wallets.length()) { "no wallet at $index" }
        val rest = JSONArray()
        for (i in 0 until wallets.length()) if (i != index) rest.put(wallets.get(i))
        val next = when {
            rest.length() == 0 -> -1
            selected == index || selected !in 0 until wallets.length() -> 0
            selected > index -> selected - 1
            else -> selected
        }
        return rest to next
    }
}
