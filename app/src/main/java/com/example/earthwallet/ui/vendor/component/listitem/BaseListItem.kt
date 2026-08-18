/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.component.listitem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun BaseListItem(
    leading: @Composable ((Modifier) -> Unit)?,
    trailing: @Composable ((Modifier) -> Unit)?,
    onClick: (() -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    below: @Composable ColumnScope.(Modifier) -> Unit = {},
    shape: Shape = RoundedCornerShape(16.dp),
    border: BorderStroke? = null,
    color: Color = Color.Transparent,
    content: @Composable (Modifier) -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        border = border,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = clickableModifier(remember { MutableInteractionSource() }, onClick)
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(contentPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leading != null) {
                        leading(Modifier.sizeIn(maxWidth = 48.dp, maxHeight = 48.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    content(Modifier.weight(1f))
                    if (trailing != null) {
                        Spacer(modifier = Modifier.width(16.dp))
                        trailing(Modifier)
                    }
                }
                below(Modifier)
            }
        }
    }
}

fun clickableModifier(
    interactionSource: MutableInteractionSource,
    onClick: (() -> Unit)?
) = if (onClick != null) {
    Modifier.clickable(
        indication = ripple(),
        interactionSource = interactionSource,
        onClick = onClick,
        role = Role.Button,
    )
} else {
    Modifier
}
