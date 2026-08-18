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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.dimensions.EarthDimensions
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.StringResource
import network.erth.wallet.ui.vendor.util.getValue
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthCheckbox(
    text: StringResource,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSpacing: Dp = 2.dp,
    spacing: Dp = EarthDimensions.Spacing.spacingMd,
    textStyles: CheckboxTextStyles = EarthCheckboxDefaults.textStyles()
) {
    EarthCheckbox(
        state =
            CheckboxState(
                title = text,
                isChecked = isChecked,
                onClick = onClick,
            ),
        modifier = modifier,
        spacing = spacing,
        textStyles = textStyles,
        textSpacing = textSpacing
    )
}

@Composable
fun EarthCheckbox(
    state: CheckboxState,
    modifier: Modifier = Modifier,
    spacing: Dp = EarthDimensions.Spacing.spacingMd,
    textSpacing: Dp = 2.dp,
    contentPadding: PaddingValues = EarthCheckboxDefaults.contentPadding,
    textStyles: CheckboxTextStyles = EarthCheckboxDefaults.textStyles()
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = state.onClick)
                .padding(contentPadding)
    ) {
        EarthCheckboxIndicator(state.isChecked)

        Spacer(spacing)

        Column {
            Text(
                text = state.title.getValue(),
                style = textStyles.title,
            )
            state.subtitle?.let {
                Spacer(textSpacing)
                Text(
                    text = it.getValue(),
                    style = textStyles.subtitle
                )
            }
        }
    }
}

@Composable
fun EarthCheckboxIndicator(isChecked: Boolean) {
    Box {
        Image(
            painter = painterResource(R.drawable.ic_earth_checkbox),
            contentDescription = null
        )

        AnimatedVisibility(
            visible = isChecked,
            enter =
                scaleIn(
                    spring(
                        stiffness = Spring.StiffnessMedium,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                ),
            exit =
                scaleOut(
                    spring(
                        stiffness = Spring.StiffnessHigh,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                )
        ) {
            Image(
                painter = painterResource(R.drawable.ic_earth_checkbox_checked),
                contentDescription = null
            )
        }
    }
}

data class CheckboxTextStyles(
    val title: TextStyle,
    val subtitle: TextStyle
)

object EarthCheckboxDefaults {
    val spacing = EarthDimensions.Spacing.spacingMd

    val contentPadding = PaddingValues(vertical = 12.dp)

    @Composable
    fun textStyles(
        title: TextStyle =
            EarthTypography.textSm.copy(
                fontWeight = FontWeight.Medium,
                color = EarthColors.Text.textPrimary
            ),
        subtitle: TextStyle =
            EarthTypography.textSm.copy(
                color = EarthColors.Text.textTertiary
            ),
    ) = CheckboxTextStyles(title = title, subtitle = subtitle)
}

data class CheckboxState(
    val title: StringResource,
    val isChecked: Boolean,
    val subtitle: StringResource? = null,
    val onClick: () -> Unit,
)

@PreviewScreens
@Composable
private fun EarthCheckboxPreview() =
    ZcashTheme {
        var isChecked by remember { mutableStateOf(false) }
        BlankSurface {
            EarthCheckbox(
                state =
                    CheckboxState(
                        title = stringRes("title"),
                        subtitle = stringRes("subtitle"),
                        isChecked = isChecked,
                        onClick = { isChecked = isChecked.not() }
                    )
            )
        }
    }
