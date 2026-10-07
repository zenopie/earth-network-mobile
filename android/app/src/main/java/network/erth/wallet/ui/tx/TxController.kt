package network.erth.wallet.ui.tx

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import network.erth.wallet.chain.SentTxLog
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

    /** What is in flight, for the pending sheet to name. */
    var lastAction: String? by mutableStateOf(null)
        private set

    /**
     * A signed tx whose messages are being built, before its sheet:
     * [TxSheets] calls [prepare] for it, and the sheet is then read back
     * from those messages (SignedSummary).
     */
    var preparing: TxConfirmDetails? by mutableStateOf(null)
        private set

    /**
     * Which request's sheet is up, which is being sent, and which were sent:
     * a sheet for a request being sent or already sent never comes back.
     */
    private val gate = TxGate()
    /** The request on the sheet (or being sent): its identity for [gate]. */
    private var requestId: Any? = null

    private var build: ((Context) -> List<ProtoAny>)? = null
    /** The messages the sheet was read from: exactly what a confirm signs. */
    private var signed: List<ProtoAny>? = null
    private var private: ((Context) -> String)? = null
    private var gasLimit: Long = DEFAULT_GAS_LIMIT
    private var feeUerth: Long = DEFAULT_FEE_UERTH
    private var onDone: (() -> Unit)? = null

    /**
     * Ask for a transaction. Shows the confirmation sheet; nothing is signed
     * until it is confirmed.
     *
     * [build] runs once, off the main thread, before the sheet ([prepare]),
     * and receives a Context so it can read the wallet. The sheet shows what
     * those messages say (SignedSummary) and a confirm signs exactly them,
     * so nothing the screen changes while the sheet is open reaches the tx.
     * The messages hold no sequence number: that is read when the tx is
     * signed, so none goes stale while the sheet is open.
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
        val id = Any()
        if (!gate.present(id)) return

        requestId = id
        this.build = build
        this.signed = null
        this.private = null
        this.gasLimit = gasLimit
        this.feeUerth = fee
        this.onDone = onSuccess
        pending = null
        preparing = details.copy(feeUerth = fee)
    }

    /**
     * Builds [preparing]'s messages and shows the sheet read back from them;
     * a build that fails, or messages that are not this wallet's, end there
     * with nothing signed. Called by [TxSheets], which has the Context.
     */
    suspend fun prepare(context: Context) {
        val details = preparing ?: return
        val builder = build ?: return
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val msgs = builder(context)
                msgs to SignedSummary.describe(msgs, details, SecureWalletManager.getWalletAddress(context).orEmpty())
            }
        }
        // Cancelled, or another request took its place, while it was built.
        if (preparing !== details) return
        preparing = null
        r.fold(
            onSuccess = { (msgs, shown) -> signed = msgs; pending = shown },
            onFailure = { e -> build = null; outcome = TxOutcome.Failure(details.action, e) },
        )
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
        val id = Any()
        if (!gate.present(id)) return
        requestId = id
        this.build = null
        this.signed = null
        this.preparing = null
        this.private = run
        this.feeUerth = estimatedFee
        this.onDone = onSuccess
        pending = details.copy(feeUerth = estimatedFee, balanceUerth = shieldedErth, shielded = true)
    }

    fun confirm(context: Context) {
        val details = pending ?: return
        val msgs = signed
        val privateRun = private
        if (msgs == null && privateRun == null) return
        // Single flight: only the request on screen, once. A second tap, or a
        // sheet some stale path put back, cannot send it again.
        val id = requestId ?: return
        if (!gate.confirm(id)) return
        pending = null
        signed = null
        lastAction = details.action
        submitting = true
        val done = onDone

        viewModelScope.launch {
            // Whether the tx may have landed: then onDone clears the form and
            // refreshes, after the outcome is on screen.
            var settled = true
            val result: TxOutcome? = try {
                val hash = withContext(Dispatchers.IO) {
                    if (privateRun != null) {
                        // The fee the sheet showed bounds what the private run may pay.
                        network.erth.wallet.privacy.PrivacyWallet.withShownFee(details.feeUerth) { privateRun(context) }
                    } else {
                        SecureWalletManager.executeWithMnemonic(context) { mnemonic ->
                            val key = EarthWallet.deriveKey(mnemonic)
                            val signer = EarthWallet.address(key)
                            // The activity list is the txs this wallet sent:
                            // the public node lists none by address.
                            EarthTx.broadcast(key, msgs!!, gasLimit, feeUerth.toString()) {
                                SentTxLog.record(context, signer, it)
                            }
                        }
                    }
                }
                TxOutcome.Success(details.action, hash)
            } catch (e: TxUnconfirmedException) {
                // Still run onDone: it clears the form and refreshes, and
                // leaving a filled-in send on screen invites sending it again
                // while the first may yet land.
                TxOutcome.Pending(details.action, e.txHash)
            } catch (e: network.erth.wallet.privacy.tx.PrivateTxEngine.FeeAboveQuote) {
                // Nothing was proven or sent: show the sheet again at the
                // chain's fee, saying so. The same request, so the gate allows it.
                if (privateRun != null && gate.reask(id)) {
                    private = privateRun
                    feeUerth = e.fee
                    pending = details.copy(feeUerth = e.fee, reask = TxGate.reaskNote(e.fee, e.shown))
                    submitting = false
                    return@launch
                }
                settled = false
                TxOutcome.Failure(details.action, e)
            } catch (e: Exception) {
                settled = false
                TxOutcome.Failure(details.action, e)
            } finally {
                gate.finish(id)
                submitting = false
            }
            outcome = result
            if (settled) done?.invoke()
        }
    }

    /** Reports a failure that happened before any sheet (a read the action needed): nothing was sent. */
    fun showFailure(action: String, e: Throwable) {
        outcome = TxOutcome.Failure(action, e)
    }

    fun cancel() {
        // No sheet is up while a tx is sent; what it holds is the send's.
        if (gate.sending) return
        gate.cancel()
        pending = null
        preparing = null
        build = null
        signed = null
        private = null
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
         * A handle bind's: the chain prices it as nine note writes, 1.2M
         * more than [PRIVATE_GAS_ESTIMATE]'s
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
         * nothing more — so the next action would be unaffordable and the
         * account stranded.
         *
         * A million gas covers the realistic follow-ups. At 0.005uerth that is
         * 5,000 uerth — small against a stake, and the difference between an
         * account that can act and one that cannot.
         *
         * Deliberately NOT applied to sending: emptying an account on purpose
         * should be allowed to empty it, less the fee.
         */
        val GAS_RESERVE_UERTH: Long get() = Fees.forGas(1_000_000L)
    }
}

/**
 * The sheets, mounted once for the whole app. No grant is offered on them:
 * free gas comes with the registration alone (RegistrationActivity's sheet),
 * so a short balance is told where its fee comes from (TxConfirmSheet).
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
) {
    controller.preparing?.let { p ->
        LaunchedEffect(p) { controller.prepare(context) }
    }
    controller.pending?.let { details ->
        TxConfirmSheet(
            details = if (details.shielded) details else details.copy(balanceUerth = balanceUerth),
            onConfirm = { controller.confirm(context) },
            onDismiss = controller::cancel,
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
