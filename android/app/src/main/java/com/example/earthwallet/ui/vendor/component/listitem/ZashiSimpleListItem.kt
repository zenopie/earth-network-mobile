/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.component.listitem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.component.BlankSurface
import network.erth.wallet.ui.vendor.component.ShimmerRectangle
import network.erth.wallet.ui.vendor.component.Spacer
import network.erth.wallet.ui.vendor.component.EarthAutoSizeText
import network.erth.wallet.ui.vendor.component.rememberEarthShimmer
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes
import com.valentinilk.shimmer.shimmer

@Composable
fun EarthSimpleListItem(
    state: SimpleListItemState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = state.title.getValue(),
            style = EarthTypography.textSm,
            fontWeight = FontWeight.Medium,
            color = EarthColors.Text.textTertiary
        )
        Spacer(1f)
        Spacer(4.dp)
        if (state.text != null) {
            SelectionContainer {
                EarthAutoSizeText(
                    text = state.text.getValue(),
                    style = EarthTypography.textSm,
                    fontWeight = FontWeight.Medium,
                    color = EarthColors.Text.textPrimary,
                    maxLines = 1
                )
            }
        } else {
            Box(modifier = Modifier.shimmer(rememberEarthShimmer())) {
                ShimmerRectangle(width = 132.dp)
            }
        }
    }
}

data class SimpleListItemState(
    val title: StringResource,
    val text: StringResource?
)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthSimpleListItem(
                state =
                    SimpleListItemState(
                        title = stringRes("Title"),
                        text = stringRes("Text")
                    )
            )
        }
    }

@PreviewScreens
@Composable
private fun LoadingPreview() =
    ZcashTheme {
        BlankSurface {
            EarthSimpleListItem(
                state =
                    SimpleListItemState(
                        title = stringRes("Title"),
                        text = null
                    )
            )
        }
    }
