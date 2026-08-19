/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.animation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.navigation.NavBackStackEntry

object ScreenAnimation {
    private const val DURATION = 400

    fun AnimatedContentTransitionScope<NavBackStackEntry>.enterTransition() =
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            initialOffset = { it },
            animationSpec = tween()
        )

    fun AnimatedContentTransitionScope<NavBackStackEntry>.exitTransition() =
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            targetOffset = { it - (it * .775f).toInt() },
            animationSpec = tween()
        )

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnterTransition() =
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            initialOffset = { it - (it * .775f).toInt() },
            animationSpec = tween()
        )

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popExitTransition() =
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            targetOffset = { it },
            animationSpec = tween()
        )

    private fun <T> tween(): TweenSpec<T> = tween(durationMillis = DURATION)
}
