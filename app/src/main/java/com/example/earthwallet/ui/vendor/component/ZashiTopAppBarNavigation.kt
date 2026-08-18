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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.theme.colors.EarthColors

@Composable
fun EarthTopAppBarBackNavigation(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = EarthTopAppBarNavigation(
    modifier = modifier,
    backContentDescriptionText = stringResource(R.string.general_back),
    drawableRes = R.drawable.ic_earth_navigation_back,
    onBack = onBack,
    enabled = enabled,
)

@Composable
fun EarthTopAppBarCloseNavigation(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) = EarthTopAppBarNavigation(
    modifier = modifier,
    backContentDescriptionText = stringResource(R.string.general_back),
    drawableRes = R.drawable.ic_navigation_close,
    onBack = onBack,
    tint = EarthColors.Text.textPrimary
)

@Composable
fun EarthTopAppBarHamburgerNavigation(onBack: () -> Unit) =
    EarthTopAppBarNavigation(
        backContentDescriptionText = stringResource(R.string.general_back),
        drawableRes = R.drawable.ic_navigation_hamburger,
        onBack = onBack,
        tint = EarthColors.Text.textPrimary
    )

@Composable
fun EarthTopAppBarBigCloseNavigation(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
    ) {
        Spacer(24.dp)
        Button(
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
            onClick = onBack,
            shape = RoundedCornerShape(12.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = EarthColors.Btns.Tertiary.btnTertiaryBg
                )
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_settings_opt_int_close),
                contentDescription = null,
                colorFilter = ColorFilter.tint(EarthColors.Btns.Tertiary.btnTertiaryFg)
            )
        }
    }
}

@Composable
fun EarthTopAppBarNavigation(
    backContentDescriptionText: String,
    @DrawableRes drawableRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        IconButton(
            onClick = onBack,
            enabled = enabled,
            modifier = Modifier.testTag(EarthTopAppBarNavigationTag.BACK)
        ) {
            Icon(
                painter = painterResource(drawableRes),
                contentDescription = backContentDescriptionText,
                tint = tint ?: LocalContentColor.current
            )
        }
    }
}

object EarthTopAppBarNavigationTag {
    const val BACK = "NAVIGATION_BACK"
}
