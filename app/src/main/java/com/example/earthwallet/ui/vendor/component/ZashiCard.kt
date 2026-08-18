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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.theme.colors.EarthColors

@Composable
fun EarthCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Color.Unspecified,
    colors: CardColors =
        CardDefaults.cardColors(
            containerColor = EarthColors.Surfaces.bgSecondary,
            contentColor = EarthColors.Text.textTertiary
        ),
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = colors,
        border =
            if (borderColor.isSpecified) {
                BorderStroke(1.dp, borderColor)
            } else {
                null
            }
    ) {
        Column(
            Modifier.padding(contentPadding)
        ) {
            content()
        }
    }
}
