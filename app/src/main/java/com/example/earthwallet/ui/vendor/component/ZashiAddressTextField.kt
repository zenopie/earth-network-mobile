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

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.theme.typography.RobotoMonoFontFamily
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import kotlin.math.absoluteValue

@Suppress("LongParameterList")
@Composable
fun EarthAddressTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = EarthTextFieldDefaults.innerModifier,
    textStyle: TextStyle =
        EarthTypography.textMd.copy(
            fontWeight = FontWeight.Medium,
            fontFamily = RobotoMonoFontFamily
        ),
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = EarthTextFieldDefaults.shape,
    contentPadding: PaddingValues =
        EarthAddressTextFieldDefaults
            .contentPadding(leadingIcon, suffix, trailingIcon, prefix),
    colors: EarthTextFieldColors = EarthTextFieldDefaults.defaultColors()
) {
    val isFocused by interactionSource.collectIsFocusedAsState()

    val visualTransformation =
        remember(isFocused) {
            if (isFocused) VisualTransformation.None else ellipsisVisualTransformation()
        }

    EarthTextField(
        state = state,
        modifier = modifier,
        innerModifier = innerModifier,
        textStyle = textStyle,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        maxLines = 1,
        minLines = 1,
        singleLine = true,
        interactionSource = interactionSource,
        shape = shape,
        contentPadding = contentPadding,
        colors = colors,
    )
}

@Suppress("MagicNumber")
private fun ellipsisVisualTransformation() =
    VisualTransformation { text ->
        val ellipsis = "..."
        val maxLength =
            (text.length / 2)
                .coerceAtMost(text.length / 3)
                .coerceAtMost(8)

        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = 0

                override fun transformedToOriginal(offset: Int): Int =
                    when {
                        text.length <= 16 -> {
                            offset
                        }

                        offset <= maxLength -> {
                            offset
                        }

                        offset in (maxLength + 1)..(maxLength + 2) -> {
                            maxLength
                        }

                        else -> {
                            val whole = maxLength * 2 + 3
                            val fromRight = (offset - whole).absoluteValue
                            text.length - fromRight
                        }
                    }.coerceIn(0, text.length)
            }

        TransformedText(
            AnnotatedString
                .Builder()
                .apply {
                    when {
                        text.length <= 16 -> {
                            append(text)
                        }

                        text.isNotBlank() -> {
                            append(text.take(maxLength))
                            append(ellipsis)
                            append(text.takeLast(maxLength))
                        }
                    }
                }.toAnnotatedString(),
            mapping
        )
    }

object EarthAddressTextFieldDefaults {
    @Composable
    fun contentPadding(
        leadingIcon: @Composable (() -> Unit)?,
        suffix: @Composable (() -> Unit)?,
        trailingIcon: @Composable (() -> Unit)?,
        prefix: @Composable (() -> Unit)?
    ) = PaddingValues(
        start = if (leadingIcon != null || prefix != null) 8.dp else 14.dp,
        end = if (suffix != null) 4.dp else 12.dp,
        top = getVerticalPadding(trailingIcon, leadingIcon, suffix, prefix),
        bottom = getVerticalPadding(trailingIcon, leadingIcon, suffix, prefix),
    )
}
