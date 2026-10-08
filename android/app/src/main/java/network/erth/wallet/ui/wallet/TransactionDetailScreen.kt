package network.erth.wallet.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.components.EarthCodeBlock
import network.erth.wallet.ui.components.EarthLabel
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.component.EarthButtonDefaults
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.theme.EarthTheme

/**
 * A transaction from the explorer: its hash and a link out. The wallet's own
 * activity opens in [ActivityDetailSheet] instead, with all it knows.
 */
@Composable
fun TransactionDetailScreen(
    txHash: String,
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
