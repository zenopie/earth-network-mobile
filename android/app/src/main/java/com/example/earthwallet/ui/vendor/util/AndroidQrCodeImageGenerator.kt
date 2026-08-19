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

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

object AndroidQrCodeImageGenerator : QrCodeImageGenerator {
    override fun generate(
        bitArray: BooleanArray,
        sizePixels: Int,
        colors: QrCodeColors
    ): Bitmap {
        val colorArray =
            bitArray.toThemeColorArray(
                foreground = colors.foreground.toArgb(),
                background = colors.background.toArgb()
            )
        return Bitmap
            .createBitmap(colorArray, sizePixels, sizePixels, Bitmap.Config.ARGB_8888)
    }

    override fun generate(
        bitArray: BooleanArray,
        sizePixels: Int,
        background: Int,
        foreground: Int
    ): Bitmap {
        val colorArray = bitArray.toThemeColorArray(foreground = foreground, background = background)
        return Bitmap
            .createBitmap(colorArray, sizePixels, sizePixels, Bitmap.Config.ARGB_8888)
    }
}

private fun BooleanArray.toThemeColorArray(foreground: Int, background: Int) =
    IntArray(size) {
        if (this[it]) foreground else background
    }

data class QrCodeColors(
    val background: Color,
    val foreground: Color,
    val border: Color,
)
