package network.erth.wallet.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import network.erth.wallet.ui.vendor.component.EarthTextField
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.component.EarthCard
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = dimens.gutter)
            .padding(top = dimens.space24, bottom = dimens.space32),
    ) {
        Text(
            text = "Send",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space24))

        EarthLabel("To")
        EarthTextField(
            value = recipient,
            onValueChange = onRecipientChange,
            error = recipientError,
            placeholder = { Text("earth1…", style = EarthTypography.textMd) },
            modifier = Modifier.fillMaxWidth(),
            // No capitalisation, no autocorrect: bech32 is lowercase, and a
            // keyboard that helpfully capitalises the first letter produces an
            // address that fails checksum for a reason nobody can see.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
        )
        Spacer(Modifier.height(dimens.space16))

        EarthLabel("Amount")
        EarthTextField(
            value = amount,
            onValueChange = onAmountChange,
            error = amountError,
            placeholder = { Text("0.00", style = EarthTypography.textMd) },
            suffix = { Text(denom, style = EarthTypography.textMd) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = balanceLabel,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space32))
        EarthButton("Review", onSend, enabled = ready, isLoading = sending,
            colors = brandButtonColors(),
        )
    }
}
