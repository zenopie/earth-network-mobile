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
import androidx.compose.ui.graphics.ColorMatrix
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

    // Earth change: filled as a primary, not outlined.
    //
    // Theirs is a white card with a hairline border and a shadow, which reads
    // as a tile on their off-white page. On Earth's white page it is a white
    // square on white — the shadow was doing all the work.
    //
    // Primary rather than secondary: these four are the app's headline actions,
    // the first thing on the first screen, and there is nothing above them to
    // rank against. Making them the quieter of the two ranks would say the main
    // thing is somewhere else.
    val fill = if (state.isEnabled) {
        EarthColors.Btns.Brand.btnBrandBg
    } else {
        EarthColors.Btns.Brand.btnBrandBgDisabled
    }

    Surface(
        modifier = modifier,
        onClick = state.onClick,
        enabled = state.isEnabled,
        color = fill,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = shadowElevation,
        interactionSource = interactionSource
    ) {
        Column(
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
                colorFilter = when {
                    state.tint -> ColorFilter.tint(
                        if (state.isEnabled) {
                            EarthColors.Btns.Brand.btnBrandFg
                        } else {
                            EarthColors.Btns.Brand.btnBrandFgDisabled
                        },
                    )
                    // Untinted art still has to read as unavailable, so it is
                    // desaturated rather than left in full colour.
                    !state.isEnabled -> ColorFilter.colorMatrix(
                        ColorMatrix().apply { setToSaturation(0f) },
                    )
                    else -> null
                },
                contentDescription = state.text.getValue()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                modifier = Modifier.padding(bottom = 16.dp),
                text = state.text.getValue(),
                style = EarthTypography.textXs,
                fontWeight = FontWeight.Medium,
                color = if (state.isEnabled) {
                    EarthColors.Btns.Brand.btnBrandFg
                } else {
                    EarthColors.Btns.Brand.btnBrandFgDisabled
                },
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
    /** Earth addition: theirs are always live; Earth has actions that wait. */
    val isEnabled: Boolean = true,
    /**
     * Earth addition: whether to ink the icon with the theme's text colour.
     *
     * Their icons are all monochrome glyphs, so tinting is unconditional
     * there. Earth's token marks are not — the ANML coin is yellow, and
     * tinting it black turns the one recognisable thing about it into another
     * grey shape.
     */
    val tint: Boolean = true,
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
