/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor.theme.typography

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import network.erth.wallet.R

object EarthTypographyInternal {
    val header1: TextStyle =
        TextStyle(
            fontSize = 56.sp,
            lineHeight = 68.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header2: TextStyle =
        TextStyle(
            fontSize = 48.sp,
            lineHeight = 60.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header3: TextStyle =
        TextStyle(
            fontSize = 40.sp,
            lineHeight = 52.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header4: TextStyle =
        TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header5: TextStyle =
        TextStyle(
            fontSize = 28.sp,
            lineHeight = 40.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header6: TextStyle =
        TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXl: TextStyle =
        TextStyle(
            fontSize = 20.sp,
            lineHeight = 30.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textLg: TextStyle =
        TextStyle(
            fontSize = 18.sp,
            lineHeight = 28.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textMd: TextStyle =
        TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textSm: TextStyle =
        TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXs: TextStyle =
        TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXxs: TextStyle =
        TextStyle(
            fontSize = 10.sp,
            lineHeight = 18.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
}

private val provider =
    GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs
    )

private val InterFont = GoogleFont(name = "Inter", bestEffort = true)
private val RobotoMonoFont = GoogleFont(name = "Roboto Mono", bestEffort = true)

val InterFontFamily =
    FontFamily(
        // W400
        Font(googleFont = InterFont, fontProvider = provider, weight = FontWeight.Normal),
        // W500
        Font(googleFont = InterFont, fontProvider = provider, weight = FontWeight.Medium),
        // W600
        Font(googleFont = InterFont, fontProvider = provider, weight = FontWeight.SemiBold),
        // W700
        Font(googleFont = InterFont, fontProvider = provider, weight = FontWeight.Bold)
    )

val RobotoMonoFontFamily =
    FontFamily(
        // W400
        Font(googleFont = RobotoMonoFont, fontProvider = provider, weight = FontWeight.Normal),
        // W500
        Font(googleFont = RobotoMonoFont, fontProvider = provider, weight = FontWeight.Medium),
    )
