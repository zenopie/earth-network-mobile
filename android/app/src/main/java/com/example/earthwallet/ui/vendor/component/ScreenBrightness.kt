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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ScreenBrightnessState { FULL, NORMAL }

class ScreenBrightness {
    private val mutableSwitch: MutableStateFlow<ScreenBrightnessState> = MutableStateFlow(ScreenBrightnessState.NORMAL)

    val referenceSwitch = mutableSwitch.asStateFlow()

    fun fullBrightness() = mutableSwitch.update { ScreenBrightnessState.FULL }

    fun restoreBrightness() = mutableSwitch.update { ScreenBrightnessState.NORMAL }
}

@Suppress("CompositionLocalAllowlist")
val LocalScreenBrightness = staticCompositionLocalOf { ScreenBrightness() }

@Composable
fun BrightenScreen() {
    val screenBrightness = LocalScreenBrightness.current
    DisposableEffect(screenBrightness) {
        screenBrightness.fullBrightness()
        onDispose { screenBrightness.restoreBrightness() }
    }
}

@Composable
fun RestoreScreenBrightness() {
    val screenBrightness = LocalScreenBrightness.current
    screenBrightness.restoreBrightness()
}
