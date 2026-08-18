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

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getString
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.rememberDesiredFormatLocale
import network.erth.wallet.ui.vendor.util.stringRes

@Suppress("LongParameterList")
@Composable
fun EarthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = EarthTextFieldDefaults.innerModifier,
    error: String? = null,
    isEnabled: Boolean = true,
    textStyle: TextStyle = EarthTypography.textMd.copy(fontWeight = FontWeight.Medium),
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    // Earth change: single line by default.
    //
    // Zashi defaults this to false, so every field in this app accepted a
    // newline — an address, an amount, a wallet name. None of them has a second
    // line to offer, and the return key inserting one instead of dismissing the
    // keyboard is what makes the input feel broken.
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = EarthTextFieldDefaults.shape,
    colors: EarthTextFieldColors = EarthTextFieldDefaults.defaultColors()
) {
    EarthTextField(
        state =
            TextFieldState(
                value = stringRes(value),
                error = error?.let { stringRes(it) },
                isEnabled = isEnabled,
                onValueChange = onValueChange,
            ),
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
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
    )
}

@Suppress("LongParameterList")
@Composable
fun EarthTextField(
    state: EnhancedTextFieldState,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = EarthTextFieldDefaults.innerModifier,
    textStyle: TextStyle = EarthTypography.textMd.copy(fontWeight = FontWeight.Medium),
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    // Earth change: single line by default.
    //
    // Zashi defaults this to false, so every field in this app accepted a
    // newline — an address, an amount, a wallet name. None of them has a second
    // line to offer, and the return key inserting one instead of dismissing the
    // keyboard is what makes the input feel broken.
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = EarthTextFieldDefaults.shape,
    contentPadding: PaddingValues = EarthTextFieldDefaults.contentPadding(leadingIcon, suffix, trailingIcon, prefix),
    colors: EarthTextFieldColors = EarthTextFieldDefaults.defaultColors()
) {
    TextFieldInternal(
        state = state,
        textStyle = textStyle,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        modifier = modifier,
        innerModifier = innerModifier
    )
}

@Suppress("LongParameterList")
@Composable
fun EarthTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = EarthTextFieldDefaults.innerModifier,
    textStyle: TextStyle = EarthTypography.textMd.copy(fontWeight = FontWeight.Medium),
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    // Earth change: single line by default.
    //
    // Zashi defaults this to false, so every field in this app accepted a
    // newline — an address, an amount, a wallet name. None of them has a second
    // line to offer, and the return key inserting one instead of dismissing the
    // keyboard is what makes the input feel broken.
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = EarthTextFieldDefaults.shape,
    contentPadding: PaddingValues = EarthTextFieldDefaults.contentPadding(leadingIcon, suffix, trailingIcon, prefix),
    colors: EarthTextFieldColors = EarthTextFieldDefaults.defaultColors()
) {
    var enhancedValueState by remember {
        mutableStateOf(
            EnhancedTextFieldState(
                innerState =
                    InnerTextFieldState(
                        value = state.value,
                        selection = TextSelection.Start,
                    ),
                error = state.error,
                isEnabled = state.isEnabled,
                onValueChange = { _ -> },
            )
        )
    }

    val textFieldValue =
        enhancedValueState.copy(
            innerState = enhancedValueState.innerState.copy(value = state.value),
            error = state.error,
            isEnabled = state.isEnabled
        )

    SideEffect {
        if (textFieldValue != enhancedValueState) {
            enhancedValueState = textFieldValue
        }
    }

    val context = LocalContext.current
    val locale = rememberDesiredFormatLocale()

    TextFieldInternal(
        state =
            enhancedValueState.copy(
                onValueChange = { newInnerState ->
                    enhancedValueState = enhancedValueState.copy(innerState = newInnerState)
                    state.onValueChange(newInnerState.value.getString(context, locale))
                }
            ),
        textStyle = textStyle,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        modifier = modifier,
        innerModifier = innerModifier
    )
}

@Composable
fun EarthTextFieldPlaceholder(res: StringResource) {
    Text(
        text = res.getValue(),
        style = EarthTypography.textMd,
        color = EarthColors.Inputs.Default.text
    )
}

