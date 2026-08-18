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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.component.BlankSurface
import network.erth.wallet.ui.vendor.component.EarthCheckboxIndicator
import network.erth.wallet.ui.vendor.component.listitem.BaseListItem
import network.erth.wallet.ui.vendor.component.listitem.EarthListItemDefaults
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.ImageResource
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes
import kotlinx.collections.immutable.persistentListOf

@Composable
fun EarthCheckboxListItem(
    state: EarthCheckboxListItemState,
    modifier: Modifier = Modifier
) {
    BaseListItem(
        modifier = modifier,
        contentPadding = EarthListItemDefaults.contentPadding,
        leading = {
            Box(
                modifier = it,
                contentAlignment = Alignment.Center
            ) {
                when (state.icon) {
                    is ImageResource.ByDrawable -> {
                        Image(
                            modifier = Modifier.sizeIn(maxWidth = 48.dp, maxHeight = 48.dp),
                            painter = painterResource(state.icon.resource),
                            contentDescription = null,
                        )
                    }

                    is ImageResource.DisplayString -> {
                        Text(
                            modifier =
                                Modifier
                                    .background(EarthColors.Surfaces.bgSecondary, CircleShape)
                                    .size(40.dp)
                                    .padding(top = 11.dp)
                                    .align(Alignment.Center),
                            text = state.icon.value,
                            style = EarthTypography.textSm,
                            color = EarthColors.Text.textTertiary,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    ImageResource.Loading -> {
                        // do nothing
                    }
                }
            }
        },
        content = {
            EarthListItemDefaults.ContentItem(
                modifier = it,
                text = state.title.getValue(),
                subtitle = state.subtitle.getValue().let(::AnnotatedString),
                titleIcons = persistentListOf(),
                isEnabled = true
            )
        },
        trailing = {
            EarthCheckboxIndicator(state.isSelected)
        },
        onClick = state.onClick,
    )
}

@Composable
@PreviewScreens
private fun PreviewChecked() =
    ZcashTheme {
        BlankSurface {
            EarthCheckboxListItem(
                modifier = Modifier.fillMaxWidth(),
                state =
                    EarthCheckboxListItemState(
                        title = stringRes("title"),
                        subtitle = stringRes("subtitle"),
                        icon = imageRes("1"),
                        isSelected = true,
                        onClick = {}
                    )
            )
        }
    }

@Composable
@PreviewScreens
private fun PreviewUnchecked() =
    ZcashTheme {
        BlankSurface {
            EarthCheckboxListItem(
                modifier = Modifier.fillMaxWidth(),
                state =
                    EarthCheckboxListItemState(
                        title = stringRes("title"),
                        subtitle = stringRes("subtitle"),
                        icon = imageRes("1"),
                        isSelected = false,
                        onClick = {}
                    )
            )
        }
    }

data class EarthCheckboxListItemState(
    val title: StringResource,
    val subtitle: StringResource,
    val icon: ImageResource,
    val isSelected: Boolean,
    val onClick: () -> Unit
) : CheckboxListItemState
