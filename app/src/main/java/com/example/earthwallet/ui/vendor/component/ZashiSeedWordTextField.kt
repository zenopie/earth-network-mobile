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

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.ui.vendor.util.getString
import network.erth.wallet.ui.vendor.util.rememberDesiredFormatLocale
import network.erth.wallet.ui.vendor.util.stringRes

@Composable
fun EarthSeedWordTextField(
    prefix: String,
    state: SeedWordTextFieldState,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val context = LocalContext.current
    val locale = rememberDesiredFormatLocale()
    EarthTextField(
        modifier = modifier,
        innerModifier = innerModifier,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        maxLines = 1,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        state =
            EnhancedTextFieldState(
                innerState =
                    InnerTextFieldState(
                        value = stringRes(state.innerState.value),
                        selection = state.innerState.selection
                    ),
                onValueChange = {
                    state.onValueChange(
                        SeedWordInnerTextFieldState(
                            value = it.value.getString(context, locale),
                            selection = it.selection
                        )
                    )
                },
                error = stringRes("").takeIf { state.isError }
            ),
        textStyle = EarthTypography.textMd,
        prefix = {
            Box(
                modifier =
                    Modifier
                        .size(22.dp)
                        .background(EarthColors.Tags.tcCountBg, CircleShape)
                        .padding(end = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = prefix,
                    style = EarthTypography.textSm,
                    color = EarthColors.Tags.tcCountFg,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        colors =
            EarthTextFieldDefaults.defaultColors(
                containerColor = EarthColors.Surfaces.bgSecondary,
                focusedContainerColor = EarthColors.Surfaces.bgPrimary,
                focusedBorderColor = EarthColors.Accordion.focusStroke
            ),
    )
}

data class SeedWordTextFieldState(
    val innerState: SeedWordInnerTextFieldState,
    val isError: Boolean,
    val onValueChange: (SeedWordInnerTextFieldState) -> Unit
)

data class SeedWordInnerTextFieldState(
    val value: String,
    val selection: TextSelection = TextSelection.Start,
)

@Composable
@PreviewScreens
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthSeedWordTextField(
                prefix = "12",
                state =
                    SeedWordTextFieldState(
                        innerState =
                            SeedWordInnerTextFieldState(
                                value = "asd",
                                selection = TextSelection.Start
                            ),
                        isError = false,
                        onValueChange = {},
                    )
            )
        }
    }