@Suppress("LongParameterList", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextFieldInternal(
    state: EnhancedTextFieldState,
    textStyle: TextStyle,
    placeholder: @Composable (() -> Unit)?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    prefix: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    singleLine: Boolean,
    maxLines: Int,
    minLines: Int,
    interactionSource: MutableInteractionSource,
    shape: Shape,
    colors: EarthTextFieldColors,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = Modifier,
) {
    val value = state.innerState.value.getValue()
    // Holds the latest internal TextFieldValue state. We need to keep it to have the correct value
    // of the composition.
    var textFieldValueState by remember {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = state.innerState.getTextRange(value)
            )
        )
    }
    // Holds the latest TextFieldValue that BasicTextField was recomposed with. We couldn't simply
    // pass `TextFieldValue(text = value)` to the CoreTextField because we need to preserve the
    // composition.
    val textFieldValue = textFieldValueState.copy(text = value, selection = state.innerState.getTextRange(value))

    SideEffect {
        if (textFieldValue.text != textFieldValueState.text ||
            textFieldValue.selection != textFieldValueState.selection ||
            textFieldValue.composition != textFieldValueState.composition
        ) {
            textFieldValueState = textFieldValue
        }
    }

    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor by colors.borderColor(state, isFocused)
    val androidColors = colors.toTextFieldColors()
    // If color is not provided via the text style, use content color as a default
    val textColor =
        textStyle.color.takeOrElse {
            androidColors.textColor(state.isEnabled, state.isError, interactionSource).value
        }
    val mergedTextStyle = textStyle.merge(TextStyle(color = textColor))

    var lastInnerState by remember(state.innerState) { mutableStateOf(state.innerState) }

    CompositionLocalProvider(LocalTextSelectionColors provides androidColors.selectionColors) {
        Column(
            modifier = modifier,
        ) {
            BasicTextField(
                value = textFieldValue,
                modifier =
                    innerModifier then
                        if (borderColor == Color.Unspecified) {
                            Modifier
                        } else {
                            Modifier.border(
                                width = 1.dp,
                                color = borderColor,
                                shape = shape
                            )
                        },
                onValueChange = { newTextFieldValueState ->
                    textFieldValueState = newTextFieldValueState

                    val stringChanged = value != newTextFieldValueState.text
                    val selectionChanged = lastInnerState.getTextRange(value) != newTextFieldValueState.selection

                    lastInnerState =
                        InnerTextFieldState(
                            value = stringRes(newTextFieldValueState.text),
                            selection = TextSelection.ByTextRange(newTextFieldValueState.selection)
                        )

                    if (stringChanged || selectionChanged) {
                        state.onValueChange(lastInnerState)
                    }
                },
                enabled = state.isEnabled,
                readOnly = false,
                textStyle = mergedTextStyle,
                cursorBrush = SolidColor(androidColors.cursorColor(state.isError).value),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
            ) { innerTextField: @Composable () -> Unit ->
                // places leading icon, text field with label and placeholder, trailing icon
                TextFieldDefaults.DecorationBox(
                    value = state.innerState.value.getValue(),
                    visualTransformation = visualTransformation,
                    innerTextField = {
                        DecorationBox(prefix = prefix, suffix = suffix, content = innerTextField)
                    },
                    placeholder =
                        if (placeholder != null) {
                            {
                                DecorationBox(prefix, suffix, placeholder)
                            }
                        } else {
                            null
                        },
                    label = null,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    prefix = prefix,
                    suffix = suffix,
                    supportingText = null,
                    shape = shape,
                    singleLine = singleLine,
                    enabled = state.isEnabled,
                    isError = state.isError,
                    interactionSource = interactionSource,
                    colors = androidColors,
                    contentPadding = contentPadding
                )
            }

            if (state.error != null && state.error.getValue().isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.error.getValue(),
                    style = EarthTypography.textSm,
                    color = colors.hintColor(state).value
                )
            }
        }
    }
}

@ReadOnlyComposable
@Composable
fun getVerticalPadding(
    trailingIcon: @Composable (() -> Unit)?,
    leadingIcon: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    prefix: @Composable (() -> Unit)?
) = when {
    trailingIcon != null || leadingIcon != null -> 12.dp
    suffix != null || prefix != null -> 4.dp
    else -> 10.dp
}

