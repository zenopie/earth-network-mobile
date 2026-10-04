package network.erth.wallet.ui.tx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.components.EarthCodeBlock
import network.erth.wallet.ui.components.EarthDetailRow
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.destructiveButtonColors
import network.erth.wallet.ui.components.formatErth
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/** What the sheet needs to describe a pending transaction. */
data class TxConfirmDetails(
    /** In the user's words: "Stake ERTH". */
    val action: String,
    /** What the chain sees: "/cosmos.staking.v1beta1.MsgDelegate". */
    val msgTypeUrl: String,
    /**
     * What the node will charge. Filled in by [TxController.request] from the
     * gas limit, so callers going through the controller should leave it alone
     * — anything they set here is overwritten. Set it only when driving
     * [TxConfirmSheet] directly, as the registration flow does.
     */
    val feeUerth: Long = 0,
    val balanceUerth: Long,
    /** Paid from a shielded note: the fee is an estimate and the balance is shielded ERTH. */
    val shielded: Boolean = false,
    /** Optional, e.g. the amount being staked. */
    val amountLabel: String? = null,
    val amountValue: String? = null,
    /**
     * Who receives it, in full: a send's recipient, a delegation's validator.
     * Never truncated — an address that differs only in the middle is exactly
     * what a clipboard-swapping attack produces, and the confirmation is the
     * last place to catch it.
     */
    val recipient: String? = null,
    val recipientLabel: String = "To",
    /**
     * A swap's floor: the least the chain will pay out before rejecting it.
     * Shown because it is the number actually signed — "You pay" alone says
     * nothing about how much slippage the message allows.
     */
    val minReceived: String? = null,
    /**
     * Rows the chain's own numbers add beyond the amount (label, value):
     * a delegation's quoted derth, what a move arrives as.
     */
    val rows: List<Pair<String, String>> = emptyList(),
    /**
     * Sentences the user must read before confirming: a slash's cut of
     * moved stake this tx settles, when moved stake can move again.
     */
    val notes: List<String> = emptyList(),
)

/**
 * The confirmation sheet, and the app's gas gate.
 *
 * Earth is transparent and contract-free, so this shows what the chain will
 * actually receive — the message type and the fee — rather than a contract
 * call. Showing both the human action and the message type lets the two be
 * checked against each other, which is the whole argument for a chain without
 * contracts.
 *
 * Its second job is onboarding. A new human has no ERTH and no on-chain
 * account, and an address the chain has never seen cannot sign anything at all:
 * the ante handler rejects an unknown signer before it looks at who is paying.
 * So when the balance cannot cover the fee, this offers free gas (GasGrant).
 */
@Composable
fun TxConfirmSheet(
    details: TxConfirmDetails,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onGetGas: () -> Unit,
    /** True once the grant has been sent and has not yet landed. */
    awaitingGas: Boolean = false,
    /** True while the grant is being asked for. */
    requestingGas: Boolean = false,
    /** Why the last request for gas was refused, if it was. */
    gasError: String? = null,
    /** The gas request's proof of work, 0..1, while it is being made. */
    gasWork: Float? = null,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val funded = details.balanceUerth >= details.feeUerth

    EarthSheet(onDismiss = onDismiss) {
        Text(
            text = details.action,
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = details.msgTypeUrl,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = dimens.space8),
        )

        if (details.amountLabel != null && details.amountValue != null) {
            EarthDetailRow(details.amountLabel, details.amountValue)
        }
        if (details.minReceived != null) {
            EarthDetailRow("Minimum received", details.minReceived)
        }
        details.rows.forEach { (label, value) -> EarthDetailRow(label, value) }
        if (details.recipient != null) {
            Text(
                text = details.recipientLabel,
                style = EarthTypography.textMd,
                color = EarthColors.Text.textTertiary,
                modifier = Modifier.fillMaxWidth().padding(top = dimens.space8),
            )
            Box(Modifier.padding(vertical = dimens.space8)) { EarthCodeBlock(details.recipient) }
        }
        details.notes.forEach { note ->
            Text(
                text = note,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = dimens.space4),
            )
        }
        EarthDetailRow(if (details.shielded) "Network fee (about)" else "Network fee", formatErth(details.feeUerth))
        EarthDetailRow(if (details.shielded) "Shielded balance" else "Balance", formatErth(details.balanceUerth))

        if (!funded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = dimens.space8)
                    .background(EarthAccent.warnTint, RoundedCornerShape(dimens.radiusSm))
                    .padding(dimens.space12),
            ) {
                Text(
                    text = when {
                        awaitingGas -> "The gas hasn't arrived yet. Give it a moment."
                        gasError != null -> gasError
                        else -> "Not enough ERTH for the fee. Tap to get free gas for this transaction."
                    },
                    style = EarthTypography.textSm,
                    color = EarthAccent.warnInk,
                )
            }
            Box(Modifier.padding(top = dimens.space12)) {
                EarthButton(
                    text = when {
                        awaitingGas -> "Waiting for gas…"
                        requestingGas && gasWork != null -> "Preparing request… ${(gasWork * 100).toInt()}%"
                        else -> "Get free gas"
                    },
                    onClick = onGetGas,
                    // A second tap while one is in flight would ask for a
                    // second grant for the same transaction.
                    enabled = !requestingGas && !awaitingGas,
                    isLoading = requestingGas || awaitingGas,
                    colors = brandButtonColors(),
                )
            }
        }

        // weight on the buttons, not on wrappers around them. A button inside a
        // weighted Box does not inherit the width — it sizes to its label and
        // sits at the box's start, which is why these looked scattered rather
        // than paired.
        Row(
            Modifier.fillMaxWidth().padding(top = dimens.space16),
            horizontalArrangement = Arrangement.spacedBy(dimens.space12),
        ) {
            EarthButton(
                text = "Cancel",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = destructiveButtonColors(),
            )
            // Confirm stays shut until the balance covers the fee: letting it
            // through would only fail in the ante handler.
            EarthButton(
                text = "Confirm",
                onClick = onConfirm,
                enabled = funded,
                modifier = Modifier.weight(1f),
                colors = brandButtonColors(),
            )
        }
    }
}
