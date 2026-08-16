package network.erth.wallet.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.Constants

/**
 * Transaction confirmation sheet, and the app's gas gate.
 *
 * Earth is transparent and contract-free, so this shows what the chain will
 * actually receive — the message type and the fee — rather than a contract call.
 *
 * Its second job is the one that matters for onboarding. A new human has no ERTH
 * and no on-chain account, and an address the chain has never seen cannot sign
 * anything at all: the ante handler rejects an unknown signer before it looks at
 * who is paying. So when the balance cannot cover the fee this offers a rewarded
 * ad, and the backend's verified-view callback sends enough dust to both create
 * the account and pay for the transaction.
 */
class TransactionConfirmationDialog(private val context: Context) {

    data class Details(
        /** Human-readable, e.g. "Register as a human". */
        val action: String,
        /** What the chain sees, e.g. "/earth.personhood.v1.MsgRegister". */
        val msgTypeUrl: String,
        /** The signer, and the address dust would be sent to. */
        val address: String,
        val feeUerth: Long,
        val gasLimit: Long,
    )

    interface Listener {
        fun onConfirmed()
        fun onCancelled()
        /**
         * Shows the rewarded ad. The callback fires with true once the reward is
         * earned — at which point Google's server-side callback is on its way to
         * the backend, and the dust is not on chain yet.
         */
        fun onWatchAdForGas(callback: (Boolean) -> Unit)
    }

    private lateinit var sheet: BottomSheetDialog
    private var settled = false

    /**
     * @param scope a lifecycle-bound scope; balance reads and the post-ad poll
     *   run on it, so they stop if the screen goes away.
     */
    fun show(details: Details, scope: CoroutineScope, listener: Listener) {
        val view = LayoutInflater.from(context).inflate(R.layout.transaction_confirmation_popup, null)
        sheet = BottomSheetDialog(context)
        sheet.setContentView(view)

        val actionText = view.findViewById<TextView>(R.id.action_text)
        val msgTypeText = view.findViewById<TextView>(R.id.msg_type_text)
        val feeText = view.findViewById<TextView>(R.id.fee_text)
        val balanceText = view.findViewById<TextView>(R.id.balance_text)
        val gasWarning = view.findViewById<TextView>(R.id.gas_warning_text)
        val adsSection = view.findViewById<LinearLayout>(R.id.ads_for_gas_section)
        val adsButton = view.findViewById<Button>(R.id.ads_for_gas_button)
        val gasStatus = view.findViewById<Button>(R.id.gas_grant_status)
        val cancelButton = view.findViewById<ImageButton>(R.id.cancel_button)
        val confirmButton = view.findViewById<View>(R.id.confirm_button)

        actionText.text = details.action
        msgTypeText.text = details.msgTypeUrl
        feeText.text = formatErth(details.feeUerth)

        // Confirm stays shut until we know the balance covers the fee. Letting it
        // through would just fail in the ante handler.
        setConfirmEnabled(confirmButton, false)

        cancelButton.setOnClickListener { settle(listener, confirmed = false) }
        confirmButton.setOnClickListener { settle(listener, confirmed = true) }
        sheet.setOnCancelListener { settle(listener, confirmed = false) }

        scope.launch {
            val balance = readBalance(details.address)
            balanceText.text = formatErth(balance)

            if (balance >= details.feeUerth) {
                setConfirmEnabled(confirmButton, true)
                return@launch
            }

            gasWarning.visibility = View.VISIBLE
            adsSection.visibility = View.VISIBLE
            adsButton.setOnClickListener {
                adsButton.isEnabled = false
                adsButton.text = "Loading ad…"
                listener.onWatchAdForGas { earned ->
                    if (!earned) {
                        adsButton.isEnabled = true
                        adsButton.text = "Watch Ad for Free Gas"
                        return@onWatchAdForGas
                    }
                    // The reward is earned on the device, but the dust arrives
                    // out of band: Google calls the backend, which then sends it.
                    // So watch the chain rather than trusting the callback.
                    adsButton.text = "Waiting for gas…"
                    scope.launch {
                        val funded = awaitFunding(details.address, details.feeUerth)
                        balanceText.text = formatErth(readBalance(details.address))
                        if (funded) {
                            adsButton.visibility = View.GONE
                            gasStatus.visibility = View.VISIBLE
                            gasWarning.visibility = View.GONE
                            setConfirmEnabled(confirmButton, true)
                        } else {
                            adsButton.isEnabled = true
                            adsButton.text = "Watch Ad for Free Gas"
                            gasWarning.text =
                                "The gas hasn't arrived yet. Give it a moment and try again."
                        }
                    }
                }
                // The ad takes a beat to appear; say so rather than looking stuck.
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!adsButton.isEnabled && adsButton.text == "Loading ad…") {
                        adsButton.text = "Showing ad…"
                    }
                }, 1000)
            }
        }

        sheet.show()
    }

    /** Fires the listener exactly once, whichever way the sheet closes. */
    private fun settle(listener: Listener, confirmed: Boolean) {
        if (settled) return
        settled = true
        if (sheet.isShowing) sheet.dismiss()
        if (confirmed) listener.onConfirmed() else listener.onCancelled()
    }

    private fun setConfirmEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private suspend fun readBalance(address: String): Long = withContext(Dispatchers.IO) {
        runCatching { Bank.balance(address, Constants.UERTH_DENOM).toLong() }.getOrDefault(0L)
    }

    /**
     * Polls until the address can cover [needed], or gives up.
     *
     * The wait spans an HTTP round trip from Google plus a block, so the window
     * is generous; returning false is "not yet", not "never".
     */
    private suspend fun awaitFunding(address: String, needed: Long): Boolean {
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            if (readBalance(address) >= needed) return true
        }
        return false
    }

    private fun formatErth(uerth: Long): String {
        if (uerth == 0L) return "0 ERTH"
        val amount = String.format("%.6f", uerth / 1_000_000.0).trimEnd('0').trimEnd('.')
        return "$amount ERTH"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_ATTEMPTS = 30 // ~90s
    }
}
