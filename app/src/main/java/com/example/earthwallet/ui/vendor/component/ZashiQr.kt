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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.util.AndroidQrCodeImageGenerator
import network.erth.wallet.ui.vendor.util.JvmQrCodeGenerator
import network.erth.wallet.ui.vendor.util.QrCodeColors
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.orDark

@Composable
fun EarthQr(
    state: QrState,
    modifier: Modifier = Modifier,
    qrSize: Dp = EarthQrDefaults.width,
    colors: QrCodeColors = QrCodeDefaults.colors(),
    contentPadding: PaddingValues = QrCodeDefaults.contentPadding()
) {
    var isFullscreenDialogVisible by remember { mutableStateOf(false) }

    EarthQrInternal(
        state = state,
        modifier =
            modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        isFullscreenDialogVisible = true
                    }
                ),
        colors = colors,
        contentPadding = contentPadding,
        qrSize = qrSize,
        enableBitmapReload = !isFullscreenDialogVisible,
        centerImage = state.centerImage,
    )

    if (isFullscreenDialogVisible) {
        Dialog(
            onDismissRequest = { isFullscreenDialogVisible = false },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
        ) {
            val parent = LocalView.current.parent
            SideEffect {
                (parent as? DialogWindowProvider)?.window?.setDimAmount(FULLSCREEN_DIM)
            }
            BrightenScreen()
            ConfigurationOverride(isDarkTheme = false) {
                FullscreenDialogContent(
                    state = state,
                    onBack = { isFullscreenDialogVisible = false },
                )
            }
        }
    }
}

@Composable
private fun EarthQrInternal(
    state: QrState,
    qrSize: Dp,
    colors: QrCodeColors,
    contentPadding: PaddingValues,
    enableBitmapReload: Boolean,
    @DrawableRes centerImage: Int?,
    modifier: Modifier = Modifier,
) {
    val qrSizePx = with(LocalDensity.current) { qrSize.roundToPx() }
    var bitmap: ImageBitmap by remember {
        mutableStateOf(getQrCode(state.qrData, qrSizePx, colors))
    }

    var reload by remember { mutableStateOf(false) }

    LaunchedEffect(state.qrData, qrSizePx, colors) {
        if (enableBitmapReload && reload) {
            bitmap = getQrCode(state.qrData, qrSizePx, colors)
        }

        reload = true
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EarthDimensions.Radius.radius4xl),
        border = BorderStroke(width = 1.dp, color = colors.border).takeIf { colors.border.isSpecified },
        color = colors.background,
    ) {
        Box(
            modifier = Modifier.padding(contentPadding)
        ) {
            Image(
                modifier = Modifier,
                bitmap = bitmap,
                contentDescription = state.contentDescription?.getValue(),
            )

            if (centerImage != null) {
                Image(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .align(Alignment.Center),
                    painter = painterResource(centerImage),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun FullscreenDialogContent(
    state: QrState,
    onBack: () -> Unit
) {
    val containerWidth = LocalWindowInfo.current.containerSize.widthDp
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack
                ).padding(start = 16.dp, end = 16.dp, bottom = 64.dp)
    ) {
        EarthQrInternal(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
            state = state,
            contentPadding = PaddingValues(6.dp),
            colors = QrCodeDefaults.colors(border = Color.Unspecified),
            qrSize = if (containerWidth > 44.dp) containerWidth - 44.dp else containerWidth,
            enableBitmapReload = true,
            centerImage = state.centerImage,
        )
    }
}

private fun getQrCode(
    address: String,
    size: Int,
    colors: QrCodeColors
): ImageBitmap {
    val qrCodePixelArray = JvmQrCodeGenerator.generate(address, size)
    return AndroidQrCodeImageGenerator.generate(qrCodePixelArray, size, colors).asImageBitmap()
}

object EarthQrDefaults {
    val width: Dp
        @Composable
        get() = (LocalConfiguration.current.screenWidthDp * WIDTH_RATIO).dp
}

private const val WIDTH_RATIO = 0.66

object QrCodeDefaults {
    fun contentPadding() = PaddingValues(16.dp)

    @Composable
    fun colors(
        background: Color = Color.White orDark EarthColors.Surfaces.bgPrimary,
        foreground: Color = Color.Black orDark Color.White,
        border: Color = EarthColors.Surfaces.strokePrimary
    ) = QrCodeColors(
        background = background,
        foreground = foreground,
        border = border
    )
}

data class QrState(
    val qrData: String,
    val contentDescription: StringResource? = null,
    @param:DrawableRes val centerImage: Int? = null,
)

private const val FULLSCREEN_DIM = .9f
