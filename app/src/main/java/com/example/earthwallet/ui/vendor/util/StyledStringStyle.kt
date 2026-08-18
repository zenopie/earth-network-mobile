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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

data class StyledStringStyle(
    val color: StringResourceColor? = null,
    val fontWeight: FontWeight? = null,
    val font: StyledStringFont? = null,
)

@Composable
fun StyledStringStyle.toSpanStyle(): SpanStyle =
    SpanStyle(
        color = color?.getColor() ?: Color.Unspecified,
        fontWeight = fontWeight,
        fontFamily = font?.getFontFamily()
    )
