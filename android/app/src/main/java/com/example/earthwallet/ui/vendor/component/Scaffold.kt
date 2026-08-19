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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.util.orDark

@Preview("Scaffold with blank background")
@Composable
private fun BlankBgScaffoldComposablePreview() {
    ZcashTheme(forceDarkMode = false) {
        BlankBgScaffold {
            Text(text = "Blank background scaffold")
        }
    }
}

@Composable
fun BlankBgScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = EarthColors.Surfaces.bgPrimary,
        topBar = topBar,
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        content = content,
        modifier = modifier,
    )
}

@Composable
fun GradientBgScaffold(
    startColor: Color,
    endColor: Color,
    modifier: Modifier = Modifier,
    startStop: Float = VERTICAL_GRADIENT_START_STOP,
    endStop: Float = VERTICAL_GRADIENT_END_STOP_LIGHT orDark VERTICAL_GRADIENT_END_STOP_DARK,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = topBar,
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        content = content,
        modifier =
            modifier
                .background(
                    earthVerticalGradient(
                        startColor = startColor,
                        endColor = endColor,
                        startStop = startStop,
                        endStop = endStop
                    )
                ),
    )
}
