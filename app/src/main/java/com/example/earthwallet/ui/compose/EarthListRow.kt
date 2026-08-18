package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.theme.EarthTheme

/**
 * An asset or holding row: icon, name, subtitle, value.
 *
 * [iconBg] and [iconFg] take the domain tokens, so a staking row and an ANML row
 * are distinguishable before their labels are read.
 */
@Composable
fun EarthListRow(
    initial: String,
    name: String,
    subtitle: String?,
    value: String?,
    modifier: Modifier = Modifier,
    iconBg: Color? = null,
    iconFg: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(dimens.space32)
                .background(
                    iconBg ?: colors.Surfaces.bgTertiary,
                    RoundedCornerShape(dimens.radiusSm),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelLarge,
                color = iconFg ?: colors.Text.textPrimary,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = dimens.space12),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.Text.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.Text.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = colors.Text.textPrimary,
            )
        }
    }
}
