package network.erth.wallet.ui.tx

import network.erth.wallet.privacy.PrivacyWallet
import network.erth.wallet.privacy.tx.UnsignedTx
import network.erth.wallet.ui.components.formatErth

/**
 * Which transaction the confirm sheet may show, and when.
 *
 * [TxController] and the registration flow keep their sheets in this, so the
 * rules live where a test can reach them: a request's sheet is shown once, a
 * confirm sends it once, and a sheet for a request that is being sent or was
 * sent can never come back. The one exception is [reask]: the chain priced the
 * tx above what the sheet showed and nothing was proven or sent, so the same
 * request is asked again at the new fee. As iOS's TxGate.
 *
 * iOS build 21 showed why this has to be one rule rather than care at each
 * call site: a registration's sheet came back over "Sending" and was
 * confirmed a second time.
 */
class TxGate {
    sealed interface Phase {
        data object Idle : Phase
        /** The sheet for this request is up. */
        data class Confirming(val id: Any) : Phase
        /** This request was confirmed and is being proven, sent or waited on. */
        data class Sending(val id: Any) : Phase
    }

    var phase: Phase = Phase.Idle
        private set

    /** Requests that reached a send. Never shown again. */
    private val spent = HashSet<Any>()

    val sending: Boolean get() = phase is Phase.Sending

    /**
     * A request's sheet may be shown: not while another tx is being sent (its
     * sheet would draw over "Sending" and could be confirmed into a second
     * broadcast), and never for a request already sent.
     */
    @Synchronized
    fun present(id: Any): Boolean {
        if (phase is Phase.Sending || id in spent) return false
        phase = Phase.Confirming(id)
        return true
    }

    /** The sheet's Confirm: only for the request on screen, and only once. */
    @Synchronized
    fun confirm(id: Any): Boolean {
        if (phase != Phase.Confirming(id)) return false
        phase = Phase.Sending(id)
        spent += id
        return true
    }

    /** The chain asks a higher fee than the sheet showed and nothing was sent: the same request goes back on screen. */
    @Synchronized
    fun reask(id: Any): Boolean {
        if (phase != Phase.Sending(id)) return false
        spent -= id
        phase = Phase.Confirming(id)
        return true
    }

    /** The send settled (landed, failed, or its outcome is unknown). */
    @Synchronized
    fun finish(id: Any) {
        if (phase == Phase.Sending(id)) phase = Phase.Idle
    }

    /** The sheet was dismissed. A send in flight is not cancelled by it. */
    @Synchronized
    fun cancel() {
        if (phase is Phase.Confirming) phase = Phase.Idle
    }

    /**
     * Whether [id]'s sheet is the one up. A gas wait started from a sheet acts
     * only while it is: once confirmed, cancelled or replaced, it stops.
     */
    @Synchronized
    fun showing(id: Any): Boolean = phase == Phase.Confirming(id)

    companion object {
        /** The sentence a re-asked sheet carries, so it does not read as the same request popping up again. */
        fun reaskNote(fee: Long, shown: Long): String =
            "Nothing was sent. The network's fee for this is ${formatErth(fee)}, more than the ${formatErth(shown)} " +
                "estimated. Confirm again to send it at that fee."

        /**
         * The chain's refusals of a registration it already holds: this identity
         * registered (1130), this passport already registered to this identity
         * (1123), or this exact registration used (1124).
         */
        val DUPLICATE_REGISTRATION_CODES = setOf(1123, 1124, 1130)

        /** Whether [e] (or a cause) is a CheckTx refusal of a registration as already held. */
        fun duplicateRegistration(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(8).any {
                it is PrivacyWallet.IdentityUsed ||
                    (it is UnsignedTx.TxRejected && it.codespace == PrivacyWallet.IDENTITY_USED_CODESPACE &&
                        it.code in DUPLICATE_REGISTRATION_CODES)
            }

        const val ALREADY_REGISTERED =
            "This wallet is already registered. The chain refused a second copy of the registration, so nothing more was sent or paid."
    }
}
