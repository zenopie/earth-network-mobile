package network.erth.wallet.ui.earn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import network.erth.wallet.ui.components.asAmountInput
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.components.EarthSheet
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
    /** A line under the amount (staked ERTH is owner-locked: it can only be unstaked). */
    note: String? = null,
    onConfirm: (validator: String, amountUerth: Long) -> Unit,
    onDismiss: () -> Unit,
    /** The validator to start on (a card's Add or Unstake). */
    initial: String? = null,
    /** The line under a pickable row's name: its terms, or what you hold there. */
    detailFor: (DelegationRow) -> String = { "${"%.0f".format(it.commission * 100)}% commission" },
    /** What the cap is in ("ERTH", or "derth" to unstake). */
    unit: String = "ERTH",
) {
    val dimens = EarthTheme.dimens
    val amountKeys = doneKeyboard(keyboardType = KeyboardType.Decimal)
    var selected by remember { mutableStateOf(choices.firstOrNull { it.validatorOperator == initial && it.pickable } ?: choices.firstOrNull { it.pickable }.takeIf { initial == null && choices.count { c -> c.pickable } == 1 }) }
    var amount by remember { mutableStateOf("") }

    val cap = selected?.let(capFor) ?: 0L
    val amountUerth = amount.toUerthOrNull()
    val error = when {
        amount.isEmpty() -> null
        amountUerth == null -> "Enter an amount, for example 1.5."
        amountUerth <= 0 -> "Enter more than zero."
        amountUerth > cap -> "That is more than ${formatUerth(cap)} $unit."
        else -> null
    }

    EarthSheet(onDismiss = onDismiss) {
        Text(
            modifier = Modifier.dismissKeyboardOnTap(),
            text = title,
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )

        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Validator")
        Spacer(Modifier.height(dimens.space8))

        choices.forEach { v ->
            ValidatorPickRow(
                row = v,
                detail = if (v.pickable) detailFor(v) else v.standing?.reason.orEmpty(),
                selected = v.validatorOperator == selected?.validatorOperator,
                onClick = { if (selected?.validatorOperator != v.validatorOperator) amount = ""; selected = v },
            )
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
            suffix = { Text(unit) },
            keyboardOptions = amountKeys.first,
            keyboardActions = amountKeys.second,
        )

        Spacer(Modifier.height(dimens.space8))
        Text(
            text = if (unit == "ERTH") "From private ERTH · ${formatUerth(cap)} available" else "Staked ${formatUerth(cap)} $unit",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
            modifier = Modifier.clickable { amount = cap.fromBaseUnits() },
        )
        note?.let {
            Spacer(Modifier.height(dimens.space8))
            Text(text = it, style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)
        }

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

/**
 * A validator in a picker: its initial, name, one line on its terms or why it
 * cannot be picked, and its standing where the row is from the whole list.
 */
@Composable
internal fun ValidatorPickRow(row: DelegationRow, detail: String, selected: Boolean, onClick: () -> Unit) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(dimens.space12)
    val enabled = row.pickable
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) EarthAccent.tint else EarthColors.Surfaces.bgSecondary, shape)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) EarthAccent.ink else Color.Transparent, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ValidatorMark(row.moniker, muted = !enabled, size = 36.dp)
        Spacer(Modifier.width(dimens.space12))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.moniker,
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) EarthColors.Text.textPrimary else EarthColors.Text.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                row.standing?.let { s ->
                    val active = s == network.erth.wallet.privacy.StakeRound.Standing.ACTIVE
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = s.label,
                        style = EarthTypography.textXs,
                        color = if (active) EarthAccent.ink else EarthColors.Text.textTertiary,
                        modifier = Modifier
                            .background(if (active) EarthAccent.tint else EarthColors.Surfaces.bgTertiary, RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (detail.isNotEmpty()) {
                Text(text = detail, style = EarthTypography.textXs, color = if (enabled) EarthColors.Text.textTertiary else EarthColors.Text.textSecondary)
            }
        }
        if (enabled) {
            Spacer(Modifier.width(dimens.space8))
            Box(
                Modifier
                    .size(20.dp)
                    .background(if (selected) EarthAccent.ink else Color.Transparent, CircleShape)
                    .border(1.5.dp, if (selected) EarthAccent.ink else EarthColors.Surfaces.strokePrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", style = EarthTypography.textXs, color = Color.White)
            }
        }
    }
}
