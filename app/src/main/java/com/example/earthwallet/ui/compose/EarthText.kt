package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.theme.EarthTheme

/** Uppercase eyebrow: "TOTAL BALANCE", "NETWORK FEE". */
@Composable
fun EarthLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = EarthTheme.colors.Text.textSecondary,
        modifier = modifier,
    )
}

/**
 * The balance.
 *
 * Deliberately the loudest thing in the app. Most of what makes a wallet feel
 * simple is one number you cannot miss and everything else stepping back.
 */
@Composable
fun EarthAmount(
    amount: String,
    modifier: Modifier = Modifier,
    denom: String? = null,
) {
    val colors = EarthTheme.colors
    Column(modifier) {
        Text(
            text = amount,
            style = MaterialTheme.typography.displayLarge,
            color = colors.Text.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (denom != null) {
            Text(
                text = denom,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.Text.textSecondary,
            )
        }
    }
}

/** A label/value line — fee, amount, balance-after. */
@Composable
fun EarthDetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.Text.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = colors.Text.textPrimary,
        )
    }
}

/**
 * Monospace block for a chain error or a transaction hash.
 *
 * Selectable, because the whole point of showing it is that the user can take
 * it somewhere else.
 */
@Composable
fun EarthCodeBlock(text: String, modifier: Modifier = Modifier) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.Text.textSecondary,
            modifier = modifier
                .fillMaxWidth()
                .background(colors.Surfaces.bgSecondary, RoundedCornerShape(dimens.radiusSm))
                .border(dimens.strokeWidth, colors.Surfaces.strokeSecondary, RoundedCornerShape(dimens.radiusSm))
                .padding(dimens.space12),
        )
    }
}
