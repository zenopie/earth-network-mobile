package network.erth.wallet.ui.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.Fees
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

    /** True from the moment an ad is watched until the gas lands (or gives up). */
    var awaitingGas: Boolean by mutableStateOf(false)
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
        onSuccess: (() -> Unit)? = null,
        build: (Context) -> List<ProtoAny>,
    ) {
        // The fee comes from the gas limit and nowhere else, and the sheet is
        // shown the same number that will be broadcast.
        //
        // It used to be passed twice — once in `details` for the sheet, once
        // here for the broadcast — and the two drifted. Claiming rewards
        // scales its gas by validator count but declared the flat default fee,
        // so with a balance between the two the sheet said "funded", never
        // offered the rewarded ad, and the transaction was then rejected by
        // the node for insufficient fee. Making it impossible to state twice is
        // the fix; correcting the one call site would only have postponed it.
        val fee = feeFor(gasLimit)

        this.build = build
        this.gasLimit = gasLimit
        this.feeUerth = fee
        this.onDone = onSuccess
        pending = details.copy(feeUerth = fee)
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

    /**
     * Waits for an ad grant to arrive, then lets the sheet notice.
     *
     * The reward callback fires when the *ad* finished, not when the gas lands:
     * Google calls the backend, the backend sends from its hot wallet, and that
     * send has to be included in a block. So a single balance read straight
     * after the ad always runs too early. This path used to do exactly that —
     * one refresh, no retry — so the grant arrived, the sheet never saw it, and
     * the confirm button stayed disabled behind "Watch an ad for gas" with no
     * indication anything was happening. Registration had the poll; every other
     * transaction did not.
     *
     * [fetchBalance] reads the chain directly rather than going through
     * WalletViewModel.refresh(), which is fire-and-forget: it returns before
     * the new balance exists, so a poll built on it would race itself.
     */
    fun awaitGas(fetchBalance: suspend () -> Long, onFunded: () -> Unit = {}) {
        val needed = pending?.feeUerth ?: return
        awaitingGas = true
        viewModelScope.launch {
            try {
                repeat(GAS_POLL_ATTEMPTS) {
                    delay(GAS_POLL_INTERVAL_MS)
                    val now = runCatching { fetchBalance() }.getOrNull() ?: 0L
                    if (now >= needed) {
                        onFunded()
                        return@launch
                    }
                }
            } finally {
                awaitingGas = false
            }
        }
    }

    fun cancel() {
        pending = null
        build = null
        awaitingGas = false
    }

    fun dismissResult() {
        outcome = null
    }

    companion object {
        const val DEFAULT_GAS_LIMIT = 400_000L

        // The grant is a bank send, so it lands in a block. Roughly a minute of
        // patience against a ~6s block time, matching the registration flow.
        private const val GAS_POLL_ATTEMPTS = 20
        private const val GAS_POLL_INTERVAL_MS = 3_000L

        /**
         * The fee for [DEFAULT_GAS_LIMIT]. Derived rather than flat: a screen
         * that raises the gas limit and keeps a flat fee builds a transaction
         * the node rejects. Use [feeFor] wherever the gas is not the default.
         */
        val DEFAULT_FEE_UERTH: Long get() = Fees.forGas(DEFAULT_GAS_LIMIT)

        /** The fee for an arbitrary gas limit. */
        fun feeFor(gasLimit: Long): Long = Fees.forGas(gasLimit)

        /**
         * What a "max" button leaves behind so the account can still act.
         *
         * Subtracting one minimum fee is not enough. Staking the maximum used
         * to leave exactly [DEFAULT_FEE_UERTH] — one 400,000-gas transaction
         * and nothing more — so the very next thing a staker wants to do,
         * claiming rewards, needed 2,750 against a 2,000 balance and was
         * unaffordable the moment it was offered. The position was staked and
         * the account was stranded.
         *
         * A million gas covers the realistic follow-ups: claim across a few
         * validators, then unstake. At 0.005uerth that is 5,000 uerth — small
         * against a stake, and the difference between an account that can act
         * and one that cannot.
         *
         * Deliberately NOT applied to sending: emptying an account on purpose
         * should be allowed to empty it, less the fee.
         */
        val GAS_RESERVE_UERTH: Long get() = Fees.forGas(1_000_000L)
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
            awaitingGas = controller.awaitingGas,
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
