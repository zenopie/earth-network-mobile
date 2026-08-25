package network.erth.wallet.ui.compose

import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.EarthCard
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
    /** Optional, e.g. the amount being staked. */
    val amountLabel: String? = null,
    val amountValue: String? = null,
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
 * So when the balance cannot cover the fee, this offers a rewarded ad.
 */
@Composable
fun TxConfirmSheet(
    details: TxConfirmDetails,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onWatchAd: () -> Unit,
    /** True once the ad has been watched and the grant is still in flight. */
    awaitingGas: Boolean = false,
    adLoading: Boolean = false,
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
        EarthDetailRow("Network fee", formatErth(details.feeUerth))
        EarthDetailRow("Balance", formatErth(details.balanceUerth))

        if (!funded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = dimens.space8)
                    .background(EarthAccent.warnTint, RoundedCornerShape(dimens.radiusSm))
                    .padding(dimens.space12),
            ) {
                Text(
                    text =
                        if (awaitingGas) {
                            "The gas hasn't arrived yet. Give it a moment and try again."
                        } else {
                            "Not enough ERTH for the fee. Watch a short ad and we'll cover it."
                        },
                    style = EarthTypography.textSm,
                    color = EarthAccent.warnInk,
                )
            }
            Box(Modifier.padding(top = dimens.space12)) {
                EarthButton(
                    text = if (awaitingGas) "Waiting for gas…" else "Watch an ad for gas",
                    onClick = onWatchAd,
                    isLoading = adLoading || awaitingGas,
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

/** uerth is micro-ERTH; six places, trimmed. */
internal fun formatErth(uerth: Long): String {
    val whole = uerth / 1_000_000
    val frac = (uerth % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return if (frac.isEmpty()) "$whole ERTH" else "$whole.$frac ERTH"
}
