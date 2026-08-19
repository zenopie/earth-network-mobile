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

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthChipButton(
    state: ChipButtonState,
    modifier: Modifier = Modifier,
    useTint: Boolean = true,
    shape: RoundedCornerShape = EarthChipButtonDefaults.shape,
    border: BorderStroke? = EarthChipButtonDefaults.border,
    color: Color = EarthChipButtonDefaults.color,
    contentPadding: PaddingValues = EarthChipButtonDefaults.contentPadding,
    textStyle: TextStyle = EarthChipButtonDefaults.textStyle,
    endIconSpacer: Dp = EarthChipButtonDefaults.endIconSpacer,
) {
    val normalizedColor by animateColorAsState(color)
    val normalizedTextColor by animateColorAsState(textStyle.color)
    Surface(
        modifier = modifier,
        shape = shape,
        border = border,
        color = normalizedColor,
    ) {
        Row(
            modifier =
                Modifier.clickable(onClick = state.onClick, enabled = state.isEnabled) then
                    Modifier.padding
                        (contentPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.startIcon != null) {
                Image(
                    painterResource(state.startIcon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(EarthColors.Btns.Tertiary.btnTertiaryFg)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = state.text.getValue(),
                style = textStyle,
                color = normalizedTextColor
            )
            if (state.endIcon != null) {
                Spacer(Modifier.width(endIconSpacer))
                Image(
                    painterResource(state.endIcon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(EarthColors.Btns.Tertiary.btnTertiaryFg).takeIf { useTint }
                )
            }
        }
    }
}

data class ChipButtonState(
    val text: StringResource,
    @param:DrawableRes val startIcon: Int? = null,
    @param:DrawableRes val endIcon: Int? = null,
    val isEnabled: Boolean = true,
    val onClick: () -> Unit,
)

object EarthChipButtonDefaults {
    val shape: RoundedCornerShape
        get() = RoundedCornerShape(10.dp)
    val border: BorderStroke?
        get() = null
    val color: Color
        @Composable get() = EarthColors.Btns.Tertiary.btnTertiaryBg
    val contentPadding: PaddingValues
        get() = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val textStyle: TextStyle
        @Composable get() =
            EarthTypography.textSm.copy(
                color = EarthColors.Btns.Tertiary.btnTertiaryFg,
                fontWeight = FontWeight.SemiBold
            )
    val endIconSpacer: Dp
        get() = 4.dp
}

@PreviewScreens
@Composable
private fun EarthChipButtonPreview() =
    ZcashTheme {
        EarthChipButton(
            state =
                ChipButtonState(
                    startIcon = R.drawable.ic_radio_button_checked,
                    text = stringRes("Test"),
                    onClick = {}
                )
        )
    }

@PreviewScreens
@Composable
private fun EarthChipButtonEndIconPreview() =
    ZcashTheme {
        EarthChipButton(
            state =
                ChipButtonState(
                    endIcon = R.drawable.ic_close,
                    text = stringRes("End Icon Chip"),
                    onClick = {}
                )
        )
    }
