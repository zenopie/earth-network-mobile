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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import network.erth.wallet.ui.vendor.util.orHiddenString
import network.erth.wallet.ui.vendor.util.stringRes
import network.erth.wallet.ui.vendor.util.stringResByNumber
import com.valentinilk.shimmer.shimmer
import java.math.BigDecimal

@Composable
internal fun EarthSwapQuoteAmount(
    state: SwapTokenAmountState?,
    modifier: Modifier = Modifier,
    isMirrored: Boolean = false,
) {
    Box(
        modifier = modifier.padding(EarthDimensions.Spacing.spacingXl),
    ) {
        Layout(
            state = state,
            isMirrored = isMirrored,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun Layout(
    state: SwapTokenAmountState?,
    isMirrored: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier.then(
                if (state == null) {
                    Modifier.shimmer(rememberEarthShimmer())
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = if (isMirrored) Alignment.End else Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isMirrored) {
                TopBottom(state, false)
                Spacer(16.dp)
                ShimmerableIcon(state)
            } else {
                ShimmerableIcon(state)
                Spacer(16.dp)
                TopBottom(state, true)
            }
        }
        EarthHorizontalDivider(
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = EarthDimensions.Spacing.spacingXl),
            color = EarthColors.Surfaces.bgTertiary,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isMirrored) Alignment.End else Alignment.Start
        ) {
            ShimmerableText(
                text = state?.let { it.amount orHiddenString stringRes(R.string.general_hideBalancesMost) },
                shimmerText = stringResByNumber(BigDecimal(".123456")).getValue(),
                style = EarthTypography.textSm,
                fontWeight = FontWeight.Medium,
                color = EarthColors.Text.textPrimary,
            )
            ShimmerableText(
                text = state?.let { it.fiatAmount orHiddenString stringRes(R.string.general_hideBalancesMost) },
                shimmerText = stringResByNumber(BigDecimal(".123")).getValue(),
                style = EarthTypography.textXxs,
                fontWeight = FontWeight.Medium,
                color = EarthColors.Text.textTertiary,
            )
        }
    }
}

@Composable
private fun RowScope.TopBottom(state: SwapTokenAmountState?, end: Boolean) {
    Column(
        horizontalAlignment = if (end) Alignment.Start else Alignment.End,
        modifier = Modifier.weight(1f)
    ) {
        ShimmerableText(
            text = state?.token?.getValue(),
            shimmerText = stringResByNumber(BigDecimal(".123456")).getValue(),
            style = EarthTypography.textSm,
            fontWeight = FontWeight.SemiBold,
            color = EarthColors.Text.textPrimary,
        )
        ShimmerableText(
            text = state?.chain?.getValue(),
            shimmerText = stringResByNumber(BigDecimal(".123456")).getValue(),
            style = EarthTypography.textXxs.copy(lineHeight = 10.sp),
            fontWeight = FontWeight.Medium,
            color = EarthColors.Text.textTertiary,
            maxLines = 2,
            textAlign = if (end) TextAlign.Start else TextAlign.End
        )
    }
}

@Composable
private fun ShimmerableIcon(state: SwapTokenAmountState?) {
    if (state == null) {
        Box {
            ShimmerCircle(
                size = 28.dp,
                color = EarthColors.Surfaces.bgTertiary
            )
            Box(
                modifier =
                    Modifier
                        .offset(4.dp, 4.dp)
                        .size(12.dp)
                        .border(1.dp, EarthColors.Surfaces.bgSecondary, CircleShape)
                        .align(Alignment.BottomEnd)
                        .background(EarthColors.Surfaces.bgTertiary, CircleShape)
            )
        }
    } else {
        Icon(state)
    }
}

@Composable
private fun Icon(state: SwapTokenAmountState) {
    if (state.bigIcon is ImageResource.ByDrawable) {
        Box {
            Image(
                modifier = Modifier.size(28.dp),
                painter = painterResource(state.bigIcon.resource),
                contentDescription = null
            )

            if (state.smallIcon is ImageResource.ByDrawable) {
                if (state.smallIcon.resource in
                    listOf(R.drawable.ic_zec_shielded, R.drawable.ic_zec_unshielded)
                ) {
                    Image(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .align(Alignment.BottomEnd)
                                .offset(4.dp, 4.dp),
                        painter = painterResource(state.smallIcon.resource),
                        contentDescription = null,
                    )
                } else {
                    Surface(
                        modifier =
                            Modifier
                                .size(14.dp)
                                .align(Alignment.BottomEnd)
                                .offset(4.dp, 4.dp),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, EarthColors.Surfaces.bgPrimary)
                    ) {
                        Image(
                            modifier = Modifier.size(14.dp),
                            painter = painterResource(state.smallIcon.resource),
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}

data class SwapTokenAmountState(
    val amount: StringResource,
    val fiatAmount: StringResource,
    val token: StringResource,
    val chain: StringResource,
    val bigIcon: ImageResource,
    val smallIcon: ImageResource,
)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            Column {
                EarthSwapQuoteAmount(
                    modifier = Modifier.fillMaxWidth(.75f),
                    state =
                        SwapTokenAmountState(
                            token = stringRes("ZEC"),
                            chain = stringRes("Chain"),
                            bigIcon = imageRes(R.drawable.ic_chain_placeholder),
                            smallIcon = imageRes(R.drawable.ic_zec_shielded),
                            amount = stringRes("0.1231231"),
                            fiatAmount = stringRes("$123.45")
                        )
                )
                EarthSwapQuoteAmount(
                    modifier = Modifier.fillMaxWidth(.75f),
                    isMirrored = true,
                    state =
                        SwapTokenAmountState(
                            token = stringRes("ZEC"),
                            chain = stringRes("Chain"),
                            bigIcon = imageRes(R.drawable.ic_chain_placeholder),
                            smallIcon = imageRes(R.drawable.ic_zec_shielded),
                            amount = stringRes("0.1231231"),
                            fiatAmount = stringRes("$123.45")
                        )
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun LoadingPreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                EarthSwapQuoteAmount(
                    state = null
                )
                EarthSwapQuoteAmount(
                    state = null,
                    isMirrored = true
                )
            }
        }
    }
