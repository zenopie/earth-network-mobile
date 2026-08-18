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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthBadge(
    text: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    contentPadding: PaddingValues = EarthBadgeDefaults.contentPadding,
    leadingIconVector: Painter? = null,
    colors: EarthBadgeColors = EarthBadgeDefaults.successColors()
) {
    EarthBadge(
        text = stringRes(text),
        shape = shape,
        leadingIconVector = leadingIconVector,
        modifier = modifier,
        colors = colors,
        contentPadding = contentPadding
    )
}

@Composable
fun EarthBadge(
    text: StringResource,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    contentPadding: PaddingValues = EarthBadgeDefaults.contentPadding,
    leadingIconVector: Painter? = null,
    colors: EarthBadgeColors = EarthBadgeDefaults.successColors()
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.container,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(contentPadding)
        ) {
            if (leadingIconVector != null) {
                Image(
                    painter = leadingIconVector,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = text.getValue(),
                style = ZcashTheme.extendedTypography.transactionItemStyles.contentMedium,
                fontSize = 14.sp,
                color = colors.text
            )
        }
    }
}

data class EarthBadgeColors(
    val border: Color,
    val text: Color,
    val container: Color,
)

object EarthBadgeDefaults {
    val contentPadding: PaddingValues
        get() = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

    @Composable
    fun successColors(
        border: Color = EarthColors.Utility.SuccessGreen.utilitySuccess200,
        text: Color = EarthColors.Utility.SuccessGreen.utilitySuccess700,
        background: Color = EarthColors.Utility.SuccessGreen.utilitySuccess50,
    ) = EarthBadgeColors(
        border = border,
        text = text,
        container = background,
    )

    @Composable
    fun hyperBlueColors(
        border: Color = EarthColors.Utility.HyperBlue.utilityBlueDark200,
        text: Color = EarthColors.Utility.HyperBlue.utilityBlueDark700,
        background: Color = EarthColors.Utility.HyperBlue.utilityBlueDark50,
    ) = EarthBadgeColors(
        border = border,
        text = text,
        container = background,
    )

    @Composable
    fun errorColors(
        border: Color = EarthColors.Utility.ErrorRed.utilityError200,
        text: Color = EarthColors.Utility.ErrorRed.utilityError700,
        background: Color = EarthColors.Utility.ErrorRed.utilityError50,
    ) = EarthBadgeColors(
        border = border,
        text = text,
        container = background,
    )

    @Composable
    fun warningColors(
        border: Color = EarthColors.Utility.WarningYellow.utilityOrange200,
        text: Color = EarthColors.Utility.WarningYellow.utilityOrange700,
        background: Color = EarthColors.Utility.WarningYellow.utilityOrange50,
    ) = EarthBadgeColors(
        border = border,
        text = text,
        container = background,
    )

    @Composable
    fun infoColors(
        border: Color = EarthColors.Utility.Gray.utilityGray200,
        text: Color = EarthColors.Utility.Gray.utilityGray700,
        background: Color = EarthColors.Utility.Gray.utilityGray50,
    ) = EarthBadgeColors(
        border = border,
        text = text,
        container = background,
    )
}

@PreviewScreens
@Composable
private fun BadgePreview() =
    ZcashTheme {
        EarthBadge(
            text = stringRes("Badge"),
            leadingIconVector = painterResource(id = android.R.drawable.ic_input_add),
        )
    }
