package network.erth.wallet.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.theme.EarthTheme

/**
 * The app's buttons.
 *
 * Four variants, because the old XML had eleven button backgrounds that differed
 * by accident rather than intent. Each one here answers a different question:
 * what is the action (Primary), what is the alternative (Secondary), what is
 * incidental (Ghost), and what cannot be undone (Destructive).
 *
 * All of them are full-width and [EarthDimens.buttonHeight] tall by default.
 * A wallet screen almost always has exactly one thing to do, and a full-width
 * button says so without needing a layout decision per screen.
 */
/**
 * Note on naming against the vendored tokens: their `Btns.Primary` is a black
 * button and their `Btns.Brand` carries the accent. Ours maps Primary onto
 * Brand, because in this app the primary action is the green one — the first
 * build after vendoring produced a black Send button, which is what that
 * mismatch looks like.
 */
enum class EarthButtonStyle { Primary, Secondary, Ghost, Destructive }

@Composable
fun EarthButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: EarthButtonStyle = EarthButtonStyle.Primary,
    enabled: Boolean = true,
    /** Shows a spinner and blocks input without changing the button's size. */
    loading: Boolean = false,
) {
    val colors = EarthTheme.colors
    val dimens = EarthTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val bg: Color
    val fg: Color
    var border: BorderStroke? = null

    when (style) {
        EarthButtonStyle.Primary -> {
            bg = when {
                !enabled -> colors.Btns.Brand.btnBrandBgDisabled
                pressed -> colors.Btns.Brand.btnBrandBgHover
                else -> colors.Btns.Brand.btnBrandBg
            }
            fg = if (enabled) colors.Btns.Brand.btnBrandFg else colors.Btns.Brand.btnBrandFgDisabled
        }
        EarthButtonStyle.Secondary -> {
            bg = when {
                !enabled -> colors.Btns.Secondary.btnSecondaryBgDisabled
                pressed -> colors.Btns.Secondary.btnSecondaryBgHover
                else -> colors.Btns.Secondary.btnSecondaryBg
            }
            fg = if (enabled) colors.Btns.Secondary.btnSecondaryFg else colors.Btns.Secondary.btnSecondaryFgDisabled
            border = BorderStroke(dimens.strokeWidth, colors.Btns.Secondary.btnSecondaryBorder)
        }
        EarthButtonStyle.Ghost -> {
            bg = if (pressed) colors.Btns.Ghost.btnGhostBgHover else colors.Btns.Ghost.btnGhostBg
            fg = if (enabled) colors.Btns.Ghost.btnGhostFg else colors.Btns.Ghost.btnGhostFgDisabled
        }
        EarthButtonStyle.Destructive -> {
            bg = when {
                !enabled -> colors.Btns.Destructive2.btnDestroy2BgDisabled
                pressed -> colors.Btns.Destructive2.btnDestroy2BgHover
                else -> colors.Btns.Destructive2.btnDestroy2Bg
            }
            fg = if (enabled) colors.Btns.Destructive2.btnDestroy2Fg else colors.Btns.Destructive2.btnDestroy2FgDisabled
        }
    }

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(dimens.buttonHeight),
        // Loading blocks input as firmly as disabled does, but keeps the enabled
        // colours: a spinner on a greyed-out button reads as "broken" rather
        // than "working".
        enabled = enabled && !loading,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(dimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = bg,
            disabledContentColor = fg,
        ),
        border = border,
        elevation = null,
        interactionSource = interaction,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(dimens.space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).padding(end = 0.dp),
                    color = fg,
                    strokeWidth = 2.dp,
                )
                Box(Modifier.size(dimens.space8))
            }
            Text(text = text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }
}
