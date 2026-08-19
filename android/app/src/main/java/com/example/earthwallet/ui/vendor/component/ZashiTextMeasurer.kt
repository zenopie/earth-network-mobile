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

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

@Composable
fun measureTextStyle(
    style: TextStyle,
    text: String = "a",
): TextLayoutResult {
    val bulletTextMeasurer = rememberTextMeasurer()
    return bulletTextMeasurer.measure(text = text, style = style)
}

val IntSize.widthDp: Dp
    @Composable
    get() = with(LocalDensity.current) { width.toDp() }

val IntSize.heightDp: Dp
    @Composable
    get() = with(LocalDensity.current) { height.toDp() }