@Composable
private fun DecorationBox(
    prefix: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    content: @Composable () -> Unit
) {
    Box(
        modifier =
            Modifier.padding(
                start = if (prefix != null) 4.dp else 0.dp,
                top = if (suffix != null || prefix != null) 8.dp else 0.dp,
                bottom = if (suffix != null || prefix != null) 8.dp else 0.dp,
                end = if (suffix != null) 4.dp else 0.dp
            )
    ) {
        content()
    }
}

data class EarthTextFieldColors(
    val textColor: Color,
    val hintColor: Color,
    val borderColor: Color,
    val focusedBorderColor: Color,
    val containerColor: Color,
    val focusedContainerColor: Color,
    val placeholderColor: Color,
    val disabledTextColor: Color,
    val disabledHintColor: Color,
    val disabledBorderColor: Color,
    val disabledContainerColor: Color,
    val disabledPlaceholderColor: Color,
    val errorTextColor: Color,
    val errorHintColor: Color,
    val errorBorderColor: Color,
    val errorContainerColor: Color,
    val errorPlaceholderColor: Color,
) {
    @Composable
    internal fun borderColor(
        state: EnhancedTextFieldState,
        isFocused: Boolean
    ): State<Color> {
        val targetValue =
            when {
                !state.isEnabled -> disabledBorderColor
                state.isError -> errorBorderColor
                isFocused -> focusedBorderColor.takeOrElse { borderColor }
                else -> borderColor
            }
        return rememberUpdatedState(targetValue)
    }

    @Composable
    internal fun hintColor(state: EnhancedTextFieldState): State<Color> {
        val targetValue =
            when {
                !state.isEnabled -> disabledHintColor
                state.isError -> errorHintColor
                else -> hintColor
            }
        return rememberUpdatedState(targetValue)
    }

    @Composable
    internal fun toTextFieldColors() =
        TextFieldDefaults.colors(
            focusedTextColor = textColor,
            unfocusedTextColor = textColor,
            disabledTextColor = disabledTextColor,
            errorTextColor = errorTextColor,
            focusedContainerColor = focusedContainerColor.takeOrElse { containerColor },
            unfocusedContainerColor = containerColor,
            disabledContainerColor = disabledContainerColor,
            errorContainerColor = errorContainerColor,
            cursorColor = textColor,
            errorCursorColor = errorTextColor,
            selectionColors = null,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = Color.Unspecified,
            unfocusedLeadingIconColor = Color.Unspecified,
            disabledLeadingIconColor = Color.Unspecified,
            errorLeadingIconColor = Color.Unspecified,
            focusedTrailingIconColor = Color.Unspecified,
            unfocusedTrailingIconColor = Color.Unspecified,
            disabledTrailingIconColor = Color.Unspecified,
            errorTrailingIconColor = Color.Unspecified,
            focusedLabelColor = Color.Unspecified,
            unfocusedLabelColor = Color.Unspecified,
            disabledLabelColor = Color.Unspecified,
            errorLabelColor = Color.Unspecified,
            focusedPlaceholderColor = placeholderColor,
            unfocusedPlaceholderColor = placeholderColor,
            disabledPlaceholderColor = disabledPlaceholderColor,
            errorPlaceholderColor = errorPlaceholderColor,
            focusedSupportingTextColor = hintColor,
            unfocusedSupportingTextColor = hintColor,
            disabledSupportingTextColor = disabledHintColor,
            errorSupportingTextColor = errorHintColor,
            focusedPrefixColor = Color.Unspecified,
            unfocusedPrefixColor = Color.Unspecified,
            disabledPrefixColor = Color.Unspecified,
            errorPrefixColor = Color.Unspecified,
            focusedSuffixColor = Color.Unspecified,
            unfocusedSuffixColor = Color.Unspecified,
            disabledSuffixColor = Color.Unspecified,
            errorSuffixColor = Color.Unspecified,
        )
}

object EarthTextFieldDefaults {
    val shape: Shape
        get() = RoundedCornerShape(8.dp)

    val innerModifier: Modifier
        get() =
            Modifier
                .defaultMinSize(minWidth = TextFieldDefaults.MinWidth)
                .fillMaxWidth()

