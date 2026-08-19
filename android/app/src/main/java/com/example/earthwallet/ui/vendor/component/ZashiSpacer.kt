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

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
fun ColumnScope.Spacer(height: Dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun ColumnScope.Spacer(weight: Float) {
    Spacer(Modifier.weight(weight))
}

@Composable
fun RowScope.Spacer(weight: Float) {
    Spacer(Modifier.weight(weight))
}

@Composable
fun RowScope.Spacer(width: Dp) {
    Spacer(Modifier.width(width))
}

@Composable
fun HorizontalSpacer(width: Dp) {
    Spacer(Modifier.width(width))
}

@Composable
fun VerticalSpacer(height: Dp) {
    Spacer(Modifier.height(height))
}
