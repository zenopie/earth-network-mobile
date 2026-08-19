package network.erth.wallet.ui.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager
import com.google.protobuf.Any as ProtoAny

/**
 * One path for every transaction: confirm, broadcast, report.
 *
 * The Compose successor to TxFlow, and it exists for the same reason: before
 * TxFlow each screen broadcast on its own and reported the outcome in a pair of
 * toasts, so nobody could see what they were about to sign or read why it
 * failed. Keeping that in one place is also what makes the gas gate universal —
 * any transaction from an underfunded account offers the rewarded ad, not just
 * registration.
 *
 * The screens never touch this directly. A screen raises intent ("stake 100"),
 * its view model turns that into messages and hands them here; the sheets are
 * driven by this state and cannot be skipped by a caller who forgets them.
 */
class TxController : ViewModel() {

    /** What is waiting on the confirmation sheet, if anything. */
    var pending: TxConfirmDetails? by mutableStateOf(null)
        private set

    /** What came back, if anything. */
    var outcome: TxOutcome? by mutableStateOf(null)
        private set

    var submitting: Boolean by mutableStateOf(false)
        private set

    /** What is in flight, for the pending sheet to name. */
    var lastAction: String? by mutableStateOf(null)
        private set

    private var build: ((Context) -> List<ProtoAny>)? = null
    private var gasLimit: Long = DEFAULT_GAS_LIMIT
    private var feeUerth: Long = DEFAULT_FEE_UERTH
    private var onDone: (() -> Unit)? = null

    /**
     * Ask for a transaction. Shows the confirmation sheet; nothing is signed
     * until it is confirmed.
     *
     * [build] runs off the main thread and receives a Context so it can read
     * the wallet — the messages are built at confirm time rather than at
     * request time so a stale sequence number cannot be baked in while the
     * sheet is open.
     */
    fun request(
        details: TxConfirmDetails,
        gasLimit: Long = DEFAULT_GAS_LIMIT,
        feeUerth: Long = DEFAULT_FEE_UERTH,
        onSuccess: (() -> Unit)? = null,
        build: (Context) -> List<ProtoAny>,
    ) {
        this.build = build
        this.gasLimit = gasLimit
        this.feeUerth = feeUerth
        this.onDone = onSuccess
        pending = details
    }

    fun confirm(context: Context) {
        val details = pending ?: return
        val builder = build ?: return
        pending = null
        lastAction = details.action
        submitting = true

        viewModelScope.launch {
            outcome = try {
                val hash = withContext(Dispatchers.IO) {
                    SecureWalletManager.executeWithMnemonic(context) { mnemonic ->
                        val key = EarthWallet.deriveKey(mnemonic)
                        EarthTx.broadcast(key, builder(context), gasLimit, feeUerth.toString())
                    }
                }
                onDone?.invoke()
                TxOutcome.Success(details.action, hash)
            } catch (e: Exception) {
                TxOutcome.Failure(details.action, e)
            } finally {
                submitting = false
            }
        }
    }

    fun cancel() {
        pending = null
        build = null
    }

    fun dismissResult() {
        outcome = null
    }

    companion object {
        const val DEFAULT_GAS_LIMIT = 400_000L
        const val DEFAULT_FEE_UERTH = 2_000L
    }
}

/**
 * The sheets, mounted once for the whole app.
 *
 * Mounted at the shell rather than per-screen so a result still arrives if the
 * screen that started the transaction has been navigated away from — a stake
 * that lands after you have gone back to home is still a stake you want told
 * about.
 */
@Composable
fun TxSheets(
    controller: TxController,
    balanceUerth: Long,
    context: Context,
    onWatchAd: () -> Unit = {},
) {
    controller.pending?.let { details ->
        TxConfirmSheet(
            details = details.copy(balanceUerth = balanceUerth),
            onConfirm = { controller.confirm(context) },
            onDismiss = controller::cancel,
            onWatchAd = onWatchAd,
        )
    }
    // Pending, then result — one sheet position, three states, so the result's
    // badge animates in over the spinner rather than appearing from nowhere.
    if (controller.submitting) {
        TxPendingSheet(action = controller.lastAction.orEmpty())
    }
    controller.outcome?.let { outcome ->
        TxResultSheet(outcome = outcome, onDismiss = controller::dismissResult)
    }
}
