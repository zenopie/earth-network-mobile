package network.erth.wallet.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import network.erth.wallet.privacy.tx.ShieldMove
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.asAmountInput
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.dismissKeyboardOnTap
import network.erth.wallet.ui.components.doneKeyboard
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.components.toUerthOrNull
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthTextField
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/** Which way ERTH moves between the public account and this wallet's private notes. */
enum class MoveDirection { Shield, Unshield }

/**
 * Moves ERTH between this wallet's public account and its private notes.
 *
 * Shield is MsgShield, signed by the account (the coins are its), the note to
 * this wallet's own shielded address. Unshield is a private transfer out of
 * the notes to this wallet's own account, its fee from the same notes. One
 * sheet for both, because the fields are the same and the difference is
 * which balance the amount comes out of.
 */
@Composable
fun MoveSheet(
    initial: MoveDirection,
    publicUerth: Long,
    privateUerth: Long,
    /** The three largest ERTH notes: what one unshield can spend, fee included. */
    unshieldableUerth: Long,
    /** The signed MsgShield's fee. */
    shieldFee: Long,
    /** The private transfer's estimated fee, as its confirm sheet shows it. */
    unshieldFee: Long,
    onConfirm: (MoveDirection, amountUerth: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    val amountKeys = doneKeyboard(keyboardType = KeyboardType.Decimal)
    var direction by remember { mutableStateOf(initial) }
    var amount by remember { mutableStateOf("") }

    val shielding = direction == MoveDirection.Shield
    val cap = if (shielding) {
        ShieldMove.maxShield(publicUerth, shieldFee)
    } else {
        ShieldMove.maxUnshield(unshieldableUerth, unshieldFee)
    }
    val amountUerth = amount.toUerthOrNull()
    val error = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount, for example 1.5."
        amountUerth <= 0 -> "Enter more than zero."
        amountUerth > cap -> "That is more than ${formatUerth(cap)} ERTH, the most this can move after its fee."
        else -> null
    }

    EarthSheet(onDismiss = onDismiss) {
        Text(
            modifier = Modifier.dismissKeyboardOnTap(),
            text = if (shielding) "Shield ERTH" else "Unshield ERTH",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space16))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.space8)) {
            MoveDirection.entries.forEach { d ->
                val selected = d == direction
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (selected) EarthAccent.tint else EarthColors.Surfaces.bgSecondary,
                            RoundedCornerShape(dimens.space12),
                        )
                        .clickable { direction = d; amount = "" }
                        .padding(dimens.space12),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (d == MoveDirection.Shield) "Shield" else "Unshield",
                        style = EarthTypography.textMd,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = EarthColors.Text.textPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(dimens.space12))
        Text(
            text = if (shielding) {
                "Public → Private. The amount is public as it enters; after that, what you do with it is not."
            } else {
                "Private → Public, to this wallet's own account. The amount is public as it leaves."
            },
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Amount")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = amount,
            onValueChange = { amount = it.asAmountInput(amount) },
            modifier = Modifier.fillMaxWidth(),
            error = error,
            placeholder = { Text("0") },
            suffix = { Text("ERTH") },
            keyboardOptions = amountKeys.first,
            keyboardActions = amountKeys.second,
        )
        Spacer(Modifier.height(dimens.space8))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (shielding) "Public ${formatUerth(publicUerth)} ERTH" else "Private ${formatUerth(privateUerth)} ERTH",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Max",
                style = EarthTypography.textSm.copy(fontWeight = FontWeight.SemiBold),
                color = EarthAccent.ink,
                modifier = Modifier
                    .clickable { amount = cap.asDecimal() }
                    .padding(dimens.space4),
            )
        }
        if (!shielding && unshieldableUerth < privateUerth) {
            // One unshield spends at most three notes.
            Spacer(Modifier.height(dimens.space4))
            Text(
                text = "Your private ERTH is spread over many notes. Merge them in Settings → Shielded notes to move more at once.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
        }

        Spacer(Modifier.height(dimens.space12))
        Text(
            text = "Private ERTH can't be seen on-chain; public ERTH is needed for Keplr, exchanges and validator actions.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = if (shielding) "Review shield" else "Review unshield",
            onClick = { if (amountUerth != null && error == null) onConfirm(direction, amountUerth) },
            enabled = amountUerth != null && amountUerth > 0 && error == null,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space16))
    }
}

/** uerth back to a plain decimal, for filling the field from "max". */
private fun Long.asDecimal(): String =
    java.math.BigDecimal(this).movePointLeft(6).stripTrailingZeros().toPlainString()
