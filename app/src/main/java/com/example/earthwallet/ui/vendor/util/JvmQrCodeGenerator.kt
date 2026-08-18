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

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

const val QR_CODE_IMAGE_MARGIN_IN_PIXELS = 2

object JvmQrCodeGenerator : QrCodeGenerator {
    override fun generate(data: String, sizePixels: Int): BooleanArray {
        val bitMatrix =
            QRCodeWriter().encode(
                data,
                BarcodeFormat.QR_CODE,
                sizePixels,
                sizePixels,
                mapOf(
                    EncodeHintType.MARGIN to QR_CODE_IMAGE_MARGIN_IN_PIXELS,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                )
            )

        return BooleanArray(sizePixels * sizePixels).apply {
            var booleanArrayPosition = 0
            for (bitMatrixX in 0 until sizePixels) {
                for (bitMatrixY in 0 until sizePixels) {
                    this[booleanArrayPosition] = bitMatrix.get(bitMatrixX, bitMatrixY)
                    booleanArrayPosition++
                }
            }
        }
    }
}
