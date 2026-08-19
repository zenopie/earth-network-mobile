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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

@Composable
fun EarthInfoRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
) {
    Row {
        Image(
            painterResource(icon),
            contentDescription = null
        )
        Spacer(16.dp)
        Column {
            Spacer(2.dp)
            Text(
                text = title,
                color = EarthColors.Text.textPrimary,
                style = EarthTypography.textSm,
                fontWeight = FontWeight.Medium
            )
            Spacer(4.dp)
            Text(
                text = subtitle,
                color = EarthColors.Text.textTertiary,
                style = EarthTypography.textSm
            )
        }
    }
}
