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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.util.Compose
import network.erth.wallet.ui.vendor.util.ComposeAsShimmerCircle
import network.erth.wallet.ui.vendor.util.ImageResource

@Suppress("MagicNumber")
@Composable
fun EarthOverlappingIcons(state: OverlappingIconsState) {
    Row(
        modifier = Modifier.Companion.width(IntrinsicSize.Min),
        verticalAlignment = Alignment.Companion.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val iconSize = 48.dp
        val iconHorizontalOffset = .1f
        val iconCutoutWidth = .75f
        val iconCutoutOffset = 1f + iconHorizontalOffset + (1 - iconCutoutWidth)
        if (state.icons.size > 1) {
            Spacer((iconSize * iconHorizontalOffset) * (state.icons.size - 1))
        }

        state.icons.forEachIndexed { index, icon ->

            val cutout =
                if (state.icons.size <= 1 || state.icons.lastIndex == index) {
                    Modifier.Companion
                } else {
                    Modifier.Companion
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Companion.Offscreen
                        }.drawWithContent {
                            drawContent()
                            drawCircle(
                                color = Color(0xFFFFFFFF),
                                radius = size.width / 2f,
                                center = Offset(x = size.width * iconCutoutOffset, y = size.height / 2f),
                                blendMode = BlendMode.Companion.DstOut
                            )
                        }.background(Color.Companion.Transparent)
                }

            val offset =
                if (index == 0) {
                    Modifier.Companion
                } else {
                    Modifier.Companion.offset(x = -(iconSize * iconHorizontalOffset) * index)
                }

            val iconModifier = offset then cutout

            when (icon) {
                is ImageResource.ByDrawable -> {
                    icon.Compose(
                        modifier =
                            iconModifier then
                                if (state.icons.size > 1) {
                                    Modifier.Companion.size(iconSize)
                                } else {
                                    Modifier.Companion
                                },
                    )
                }

                is ImageResource.Loading -> {
                    icon.ComposeAsShimmerCircle(
                        modifier = iconModifier,
                        size = iconSize
                    )
                }

                is ImageResource.DisplayString -> {
                    // do nothing
                }
            }
        }
    }
}

data class OverlappingIconsState(
    val icons: List<ImageResource>
)
