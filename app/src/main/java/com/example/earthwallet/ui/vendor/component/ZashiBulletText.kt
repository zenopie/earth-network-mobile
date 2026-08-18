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

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

@Composable
fun EarthBulletText(
    vararg bulletText: String,
    modifier: Modifier = Modifier,
    style: TextStyle = EarthTypography.textSm,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = EarthColors.Text.textPrimary,
) {
    EarthBulletText(
        bulletTexts = bulletText.toList(),
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = color,
    )
}

@Composable
fun EarthBulletText(
    bulletTexts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = EarthTypography.textSm,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = EarthColors.Text.textPrimary,
) {
    val normalizedStyle = style.copy(fontWeight = fontWeight)
    val bulletString = remember { "\u2022  " }
    val bulletTextMeasurer = rememberTextMeasurer()
    val bulletStringWidth =
        remember(normalizedStyle, bulletTextMeasurer) {
            bulletTextMeasurer.measure(text = bulletString, style = normalizedStyle).size.width
        }
    val bulletRestLine = with(LocalDensity.current) { bulletStringWidth.toSp() }
    val bulletParagraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = bulletRestLine))
    Text(
        modifier = modifier,
        text =
            buildAnnotatedString {
                withStyle(style = bulletParagraphStyle) {
                    bulletTexts.forEachIndexed { index, string ->
                        if (index != 0) {
                            appendLine()
                        }
                        append(bulletString)
                        append(string)
                    }
                }
            },
        style = style,
        fontWeight = fontWeight,
        color = color,
    )
}
