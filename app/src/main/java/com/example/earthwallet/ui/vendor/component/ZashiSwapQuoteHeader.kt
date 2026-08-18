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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.util.TickerLocation
import network.erth.wallet.ui.vendor.util.imageRes
import network.erth.wallet.ui.vendor.util.stringRes
import network.erth.wallet.ui.vendor.util.stringResByDynamicCurrencyNumber

@Suppress("MagicNumber")
@Composable
fun EarthSwapQuoteHeader(
    state: SwapQuoteHeaderState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EarthDimensions.Radius.radius2xl),
        color = EarthColors.Surfaces.bgSecondary
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            EarthSwapQuoteAmount(
                modifier = Modifier.weight(1f),
                state = state.from
            )
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(8.dp),
                color = EarthColors.Btns.Secondary.btnSecondaryBg
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = null
                    )
                }
            }
            EarthSwapQuoteAmount(
                modifier = Modifier.weight(1f),
                state = state.to,
                isMirrored = true,
            )
        }
    }
}

data class SwapQuoteHeaderState(
    val from: SwapTokenAmountState?,
    val to: SwapTokenAmountState?,
)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthSwapQuoteHeader(
                state =
                    SwapQuoteHeaderState(
                        from =
                            SwapTokenAmountState(
                                bigIcon = imageRes(R.drawable.ic_chain_placeholder),
                                smallIcon = imageRes(R.drawable.ic_zec_shielded),
                                amount = stringResByDynamicCurrencyNumber(0.000000421423154, "", TickerLocation.HIDDEN),
                                fiatAmount = stringResByDynamicCurrencyNumber(0.0000000000000021312, "$"),
                                token = stringRes("ZEC"),
                                chain = stringRes("Chain"),
                            ),
                        to =
                            SwapTokenAmountState(
                                bigIcon = imageRes(R.drawable.ic_chain_placeholder),
                                smallIcon = imageRes(R.drawable.ic_zec_shielded),
                                amount = stringResByDynamicCurrencyNumber(0.000000421423154, "", TickerLocation.HIDDEN),
                                fiatAmount = stringResByDynamicCurrencyNumber(0.0000000000000021312, "$"),
                                token = stringRes("ZEC"),
                                chain = stringRes("Chain"),
                            )
                    )
            )
        }
    }

@PreviewScreens
@Composable
private fun LoadingPreview() =
    ZcashTheme {
        BlankSurface {
            EarthSwapQuoteHeader(
                state =
                    SwapQuoteHeaderState(
                        from = null,
                        to = null
                    )
            )
        }
    }
