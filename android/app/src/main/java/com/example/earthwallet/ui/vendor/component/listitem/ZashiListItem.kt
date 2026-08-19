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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.BlankSurface
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.ImageResource
import network.erth.wallet.ui.vendor.util.Itemizable
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun EarthListItem(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageResource? = null,
    badge: ImageResource? = null,
    type: EarthListItemDesignType = EarthListItemDesignType.PRIMARY,
    contentPadding: PaddingValues = EarthListItemDefaults.contentPadding,
    titleIcons: ImmutableList<Int> = persistentListOf(),
    subtitle: String? = null,
    isEnabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    EarthListItem(
        modifier = modifier,
        contentPadding = contentPadding,
        state =
            ListItemState(
                title = stringRes(title),
                subtitle = subtitle?.let { stringRes(it) },
                isEnabled = isEnabled,
                onClick = onClick,
                bigIcon = icon,
                smallIcon = badge,
                titleIcons = titleIcons,
                design = type
            ),
    )
}

@Composable
fun EarthListItem(
    state: ListItemState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = EarthListItemDefaults.contentPadding,
    colors: EarthListItemColors =
        when (state.design) {
            EarthListItemDesignType.PRIMARY -> EarthListItemDefaults.primaryColors()
            EarthListItemDesignType.SECONDARY -> EarthListItemDefaults.secondaryColors()
        },
    leading: (@Composable (Modifier) -> Unit)? =
        state.bigIcon?.let { icon ->
            {
                EarthListItemDefaults.LeadingItem(
                    modifier = it,
                    icon = icon,
                    badge = state.smallIcon,
                    contentDescription = state.title.getValue()
                )
            }
        },
    trailing: (@Composable (Modifier) -> Unit)? =
        if (state.isEnabled && state.onClick != null) {
            {
                EarthListItemDefaults.TrailingItem(
                    contentDescription = state.title.getValue(),
                    modifier = it
                )
            }
        } else {
            null
        },
    content: @Composable (Modifier) -> Unit = {
        EarthListItemDefaults.ContentItem(
            modifier = it,
            text = state.title.getValue(),
            subtitle = state.subtitle?.getValue()?.let(::AnnotatedString),
            titleIcons = state.titleIcons,
            isEnabled = state.isEnabled
        )
    },
) {
    BaseListItem(
        modifier = modifier,
        contentPadding = contentPadding,
        leading = leading,
        content = content,
        trailing = trailing,
        onClick = state.onClick.takeIf { state.isEnabled },
        border = colors.borderColor.takeIf { !it.isUnspecified }?.let { BorderStroke(1.dp, it) },
        color = colors.backgroundColor
    )
}

@Composable
private fun EarthListLeadingItem(
    bigIcon: ImageResource?,
    smallIcon: ImageResource?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    if (bigIcon is ImageResource.ByDrawable) {
        Box(modifier) {
            Image(
                modifier = Modifier.fillMaxSize(),
                painter = painterResource(bigIcon.resource),
                contentDescription = contentDescription,
                contentScale = ContentScale.FillHeight
            )
            if (smallIcon is ImageResource.ByDrawable) {
                Image(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .align(Alignment.BottomEnd)
                            .offset(3.dp, 3.dp),
                    painter = painterResource(smallIcon.resource),
                    contentDescription = contentDescription,
                )
            }
        }
    }
}

@Composable
private fun EarthListTrailingItem(
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = contentDescription,
        )
    }
}

