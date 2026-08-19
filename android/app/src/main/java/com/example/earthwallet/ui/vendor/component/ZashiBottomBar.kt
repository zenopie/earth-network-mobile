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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthBottomBar(
    isElevated: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shadowElevation = if (isElevated) 4.dp else 0.dp,
        color = EarthColors.Surfaces.bgPrimary,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            content()
            Spacer(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
        }
    }
}

@PreviewScreens
@Composable
private fun BottomBarPreview() =
    ZcashTheme {
        EarthBottomBar(
            isElevated = true
        ) {
            EarthButton(
                state = ButtonState(text = stringRes("Save Button")),
                modifier =
                    Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
            )
        }
    }
