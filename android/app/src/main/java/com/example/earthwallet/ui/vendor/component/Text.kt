/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
@file:Suppress("TooManyFunctions")

package network.erth.wallet.ui.vendor.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import network.erth.wallet.ui.vendor.theme.ZcashTheme

@Composable
@Suppress("LongParameterList")
fun Body(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Start,
    textFontWeight: FontWeight = FontWeight.Normal,
    color: Color = ZcashTheme.colors.textPrimary,
) {
    Text(
        text = text,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        modifier = modifier,
        style = ZcashTheme.typography.primary.bodyLarge,
        fontWeight = textFontWeight
    )
}

@Composable
@Suppress("LongParameterList")
fun Small(
    text: String,
    modifier: Modifier = Modifier,
    textFontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Start,
    color: Color = ZcashTheme.colors.textPrimary,
) {
    Text(
        text = text,
        color = color,
        fontWeight = textFontWeight,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
@Suppress("LongParameterList")
fun Tiny(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Start,
    color: Color = ZcashTheme.colors.textPrimary,
) {
    Text(
        text = text,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
    )
}
