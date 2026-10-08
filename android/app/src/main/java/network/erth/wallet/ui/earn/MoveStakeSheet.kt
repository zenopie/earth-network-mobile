package network.erth.wallet.ui.earn

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.components.EarthSheet
import network.erth.wallet.ui.components.asAmountInput
import network.erth.wallet.ui.components.toUerthOrNull

/**
 * Move stake: from one of your validators to another, with no unbonding gap
 * (MsgRedelegate). Laid out like Swap: the amount big, Max, where it leaves
 * and where it goes as two rows, one button.
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
    onConfirm: (src: String, dst: String, amountDerth: Long) -> Unit,
    onDismiss: () -> Unit,
    /** The validator it leaves, when a validator sheet's Move opened it. */
    initial: String? = null,
    /** A source row's line: what may move now, in ERTH. */
    sourceDetail: (DelegationRow) -> String = { "${stakeAmount(it.amountUerth)} derth" },
    /** A destination row's line: its commission. */
    destinationDetail: (DelegationRow) -> String = { "${"%.0f".format(it.commission * 100)}% commission" },
) {
    var src by remember { mutableStateOf(sources.firstOrNull { it.validatorOperator == initial } ?: sources.singleOrNull()) }
    var dst by remember { mutableStateOf<DelegationRow?>(null) }
    var amount by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: from, false: to

    val cap = src?.amountUerth ?: 0L
    val amountDerth = amount.toUerthOrNull()
    val error = if (amountDerth != null && amountDerth > cap) "More than available" else null

    EarthSheet(onDismiss = onDismiss) {
        when (picking) {
            true -> {
                SheetTitle("Move from")
                ValidatorPicker(sources, src?.validatorOperator, sourceDetail) { v ->
                    if (src?.validatorOperator != v.validatorOperator) amount = ""
                    src = v
                    if (dst?.validatorOperator == v.validatorOperator) dst = null
                    picking = null
                }
                return@EarthSheet
            }
            false -> {
                SheetTitle("Move to")
                ValidatorPicker(destinations.filter { it.validatorOperator != src?.validatorOperator }, dst?.validatorOperator, destinationDetail) { v ->
                    dst = v
                    picking = null
                }
                return@EarthSheet
            }
            null -> Unit
        }
        SheetTitle("Move")
        StakeAmountField(amount, { amount = it.asAmountInput(amount) }, "derth", cap, error)
        Spacer(Modifier.height(24.dp))
        ValidatorSelectRow("From", src?.moniker) { picking = true }
        Spacer(Modifier.height(8.dp))
        ValidatorSelectRow("To", dst?.moniker) { picking = false }
        Spacer(Modifier.height(32.dp))
        PillButton(
            text = "Move",
            enabled = src != null && dst != null && amountDerth != null && amountDerth > 0 && error == null,
        ) {
            val s = src
            val d = dst
            if (s != null && d != null && amountDerth != null && amountDerth > 0 && error == null) onConfirm(s.validatorOperator, d.validatorOperator, amountDerth)
        }
    }
}
