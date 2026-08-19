/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color

@Composable
internal fun TextFieldColors.textColor(
    enabled: Boolean,
    isError: Boolean,
    interactionSource: InteractionSource
): State<Color> {
    val focused by interactionSource.collectIsFocusedAsState()

    val targetValue =
        when {
            !enabled -> disabledTextColor
            isError -> errorTextColor
            focused -> focusedTextColor
            else -> unfocusedTextColor
        }
    return rememberUpdatedState(targetValue)
}

internal val TextFieldColors.selectionColors: TextSelectionColors
    @Composable get() = textSelectionColors

@Composable
internal fun TextFieldColors.cursorColor(isError: Boolean): State<Color> =
    rememberUpdatedState(if (isError) errorCursorColor else cursorColor)
