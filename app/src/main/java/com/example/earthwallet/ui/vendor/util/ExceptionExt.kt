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

fun Throwable.getCausesAsSequence(): Sequence<Exception> =
    sequence {
        var current: Exception? = this@getCausesAsSequence as? Exception
        while (current != null) {
            yield(current)
            current = current.cause as? Exception
        }
    }
