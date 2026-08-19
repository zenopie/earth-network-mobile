package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * The wallets in this install.
 *
 * A list with the current one marked, and two ways to add another. Switching is
 * the whole reason the screen exists, so it happens on a tap of the row rather
 * than behind a menu — there is nothing else a row could usefully do.
 *
 * No delete. The old app had one and it removed the only copy of a mnemonic
 * behind a single confirm; until this can show the phrase and make you
 * acknowledge you have it, leaving a wallet in the list costs nothing and
 * removing one can cost everything.
 */
@Composable
fun WalletsScreen(
    state: WalletsUiState?,
    onSelect: (Int) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space8))

        state?.wallets?.forEachIndexed { index, wallet ->
            val isSelected = index == state.selectedIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(index) }
                    .padding(vertical = dimens.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(dimens.space32)
                        .background(
                            if (isSelected) EarthAccent.tint else EarthColors.Surfaces.bgSecondary,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = wallet.name.take(1).uppercase(),
                        style = EarthTypography.textSm,
                        color = if (isSelected) {
                            EarthAccent.ink
                        } else {
                            EarthColors.Text.textTertiary
                        },
                    )
                }
                Column(Modifier.weight(1f).padding(start = dimens.space12)) {
                    Text(
                        text = wallet.name,
                        style = EarthTypography.textMd,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = EarthColors.Text.textPrimary,
                    )
                    Text(
                        text = wallet.address,
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (isSelected) {
                    Text(
                        text = "✓",
                        style = EarthTypography.textMd,
                        color = EarthAccent.ink,
                    )
                }
            }
        }

        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "Create a wallet",
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space8))
        EarthButton(
            text = "Import a recovery phrase",
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
            colors = EarthButtonDefaults.secondaryColors(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}
