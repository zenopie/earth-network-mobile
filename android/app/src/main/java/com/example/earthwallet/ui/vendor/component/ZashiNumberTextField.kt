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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.StringResource.Companion.NUMBER_FORMAT_LOCALE
import network.erth.wallet.ui.vendor.util.getString
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes
import network.erth.wallet.ui.vendor.util.stringResByDynamicNumber
import network.erth.wallet.ui.vendor.util.stringResByNumber
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParseException

@Suppress("LongParameterList")
@Composable
fun EarthNumberTextField(
    state: NumberTextFieldState,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = EarthTextFieldDefaults.innerModifier,
    textStyle: TextStyle = EarthNumberTextFieldDefaults.textStyle,
    placeholder: @Composable (() -> Unit)? = { EarthNumberTextFieldDefaults.Placeholder() },
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = EarthTextFieldDefaults.shape,
    contentPadding: PaddingValues =
        EarthTextFieldDefaults.contentPadding(
            leadingIcon = leadingIcon,
            suffix = suffix,
            trailingIcon = null,
            prefix = prefix
        ),
    colors: EarthTextFieldColors = EarthTextFieldDefaults.defaultColors()
) {
    val textFieldState = createTextFieldState(state)
    EarthTextField(
        state = textFieldState,
        modifier = modifier,
        innerModifier = innerModifier,
        textStyle = textStyle,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = null,
        prefix = prefix,
        suffix = suffix,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        maxLines = 1,
        minLines = 1,
        interactionSource = interactionSource,
        shape = shape,
        contentPadding = contentPadding,
        colors = colors,
    )
}

@Composable
private fun createTextFieldState(state: NumberTextFieldState): EnhancedTextFieldState {
    val context = LocalContext.current
    val text =
        state.innerState.innerTextFieldState.value
            .getValue()
            .replace(" ", "")
    val selection =
        when (val selection = state.innerState.innerTextFieldState.selection) {
            is TextSelection.ByTextRange -> {
                TextSelection.ByTextRange(
                    TextRange(selection.range.start, selection.range.end.coerceAtMost(text.length))
                )
            }

            TextSelection.End -> {
                selection
            }

            TextSelection.Start -> {
                selection
            }
        }

    val textFieldState =
        EnhancedTextFieldState(
            innerState =
                state.innerState.innerTextFieldState.copy(
                    value = stringRes(text),
                    selection = selection
                ),
            isEnabled = state.isEnabled,
            error = state.explicitError ?: state.defaultNumberError.takeIf { state.innerState.isError },
            onValueChange = { innerState ->
                val newText = innerState.value.getString(context).replace(" ", "")
                val normalized: String
                val amount: BigDecimal?
                val lastValidAmount: BigDecimal?

                if (newText != text) {
                    normalized = EarthNumberTextFieldParser.normalizeInput(innerState.value.getString(context))
                    amount = EarthNumberTextFieldParser.toBigDecimalOrNull(normalized)
                    lastValidAmount = amount ?: state.innerState.lastValidAmount
                } else {
                    normalized = text
                    amount = state.innerState.amount
                    lastValidAmount = state.innerState.lastValidAmount
                }

                val new =
                    state.innerState.copy(
                        innerTextFieldState =
                            state.innerState.innerTextFieldState.copy(
                                value = stringRes(normalized),
                                selection = innerState.selection
                            ),
                        amount = amount,
                        lastValidAmount = lastValidAmount
                    )
                state.onValueChange(new)
            }
        )
    return textFieldState
}

data class NumberTextFieldState(
    val innerState: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val isEnabled: Boolean = true,
    val explicitError: StringResource? = null,
    val defaultNumberError: StringResource = stringRes(""),
    val onValueChange: (NumberTextFieldInnerState) -> Unit,
) {
    val isError = explicitError != null || innerState.isError
}

data class NumberTextFieldInnerState(
    val innerTextFieldState: InnerTextFieldState =
        InnerTextFieldState(
            value = stringRes(value = ""),
            selection = TextSelection.Start
        ),
    val amount: BigDecimal? = null,
    val lastValidAmount: BigDecimal? = null,
) {
    val isError = amount == null && !innerTextFieldState.value.isEmpty()

    companion object {
        fun fromAmount(amount: BigDecimal) =
            NumberTextFieldInnerState(
                innerTextFieldState =
                    InnerTextFieldState(
                        value = stringResByNumber(amount),
                        selection = TextSelection.Start
                    ),
                amount = amount,
                lastValidAmount = amount
            )
    }
}

object EarthNumberTextFieldDefaults {
    val textStyle
        @Composable get() = EarthTypography.textMd.copy(fontWeight = FontWeight.Medium)

    @Composable
    fun Placeholder(
        modifier: Modifier = Modifier,
        style: TextStyle = EarthTypography.textMd,
        fontWeight: FontWeight = FontWeight.Normal,
        textAlign: TextAlign = TextAlign.Start,
        contentAlignment: Alignment = Alignment.CenterStart,
        text: String = stringResByDynamicNumber(0).getValue()
    ) {
        EarthAutoSizeText(
            text = text,
            modifier = modifier,
            style = style,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = 1,
            contentAlignment = contentAlignment
        )
    }
}

