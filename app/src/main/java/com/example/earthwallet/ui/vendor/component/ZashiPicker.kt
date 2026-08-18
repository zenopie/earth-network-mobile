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

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.ImageResource
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthPicker(
    state: PickerState,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        if (state.isEnabled) EarthColors.Dropdowns.Default.bg else EarthColors.Dropdowns.Disabled.bg
    )
    val borderColor = if (state.isEnabled) Color.Unspecified else EarthColors.Inputs.Disabled.stroke

    Surface(
        modifier = modifier,
        onClick = { if (state.isEnabled) state.onClick() },
        shape = RoundedCornerShape(EarthDimensions.Radius.radiusLg),
        color = bgColor,
        border = if (borderColor.isUnspecified) null else BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.bigIcon is ImageResource.ByDrawable) {
                Box {
                    Image(
                        modifier = Modifier.size(24.dp),
                        painter = painterResource(state.bigIcon.resource),
                        contentDescription = null,
                    )
                    if (state.smallIcon is ImageResource.ByDrawable) {
                        Image(
                            modifier =
                                Modifier
                                    .size(14.dp)
                                    .align(Alignment.BottomEnd)
                                    .offset(3.dp, 3.dp),
                            painter = painterResource(state.smallIcon.resource),
                            contentDescription = null,
                        )
                    }
                }
                Spacer(8.dp)
            }

            if (state.text != null) {
                val textColor by animateColorAsState(
                    if (state.isEnabled) {
                        EarthColors.Dropdowns.Filled.textMain
                    } else {
                        EarthColors.Dropdowns.Disabled.textMain
                    }
                )

                Text(
                    text = state.text.getValue(),
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            } else {
                val textColor by animateColorAsState(
                    if (state.isEnabled) {
                        EarthColors.Dropdowns.Default.text
                    } else {
                        EarthColors.Dropdowns.Disabled.textMain
                    }
                )

                Text(
                    text = state.placeholder.getValue(),
                    style = EarthTypography.textMd,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }

            Spacer(1f)

            val tintColor = if (state.isEnabled) Color.Unspecified else EarthColors.Dropdowns.Disabled.icon

            Image(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                colorFilter = if (tintColor.isSpecified) ColorFilter.tint(tintColor) else null
            )
        }
    }
}

data class PickerState(
    val bigIcon: ImageResource?,
    val smallIcon: ImageResource?,
    val text: StringResource?,
    val placeholder: StringResource,
    val isEnabled: Boolean = true,
    val onClick: () -> Unit
)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthPicker(
                state =
                    PickerState(
                        bigIcon = imageRes(R.drawable.ic_item_keystone),
                        smallIcon = imageRes(R.drawable.ic_item_keystone),
                        text = stringRes("Text"),
                        placeholder = stringRes("Placeholder"),
                        onClick = {}
                    )
            )
        }
    }

@PreviewScreens
@Composable
private fun PlaceholderPreview() =
    ZcashTheme {
        BlankSurface {
            EarthPicker(
                state =
                    PickerState(
                        bigIcon = null,
                        smallIcon = null,
                        text = null,
                        placeholder = stringRes("Placeholder..."),
                        onClick = {}
                    )
            )
        }
    }
