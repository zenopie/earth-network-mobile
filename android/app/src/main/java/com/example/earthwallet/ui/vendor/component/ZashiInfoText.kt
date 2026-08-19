/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

@Composable
fun EarthInfoText(
    text: String,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    color: Color = EarthColors.Text.textTertiary,
    style: TextStyle = EarthTypography.textXs,
    textAlign: TextAlign = TextAlign.Start,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
) {
    Row(
        modifier = modifier,
        verticalAlignment = verticalAlignment
    ) {
        Image(
            modifier = Modifier,
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color)
        )
        Spacer(8.dp)
        Text(
            modifier =
                textModifier
                    .weight(1f, false),
            text = text,
            textAlign = textAlign,
            style = style,
            color = color
        )
    }
}

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        EarthInfoText("Text")
    }
