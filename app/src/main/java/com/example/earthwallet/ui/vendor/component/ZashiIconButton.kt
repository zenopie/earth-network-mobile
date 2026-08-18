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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EarthIconButton(
    state: IconButtonState,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
    ) {
        Box(
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = {
                            state.hapticFeedbackType?.let {
                                runCatching { haptic.performHapticFeedback(it) }
                            }
                            state.onClick()
                        },
                        onDoubleClick =
                            state.onDoubleClick?.let {
                                {
                                    state.hapticFeedbackType?.let {
                                        runCatching { haptic.performHapticFeedback(it) }
                                    }
                                    it()
                                }
                            },
                        enabled = state.isEnabled,
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 24.dp)
                    ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(state.icon),
                contentDescription = state.contentDescription?.getValue(),
                tint = Color.Unspecified
            )
        }
        if (state.badge != null) {
            Badge(state.badge)
        }
    }
}

@Composable
private fun BoxScope.Badge(badge: StringResource) {
    Text(
        modifier =
            Modifier
                .size(20.dp)
                .background(EarthColors.Utility.Gray.utilityGray900, CircleShape)
                .align(Alignment.TopEnd)
                .padding(top = 3.dp),
        text = badge.getValue(),
        textAlign = TextAlign.Center,
        color = EarthColors.Surfaces.bgPrimary,
        style = EarthTypography.textXs,
        fontWeight = FontWeight.Medium
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EarthImageButton(
    state: IconButtonState,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier =
            modifier.combinedClickable(
                onClick = {
                    state.hapticFeedbackType?.let {
                        runCatching { haptic.performHapticFeedback(it) }
                    }
                    state.onClick()
                },
                onDoubleClick =
                    state.onDoubleClick?.let {
                        {
                            state.hapticFeedbackType?.let {
                                runCatching { haptic.performHapticFeedback(it) }
                            }
                            it()
                        }
                    },
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state.isEnabled
            )
    ) {
        Image(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            painter = painterResource(state.icon),
            contentDescription = state.contentDescription?.getValue(),
            contentScale = ContentScale.Inside
        )

        if (state.badge != null) {
            Badge(state.badge)
        }
    }
}

data class IconButtonState(
    @param:DrawableRes val icon: Int,
    val contentDescription: StringResource? = null,
    val badge: StringResource? = null,
    val isEnabled: Boolean = true,
    val hapticFeedbackType: HapticFeedbackType? = null,
    val onDoubleClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        EarthIconButton(
            state =
                IconButtonState(
                    icon = R.drawable.ic_item_keystone,
                    badge = stringRes("1"),
                    onClick = {}
                )
        )
    }

@PreviewScreens
@Composable
private fun ImagePreview() =
    ZcashTheme {
        EarthImageButton(
            state =
                IconButtonState(
                    icon = R.drawable.ic_item_keystone,
                    badge = stringRes("1"),
                    onClick = {}
                )
        )
    }
