/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

private const val FULL_ROTATION_DEGREES = 360f

/**
 * Rotates the element in discrete jumps (e.g. a mechanical/ticking loading-icon look) rather than
 * a smooth continuous spin — the angle snaps by [stepDegrees] every [stepDurationMs].
 */
fun Modifier.steppedRotation(stepDegrees: Float = 45f, stepDurationMs: Long = 100L): Modifier =
    composed {
        var angle by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(stepDegrees, stepDurationMs) {
            while (true) {
                delay(stepDurationMs)
                angle = (angle + stepDegrees) % FULL_ROTATION_DEGREES
            }
        }
        graphicsLayer { rotationZ = angle }
    }