    @Suppress("LongParameterList")
    @Composable
    fun defaultColors(
        textColor: Color = EarthColors.Inputs.Filled.text,
        hintColor: Color = EarthColors.Inputs.Default.hint,
        borderColor: Color = Color.Unspecified,
        focusedBorderColor: Color = EarthColors.Inputs.Focused.stroke,
        containerColor: Color = EarthColors.Inputs.Default.bg,
        focusedContainerColor: Color = EarthColors.Inputs.Focused.bg,
        placeholderColor: Color = EarthColors.Inputs.Default.text,
        disabledTextColor: Color = EarthColors.Inputs.Disabled.text,
        disabledHintColor: Color = EarthColors.Inputs.Disabled.hint,
        disabledBorderColor: Color = EarthColors.Inputs.Disabled.stroke,
        disabledContainerColor: Color = EarthColors.Inputs.Disabled.bg,
        disabledPlaceholderColor: Color = EarthColors.Inputs.Disabled.iconMain,
        errorTextColor: Color = EarthColors.Inputs.ErrorFilled.text,
        errorHintColor: Color = EarthColors.Inputs.ErrorDefault.hint,
        errorBorderColor: Color = EarthColors.Inputs.ErrorDefault.stroke,
        errorContainerColor: Color = EarthColors.Inputs.ErrorDefault.bg,
        errorPlaceholderColor: Color = EarthColors.Inputs.ErrorDefault.text,
    ) = EarthTextFieldColors(
        textColor = textColor,
        hintColor = hintColor,
        borderColor = borderColor,
        focusedBorderColor = focusedBorderColor,
        containerColor = containerColor,
        focusedContainerColor = focusedContainerColor,
        placeholderColor = placeholderColor,
        disabledTextColor = disabledTextColor,
        disabledHintColor = disabledHintColor,
        disabledBorderColor = disabledBorderColor,
        disabledContainerColor = disabledContainerColor,
        disabledPlaceholderColor = disabledPlaceholderColor,
        errorTextColor = errorTextColor,
        errorHintColor = errorHintColor,
        errorBorderColor = errorBorderColor,
        errorContainerColor = errorContainerColor,
        errorPlaceholderColor = errorPlaceholderColor,
    )

    @Composable
    fun contentPadding(
        leadingIcon: @Composable (() -> Unit)?,
        suffix: @Composable (() -> Unit)?,
        trailingIcon: @Composable (() -> Unit)?,
        prefix: @Composable (() -> Unit)?
    ) = PaddingValues(
        start = if (leadingIcon != null) 8.dp else 14.dp,
        end = if (suffix != null) 4.dp else 12.dp,
        top = getVerticalPadding(trailingIcon, leadingIcon, suffix, prefix),
        bottom = getVerticalPadding(trailingIcon, leadingIcon, suffix, prefix),
    )
}

data class TextFieldState(
    val value: StringResource,
    val error: StringResource? = null,
    val isEnabled: Boolean = true,
    val onValueChange: (String) -> Unit,
) {
    val isError = error != null
}

data class EnhancedTextFieldState(
    val innerState: InnerTextFieldState,
    val error: StringResource? = null,
    val isEnabled: Boolean = true,
    val onValueChange: (InnerTextFieldState) -> Unit,
) {
    val isError = error != null
}

data class InnerTextFieldState(
    val value: StringResource,
    val selection: TextSelection = TextSelection.Start,
) {
    fun getTextRange(value: String): TextRange {
        return when (selection) {
            is TextSelection.ByTextRange -> return selection.range
            TextSelection.End -> TextRange(value.length)
            TextSelection.Start -> TextRange.Zero
        }
    }
}

sealed interface TextSelection {
    data object End : TextSelection

    data object Start : TextSelection

    data class ByTextRange(
        val range: TextRange
    ) : TextSelection
}

@PreviewScreens
@Composable
private fun DefaultPreview() =
    ZcashTheme {
        EarthTextField(
            state =
                TextFieldState(
                    value = stringRes("Text")
                ) {}
        )
    }

@PreviewScreens
@Composable
private fun ErrorPreview() =
    ZcashTheme {
        EarthTextField(
            state =
                TextFieldState(
                    value = stringRes("Text"),
                    error = stringRes("Error"),
                ) {}
        )
    }
