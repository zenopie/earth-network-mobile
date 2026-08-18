package network.erth.wallet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import network.erth.wallet.ui.theme.EarthTheme

/**
 * Text input.
 *
 * Built on BasicTextField rather than Material's TextField because Material's
 * brings its own container, label animation and indicator line, all of which
 * would have to be fought back to the tokens. Focus here is a green stroke, not
 * a glow — a two-pixel change with nothing moving.
 *
 * [error] takes precedence over focus: a field that is both focused and wrong
 * should say wrong.
 */
@Composable
fun EarthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    /** Amount fields get the numeric keyboard and nothing else. */
    numeric: Boolean = false,
    /** Rendered inside the field, e.g. "ERTH" or a MAX button. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val stroke = when {
        error != null -> colors.Inputs.ErrorDefault.stroke
        focused -> colors.Inputs.Focused.stroke
        else -> colors.Inputs.Default.stroke
    }
    val bg = when {
        !enabled -> colors.Inputs.Disabled.bg
        value.isNotEmpty() -> colors.Inputs.Filled.bg
        else -> colors.Inputs.Default.bg
    }

    Column(modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.Inputs.Default.label,
                modifier = Modifier.padding(bottom = dimens.space4),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(dimens.radiusMd))
                .border(dimens.strokeWidth, stroke, RoundedCornerShape(dimens.radiusMd))
                .padding(horizontal = dimens.space16, vertical = dimens.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.Inputs.Default.text),
                    cursorBrush = SolidColor(colors.Inputs.Focused.stroke),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                    ),
                    decorationBox = { inner ->
                        if (value.isEmpty() && hint != null) {
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.Inputs.Default.hint,
                            )
                        }
                        inner()
                    },
                )
            }
            if (trailing != null) {
                Row(Modifier.padding(start = dimens.space8)) { trailing() }
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.Text.textError,
                modifier = Modifier.padding(top = dimens.space4),
            )
        }
    }
}
