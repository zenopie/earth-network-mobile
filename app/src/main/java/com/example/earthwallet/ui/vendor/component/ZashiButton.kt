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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.component.ButtonStyle.DESTRUCTIVE1
import network.erth.wallet.ui.vendor.component.ButtonStyle.DESTRUCTIVE2
import network.erth.wallet.ui.vendor.component.ButtonStyle.PRIMARY
import network.erth.wallet.ui.vendor.component.ButtonStyle.SECONDARY
import network.erth.wallet.ui.vendor.component.ButtonStyle.TERTIARY
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.colors.EarthColorsInternal
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.steppedRotation
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    hapticFeedbackType: HapticFeedbackType? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    style: TextStyle = EarthButtonDefaults.style,
    shape: Shape = EarthButtonDefaults.shape,
    contentPadding: PaddingValues = EarthButtonDefaults.contentPadding,
    colors: EarthButtonColors = LocalEarthButtonColors.current ?: EarthButtonDefaults.primaryColors(),
    content: @Composable RowScope.(EarthButtonScope) -> Unit = EarthButtonDefaults.content
) {
    val state =
        remember(text, icon, trailingIcon, enabled, isLoading, onClick) {
            ButtonState(
                text = stringRes(text),
                icon = icon,
                trailingIcon = trailingIcon,
                isEnabled = enabled,
                isLoading = isLoading,
                onClick = onClick,
                hapticFeedbackType = hapticFeedbackType
            )
        }

    EarthButton(
        state = state,
        modifier = modifier,
        style = style,
        shape = shape,
        contentPadding = contentPadding,
        defaultPrimaryColors = colors,
        content = content
    )
}

