package network.erth.wallet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Send: the form, its validation, and the confirmation gate.
 *
 * Their SendView is 856 lines because Zcash sends have a memo, a shielded and
 * a transparent pool to choose between, an exchange-rate line and a proposal
 * step that can fail before broadcast. Earth has an address, an amount and a
 * fee, so the form is small and the state that mattered was always the
 * confirmation gate — which lives here rather than in the screen so the sheet
 * cannot be bypassed by a caller that forgets to show it.
 */
@Composable
fun SendFlow(
    state: WalletUiState,
    onSubmit: (recipient: String, amount: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    val recipientError = when {
        recipient.isEmpty() -> null
        !recipient.startsWith("earth1") -> "An Earth address starts with earth1."
        recipient.length < 39 -> "That address is too short."
        else -> null
    }

    val amountUerth = amount.toBigDecimalOrNull()
        ?.movePointRight(6)
        ?.toLong()

    val amountError = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount, for example 1.5."
        amountUerth <= 0 -> "Enter more than zero."
        amountUerth > state.balanceUerth -> "That is more than your balance."
        else -> null
    }

    val valid = recipient.isNotEmpty() && amount.isNotEmpty() &&
        recipientError == null && amountError == null

    SendScreen(
        recipient = recipient,
        onRecipientChange = { recipient = it },
        amount = amount,
        onAmountChange = { amount = it },
        balanceLabel = formatUerth(state.balanceUerth),
        denom = "ERTH",
        onSend = { if (valid) confirming = true },
        recipientError = recipientError,
        amountError = amountError,
        modifier = modifier,
    )

    if (confirming) {
        TxConfirmSheet(
            details = TxConfirmDetails(
                action = "Send ERTH",
                msgTypeUrl = "/cosmos.bank.v1beta1.MsgSend",
                feeUerth = SEND_FEE_UERTH,
                balanceUerth = state.balanceUerth,
                amountLabel = "Amount",
                amountValue = "$amount ERTH",
            ),
            onConfirm = {
                confirming = false
                onSubmit(recipient, amount)
            },
            onDismiss = { confirming = false },
            onWatchAd = {},
        )
    }
}

/** What a bank send costs at the chain's minimum gas price. */
private const val SEND_FEE_UERTH = 2_000L

private fun String.toBigDecimalOrNull(): java.math.BigDecimal? =
    runCatching { java.math.BigDecimal(this) }.getOrNull()
