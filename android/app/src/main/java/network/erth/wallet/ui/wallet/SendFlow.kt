package network.erth.wallet.ui.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.google.protobuf.ByteString
import cosmos.base.v1beta1.CoinOuterClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.earth.proto.shielded.MsgShield
import network.erth.wallet.crypto.Bech32
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.privacy.chain.PrivacyQueries
import network.erth.wallet.privacy.handles.HandleDirectory
import network.erth.wallet.privacy.handles.Handles
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import network.erth.wallet.Constants
import network.erth.wallet.chain.Bank
import network.erth.wallet.privacy.PrivacySession
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.ui.components.asAmountInput
import network.erth.wallet.ui.components.rememberAddressScanner
import network.erth.wallet.ui.components.toUerthOrNull
import network.erth.wallet.ui.tx.TxConfirmDetails
import network.erth.wallet.ui.tx.TxController
import network.erth.wallet.wallet.SecureWalletManager

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
    val scope = rememberCoroutineScope()

    // A handle ("@alice" or "alice") is looked up in the whole directory,
    // downloaded in full and cached, never asked about alone: the node must
    // not learn who is about to pay whom. Re-fetched fresh before the confirm.
    val toHandle = Handles.looksLikeHandle(recipient)
    var resolution: HandleDirectory.Resolution? by remember { mutableStateOf(null) }
    var resolving by remember { mutableStateOf(false) }
    LaunchedEffect(recipient) {
        resolution = null
        if (!toHandle || Handles.parse(recipient) == null) return@LaunchedEffect
        delay(400)
        resolving = true
        resolution = withContext(Dispatchers.IO) {
            runCatching { PrivacyQueries.handles.resolveForPayment(recipient) }
                .getOrElse { HandleDirectory.Resolution.NotPayable("Couldn't load the handle directory: ${it.message ?: "network error"}") }
        }
        resolving = false
    }
    val handleTarget = (resolution as? HandleDirectory.Resolution.Payable)

    // ERTH holdings sort first, so it is the default without a special case.
    // A wallet with no balances at all still needs something to render.
    // Transparent holdings, plus what is held only shielded (ANML, derth),
    // so a private send can name any of it.
    val holdings = (
        state.holdings + state.shielded.keys
            .filter { d -> state.holdings.none { it.denom == d } && !d.startsWith("asset/") }
            .map { d -> Holding(denom = d, symbol = if (d == "uanml") "ANML" else d, amount = 0) }
        ).ifEmpty {
        listOf(Holding(denom = Constants.UERTH_DENOM, symbol = "ERTH", amount = 0))
    }
    var selectedDenom by remember { mutableStateOf(holdings.first().denom) }
    val selected = holdings.firstOrNull { it.denom == selectedDenom } ?: holdings.first()

    // A shielded recipient (erthz1...) is paid privately from shielded notes;
    // a transparent one by a bank send, as before.
    val shieldedTo = recipient.startsWith(ShieldedAddress.HRP + "1")
    val shieldedBalance = state.shielded[selected.denom] ?: 0L
    val amountUerth = amount.toUerthOrNull()
    val sendingErth = selected.denom == Constants.UERTH_DENOM
    // A handle is paid privately from notes when they cover it; otherwise
    // from the public balance, shielded straight to the handle's address.
    val handleFromNotes = amountUerth != null && amountUerth <= shieldedBalance && state.shieldedErthUerth > 0

    val recipientError = when {
        recipient.isEmpty() -> null
        toHandle && Handles.parse(recipient) == null -> "A handle is 3-32 of a-z, 0-9 and -."
        toHandle -> (resolution as? HandleDirectory.Resolution.NotPayable)?.reason
            ?: handleTarget?.takeIf { it.address.encode() == state.shieldedAddress }?.let { "@${it.entry.handle} names this wallet's own address." }
        shieldedTo && !ShieldedAddress.isShielded(recipient) -> "That shielded address is not valid."
        shieldedTo && recipient == state.shieldedAddress -> "That is this wallet's own shielded address."
        shieldedTo -> null
        !recipient.startsWith("earth1") -> "An Earth address starts with earth1, or erthz1 to send privately."
        recipient.length < 39 -> "That address is too short."
        // The chain checks the checksum only when the send executes, after
        // the fee is spent, so a typo is caught here instead.
        runCatching { Bech32.decode(recipient) }.getOrNull()?.size != 20 -> "That is not a valid Earth address."
        recipient == state.address -> "That is this wallet's own address."
        else -> null
    }

    val amountError = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount, for example 1.5."
        amountUerth <= 0 -> "Enter more than zero."
        toHandle && handleFromNotes -> null
        toHandle && amountUerth > selected.amount -> "That is more than your ${selected.symbol}, public or shielded."
        toHandle && sendingErth && amountUerth + TxController.DEFAULT_FEE_UERTH > state.balanceUerth -> "That leaves nothing for the fee."
        toHandle && !sendingErth && state.balanceUerth < TxController.DEFAULT_FEE_UERTH -> "You need a little ERTH to pay the fee."
        toHandle -> null
        shieldedTo && amountUerth > shieldedBalance -> "That is more than your shielded ${selected.symbol}."
        shieldedTo && state.shieldedErthUerth <= 0 -> "You need shielded ERTH to pay the fee."
        shieldedTo -> null
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
        recipientError == null && amountError == null && amountUerth != null &&
        (!toHandle || (handleTarget != null && !resolving))

    val recipientHint = when {
        !toHandle -> null
        resolving -> "Looking up the handle…"
        handleTarget != null -> "@${handleTarget.entry.handle} → ${Handles.truncate(handleTarget.entry.address)}" +
            if (amountUerth != null && !handleFromNotes) " · shielded from your public balance" else " · sent privately"
        else -> null
    }

    fun clear() { recipient = ""; amount = "" }

    // Pays a handle: the directory fetched again (fresh) so a handle that
    // lapsed or changed hands since the preview is never paid, then the
    // confirm shows the handle and the address it names now.
    fun payHandle(amountUerth: Long) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { PrivacyQueries.handles.resolveForPayment(recipient) } }
        val target = r.getOrNull()
        if (target !is HandleDirectory.Resolution.Payable) {
            resolution = target ?: HandleDirectory.Resolution.NotPayable("Couldn't load the handle directory: ${r.exceptionOrNull()?.message ?: "network error"}")
            return@launch
        }
        resolution = target
        val to = target.address
        // The whole address the note goes to, under the handle that named it.
        val label = target.entry.address
        val labelTitle = "To @${target.entry.handle}"
        if (handleFromNotes) {
            tx.requestPrivate(
                details = TxConfirmDetails(
                    action = "Pay @${target.entry.handle} privately",
                    msgTypeUrl = PrivateMsgs.SEND,
                    balanceUerth = 0L,
                    amountLabel = "Amount",
                    amountValue = "$amount ${selected.symbol}",
                    recipient = label,
                    recipientLabel = labelTitle,
                ),
                shieldedErth = state.shieldedErthUerth,
                onSuccess = { clear(); onSent() },
                run = { ctx -> PrivacySession.wallet(ctx).send(to, selected.denom, amountUerth).hash },
            )
        } else {
            // MsgShield, signed by the account (the coins are its), the note
            // minted straight to the handle's address: the amount is public,
            // who it pays is not.
            tx.request(
                details = TxConfirmDetails(
                    action = "Pay @${target.entry.handle} from your public balance",
                    msgTypeUrl = PrivateMsgs.SHIELD,
                    balanceUerth = state.balanceUerth,
                    amountLabel = "Amount",
                    amountValue = "$amount ${selected.symbol}",
                    recipient = label,
                    recipientLabel = labelTitle,
                ),
                onSuccess = { clear(); onSent() },
                build = { ctx ->
                    val out = PrivacySession.wallet(ctx).payout(selected.denom, to)
                    val msg = MsgShield.newBuilder()
                        .setSender(SecureWalletManager.getWalletAddress(ctx).orEmpty())
                        .setAmount(CoinOuterClass.Coin.newBuilder().setDenom(selected.denom).setAmount(amountUerth.toString()))
                        .setPc(ByteString.copyFrom(out.pc.toBytes()))
                        .setCiphertext(ByteString.copyFrom(out.ciphertext))
                        .build()
                    listOf(EarthTx.anyOf(PrivateMsgs.SHIELD, msg))
                },
            )
        }
    }

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
        recipientHint = recipientHint,
        modifier = modifier,
        onSend = {
            if (!valid || amountUerth == null) return@SendScreen
            if (toHandle) {
                payHandle(amountUerth)
                return@SendScreen
            }
            if (shieldedTo) {
                val to = ShieldedAddress.decode(recipient)
                tx.requestPrivate(
                    details = TxConfirmDetails(
                        action = "Send ${selected.symbol} privately",
                        msgTypeUrl = PrivateMsgs.SEND,
                        balanceUerth = 0L,
                        amountLabel = "Amount",
                        amountValue = "$amount ${selected.symbol}",
                        recipient = recipient,
                    ),
                    shieldedErth = state.shieldedErthUerth,
                    onSuccess = {
                        recipient = ""
                        amount = ""
                        onSent()
                    },
                    run = { ctx -> PrivacySession.wallet(ctx).send(to, selected.denom, amountUerth).hash },
                )
                return@SendScreen
            }
            // Captured now: the build must not read the field as it is later.
            val to = recipient
            tx.request(
                details = TxConfirmDetails(
                    action = "Send ${selected.symbol}",
                    msgTypeUrl = "/cosmos.bank.v1beta1.MsgSend",
                    balanceUerth = state.balanceUerth,
                    amountLabel = "Amount",
                    amountValue = "$amount ${selected.symbol}",
                    recipient = to,
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
                            to,
                            selected.denom,
                            amountUerth.toString(),
                        ),
                    )
                },
            )
        },
    )
}
