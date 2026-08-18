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

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp
import android.os.Build

fun Modifier.blurCompat(
    radius: Dp,
    max: Dp
): Modifier =
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
        this.blur(radius)
    } else {
        val progression = 1 - (radius.value / max.value)
        this
            .alpha(progression)
    }
