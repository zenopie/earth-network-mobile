/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
@file:Suppress("TooManyFunctions")

package network.erth.wallet.ui.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.designsystem.theme.ZcashTheme
import network.erth.wallet.ui.designsystem.theme.internal.SecondaryTypography
import network.erth.wallet.ui.designsystem.theme.internal.TopAppBarColors

@Composable
@Suppress("LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
fun SmallTopAppBar(
    modifier: Modifier = Modifier,
    colors: TopAppBarColors = ZcashTheme.colors.topAppBarColors,
    hamburgerMenuActions: (@Composable RowScope.() -> Unit)? = null,
    navigationAction: @Composable () -> Unit = {},
    regularActions: (@Composable RowScope.() -> Unit)? = null,
    subTitle: String? = null,
    showTitleLogo: Boolean = false,
    titleText: String? = null,
    titleStyle: TextStyle = SecondaryTypography.headlineSmall,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    CenterAlignedTopAppBar(
        windowInsets = windowInsets,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var restoringSpacerHeight: Dp = 0.dp

                if (titleText != null) {
                    Text(
                        text = titleText.uppercase(),
                        style = titleStyle,
                        color = colors.titleColor,
                    )
                    restoringSpacerHeight = ZcashTheme.dimens.spacingTiny
                } else if (showTitleLogo) {
                    Icon(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = null,
                        tint = colors.titleColor,
                        modifier = Modifier.height(ZcashTheme.dimens.topAppBarZcashLogoHeight)
                    )
                    restoringSpacerHeight = ZcashTheme.dimens.spacingSmall
                }

                if (subTitle != null) {
                    Spacer(modifier = Modifier.height(restoringSpacerHeight))

                    @Suppress("MagicNumber")
                    Text(
                        text = subTitle.uppercase(),
                        style = ZcashTheme.extendedTypography.restoringTopAppBarStyle,
                        color = colors.subTitleColor,
                        modifier = Modifier.fillMaxWidth(0.75f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            navigationAction()
        },
        actions = {
            regularActions?.invoke(this)
            hamburgerMenuActions?.invoke(this)
        },
        colors = colors.toMaterialTopAppBarColors(),
        modifier =
            Modifier
                .testTag(CommonTag.TOP_APP_BAR)
                .then(modifier)
    )
}

@Composable
@Suppress("LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
fun SmallTopAppBar(
    modifier: Modifier = Modifier,
    colors: TopAppBarColors = ZcashTheme.colors.topAppBarColors,
    hamburgerMenuActions: (@Composable RowScope.() -> Unit)? = null,
    navigationAction: @Composable () -> Unit = {},
    regularActions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    CenterAlignedTopAppBar(
        windowInsets = windowInsets,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (content != null) content()
            }
        },
        navigationIcon = {
            navigationAction()
        },
        actions = {
            regularActions?.invoke(this)
            hamburgerMenuActions?.invoke(this)
        },
        colors = colors.toMaterialTopAppBarColors(),
        modifier =
            Modifier
                .testTag(CommonTag.TOP_APP_BAR)
                .then(modifier)
    )
}
