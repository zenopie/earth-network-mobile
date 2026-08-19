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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import network.erth.wallet.ui.vendor.theme.ZcashTheme

@Preview("Column with blank background")
@Composable
private fun BlankBgColumnComposablePreview() {
    ZcashTheme(forceDarkMode = false) {
        BlankBgColumn {
            Text(text = "Blank background column")
        }
    }
}

@Composable
fun BlankBgColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.then(Modifier.background(ZcashTheme.colors.backgroundColor)),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
