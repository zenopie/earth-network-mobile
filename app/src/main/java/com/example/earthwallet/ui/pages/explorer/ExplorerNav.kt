package network.erth.wallet.ui.pages.explorer

import android.app.Activity
import android.os.Bundle
import network.erth.wallet.ui.host.HostActivity

/**
 * Routing into the explorer's detail screen.
 *
 * All four detail kinds share one fragment, so navigation is a single tag plus
 * arguments. Centralised here so the tab fragments don't each need to know the
 * argument keys.
 */
object ExplorerNav {

    const val ARG_KIND = "explorer_kind"
    const val ARG_VALUE = "explorer_value"

    const val KIND_BLOCK = "block"
    const val KIND_TX = "tx"
    const val KIND_ACCOUNT = "account"
    const val KIND_VALIDATOR = "validator"

    fun openBlock(activity: Activity?, height: Long) = open(activity, KIND_BLOCK, height.toString())

    fun openTx(activity: Activity?, hash: String) = open(activity, KIND_TX, hash)

    fun openAccount(activity: Activity?, address: String) = open(activity, KIND_ACCOUNT, address)

    fun openValidator(activity: Activity?, operator: String) =
        open(activity, KIND_VALIDATOR, operator)

    private fun open(activity: Activity?, kind: String, value: String) {
        val host = activity as? HostActivity ?: return
        host.showFragment(
            "explorer_detail",
            Bundle().apply {
                putString(ARG_KIND, kind)
                putString(ARG_VALUE, value)
            },
        )
    }
}
