/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.designsystem.util

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

sealed interface ImageResource {
    @JvmInline
    value class ByDrawable(
        @param:DrawableRes val resource: Int
    ) : ImageResource

    @JvmInline
    value class DisplayString(
        val value: String
    ) : ImageResource

    data object Loading : ImageResource
}

@Stable
fun imageRes(
    @DrawableRes resource: Int
): ImageResource = ImageResource.ByDrawable(resource)

@Stable
fun imageRes(value: String): ImageResource = ImageResource.DisplayString(value)

@Composable
fun ImageResource.ByDrawable.Compose(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null
) {
    Image(
        modifier = modifier,
        painter = painterResource(resource),
        contentDescription = contentDescription,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter
    )
}
