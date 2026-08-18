package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Receive.
 *
 * The address is shown in full and monospaced rather than truncated: this is
 * the one screen where the whole string is the point, and an ellipsis in the
 * middle of an address the user is about to check character by character is
 * actively unhelpful.
 */
@Composable
fun ReceiveScreen(
    address: String,
    modifier: Modifier = Modifier,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.Surfaces.bgPrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = dimens.gutter)
            .padding(top = dimens.space24, bottom = dimens.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Receive",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.Text.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(dimens.space24))
        Text(
            text = "Your Earth address",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.Text.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(dimens.space12))
        EarthCodeBlock(address)
        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "Copy address",
            onClick = { clipboard.setText(AnnotatedString(address)) },
        )
    }
}
