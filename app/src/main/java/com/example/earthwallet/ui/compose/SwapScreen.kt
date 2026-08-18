package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthIconButton
import network.erth.wallet.ui.vendor.component.EarthTextField
import network.erth.wallet.ui.vendor.component.IconButtonState
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.stringRes

/**
 * Swap, against the ERTH/ANML pool.
 *
 * Their swap screen quotes across chains through a provider, with a route, a
 * slippage setting and a quote that expires. Earth's pool is on the same chain
 * and prices itself, so what survives is the composition: two stacked panels
 * for what goes in and what comes out, with a circular flip button straddling
 * the seam. The flip button is the reason the panels are stacked rather than
 * side by side — it has to sit on the boundary to read as reversing it.
 */
@Composable
fun SwapScreen(
    erthBalance: String,
    anmlBalance: String,
    modifier: Modifier = Modifier,
    onSwap: (from: String, amount: String) -> Unit = { _, _ -> },
) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)

    var erthIn by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf("") }

    val fromDenom = if (erthIn) "ERTH" else "ANML"
    val toDenom = if (erthIn) "ANML" else "ERTH"
    val fromBalance = if (erthIn) erthBalance else anmlBalance
    val toBalance = if (erthIn) anmlBalance else erthBalance

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space16))

        Box {
            Column {
                SwapPanel(
                    label = "You pay",
                    denom = fromDenom,
                    balance = fromBalance,
                    shape = shape,
                    value = amount,
                    onValueChange = { amount = it },
                )
                Spacer(Modifier.height(dimens.space8))
                SwapPanel(
                    label = "You receive",
                    denom = toDenom,
                    balance = toBalance,
                    shape = shape,
                    value = "",
                    onValueChange = {},
                    readOnly = true,
                )
            }

            // Straddles the seam between the two panels: half its height sits
            // in each, which is what makes it read as reversing them rather
            // than as an action on the panel above.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(dimens.space48)
                    .background(EarthColors.Surfaces.bgPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                EarthIconButton(
                    state = IconButtonState(
                        icon = network.erth.wallet.R.drawable.ic_swap_toggle,
                        contentDescription = stringRes("Reverse the swap"),
                        onClick = { erthIn = !erthIn },
                    ),
                )
            }
        }

        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "Review swap",
            onClick = { onSwap(fromDenom, amount) },
            enabled = amount.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}

@Composable
private fun SwapPanel(
    label: String,
    denom: String,
    balance: String,
    shape: androidx.compose.ui.graphics.Shape,
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean = false,
) {
    val dimens = EarthTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(EarthColors.Surfaces.bgSecondary, shape)
            .padding(dimens.space16),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EarthLabel(label)
            Spacer(Modifier.weight(1f))
            Text(
                text = "Balance $balance",
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary,
            )
        }
        Spacer(Modifier.height(dimens.space8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (readOnly) {
                    Text(
                        text = value.ifEmpty { "0" },
                        style = EarthTypography.header5,
                        color = EarthColors.Text.textTertiary,
                    )
                } else {
                    EarthTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text("0") },
                    )
                }
            }
            Spacer(Modifier.width(dimens.space12))
            Text(
                text = denom,
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = EarthColors.Text.textPrimary,
            )
        }
    }
}
