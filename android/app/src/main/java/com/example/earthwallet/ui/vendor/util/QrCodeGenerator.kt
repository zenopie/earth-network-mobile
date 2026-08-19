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

interface QrCodeGenerator {
    /**
     * @param data Data to encode into the QR code.
     * @param sizePixels Size in pixels of the QR code.
     * @return A QR code pixel matrix, represented as an array of booleans where false is white and true is black.
     */
    fun generate(
        data: String,
        sizePixels: Int
    ): BooleanArray
}
