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

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.util.Log

/**
 * This operation performs calculation of the screen height.
 *
 * @return [ScreenHeight] a wrapper object of the calculated heights in density pixels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun screenHeight(): ScreenHeight {
    val configuration = LocalConfiguration.current

    val statusBars = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    Log.d("Earth", ("Screen height: Status bar height raw: $statusBars").toString())

    val navigationBars = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    Log.d("Earth", ("Screen height: Navigation bar height raw: $navigationBars").toString())

    val contentHeight = configuration.screenHeightDp.dp
    Log.d("Earth", ("Screen height: Screen content height: $contentHeight").toString())

    val statusBarHeight =
        statusBars.run {
            if (value <= 0f) {
                24.dp
            } else {
                this
            }
        }
    Log.d("Earth", ("Screen height: Status bar height: $statusBarHeight").toString())

    val navigationBarHeight =
        navigationBars.run {
            if (value <= 0f) {
                88.dp
            } else {
                this
            }
        }
    Log.d("Earth", ("Screen height: Navigation bar height: $navigationBarHeight").toString())

    return ScreenHeight(
        contentHeight = contentHeight,
        systemStatusBarHeight = statusBarHeight,
        systemNavigationBarHeight = navigationBarHeight
    )
}

data class ScreenHeight(
    val contentHeight: Dp,
    val systemStatusBarHeight: Dp,
    val systemNavigationBarHeight: Dp
) {
    fun overallScreenHeight(): Dp =
        (contentHeight + systemBarsHeight()).also {
            Log.d("Earth", ("Screen height: Overall height: $it").toString())
        }

    fun systemBarsHeight(): Dp =
        (systemStatusBarHeight + systemNavigationBarHeight).also {
            Log.d("Earth", ("Screen height: System bars height: $it").toString())
        }
}
