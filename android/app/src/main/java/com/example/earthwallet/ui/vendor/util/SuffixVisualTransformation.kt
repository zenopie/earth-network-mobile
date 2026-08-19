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

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class SuffixVisualTransformation(
    val suffix: String
) : VisualTransformation {
    @Suppress("ReturnCount")
    override fun filter(text: AnnotatedString): TransformedText {
        val result = text + AnnotatedString(suffix)

        val textWithSuffixMapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset

                override fun transformedToOriginal(offset: Int): Int {
                    if (text.isEmpty()) return 0
                    if (offset > text.length) return text.length
                    return offset
                }
            }

        return TransformedText(result, textWithSuffixMapping)
    }
}
