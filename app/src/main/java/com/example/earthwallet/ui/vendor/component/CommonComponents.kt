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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.theme.ZcashTheme

@Preview
@Composable
private fun TopScreenLogoRegularComposablePreview() {
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            TopScreenLogoTitle(
                title = "Test screen title",
                logoContentDescription = "Test logo content description"
            )
        }
    }
}

@Preview
@Composable
private fun TopScreenLogoRegularDarkComposablePreview() {
    ZcashTheme(forceDarkMode = true) {
        BlankSurface {
            TopScreenLogoTitle(
                title = "Test screen title",
                logoContentDescription = "Test logo content description"
            )
        }
    }
}

@Preview
@Composable
private fun TopScreenLogoLongComposablePreview() {
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            TopScreenLogoTitle(
                title = "Test screen title which is very very long and can overflow the allowed title length",
                logoContentDescription = "Test logo content description"
            )
        }
    }
}

@Composable
fun TopScreenLogoTitle(
    title: String,
    logoContentDescription: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Image(
            painter = painterResource(id = R.drawable.logo),
            colorFilter = ColorFilter.tint(color = ZcashTheme.colors.secondaryColor),
            contentDescription = logoContentDescription,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(ZcashTheme.dimens.spacingLarge))

        Text(
            text = title,
            color = ZcashTheme.colors.textPrimary,
            style = ZcashTheme.typography.secondary.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
