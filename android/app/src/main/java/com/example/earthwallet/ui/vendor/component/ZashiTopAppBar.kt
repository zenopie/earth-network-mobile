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

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.internal.SecondaryTypography
import network.erth.wallet.ui.vendor.theme.internal.TopAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun EarthSmallTopAppBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    showTitleLogo: Boolean = false,
    colors: TopAppBarColors = ZcashTheme.colors.topAppBarColors,
    navigationAction: @Composable () -> Unit = {},
    hamburgerMenuActions: (@Composable RowScope.() -> Unit)? = null,
    regularActions: (@Composable RowScope.() -> Unit)? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    SmallTopAppBar(
        windowInsets = windowInsets,
        modifier = modifier,
        colors = colors,
        hamburgerMenuActions = hamburgerMenuActions,
        navigationAction = navigationAction,
        regularActions = regularActions,
        subTitle = subtitle,
        showTitleLogo = showTitleLogo,
        titleText = title,
        titleStyle = SecondaryTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun EarthSmallTopAppBar(
    content: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: TopAppBarColors = ZcashTheme.colors.topAppBarColors,
    navigationAction: @Composable () -> Unit = {},
    hamburgerMenuActions: (@Composable RowScope.() -> Unit)? = null,
    regularActions: (@Composable RowScope.() -> Unit)? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    SmallTopAppBar(
        modifier = modifier,
        colors = colors,
        hamburgerMenuActions = hamburgerMenuActions,
        navigationAction = navigationAction,
        regularActions = regularActions,
        content = content,
        windowInsets = windowInsets,
    )
}

@PreviewScreens
@Composable
private fun EarthSmallTopAppBarPreview() =
    ZcashTheme {
        EarthSmallTopAppBar(
            title = "Test Title",
            subtitle = "Subtitle",
        )
    }
