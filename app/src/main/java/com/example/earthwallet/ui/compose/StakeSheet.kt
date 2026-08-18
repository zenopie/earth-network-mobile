package network.erth.wallet.ui.compose

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthTextField
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Pick a validator, then an amount.
 *
 * One sheet for both directions. Staking and unstaking differ only in which
 * list you choose from and what the cap is, and two near-identical sheets is
 * two places for the amount parsing to drift apart.
 *
 * Validators are listed with their commission because that is the only figure
 * that differs between them from a delegator's side, and unsorted rather than
 * ranked — ranking by stake concentrates it further, which is the opposite of
 * what a delegator picking a validator should be nudged toward.
 */
@Composable
fun StakeSheet(
    title: String,
    /** What can be chosen: the bonded set to stake, your own delegations to unstake. */
    choices: List<DelegationRow>,
    /** Cap in uerth: spendable balance to stake, the delegation to unstake. */
    capFor: (DelegationRow) -> Long,
    confirmLabel: String,
    onConfirm: (validator: String, amountUerth: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = EarthTheme.dimens
    var selected by remember { mutableStateOf(choices.firstOrNull()) }
    var amount by remember { mutableStateOf("") }

    val cap = selected?.let(capFor) ?: 0L
    val amountUerth = amount.toUerthOrNull()
    val error = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount, for example 1.5."
        amountUerth <= 0 -> "Enter more than zero."
        amountUerth > cap -> "That is more than ${formatUerth(cap)} ERTH."
        else -> null
    }

    EarthSheet(onDismiss = onDismiss) {
        Text(
            text = title,
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )

        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Validator")
        Spacer(Modifier.height(dimens.space8))

        choices.forEach { v ->
            val isSelected = v.validatorOperator == selected?.validatorOperator
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) {
                            EarthAccent.tint
                        } else {
                            EarthColors.Surfaces.bgSecondary
                        },
                        RoundedCornerShape(dimens.space12),
                    )
                    .clickable { selected = v; amount = "" }
                    .padding(dimens.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = v.moniker,
                        style = EarthTypography.textMd,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = EarthColors.Text.textPrimary,
                    )
                    Text(
                        text = "${"%.0f".format(v.commission * 100)}% commission",
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textTertiary,
                    )
                }
                Text(
                    text = formatUerth(capFor(v)),
                    style = EarthTypography.textSm,
                    color = EarthColors.Text.textTertiary,
                )
            }
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
            suffix = { Text("ERTH") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        Spacer(Modifier.height(dimens.space8))
        Text(
            text = "Available ${formatUerth(cap)} ERTH",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
            modifier = Modifier.clickable { amount = cap.asDecimal() },
        )

        Spacer(Modifier.height(dimens.space16))
        EarthButton(
            text = confirmLabel,
            onClick = {
                val v = selected
                if (v != null && amountUerth != null && error == null) {
                    onConfirm(v.validatorOperator, amountUerth)
                }
            },
            enabled = selected != null && amountUerth != null && error == null,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space16))
    }
}

/** ERTH as typed, to uerth. Null when it is not a number. */
internal fun String.toUerthOrNull(): Long? =
    runCatching { java.math.BigDecimal(this).movePointRight(6).toLong() }.getOrNull()

/** uerth back to a plain decimal, for filling the field from "max". */
private fun Long.asDecimal(): String =
    java.math.BigDecimal(this).movePointLeft(6).stripTrailingZeros().toPlainString()
