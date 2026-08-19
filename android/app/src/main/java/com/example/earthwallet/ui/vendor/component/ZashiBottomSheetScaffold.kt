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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import network.erth.wallet.ui.vendor.theme.colors.EarthColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarthBottomSheetScaffold(
    sheetContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    scaffoldState: BottomSheetScaffoldState = rememberBottomSheetScaffoldState(),
    sheetSwipeEnabled: Boolean = true,
    topBar: @Composable (() -> Unit)? = null,
    snackbarHost: @Composable (SnackbarHostState) -> Unit = { SnackbarHost(it) },
    containerColor: Color = EarthColors.Surfaces.bgPrimary,
    contentColor: Color = EarthColors.Text.textPrimary,
    content: @Composable ((PaddingValues) -> Unit)? = null
) {
    BottomSheetScaffold(
        sheetContent = sheetContent,
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetShape = EarthModalBottomSheetDefaults.SheetShape,
        sheetContainerColor = EarthModalBottomSheetDefaults.ContainerColor,
        sheetContentColor = EarthModalBottomSheetDefaults.ContentColor,
        sheetDragHandle = { EarthModalBottomSheetDragHandle() },
        sheetSwipeEnabled = sheetSwipeEnabled,
        topBar = topBar,
        snackbarHost = snackbarHost,
        containerColor = containerColor,
        contentColor = contentColor,
        content = content ?: { },
    )
}
