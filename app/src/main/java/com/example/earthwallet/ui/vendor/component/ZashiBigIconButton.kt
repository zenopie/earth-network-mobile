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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.orDark
import network.erth.wallet.ui.vendor.util.stringRes

@Suppress("MagicNumber")
@Composable
fun EarthBigIconButton(
    state: BigIconButtonState,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shadowElevation by animateDpAsState(if (isPressed) 0.dp else (2.dp orDark 4.dp))

    val darkBgGradient =
        Brush.verticalGradient(
            0f to EarthColors.Surfaces.strokeSecondary,
            .66f to EarthColors.Surfaces.strokeSecondary.copy(alpha = 0.5f),
            1f to EarthColors.Surfaces.strokeSecondary.copy(alpha = 0.25f),
        )

    val darkBorderGradient =
        Brush.verticalGradient(
            0f to EarthColors.Surfaces.strokePrimary,
            1f to EarthColors.Surfaces.strokePrimary.copy(alpha = 0f),
        )

    val backgroundModifier =
        Modifier.background(EarthColors.Surfaces.bgPrimary) orDark
            Modifier.background(darkBgGradient)

    Surface(
        modifier = modifier,
        onClick = state.onClick,
        color = EarthColors.Surfaces.bgPrimary,
        shape = RoundedCornerShape(22.dp),
        border =
            BorderStroke(.5.dp, EarthColors.Utility.Gray.utilityGray100) orDark
                BorderStroke(.5.dp, darkBorderGradient),
        shadowElevation = shadowElevation,
        interactionSource = interactionSource
    ) {
        Column(
            modifier = backgroundModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                // Earth change: tint at the call site. Their icons ship in the
                // theme's ink already; Earth's action icons are reused from the
                // old app where they were drawn white for a coloured button, so
                // untinted they are invisible on this ground.
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .size(24.dp),
                painter = painterResource(state.icon),
                colorFilter = ColorFilter.tint(EarthColors.Text.textPrimary),
                contentDescription = state.text.getValue()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                modifier = Modifier.padding(bottom = 16.dp),
                text = state.text.getValue(),
                style = EarthTypography.textXs,
                fontWeight = FontWeight.Medium,
                color = EarthColors.Text.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

data class BigIconButtonState(
    val text: StringResource,
    @param:DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        EarthBigIconButton(
            state =
                BigIconButtonState(
                    text = stringRes("Text"),
                    icon = R.drawable.ic_reveal,
                    onClick = {}
                )
        )
    }
