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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.erth.wallet.ui.vendor.newcomponent.PreviewScreens
import network.erth.wallet.ui.vendor.theme.ZcashTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

@Composable
fun EarthSeedWordText(
    prefix: String,
    state: SeedWordTextState,
    modifier: Modifier = Modifier,
    prefixContent: @Composable (Modifier, String) -> Unit = { mod, text -> EarthSeedWordPrefixContent(text, mod) },
    content: @Composable (Modifier, String) -> Unit = { mod, text -> EarthSeedWordTextContent(text, mod) }
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = EarthColors.Surfaces.bgSecondary,
    ) {
        Box(
            contentAlignment = Alignment.CenterStart
        ) {
            prefixContent(Modifier, prefix)
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                content(
                    Modifier.weight(1f),
                    state.text
                )
            }
        }
    }
}

@Composable
fun EarthSeedWordPrefixContent(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        modifier = modifier then Modifier.padding(start = 12.dp),
        text = text,
        color = EarthColors.Text.textTertiary,
        style = EarthTypography.textXs,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun EarthSeedWordTextContent(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier then Modifier.padding(start = 32.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Text(
            // While hidden the visible text is a meaningless mask ("•••••"); override the
            // accessibility node so a screen reader announces a single descriptive label instead
            // of spelling out the mask for every word. Does not affect the security invariant.
            modifier =
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            text = text,
            color = EarthColors.Text.textPrimary,
            style = EarthTypography.textMd,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

data class SeedWordTextState(
    val text: String
)

@Composable
@PreviewScreens
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            EarthSeedWordText(
                modifier = Modifier.fillMaxWidth(),
                prefix = "11",
                state =
                    SeedWordTextState(
                        text = "asdasdasd",
                    )
            )
        }
    }
