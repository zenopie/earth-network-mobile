package network.erth.wallet.ui.components

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.ui.ads.RewardedAds
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * One path for every transaction the app sends: confirm, broadcast, report.
 *
 * Before this, each fragment did its own thing — most broadcast with no
 * confirmation at all, and reported the outcome with a pair of toasts, one
 * saying "Staked" and one saying "Failed: ${e.message}". So the user could not
 * see what they were about to sign, and could not read why it did not work.
 *
 * Routing everything through here also means the ads-for-gas gate applies
 * everywhere rather than only to registration: any transaction from an
 * underfunded account now offers the rewarded ad, which matters because
 * registration is not necessarily the first thing a new human tries.
 */
object TxFlow {
    private const val TAG = "TxFlow"

    /** Mirrors EarthTx.broadcast's defaults; registration overrides both. */
    const val DEFAULT_GAS_LIMIT = 400_000L
    const val DEFAULT_FEE_UERTH = 2_000L

    /**
     * Confirms with the user, runs [broadcast] off the main thread, then shows
     * the result.
     *
     * [action] is what the user is doing, in their words ("Stake ERTH"), and is
     * reused as the title of both result sheets. [msgTypeUrl] is what the chain
     * sees; the sheet shows both, because this chain has no contracts to hide
     * behind and the two should be checkable against each other.
     *
     * [onSuccess] runs on the main thread after a successful broadcast, for the
     * screen to clear inputs and refresh. [onFinally] always runs, so callers
     * can re-enable their button on every path including cancellation.
     */
    fun run(
        fragment: Fragment,
        action: String,
        msgTypeUrl: String,
        gasLimit: Long = DEFAULT_GAS_LIMIT,
        feeUerth: Long = DEFAULT_FEE_UERTH,
        onSuccess: (() -> Unit)? = null,
        onFinally: (() -> Unit)? = null,
        broadcast: suspend () -> String,
    ) {
        val context = fragment.context ?: return
        val address = SecureWalletManager.getWalletAddress(context)
        if (address == null) {
            TxResult.message(context, "No wallet", "Create or select a wallet first.")
            onFinally?.invoke()
            return
        }

        TransactionConfirmationDialog(context).show(
            TransactionConfirmationDialog.Details(
                action = action,
                msgTypeUrl = msgTypeUrl,
                address = address,
                feeUerth = feeUerth,
                gasLimit = gasLimit,
            ),
            fragment.viewLifecycleOwner.lifecycleScope,
            object : TransactionConfirmationDialog.Listener {
                override fun onConfirmed() {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val hash = withContext(Dispatchers.IO) { broadcast() }
                            Log.i(TAG, "$action broadcast: $hash")
                            fragment.context?.let { TxResult.success(it, action, hash) }
                            onSuccess?.invoke()
                        } catch (e: Exception) {
                            // Logged as well as shown: the sheet is for the user,
                            // the log is for whoever they send it to.
                            Log.e(TAG, "$action failed", e)
                            fragment.context?.let { TxResult.failure(it, action, e) }
                        } finally {
                            onFinally?.invoke()
                        }
                    }
                }

                override fun onCancelled() {
                    onFinally?.invoke()
                }

                override fun onWatchAdForGas(callback: (Boolean) -> Unit) {
                    val activity = fragment.activity
                    if (activity == null) {
                        callback(false)
                    } else {
                        RewardedAds.show(activity, address, callback)
                    }
                }
            },
        )
    }
}
