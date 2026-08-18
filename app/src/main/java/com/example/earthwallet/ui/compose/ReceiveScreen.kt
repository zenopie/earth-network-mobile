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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Receive.
 *
 * The layout is adapted from Zodl's receive screen — the address panel as a
 * large-radius card with the icon, title and subtitle stacked, and the warning
 * footer beneath. What changed is the content model: theirs carries one panel
 * per address type because Zcash has shielded and transparent addresses, and a
 * colour mode per account source because it also supports Keystone hardware
 * wallets. Earth has one address, so there is one panel and no colour mode.
 *
 * The address is shown in full and monospaced rather than truncated: this is
 * the screen where the whole string is the point.
 */
data class ReceiveUiState(
    val address: String,
    val label: String = "Earth address",
)

@Composable
fun ReceiveScreen(
    state: ReceiveUiState,
    modifier: Modifier = Modifier,
    /** Off when an ancestor already scrolls; two nested scrolls measure with an
     *  infinite height constraint and Compose throws rather than guessing. */
    scrollable: Boolean = true,
) {
    val dimens = EarthTheme.dimens
    val clipboard = LocalClipboardManager.current

    Column(
        modifier
            .fillMaxWidth()
            .background(EarthColors.Surfaces.bgPrimary)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(dimens.space16),
    ) {
        Text(
            text = "Receive",
            style = EarthTypography.header5.copy(color = EarthColors.Text.textPrimary),
            modifier = Modifier.padding(vertical = dimens.space12),
        )

        AddressPanel(
            label = state.label,
            address = state.address,
            onClick = { clipboard.setText(AnnotatedString(state.address)) },
        )

        Spacer(Modifier.height(dimens.space24))
        Text(
            text = "Only send ERTH and Earth-network tokens to this address. " +
                "Anything sent from another chain will not arrive.",
            color = EarthColors.Text.textTertiary,
            style = EarthTypography.textSm,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = dimens.space16)
                .fillMaxWidth(),
        )
    }
}

/**
 * The address card.
 *
 * Their AddressPanel switches container, button and text colour on a colour
 * mode; ours takes the surface tokens, since there is only one kind of address
 * to show.
 */
@Composable
private fun AddressPanel(
    label: String,
    address: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    val shape = RoundedCornerShape(EarthDimensions.Radius.radius3xl)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(EarthColors.Surfaces.bgSecondary, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(dimens.space16),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(EarthAccent.tint, RoundedCornerShape(dimens.radiusMd)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "E",
                    style = EarthTypography.textMd.copy(color = EarthAccent.ink),
                )
            }
            Spacer(Modifier.width(dimens.space12))
            Column {
                Text(
                    text = label,
                    color = EarthColors.Text.textPrimary,
                    style = EarthTypography.textMd,
                )
                Text(
                    text = "Tap to copy",
                    color = EarthColors.Text.textTertiary,
                    style = EarthTypography.textSm,
                )
            }
        }
        Spacer(Modifier.height(dimens.space16))
        Text(
            text = address,
            color = EarthColors.Text.textPrimary,
            style = EarthTypography.textSm,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