object EarthNumberTextFieldParser {
    /**
     * Sanitize raw user [input] into a string the parser can read. Whitespace grouping is stripped, and
     * mixed separators (e.g. a pasted "1,234.56" or the European "1.234,56") keep only the LAST separator
     * as the decimal point while the earlier ones are treated as grouping and removed — both become
     * "1234.56".
     *
     * When every separator is the same character (e.g. "1,234,567" or "1.234.567") they are all treated
     * as grouping and dropped, yielding "1234567".
     *
     * A separator at the very end is handled specially. If the rest already resolves to a value with a
     * decimal point (e.g. "1.234" or "1,234", where the single separator is the decimal), the trailing
     * separator is redundant and dropped, keeping "1.234" — typing another separator does not reset an
     * amount that already has a decimal. If the rest is a plain integer / grouped value (e.g. "1234" or
     * "1,234,567"), the trailing separator starts the decimal part, yielding "1234." / "1234567.".
     */
    @Suppress("ReturnCount")
    fun normalizeInput(input: String): String {
        val symbols = DecimalFormatSymbols(NUMBER_FORMAT_LOCALE)
        val decimalSeparator = symbols.decimalSeparator
        val separators = setOf(decimalSeparator, symbols.groupingSeparator)

        val cleaned =
            input
                .replace("\u00A0", "") // NO-BREAK SPACE
                .replace("\u202F", "") // NARROW NO-BREAK SPACE
                .replace("\u0020", "") // REGULAR SPACE

        val lastSeparatorIndex = cleaned.indexOfLast { it in separators }
        if (lastSeparatorIndex < 0) return cleaned

        if (lastSeparatorIndex == cleaned.lastIndex) {
            // Trailing separator: only the decimal point when the rest isn't already a decimal.
            val normalizedHead = normalizeInput(cleaned.substring(0, lastSeparatorIndex))
            return if (normalizedHead.contains(decimalSeparator)) {
                normalizedHead
            } else {
                "$normalizedHead$decimalSeparator"
            }
        }

        val presentSeparators = cleaned.filter { it in separators }
        if (presentSeparators.length > 1 && presentSeparators.toSet().size == 1) {
            // All separators are identical → pure grouping (e.g. "1,234,567"); drop them all.
            return cleaned.filterNot { it in separators }
        }

        // Otherwise the last separator is the decimal point; earlier separators are grouping.
        val integerPart = cleaned.substring(0, lastSeparatorIndex).filterNot { it in separators }
        val fractionPart = cleaned.substring(lastSeparatorIndex + 1)
        return "$integerPart$decimalSeparator$fractionPart"
    }

    /**
     * Convert user's [input] to a number of type [BigDecimal]. The decimal separator is derived from the
     * forced number locale ([StringResource.NUMBER_FORMAT_LOCALE]).
     *
     * This function first validates the input format, then uses DecimalFormat with isParseBigDecimal=true
     * to parse the string. According to Java documentation, when isParseBigDecimal is true, parse() should
     * always return BigDecimal directly, making type conversion unnecessary in most cases.
     *
     * @param input The normalized string to parse (should be already processed by normalizeInput)
     * @return [BigDecimal] if [input] is a valid number representation, null otherwise
     */
    @Suppress("ReturnCount")
    fun toBigDecimalOrNull(input: String): BigDecimal? {
        val symbols = DecimalFormatSymbols(NUMBER_FORMAT_LOCALE)
        if (!isValidNumericWithOptionalDecimalSeparator(input = input, symbols = symbols)) return null
        val pattern = (DecimalFormat.getInstance(NUMBER_FORMAT_LOCALE) as? DecimalFormat)?.toPattern()

        val decimalFormat =
            if (pattern != null) {
                DecimalFormat(pattern, symbols).apply { this.isParseBigDecimal = true }
            } else {
                DecimalFormat().apply {
                    this.decimalFormatSymbols = symbols
                    this.isParseBigDecimal = true
                }
            }

        return try {
            when (val parsedNumber = decimalFormat.parse(input)) {
                null -> null

                is BigDecimal -> parsedNumber

                // The following branches should rarely/never execute when isParseBigDecimal=true,
                // but are kept as defensive fallbacks. Using string conversion to avoid precision loss.
                is Double -> BigDecimal.valueOf(parsedNumber)

                is Float -> BigDecimal(parsedNumber.toString())

                else -> BigDecimal(parsedNumber.toString())
            }
        } catch (_: ParseException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
    }

    /**
     * Validates if the input string represents a valid numeric format with an optional decimal separator.
     *
     * Valid patterns:
     * - Integer: "123"
     * - Decimal: "123.456" or "123,456" (depending on locale)
     * - Trailing separator: "123." or "123,"
     * - Leading separator: ".123" or ",123"
     * - Zero: "0"
     *
     * Invalid patterns:
     * - Empty: ""
     * - Only separator: "." or ","
     * - Multiple separators: "12.34.56"
     * - Non-numeric: "abc", "12a34"
     *
     * @param input The string to validate (should be already normalized via normalizeInput)
     * @param symbols The decimal format symbols containing the decimal separator for the locale
     * @return true if the input is a valid numeric format, false otherwise
     */
    private fun isValidNumericWithOptionalDecimalSeparator(
        input: String,
        symbols: DecimalFormatSymbols
    ): Boolean {
        if (input.isEmpty()) return false

        // Escape the decimal separator for use in regex
        // We need to escape all regex metacharacters: . ^ $ * + ? { } [ ] \ | ( )
        val decimalSeparator = Regex.escape(symbols.decimalSeparator.toString())

        // Pattern breakdown:
        // ^(?:\d+SEPARATOR?\d*|SEPARATOR\d+)$
        // - First alternative: digits, optional separator, optional digits (e.g., "123", "123.", "123.45")
        // - Second alternative: separator followed by digits (e.g., ".45")
        val regex = Regex("^(?:\\d+$decimalSeparator?\\d*|$decimalSeparator\\d+)$")

        return regex.matches(input)
    }
}

@Composable
@Preview
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                EarthNumberTextField(
                    state = NumberTextFieldState {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