@Suppress("LongParameterList")
@Composable
fun EarthButton(
    state: ButtonState,
    modifier: Modifier = Modifier,
    style: TextStyle = EarthButtonDefaults.style,
    shape: Shape = EarthButtonDefaults.shape,
    contentPadding: PaddingValues = EarthButtonDefaults.contentPadding,
    defaultPrimaryColors: EarthButtonColors = LocalEarthButtonColors.current ?: EarthButtonDefaults.primaryColors(),
    defaultSecondaryColors: EarthButtonColors = EarthButtonDefaults.secondaryColors(),
    defaultTertiaryColors: EarthButtonColors = EarthButtonDefaults.tertiaryColors(),
    defaultDestructive1Colors: EarthButtonColors = EarthButtonDefaults.destructive1Colors(),
    defaultDestructive2Colors: EarthButtonColors = EarthButtonDefaults.destructive2Colors(),
    content: @Composable RowScope.(EarthButtonScope) -> Unit = EarthButtonDefaults.content
) {
    val scope =
        object : EarthButtonScope {
            @Composable
            override fun LeadingIcon() {
                if (state.icon != null) {
                    Image(
                        painter = painterResource(state.icon),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(20.dp)
                                .let { if (state.isIconRotating) it.steppedRotation() else it },
                        colorFilter = ColorFilter.tint(LocalContentColor.current)
                    )
                }
            }

            @Composable
            override fun TrailingIcon() {
                if (state.trailingIcon != null) {
                    Image(
                        painter = painterResource(state.trailingIcon),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(LocalContentColor.current)
                    )
                }
            }

            @Composable
            override fun Text() {
                Text(
                    text = state.text.getValue(),
                    style = style,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            @Composable
            override fun Loading() {
                if (state.isLoading) {
                    val isLightContent = LocalContentColor.current.luminance() > EarthButtonDefaults.IS_LIGHT_THRESHOLD
                    LottieProgress(
                        loadingRes = if (isLightContent) R.raw.lottie_loading_white else R.raw.lottie_loading
                    )
                }
            }
        }

    val actualColors =
        when (state.style) {
            PRIMARY -> defaultPrimaryColors
            SECONDARY -> defaultSecondaryColors
            TERTIARY -> defaultTertiaryColors
            DESTRUCTIVE1 -> defaultDestructive1Colors
            DESTRUCTIVE2 -> defaultDestructive2Colors
            null -> defaultPrimaryColors
        }

    val borderColor = if (state.isEnabled) actualColors.borderColor else actualColors.disabledBorderColor

    val haptic = LocalHapticFeedback.current

    Button(
        onClick =
            if (state.hapticFeedbackType != null) {
                {
                    runCatching { haptic.performHapticFeedback(state.hapticFeedbackType) }
                    state.onClick()
                }
            } else {
                state.onClick
            },
        modifier = modifier,
        shape = shape,
        contentPadding = contentPadding,
        enabled = state.isEnabled,
        colors = actualColors.toButtonColors(),
        border = borderColor.takeIf { it != Color.Unspecified }?.let { BorderStroke(1.dp, it) },
        content = { content(scope) }
    )
}

interface EarthButtonScope {
    @Composable
    fun LeadingIcon()

    @Composable
    fun TrailingIcon()

    @Composable
    fun Text()

    @Composable
    fun Loading()
}

object EarthButtonDefaults {
    val content: @Composable RowScope.(EarthButtonScope) -> Unit
        get() = { scope ->
            scope.LeadingIcon()
            Spacer(modifier = Modifier.width(6.dp))
            scope.Text()
            Spacer(modifier = Modifier.width(6.dp))
            scope.TrailingIcon()
            scope.Loading()
        }

    val style: TextStyle
        @Composable get() = EarthTypography.textMd

    val contentPadding: PaddingValues
        get() = PaddingValues(horizontal = 10.dp)

    val shape: Shape
        get() = RoundedCornerShape(12.dp)

    const val IS_LIGHT_THRESHOLD = 0.5f

    @Composable
    fun primaryColors(
        source: EarthColorsInternal = EarthColors,
        containerColor: Color = source.Btns.Primary.btnPrimaryBg,
        contentColor: Color = source.Btns.Primary.btnPrimaryFg,
        disabledContainerColor: Color = source.Btns.Primary.btnPrimaryBgDisabled,
        disabledContentColor: Color = source.Btns.Primary.btnBoldFgDisabled,
    ) = EarthButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        borderColor = Color.Unspecified,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
        disabledBorderColor = Color.Unspecified
    )

    @Composable
    fun secondaryColors(
        source: EarthColorsInternal = EarthColors,
        containerColor: Color = source.Btns.Secondary.btnSecondaryBg,
        contentColor: Color = source.Btns.Secondary.btnSecondaryFg,
        // Earth fix: default to the border token rather than dropping it. The
        // secondary button's fill is the same white as the page, so without
        // the border it is invisible — the palette has btnSecondaryBorder for
        // exactly this and nothing was reading it.
        borderColor: Color = source.Btns.Secondary.btnSecondaryBorder,
        disabledContainerColor: Color = source.Btns.Secondary.btnSecondaryBgDisabled,
        disabledContentColor: Color = source.Btns.Secondary.btnSecondaryFgDisabled,
    ) = EarthButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        borderColor = borderColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
        disabledBorderColor = Color.Unspecified
    )

    @Composable
    fun tertiaryColors(
        source: EarthColorsInternal = EarthColors,
        containerColor: Color = source.Btns.Tertiary.btnTertiaryBg,
        contentColor: Color = source.Btns.Tertiary.btnTertiaryFg,
        disabledContainerColor: Color = source.Btns.Tertiary.btnTertiaryBgDisabled,
        disabledContentColor: Color = source.Btns.Tertiary.btnTertiaryFgDisabled,
    ) = EarthButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        borderColor = Color.Unspecified,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
        disabledBorderColor = Color.Unspecified
    )

    @Composable
    fun destructive1Colors(
        source: EarthColorsInternal = EarthColors,
        containerColor: Color = source.Btns.Destructive1.btnDestroy1Bg,
        contentColor: Color = source.Btns.Destructive1.btnDestroy1Fg,
        borderColor: Color = source.Btns.Destructive1.btnDestroy1Border,
        disabledContainerColor: Color = source.Btns.Destructive1.btnDestroy1BgDisabled,
        disabledContentColor: Color = source.Btns.Destructive1.btnDestroy1FgDisabled,
    ) = EarthButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
        borderColor = borderColor,
        disabledBorderColor = Color.Unspecified
    )

    @Composable
    fun destructive2Colors(
        source: EarthColorsInternal = EarthColors,
        containerColor: Color = source.Btns.Destructive2.btnDestroy2Bg,
        contentColor: Color = source.Btns.Destructive2.btnDestroy2Fg,
        borderColor: Color = Color.Unspecified,
        disabledContainerColor: Color = source.Btns.Destructive2.btnDestroy2BgDisabled,
        disabledContentColor: Color = source.Btns.Destructive2.btnDestroy2FgDisabled,
    ) = EarthButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
        borderColor = borderColor,
        disabledBorderColor = Color.Unspecified
    )
}

