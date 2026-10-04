package network.erth.wallet.ui.tx

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.Any as ProtoAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.chain.Fees
import network.erth.wallet.chain.TxUnconfirmedException
import network.erth.wallet.crypto.EarthWallet
import network.erth.wallet.wallet.SecureWalletManager

/**
 * One path for every transaction: confirm, broadcast, report.
 *
 * One place, so every transaction shows what is about to be signed before it
 * is, and why it failed when it does; no screen broadcasts on its own.
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

    /** True while the grant is being asked for, before anything is sent. */
    var requestingGas: Boolean by mutableStateOf(false)
        private set

    /** True from the moment the grant is sent until the gas lands (or gives up). */
    var awaitingGas: Boolean by mutableStateOf(false)
        private set

    /** Why no gas can be had here (see [requestGas]). */
    var gasError: String? by mutableStateOf(null)
        private set

    /** What is in flight, for the pending sheet to name. */
    var lastAction: String? by mutableStateOf(null)
        private set

    private var build: ((Context) -> List<ProtoAny>)? = null
    private var private: ((Context) -> String)? = null
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
        // shown the same number that will be broadcast. A fee stated
        // separately for the sheet and the broadcast can drift (a gas limit
        // that scales, say, by validator count under a flat fee): the sheet
        // would say "funded" and the node would reject the tx.
        val fee = feeFor(gasLimit)

        this.build = build
        this.private = null
        this.gasLimit = gasLimit
        this.feeUerth = fee
        this.onDone = onSuccess
        pending = details.copy(feeUerth = fee)
    }

    /**
     * Ask for a private transaction: unsigned, proven on the phone, its fee
     * paid from a shielded ERTH note. [run] proves and broadcasts (a
     * PrivacyWallet action) and returns the tx hash; it runs off the main
     * thread once confirmed. [estimatedFee] is what the sheet shows: the
     * chain's exact fee is only known after simulating, which [run] does.
     * [shieldedErth] is the balance the fee comes from, so the sheet's
     * funding check is against the right pool.
     */
    fun requestPrivate(
        details: TxConfirmDetails,
        estimatedFee: Long = feeFor(PRIVATE_GAS_ESTIMATE),
        shieldedErth: Long,
        onSuccess: (() -> Unit)? = null,
        run: (Context) -> String,
    ) {
        this.build = null
        this.private = run
        this.feeUerth = estimatedFee
        this.onDone = onSuccess
        pending = details.copy(feeUerth = estimatedFee, balanceUerth = shieldedErth, shielded = true)
    }

    fun confirm(context: Context) {
        val details = pending ?: return
        val builder = build
        val privateRun = private
        if (builder == null && privateRun == null) return
        pending = null
        lastAction = details.action
        submitting = true

        viewModelScope.launch {
            outcome = try {
                val hash = withContext(Dispatchers.IO) {
                    if (privateRun != null) {
                        // The fee the sheet showed bounds what the private run may pay.
                        network.erth.wallet.privacy.PrivacyWallet.withShownFee(details.feeUerth) { privateRun(context) }
                    } else {
                        SecureWalletManager.executeWithMnemonic(context) { mnemonic ->
                            val key = EarthWallet.deriveKey(mnemonic)
                            EarthTx.broadcast(key, builder!!(context), gasLimit, feeUerth.toString())
                        }
                    }
                }
                onDone?.invoke()
                TxOutcome.Success(details.action, hash)
            } catch (e: TxUnconfirmedException) {
                // Still run onDone: it clears the form and refreshes, and
                // leaving a filled-in send on screen invites sending it again
                // while the first may yet land.
                onDone?.invoke()
                TxOutcome.Pending(details.action, e.txHash)
            } catch (e: network.erth.wallet.privacy.tx.PrivateTxEngine.FeeAboveQuote) {
                // Nothing was proven or sent: show the sheet again at the chain's fee.
                if (privateRun != null) {
                    private = privateRun
                    feeUerth = e.fee
                    pending = details.copy(feeUerth = e.fee)
                }
                null
            } catch (e: Exception) {
                TxOutcome.Failure(details.action, e)
            } finally {
                submitting = false
            }
        }
    }

    /**
     * Free gas exists only for a first registration (RegistrationActivity),
     * paid as a shielded note. Every later fee comes from the registration
     * reward, so there is nothing to ask for here: this says so.
     */
    @Suppress("UNUSED_PARAMETER")
    fun requestGas(
        address: String,
        fetchBalance: suspend () -> Long,
        onFunded: () -> Unit = {},
    ) {
        gasError = "Free gas is granted once, with your registration. Fees are paid from your shielded ERTH."
    }

    /** Reports a failure that happened before any sheet (a read the action needed): nothing was sent. */
    fun showFailure(action: String, e: Throwable) {
        outcome = TxOutcome.Failure(action, e)
    }

    fun cancel() {
        pending = null
        build = null
        private = null
        awaitingGas = false
        gasError = null
    }

    fun dismissResult() {
        outcome = null
    }

    companion object {
        const val DEFAULT_GAS_LIMIT = 400_000L

        /**
         * A private tx's gas for the confirm sheet: what the sheet shows is
         * the most the tx may then pay without asking again (a
         * higher simulated fee shows the sheet again at it). Two actions
         * (4.7M at x/shielded's defaults), a stake proof (3.15M) or a
         * membership proof (2.15M), the tx's bytes and the 10% headroom fit
         * under it (PrivateTxEngine's gas schedule).
         */
        const val PRIVATE_GAS_ESTIMATE = 10_000_000L

        /**
         * A handle bind's: the chain prices it as nine note writes (chain
         * 203d3b2), 1.2M more than [PRIVATE_GAS_ESTIMATE]'s
         * membership, with a three-action fee bundle (the state record).
         */
        const val BIND_HANDLE_GAS_ESTIMATE = 12_500_000L

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
         * Subtracting one minimum fee is not enough: staking the maximum would
         * leave exactly [DEFAULT_FEE_UERTH] — one 400,000-gas transaction and
         * nothing more — so the next thing a staker wants to do, claiming
         * rewards across validators, would be unaffordable and the account
         * stranded.
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
    onGetGas: () -> Unit = {},
) {
    controller.pending?.let { details ->
        TxConfirmSheet(
            details = if (details.shielded) details else details.copy(balanceUerth = balanceUerth),
            onConfirm = { controller.confirm(context) },
            onDismiss = controller::cancel,
            onGetGas = onGetGas,
            awaitingGas = controller.awaitingGas,
            requestingGas = controller.requestingGas,
            gasError = controller.gasError,
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
