package network.erth.wallet.ui.earn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.erth.wallet.R
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.asAmountInput
import network.erth.wallet.ui.components.dismissKeyboardOnTap
import network.erth.wallet.ui.components.doneKeyboard
import network.erth.wallet.ui.components.formatUerth
import network.erth.wallet.ui.components.fromBaseUnits
import network.erth.wallet.ui.components.toUerthOrNull
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent

/**
 * An amount and a validator, laid out like Swap: the amount big, Max, the
 * validator as one row (its picker in place of the amount while choosing),
 * one button.
 *
 * One sheet for both directions (and for locking a position). Staking and
 * unstaking differ only in which list you choose from and what the cap is,
 * and two near-identical sheets is two places for the amount parsing to
 * drift apart.
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
    /** The validator to start on (a validator sheet's Add or Unstake). */
    initial: String? = null,
    /** The small line under a pickable row's name: its commission, or what you hold there. */
    detailFor: (DelegationRow) -> String = { "${"%.0f".format(it.commission * 100)}% commission" },
    /** What the cap is in ("ERTH", or "derth" to unstake). */
    unit: String = "ERTH",
    /** The validator row's small label ("To", "From"). */
    rowLabel: String = "Validator",
    /** Open on the picker when several can be chosen and none is (Unstake with more than one). */
    pickFirst: Boolean = false,
) {
    val pickable = choices.filter { it.pickable }
    var selected by remember {
        mutableStateOf(choices.firstOrNull { it.validatorOperator == initial && it.pickable } ?: pickable.singleOrNull())
    }
    // Several to choose from and none chosen: the picker first.
    var picking by remember { mutableStateOf(pickFirst && selected == null && pickable.size > 1) }
    var amount by remember { mutableStateOf("") }

    val cap = selected?.let(capFor) ?: 0L
    val amountUerth = amount.toUerthOrNull()
    val error = if (amountUerth != null && amountUerth > cap) "More than available" else null

    EarthSheet(onDismiss = onDismiss) {
        SheetTitle(if (picking) "Choose validator" else title)
        if (picking) {
            ValidatorPicker(choices, selected?.validatorOperator, detailFor) { v ->
                if (selected?.validatorOperator != v.validatorOperator) amount = ""
                selected = v
                picking = false
            }
            return@EarthSheet
        }
        StakeAmountField(amount, { amount = it.asAmountInput(amount) }, unit, cap, error)
        Spacer(Modifier.height(24.dp))
        ValidatorSelectRow(label = rowLabel, moniker = selected?.moniker) { picking = true }
        Spacer(Modifier.height(32.dp))
        PillButton(
            text = confirmLabel,
            enabled = selected != null && amountUerth != null && amountUerth > 0 && error == null,
        ) {
            val v = selected
            if (v != null && amountUerth != null && amountUerth > 0 && error == null) onConfirm(v.validatorOperator, amountUerth)
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(
        text = text,
        style = EarthTypography.textMd.copy(fontSize = 17.sp),
        fontWeight = FontWeight.SemiBold,
        color = EarthColors.Text.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().dismissKeyboardOnTap(),
    )
}

/** The amount, big and centred, with what is available and Max under it, and an error only when there is one. */
@Composable
internal fun StakeAmountField(value: String, onValueChange: (String) -> Unit, unit: String, available: Long, error: String?) {
    val keys = doneKeyboard(keyboardType = KeyboardType.Decimal)
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = EarthTypography.header2.copy(
                fontSize = if (value.length > 9) 40.sp else 56.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = EarthColors.Text.textPrimary,
            ),
            cursorBrush = SolidColor(EarthAccent.ink),
            keyboardOptions = keys.first,
            keyboardActions = keys.second,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        "0",
                        style = EarthTypography.header2.copy(fontSize = 56.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                        color = EarthColors.Text.textTertiary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                inner()
            },
        )
        Text(unit, style = EarthTypography.textMd, color = EarthColors.Text.textTertiary)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${formatUerth(available)} available", style = EarthTypography.textSm, color = EarthColors.Text.textTertiary)
            if (available > 0) {
                Text(
                    text = "Max",
                    style = EarthTypography.textXs,
                    fontWeight = FontWeight.SemiBold,
                    color = EarthColors.Btns.Secondary.btnSecondaryFg,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(EarthColors.Btns.Secondary.btnSecondaryBg)
                        .clickable { onValueChange(available.fromBaseUnits()) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            text = error ?: " ",
            style = EarthTypography.textSm,
            color = EarthColors.Utility.ErrorRed.utilityError700,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The validator, as one row in a Swap panel's style: tap to choose. */
@Composable
internal fun ValidatorSelectRow(label: String, moniker: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(EarthColors.Surfaces.bgSecondary)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ValidatorMark(moniker ?: "?", muted = moniker == null, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = EarthTypography.textXs, color = EarthColors.Text.textTertiary)
            Text(
                text = moniker ?: "Choose validator",
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = if (moniker == null) EarthColors.Text.textSecondary else EarthColors.Text.textPrimary,
                maxLines = 1,
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(EarthColors.Text.textTertiary),
        )
    }
}

/**
 * Choose a validator: initial, name, one small figure, a check on the chosen
 * one. Ones that cannot be picked are greyed with their standing.
 */
@Composable
internal fun ValidatorPicker(
    rows: List<DelegationRow>,
    selected: String?,
    detailFor: (DelegationRow) -> String,
    onPick: (DelegationRow) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    if (rows.isEmpty()) {
        Text("None", style = EarthTypography.textMd, color = EarthColors.Text.textTertiary, modifier = Modifier.padding(vertical = 32.dp))
    }
    rows.forEach { v ->
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (v.pickable) Modifier.clickable { onPick(v) } else Modifier)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ValidatorMark(v.moniker, muted = !v.pickable)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = v.moniker,
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = if (v.pickable) EarthColors.Text.textPrimary else EarthColors.Text.textTertiary,
                    maxLines = 1,
                )
                Text(
                    text = if (v.pickable) detailFor(v) else v.standing?.label.orEmpty(),
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary,
                    maxLines = 1,
                )
            }
            if (v.validatorOperator == selected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "✓",
                    style = EarthTypography.textSm,
                    fontWeight = FontWeight.Bold,
                    color = EarthColors.Surfaces.bgPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(22.dp).background(EarthAccent.ink, CircleShape),
                )
            }
        }
    }
}
