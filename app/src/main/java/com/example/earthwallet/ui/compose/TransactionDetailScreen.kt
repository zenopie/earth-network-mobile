package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * A single transaction.
 *
 * Their TransactionDetail leads with the amount and the state, then lists the
 * fields, then offers the explorer. Kept as is — the hash is the last thing
 * anyone reads and the first thing they need to copy, so it sits at the bottom
 * in a block sized for reading rather than inline in a row that truncates it.
 */
@Composable
fun TransactionDetailScreen(
    txHash: String,
    row: ActivityRow?,
    onOpenExplorer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(dimens.space24))

        Box(
            Modifier
                .size(dimens.space48)
                .background(
                    if (row?.failed == true) {
                        EarthColors.Utility.ErrorRed.utilityError50
                    } else {
                        EarthColors.Utility.SuccessGreen.utilitySuccess50
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (row?.failed == true) "✕" else "✓",
                style = EarthTypography.header5,
                color = if (row?.failed == true) {
                    EarthColors.Utility.ErrorRed.utilityError700
                } else {
                    EarthColors.Utility.SuccessGreen.utilitySuccess700
                },
            )
        }

        Spacer(Modifier.height(dimens.space12))
        Text(
            text = row?.amount ?: "—",
            style = EarthTypography.header2,
            color = EarthColors.Text.textPrimary,
        )
        Text(
            text = if (row?.failed == true) "Failed" else "Confirmed",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )

        Spacer(Modifier.height(dimens.space24))
        if (row != null) {
            EarthDetailRow("Type", row.kind.name)
            EarthDetailRow("Counterparty", row.counterparty)
            EarthDetailRow("When", row.timestamp)
        }

        Spacer(Modifier.height(dimens.space16))
        EarthLabel("Transaction hash")
        Spacer(Modifier.height(dimens.space4))
        EarthCodeBlock(txHash, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "View in explorer",
            onClick = onOpenExplorer,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}
