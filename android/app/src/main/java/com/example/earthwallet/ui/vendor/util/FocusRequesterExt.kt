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

import androidx.compose.ui.focus.FocusRequester

/**
 * @see [FocusRequester.requestFocus]
 *
 * @return true if the focus was successfully requested, false if the focus request was canceled or null if request
 * focus failed
 */
fun FocusRequester.tryRequestFocus(): Boolean? =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        null
    }
