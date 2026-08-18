package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Send.
 *
 * Validation is reported inline on the field that is wrong, not as a sheet or a
 * vanished toast — the user is looking at the field, so that is where the
 * answer belongs. Sheets are for things that happen after a signature.
 */
@Composable
fun SendScreen(
    recipient: String,
    onRecipientChange: (String) -> Unit,
    amount: String,
    onAmountChange: (String) -> Unit,
    balanceLabel: String,
    denom: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    recipientError: String? = null,
    amountError: String? = null,
    sending: Boolean = false,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val ready = recipient.isNotBlank() && amount.isNotBlank() &&
        recipientError == null && amountError == null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = dimens.gutter)
            .padding(top = dimens.space24, bottom = dimens.space32),
    ) {
        Text(
            text = "Send",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space24))

        EarthTextField(
            value = recipient,
            onValueChange = onRecipientChange,
            label = "To",
            hint = "earth1…",
            error = recipientError,
        )
        Spacer(Modifier.height(dimens.space16))

        EarthTextField(
            value = amount,
            onValueChange = onAmountChange,
            label = "Amount",
            hint = "0.00",
            error = amountError,
            numeric = true,
            trailing = {
                Text(
                    text = denom,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text.textSecondary,
                )
            },
        )
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = balanceLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space32))
        EarthButton("Review", onSend, enabled = ready, loading = sending)
    }
}
