package network.erth.wallet.ui.earn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.asAmountInput
import network.erth.wallet.ui.components.brandButtonColors
import network.erth.wallet.ui.components.dismissKeyboardOnTap
import network.erth.wallet.ui.components.doneKeyboard
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.components.fromBaseUnits
import network.erth.wallet.ui.components.toUerthOrNull
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthTextField
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Move stake: from one of your validators to another, with no unbonding gap
 * (MsgRedelegate). Pick where it leaves, where it goes, then how much.
 *
 * [sources] carry what may move now as their amount: stake moved in recently
 * stays where it is until its window closes, so it is not offered. The
 * destinations are the bonded set less the source, unsorted, as on the stake
 * sheet. The confirm sheet that follows shows the chain's own numbers (what
 * arrives, at the live rates).
 */
@Composable
fun MoveStakeSheet(
    sources: List<DelegationRow>,
    destinations: List<DelegationRow>,
    /** A line under the amount: what moving does to the moved stake. */
    note: String,
    onConfirm: (src: String, dst: String, amountDerth: Long) -> Unit,
    onDismiss: () -> Unit,
    /** The validator it leaves, when a card's Move opened it. */
    initial: String? = null,
    /** A source row's line: what may move now, in ERTH. */
    sourceDetail: (DelegationRow) -> String = { "${formatUerth(it.amountUerth)} derth can move now" },
    /** A destination row's line: its terms. */
    destinationDetail: (DelegationRow) -> String = { "${"%.0f".format(it.commission * 100)}% commission" },
) {
    val dimens = EarthTheme.dimens
    val amountKeys = doneKeyboard(keyboardType = KeyboardType.Decimal)
    var src by remember { mutableStateOf(sources.firstOrNull { it.validatorOperator == initial } ?: sources.firstOrNull()) }
    var dst by remember { mutableStateOf<DelegationRow?>(null) }
    var amount by remember { mutableStateOf("") }

    val cap = src?.amountUerth ?: 0L
    val amountDerth = amount.toUerthOrNull()
    val error = when {
        amount.isEmpty() -> null
        amountDerth == null -> "Enter an amount, for example 1.5."
        amountDerth <= 0 -> "Enter more than zero."
        amountDerth > cap -> "That is more than the ${formatUerth(cap)} derth that can move now."
        else -> null
    }

    EarthSheet(onDismiss = onDismiss) {
        Text(
            modifier = Modifier.dismissKeyboardOnTap(),
            text = "Move stake",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )

        Spacer(Modifier.height(dimens.space16))
        EarthLabel("From")
        Spacer(Modifier.height(dimens.space8))
        sources.forEach { v ->
            ValidatorPickRow(v, sourceDetail(v), v.validatorOperator == src?.validatorOperator) {
                if (src?.validatorOperator != v.validatorOperator) amount = ""
                src = v
                if (dst?.validatorOperator == v.validatorOperator) dst = null
            }
            Spacer(Modifier.height(dimens.space8))
        }

        Spacer(Modifier.height(dimens.space8))
        EarthLabel("To")
        Spacer(Modifier.height(dimens.space8))
        destinations.filter { it.validatorOperator != src?.validatorOperator }.forEach { v ->
            ValidatorPickRow(v, destinationDetail(v), v.validatorOperator == dst?.validatorOperator) { dst = v }
            Spacer(Modifier.height(dimens.space8))
        }

        Spacer(Modifier.height(dimens.space8))
        EarthLabel("Amount")
        Spacer(Modifier.height(dimens.space8))
        EarthTextField(
            value = amount,
            onValueChange = { amount = it.asAmountInput(amount) },
            modifier = Modifier.fillMaxWidth(),
            error = error,
            placeholder = { Text("0") },
            suffix = { Text("derth") },
            keyboardOptions = amountKeys.first,
            keyboardActions = amountKeys.second,
        )
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = "Can move now ${formatUerth(cap)} derth",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
            modifier = Modifier.clickable { amount = cap.fromBaseUnits() },
        )
        Spacer(Modifier.height(dimens.space8))
        Text(text = note, style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = "Review move",
            onClick = {
                val s = src
                val d = dst
                if (s != null && d != null && amountDerth != null && error == null) onConfirm(s.validatorOperator, d.validatorOperator, amountDerth)
            },
            enabled = src != null && dst != null && amountDerth != null && error == null,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space16))
    }
}