data class EarthButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
    val disabledBorderColor: Color,
)

/**
 * @property style explicit button style
 */
data class ButtonState(
    val text: StringResource,
    val style: ButtonStyle? = null,
    @param:DrawableRes val icon: Int? = null,
    @param:DrawableRes val trailingIcon: Int? = null,
    val isEnabled: Boolean = true,
    val isLoading: Boolean = false,
    // Spins [icon] in discrete 45°/100ms steps — for a static loading-style icon (e.g. a clock/
    // spinner glyph) rather than the built-in Lottie [isLoading] spinner.
    val isIconRotating: Boolean = false,
    val hapticFeedbackType: HapticFeedbackType? = null,
    val onClick: () -> Unit = {},
) {
    companion object {
        val preview = ButtonState(stringRes("Test"))
    }
}

enum class ButtonStyle {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    DESTRUCTIVE1,
    DESTRUCTIVE2,
}

@Composable
fun EarthButtonColors.toButtonColors() =
    ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
    )

@Suppress("CompositionLocalAllowlist")
val LocalEarthButtonColors =
    compositionLocalOf<EarthButtonColors?> {
        null
    }

@PreviewScreens
@Composable
private fun PrimaryPreview() =
    ZcashTheme {
        BlankSurface {
            EarthButton(
                modifier = Modifier.fillMaxWidth(),
                text = "Primary",
                onClick = {},
            )
        }
    }

@PreviewScreens
@Composable
private fun PrimaryWithIconPreview() =
    ZcashTheme {
        BlankSurface {
            EarthButton(
                modifier = Modifier.fillMaxWidth(),
                text = "Primary",
                icon = android.R.drawable.ic_secure,
                onClick = {},
            )
        }
    }

@PreviewScreens
@Composable
private fun TertiaryPreview() =
    ZcashTheme {
        BlankSurface {
            EarthButton(
                modifier = Modifier.fillMaxWidth(),
                text = "Primary",
                colors = EarthButtonDefaults.tertiaryColors(),
                onClick = {},
            )
        }
    }

@PreviewScreens
@Composable
private fun DestroyPreview() =
    ZcashTheme {
        BlankSurface {
            EarthButton(
                modifier = Modifier.fillMaxWidth(),
                text = "Primary",
                colors = EarthButtonDefaults.destructive1Colors(),
                isLoading = true,
                onClick = {},
            )
        }
    }

@PreviewScreens
@Composable
private fun SmallWidthPreview() =
    ZcashTheme {
        BlankSurface {
            EarthButton(
                modifier = Modifier.wrapContentWidth(),
                text = "Small Width Button",
                colors = EarthButtonDefaults.destructive1Colors(),
                onClick = {},
            )
        }
    }
