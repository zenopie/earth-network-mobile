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
    val scan = rememberAddressScanner { recipient = it }
    var amount by remember { mutableStateOf("") }

    // ERTH holdings sort first, so it is the default without a special case.
    // A wallet with no balances at all still needs something to render.
    val holdings = state.holdings.ifEmpty {
        listOf(Holding(denom = Constants.UERTH_DENOM, symbol = "ERTH", amount = 0))
    }
    var selectedDenom by remember { mutableStateOf(holdings.first().denom) }
    val selected = holdings.firstOrNull { it.denom == selectedDenom } ?: holdings.first()

    val recipientError = when {
        recipient.isEmpty() -> null
        !recipient.startsWith("earth1") -> "An Earth address starts with earth1."
        recipient.length < 39 -> "That address is too short."
        recipient == state.address -> "That is this wallet's own address."
        else -> null
    }

    val amountUerth = amount.toUerthOrNull()

    val sendingErth = selected.denom == Constants.UERTH_DENOM

    val amountError = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount, for example 1.5."
        amountUerth <= 0 -> "Enter more than zero."
        amountUerth > selected.amount -> "That is more than your ${selected.symbol}."
        // The fee is always paid in ERTH. Sending ERTH, it has to come out of
        // what is left after the amount; sending anything else, it only has to
        // exist. Two different checks, and running the ERTH one against an
        // ANML balance was the bug this replaces.
        sendingErth && amountUerth + TxController.DEFAULT_FEE_UERTH > state.balanceUerth ->
            "That leaves nothing for the fee."
        !sendingErth && state.balanceUerth < TxController.DEFAULT_FEE_UERTH ->
            "You need a little ERTH to pay the fee."
        else -> null
    }

    val valid = recipient.isNotEmpty() && amount.isNotEmpty() &&
        recipientError == null && amountError == null && amountUerth != null

    SendScreen(
        recipient = recipient,
        onRecipientChange = { recipient = it.trim() },
        onScan = scan,
        amount = amount,
        onAmountChange = { amount = it.asAmountInput(amount) },
        balanceLabel = selected.display,
        selected = selected,
        holdings = holdings,
        onSelectToken = { selectedDenom = it.denom; amount = "" },
        recipientError = recipientError,
        amountError = amountError,
        modifier = modifier,
        onSend = {
            if (!valid || amountUerth == null) return@SendScreen
            tx.request(
                details = TxConfirmDetails(
                    action = "Send ${selected.symbol}",
                    msgTypeUrl = "/cosmos.bank.v1beta1.MsgSend",
                    feeUerth = TxController.DEFAULT_FEE_UERTH,
                    balanceUerth = state.balanceUerth,
                    amountLabel = "Amount",
                    amountValue = "$amount ${selected.symbol}",
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
                            selected.denom,
                            amountUerth.toString(),
                        ),
                    )
                },
            )
        },
    )
}
