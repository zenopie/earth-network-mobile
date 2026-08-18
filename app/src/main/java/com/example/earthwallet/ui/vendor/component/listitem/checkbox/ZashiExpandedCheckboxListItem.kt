/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.component.listitem.checkbox

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.BlankSurface
import network.erth.wallet.ui.vendor.component.EarthCheckboxIndicator
import network.erth.wallet.ui.vendor.component.listitem.EarthListItemDefaults
import network.erth.wallet.ui.vendor.component.listitem.clickableModifier
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.StyledStringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes
import network.erth.wallet.ui.vendor.util.withStyle

@Composable
fun EarthExpandedCheckboxListItem(
    state: EarthExpandedCheckboxListItemState,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    ExpandedBaseListItem(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        leading = {
            EarthListItemDefaults.LeadingItem(
                modifier = it,
                icon = imageRes(state.icon),
                badge = null,
                contentDescription = state.title.getValue()
            )
        },
        content = {
            Column(
                modifier = it
            ) {
                Row {
                    Text(
                        text = state.title.getValue(),
                        style = EarthTypography.textSm,
                        fontWeight = FontWeight.SemiBold,
                        color = EarthColors.Text.textPrimary
                    )
                }
                Text(
                    text = state.subtitle.getValue(),
                    style = EarthTypography.textXs,
                    color = EarthColors.Text.textTertiary
                )
            }
        },
        trailing = {
            EarthCheckboxIndicator(state.isSelected)
        },
        below = {
            state.info?.let {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = it.title.getValue(),
                        style = EarthTypography.textSm,
                        color = EarthColors.Text.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = it.subtitle.getValue(),
                        style = EarthTypography.textXs,
                        color = EarthColors.Text.textTertiary,
                    )
                }
            }
        },
        border =
            BorderStroke(
                1.dp,
                if (state.isSelected) {
                    EarthColors.Surfaces.bgAlt
                } else {
                    EarthColors.Surfaces.strokeSecondary
                }
            ),
        onClick =
            if (state.hapticFeedbackType == null) {
                state.onClick
            } else {
                {
                    runCatching { haptic.performHapticFeedback(state.hapticFeedbackType) }
                    state.onClick()
                }
            },
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun ExpandedBaseListItem(
    shape: Shape,
    contentPadding: PaddingValues,
    onClick: (() -> Unit)?,
    leading: @Composable (Modifier) -> Unit,
    trailing: @Composable (Modifier) -> Unit,
    below: @Composable ColumnScope.(Modifier) -> Unit,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        border = border,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                clickableModifier(remember { MutableInteractionSource() }, onClick)
                    .padding(contentPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                leading(Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(8.dp))
                content(Modifier.weight(1f))
                Spacer(modifier = Modifier.width(16.dp))
                trailing(Modifier)
            }
            below(Modifier)
        }
    }
}

data class EarthExpandedCheckboxListItemState(
    val title: StringResource,
    val subtitle: StyledStringResource,
    val icon: Int,
    val isSelected: Boolean,
    val hapticFeedbackType: HapticFeedbackType? =
        if (isSelected) {
            HapticFeedbackType.ToggleOff
        } else {
            HapticFeedbackType.ToggleOn
        },
    val info: EarthExpandedCheckboxRowState?,
    val onClick: () -> Unit
) : CheckboxListItemState

data class EarthExpandedCheckboxRowState(
    val title: StringResource,
    val subtitle: StringResource,
)

@Composable
@PreviewScreens
private fun ExpandedPreviewChecked() =
    ZcashTheme {
        BlankSurface {
            EarthExpandedCheckboxListItem(
                modifier = Modifier.fillMaxWidth(),
                state =
                    EarthExpandedCheckboxListItemState(
                        title = stringRes("title"),
                        subtitle = stringRes("subtitle").withStyle(),
                        icon = R.drawable.ic_radio_button_checked,
                        isSelected = true,
                        info =
                            EarthExpandedCheckboxRowState(
                                title = stringRes("title"),
                                subtitle = stringRes("subtitle")
                            ),
                        onClick = {}
                    )
            )
        }
    }

@Composable
@PreviewScreens
private fun PreviewWithNoInfo() =
    ZcashTheme {
        BlankSurface {
            EarthExpandedCheckboxListItem(
                modifier = Modifier.fillMaxWidth(),
                state =
                    EarthExpandedCheckboxListItemState(
                        title = stringRes("title"),
                        subtitle = stringRes("subtitle").withStyle(),
                        icon = R.drawable.ic_radio_button_checked,
                        isSelected = true,
                        info = null,
                        onClick = {}
                    )
            )
        }
    }

@Composable
@PreviewScreens
private fun ExpandedPreviewUnchecked() =
    ZcashTheme {
        BlankSurface {
            EarthExpandedCheckboxListItem(
                modifier = Modifier.fillMaxWidth(),
                state =
                    EarthExpandedCheckboxListItemState(
                        title = stringRes("title"),
                        subtitle = stringRes("subtitle").withStyle(),
                        icon = R.drawable.ic_radio_button_checked,
                        isSelected = false,
                        info =
                            EarthExpandedCheckboxRowState(
                                title = stringRes("title"),
                                subtitle = stringRes("subtitle")
                            ),
                        onClick = {}
                    )
            )
        }
    }
