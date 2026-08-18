/*
 * Re-skinned to the Sprout ramps: Zcash gold becomes Earth green and the warm
 * olive neutrals become green-biased ones. Their step names (25..950) are
 * unchanged, so all 1,150 lines of light and dark mapping resolve untouched.
 */
/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
@file:Suppress("ObjectPropertyName", "ObjectPropertyNaming", "MagicNumber")

package network.erth.wallet.ui.vendor.theme.colors

import androidx.compose.ui.graphics.Color

internal object Base {
    val Bone = Color(0xFFFFFFFF)
    val Concrete = Color(0xFFF7F9F6)
    val Espresso = Color(0xFF3C443A)
    val Obsidian = Color(0xFF0F120E)
    val Brand = Color(0xFF00C244)
}

internal object Gray {
    val `25` = Color(0xFFFFFFFF)
    val `50` = Color(0xFFF7F9F6)
    val `100` = Color(0xFFEDF1EA)
    val `200` = Color(0xFFDCE3D8)
    val `300` = Color(0xFFC3CCBE)
    val `400` = Color(0xFFAAB4A5)
    val `500` = Color(0xFF8B9587)
    val `600` = Color(0xFF727C6D)
    val `700` = Color(0xFF5A6356)
    val `800` = Color(0xFF3C443A)
    val `900` = Color(0xFF262B24)
    val `950` = Color(0xFF0F120E)
}

internal object Brand {
    val `25` = Color(0xFFF2FDF5)
    val `50` = Color(0xFFE4FBEA)
    val `100` = Color(0xFFC4F6D2)
    val `200` = Color(0xFF97EDB0)
    val `300` = Color(0xFF5FE087)
    val `400` = Color(0xFF22D160)
    val `500` = Color(0xFF00C244)
    val `600` = Color(0xFF00A238)
    val `700` = Color(0xFF00822D)
    val `800` = Color(0xFF056624)
    val `900` = Color(0xFF08521F)
    val `950` = Color(0xFF032D12)
}

internal object Shark {
    val `25` = Color(0xFFFAFBF9)
    val `50` = Color(0xFFF1F3EF)
    val `100` = Color(0xFFE3E7E0)
    val `200` = Color(0xFFCDD3C9)
    val `300` = Color(0xFFB0B8AB)
    val `400` = Color(0xFF939C8E)
    val `500` = Color(0xFF78826F)
    val `600` = Color(0xFF5E6857)
    val `700` = Color(0xFF474F42)
    val `800` = Color(0xFF31372E)
    val `900` = Color(0xFF1D211B)
    val `950` = Color(0xFF0B0D0A)
}

internal object SharkShades {
    val `00dp` = Color(0xFF0F120E)
    val `01dp` = Color(0xFF14180F)
    val `02dp` = Color(0xFF181D14)
    val `03dp` = Color(0xFF1B2118)
    val `04dp` = Color(0xFF1E2419)
    val `06dp` = Color(0xFF23291E)
    val `08dp` = Color(0xFF272E22)
    val `12dp` = Color(0xFF2C3427)
    val `16dp` = Color(0xFF313A2B)
    val `24dp` = Color(0xFF3A4433)
}

internal object SuccessGreen {
    val `25` = Color(0xFFF2FDF5)
    val `50` = Color(0xFFE4FBEA)
    val `100` = Color(0xFFC4F6D2)
    val `200` = Color(0xFF97EDB0)
    val `300` = Color(0xFF5FE087)
    val `400` = Color(0xFF22D160)
    val `500` = Color(0xFF00C244)
    val `600` = Color(0xFF00A238)
    val `700` = Color(0xFF00822D)
    val `800` = Color(0xFF056624)
    val `900` = Color(0xFF08521F)
    val `950` = Color(0xFF032D12)
}

internal object ErrorRed {
    val `25` = Color(0xFFFEF3F2)
    val `50` = Color(0xFFFDECEA)
    val `100` = Color(0xFFFBD9D5)
    val `200` = Color(0xFFFecddca)
    val `300` = Color(0xFFF7B8B1)
    val `400` = Color(0xFFF08D82)
    val `500` = Color(0xFFE45F51)
    val `600` = Color(0xFFD93025)
    val `700` = Color(0xFFB4231A)
    val `800` = Color(0xFF8F1B14)
    val `900` = Color(0xFF701710)
    val `950` = Color(0xFF54120C)
}

internal object WarningYellow {
    val `25` = Color(0xFFFFFAEB)
    val `50` = Color(0xFFFEF0C7)
    val `100` = Color(0xFFFEDF89)
    val `200` = Color(0xFFFEC84B)
    val `300` = Color(0xFFFDB022)
    val `400` = Color(0xFFF79009)
    val `500` = Color(0xFFDC6803)
    val `600` = Color(0xFFB54708)
    val `700` = Color(0xFF93370D)
    val `800` = Color(0xFF7A2E0E)
    val `900` = Color(0xFF5C230B)
    val `950` = Color(0xFF2E1105)
}

object HyperBlue {
    val `25` = Color(0xFFF5F8FF)
    val `50` = Color(0xFFEFF4FF)
    val `100` = Color(0xFFD1E0FF)
    val `200` = Color(0xFFB2CCFF)
    val `300` = Color(0xFF84ADFF)
    val `400` = Color(0xFF528BFF)
    val `500` = Color(0xFF2970FF)
    val `600` = Color(0xFF155EEF)
    val `700` = Color(0xFF004EEB)
    val `800` = Color(0xFF0040C1)
    val `900` = Color(0xFF00359E)
    val `950` = Color(0xFF002266)
}

internal object Indigo {
    val `25` = Color(0xFFF3F7FB)
    val `50` = Color(0xFFE5EFF7)
    val `100` = Color(0xFFC7DEEE)
    val `200` = Color(0xFF9AC6E0)
    val `300` = Color(0xFF66A8CE)
    val `400` = Color(0xFF3B87B6)
    val `500` = Color(0xFF22699A)
    val `600` = Color(0xFF1B537B)
    val `700` = Color(0xFF17435F)
    val `800` = Color(0xFF14364C)
    val `900` = Color(0xFF0F2736)
    val `950` = Color(0xFF08161D)
}

internal object Purple {
    val `25` = Color(0xFFF8F5FF)
    val `50` = Color(0xFFEFE9FE)
    val `100` = Color(0xFFDCD0FC)
    val `200` = Color(0xFFC1AAF8)
    val `300` = Color(0xFFA182F1)
    val `400` = Color(0xFF8259E6)
    val `500` = Color(0xFF6B3FD4)
    val `600` = Color(0xFF5630AE)
    val `700` = Color(0xFF45268A)
    val `800` = Color(0xFF371F6E)
    val `900` = Color(0xFF28164F)
    val `950` = Color(0xFF150B29)
}

internal object Espresso {
    val `25` = Color(0xFFFBF6F2)
    val `50` = Color(0xFFF5EAE0)
    val `100` = Color(0xFFE9D2BE)
    val `200` = Color(0xFFD8B294)
    val `300` = Color(0xFFC28F67)
    val `400` = Color(0xFFA87046)
    val `500` = Color(0xFF8C5834)
    val `600` = Color(0xFF70452A)
    val `700` = Color(0xFF5A3722)
    val `800` = Color(0xFF472C1C)
    val `900` = Color(0xFF341F14)
    val `950` = Color(0xFF1A0F09)
}

internal object TransparentColorPalette {
    val Light = Color(0xFFFFFFFF)
    val Dark = Color(0xFF231F20)
}

internal object Accent {
    val Green = Color(0xFF00C244)
}