@Suppress("MagicNumber")
@Composable
private fun EarthListContentItem(
    text: String,
    subtitle: AnnotatedString?,
    titleIcons: ImmutableList<Int>,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = EarthTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (isEnabled) {
                        EarthColors.Text.textPrimary
                    } else {
                        EarthColors.Text.textDisabled
                    }
            )
            titleIcons.forEachIndexed { index, icon ->
                if (index == 0) {
                    Spacer(Modifier.width(6.dp))
                    Image(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                        painter = painterResource(icon),
                        contentDescription = null,
                    )
                } else {
                    val offset = (-index.toDouble().pow(2.0) - index).roundToInt().dp

                    Image(
                        modifier =
                            Modifier
                                .offset(x = offset)
                                .size(24.dp)
                                .border(2.dp, EarthColors.Surfaces.bgPrimary, CircleShape)
                                .size(20.dp)
                                .padding(2.dp)
                                .clip(CircleShape),
                        painter = painterResource(icon),
                        contentDescription = null,
                    )
                }
            }
        }
        subtitle?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = it,
                style = EarthTypography.textXs,
                color = EarthColors.Text.textTertiary
            )
        }
    }
}

data class ListItemState(
    val title: StringResource,
    val bigIcon: ImageResource? = null,
    val smallIcon: ImageResource? = null,
    val design: EarthListItemDesignType = EarthListItemDesignType.PRIMARY,
    val subtitle: StringResource? = null,
    val titleIcons: ImmutableList<Int> = persistentListOf(),
    val isEnabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
    override val contentType: Any = "",
    override val key: Any = UUID.randomUUID().toString(),
) : Itemizable

data class EarthListItemColors(
    val borderColor: Color,
    val backgroundColor: Color
)

object EarthListItemDefaults {
    val contentPadding: PaddingValues
        get() = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

    @Composable
    fun LeadingItem(
        icon: ImageResource,
        badge: ImageResource?,
        contentDescription: String,
        modifier: Modifier = Modifier,
    ) = EarthListLeadingItem(icon, badge, contentDescription, modifier)

    @Composable
    fun TrailingItem(
        contentDescription: String,
        modifier: Modifier = Modifier
    ) = EarthListTrailingItem(contentDescription, modifier)

    @Composable
    fun ContentItem(
        text: String,
        subtitle: AnnotatedString?,
        titleIcons: ImmutableList<Int>,
        isEnabled: Boolean,
        modifier: Modifier = Modifier,
    ) = EarthListContentItem(text, subtitle, titleIcons, isEnabled, modifier)

    @Composable
    fun primaryColors(
        borderColor: Color = Color.Unspecified,
        backgroundColor: Color = Color.Transparent
    ): EarthListItemColors = EarthListItemColors(borderColor = borderColor, backgroundColor = backgroundColor)

    @Composable
    fun secondaryColors(
        borderColor: Color = EarthColors.Surfaces.strokeSecondary,
        backgroundColor: Color = Color.Transparent
    ): EarthListItemColors = EarthListItemColors(borderColor = borderColor, backgroundColor = backgroundColor)
}

@PreviewScreens
@Composable
private fun PrimaryPreview() =
    ZcashTheme {
        BlankSurface {
            Column(
                verticalArrangement = spacedBy(16.dp)
            ) {
                EarthListItem(
                    title = "Test",
                    subtitle = "Subtitle",
                    icon = imageRes(R.drawable.ic_item_keystone),
                    badge = imageRes(R.drawable.ic_item_keystone),
                    onClick = {},
                    titleIcons =
                        persistentListOf(
                            R.drawable.ic_radio_button_checked,
                            R.drawable.ic_radio_button_checked,
                        )
                )
                EarthListItem(
                    title = "Test",
                    subtitle = "Subtitle",
                    isEnabled = false,
                    onClick = {},
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun SecondaryPreview() =
    ZcashTheme {
        BlankSurface {
            Column(
                verticalArrangement = spacedBy(16.dp)
            ) {
                EarthListItem(
                    title = "Test",
                    subtitle = "Subtitle",
                    type = EarthListItemDesignType.SECONDARY,
                    icon = imageRes(R.drawable.ic_radio_button_checked),
                    onClick = {},
                    titleIcons = persistentListOf(R.drawable.ic_radio_button_checked)
                )
                EarthListItem(
                    title = "Test",
                    subtitle = "Subtitle",
                    type = EarthListItemDesignType.SECONDARY,
                    isEnabled = false,
                    onClick = {},
                )
            }
        }
    }
