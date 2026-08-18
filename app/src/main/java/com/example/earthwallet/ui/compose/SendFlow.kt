package network.erth.wallet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import network.erth.wallet.Constants
import network.erth.wallet.chain.Bank
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * Send: the form, its validation, and the confirmation gate.
 *
 * Their SendView is 856 lines because a Zcash send has a memo, a shielded and
 * a transparent pool to choose between, an exchange-rate line and a proposal
 * step that can fail before broadcast. Earth has an address, an amount and a
 * fee, so the form is small and the state that mattered was always the
 * confirmation gate — which lives here rather than in the screen so a caller
 * cannot forget to show it.
 */
@Composable
fun SendFlow(
    state: WalletUiState,
    tx: TxController,
    onSent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }

    val recipientError = when {
        recipient.isEmpty() -> null
        !recipient.startsWith("earth1") -> "An Earth address starts with earth1."
        recipient.length < 39 -> "That address is too short."
        recipient == state.address -> "That is this wallet's own address."
        else -> null
    }

    val amountUerth = amount.toUerthOrNull()

    val amountError = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount, for example 1.5."
        amountUerth <= 0 -> "Enter more than zero."
        // The fee comes out of the same balance, so sending exactly the
        // balance always fails on chain. Say so here rather than after a fee
        // has been spent finding out.
        amountUerth + TxController.DEFAULT_FEE_UERTH > state.balanceUerth ->
            "That leaves nothing for the fee."
        else -> null
    }

    val valid = recipient.isNotEmpty() && amount.isNotEmpty() &&
        recipientError == null && amountError == null && amountUerth != null

    SendScreen(
        recipient = recipient,
        onRecipientChange = { recipient = it },
        amount = amount,
        onAmountChange = { amount = it },
        balanceLabel = formatUerth(state.balanceUerth),
        denom = "ERTH",
        recipientError = recipientError,
        amountError = amountError,
        modifier = modifier,
        onSend = {
            if (!valid || amountUerth == null) return@SendScreen
            tx.request(
                details = TxConfirmDetails(
                    action = "Send ERTH",
                    msgTypeUrl = "/cosmos.bank.v1beta1.MsgSend",
                    feeUerth = TxController.DEFAULT_FEE_UERTH,
                    balanceUerth = state.balanceUerth,
                    amountLabel = "Amount",
                    amountValue = "$amount ERTH",
                ),
                onSuccess = {
                    recipient = ""
                    amount = ""
                    onSent()
                },
                build = { ctx ->
                    val from = SecureWalletManager.getWalletAddress(ctx).orEmpty()
                    listOf(
                        Bank.msgSend(
                            from,
                            recipient,
                            Constants.UERTH_DENOM,
                            amountUerth.toString(),
                        ),
                    )
                },
            )
        },
    )
}
