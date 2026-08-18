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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import network.erth.wallet.ui.vendor.theme.ZcashTheme

@Preview("Blank background")
@Composable
private fun BlankSurfacePreview() {
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            Text(
                text = "Test text on the blank app background",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun BlankSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        color = ZcashTheme.colors.backgroundColor,
        shape = RectangleShape,
        content = content,
        modifier = modifier
    )
}
