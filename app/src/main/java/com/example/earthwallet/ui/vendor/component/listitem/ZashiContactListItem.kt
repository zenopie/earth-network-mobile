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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.BlankSurface
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.ImageResource
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.StyledStringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes
import network.erth.wallet.ui.vendor.util.stringResByAddress

@Composable
fun EarthContactListItem(
    state: ContactListItemState,
    modifier: Modifier = Modifier
) {
    BaseListItem(
        modifier = modifier,
        leading = { ContactItemLeading(modifier = it, state = state) },
        content = { ContactItemContent(modifier = it, state = state) },
        trailing = { EarthListItemDefaults.TrailingItem(contentDescription = state.name.getValue(), modifier = it) },
        onClick = state.onClick,
        contentPadding =
            PaddingValues(
                start = 20.dp,
                top = 12.dp,
                end = 20.dp,
                bottom = if (state.isShielded) 8.dp else 12.dp
            )
    )
}

@Composable
private fun ContactItemLeading(
    state: ContactListItemState,
    modifier: Modifier = Modifier,
) {
    when (state.bigIcon) {
        is ImageResource.ByDrawable -> {
            Box(modifier) {
                Image(
                    painter = painterResource(state.bigIcon.resource),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
                if (state.smallIcon is ImageResource.ByDrawable) {
                    Image(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .offset(4.dp, 4.dp),
                        painter = painterResource(state.smallIcon.resource),
                        contentDescription = null,
                    )
                }
            }
        }

        is ImageResource.DisplayString -> {
            Box(
                modifier.size(40.dp)
            ) {
                Text(
                    modifier =
                        Modifier
                            .background(EarthColors.Avatars.avatarBg, CircleShape)
                            .size(40.dp)
                            .padding(top = 11.dp)
                            .align(Alignment.Center),
                    text = state.bigIcon.value,
                    style = EarthTypography.textSm,
                    color = EarthColors.Avatars.avatarTextFg,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.isShielded) {
                    Image(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(24.dp),
                        painter = painterResource(id = R.drawable.ic_address_book_shielded),
                        contentDescription = null
                    )
                }
                if (state.smallIcon is ImageResource.ByDrawable) {
                    Image(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .offset(4.dp, 4.dp),
                        painter = painterResource(state.smallIcon.resource),
                        contentDescription = null,
                    )
                }
            }
        }

        ImageResource.Loading -> {
            // do nothing
        }
    }
}

@Composable
private fun ContactItemContent(
    state: ContactListItemState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
    ) {
        Text(
            text = state.name.getValue(),
            style = EarthTypography.textMd,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textPrimary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = state.address.getValue(),
            style = EarthTypography.textXs,
            color = EarthColors.Text.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

data class ContactListItemState(
    val bigIcon: ImageResource,
    val smallIcon: ImageResource?,
    val isShielded: Boolean,
    val name: StringResource,
    val address: StyledStringResource,
    val onClick: () -> Unit,
)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthContactListItem(
                state =
                    ContactListItemState(
                        name = stringRes("Name Surname"),
                        address = stringResByAddress("3iY5ZSkRnevzSMu4hosasdasdasdasd12312312dasd9hw2"),
                        bigIcon = imageRes("NS"),
                        smallIcon = imageRes(R.drawable.ic_chain_zec),
                        isShielded = false,
                        onClick = {}
                    )
            )
        }
    }
