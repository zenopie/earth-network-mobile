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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthVersion(
    version: StringResource,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null
) {
    Column(
        modifier =
            modifier then
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        indication = null,
                        onClick = {},
                        onLongClick = onLongClick,
                        onDoubleClick = onDoubleClick,
                        interactionSource = remember { MutableInteractionSource() },
                    )
                } else {
                    Modifier
                }
    ) {
        Image(
            modifier = Modifier.align(CenterHorizontally).width(79.dp),
            painter =
                painterResource(id = R.drawable.app_logo),
            contentDescription = version.getValue()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            modifier = Modifier.align(CenterHorizontally),
            text = version.getValue(),
            color = EarthColors.Text.textTertiary
        )
    }
}

@PreviewScreens
@Composable
private fun EarthVersionPreview() =
    ZcashTheme {
        BlankSurface {
            EarthVersion(version = stringRes("Version"))
        }
    }
