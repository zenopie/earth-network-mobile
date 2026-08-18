@file:Suppress("ObjectPropertyName", "ObjectPropertyNaming", "MagicNumber")

package network.erth.wallet.ui.theme.colors

import androidx.compose.ui.graphics.Color

/**
 * Raw colour. Eleven ramps, twelve steps each.
 *
 * Nothing outside this package should reference these: screens name a job
 * (`btnPrimaryBg`), not a value (`Sprout.500`). A ramp step is a fact about
 * colour; a semantic token is a decision about the product, and keeping the two
 * apart is what lets dark mode be a remapping rather than a redesign.
 *
 * The neutrals are green-biased on purpose. A stock grey beside a saturated
 * accent is the tell that a palette was assembled rather than chosen — which is
 * exactly what the old XML palette did, pairing Tailwind's #F4F4F5 with
 * Material's #4CAF50 and a #22C55E that arrived from somewhere else.
 */

/** Brand. Also success — see [network.erth.wallet.ui.theme.colors.EarthColorsInternal]. */
internal object Sprout {
    val `25` = Color(0xFFF2FDF5)
    val `50` = Color(0xFFE4FBEA)
    val `100` = Color(0xFFC4F6D2)
    val `200` = Color(0xFF97EDB0)
    val `300` = Color(0xFF5FE087)
    val `400` = Color(0xFF22D160)
    val `500` = Color(0xFF00C244) // accent
    val `600` = Color(0xFF00A238)
    val `700` = Color(0xFF00822D)
    val `800` = Color(0xFF056624)
    val `900` = Color(0xFF08521F)
    val `950` = Color(0xFF032D12)
}

/** Neutral. Every surface and text tone; biased toward Sprout. */
internal object Stone {
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

/** Overlays, scrims and disabled fills. */
internal object Ash {
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

/** Error and destructive. */
internal object Ember {
    val `25` = Color(0xFFFEF3F2)
    val `50` = Color(0xFFFDECEA)
    val `100` = Color(0xFFFBD9D5)
    val `200` = Color(0xFFF7B8B1)
    val `300` = Color(0xFFF08D82)
    val `400` = Color(0xFFE45F51)
    val `500` = Color(0xFFD93025)
    val `600` = Color(0xFFD93025)
    val `700` = Color(0xFFB4231A)
    val `800` = Color(0xFF8F1B14)
    val `900` = Color(0xFF701710)
    val `950` = Color(0xFF2E0906)
}

/** Warning. Low gas, expiring registration. */
internal object Amber {
    val `25` = Color(0xFFFFFAEB)
    val `50` = Color(0xFFFEF0C7)
    val `100` = Color(0xFFFEDF89)
    val `200` = Color(0xFFFEC84B)
    val `300` = Color(0xFFFDB022)
    val `400` = Color(0xFFF79009)
    val `500` = Color(0xFFDC6803)
    val `600` = Color(0xFFDC6803)
    val `700` = Color(0xFFB54708)
    val `800` = Color(0xFF93370D)
    val `900` = Color(0xFF7A2E0E)
    val `950` = Color(0xFF2E1105)
}

/** Informational notices. */
internal object Slate {
    val `25` = Color(0xFFF3F7FB)
    val `50` = Color(0xFFE5EFF7)
    val `100` = Color(0xFFC7DEEE)
    val `200` = Color(0xFF9AC6E0)
    val `300` = Color(0xFF66A8CE)
    val `400` = Color(0xFF3B87B6)
    val `500` = Color(0xFF22699A)
    val `600` = Color(0xFF22699A)
    val `700` = Color(0xFF1B537B)
    val `800` = Color(0xFF17435F)
    val `900` = Color(0xFF14364C)
    val `950` = Color(0xFF08161D)
}

/** Staking pillar. */
internal object Moss {
    val `25` = Color(0xFFF4F8F1)
    val `50` = Color(0xFFE8F1E1)
    val `100` = Color(0xFFCFE3C2)
    val `200` = Color(0xFFAECF9B)
    val `300` = Color(0xFF87B76F)
    val `400` = Color(0xFF639E4C)
    val `500` = Color(0xFF4A8536)
    val `600` = Color(0xFF4A8536)
    val `700` = Color(0xFF3A6B29)
    val `800` = Color(0xFF2E5421)
    val `900` = Color(0xFF25431B)
    val `950` = Color(0xFF0D1A09)
}

/** ANML and proof-of-personhood. */
internal object Clay {
    val `25` = Color(0xFFFBF6F2)
    val `50` = Color(0xFFF5EAE0)
    val `100` = Color(0xFFE9D2BE)
    val `200` = Color(0xFFD8B294)
    val `300` = Color(0xFFC28F67)
    val `400` = Color(0xFFA87046)
    val `500` = Color(0xFF8C5834)
    val `600` = Color(0xFF8C5834)
    val `700` = Color(0xFF70452A)
    val `800` = Color(0xFF5A3722)
    val `900` = Color(0xFF472C1C)
    val `950` = Color(0xFF1A0F09)
}

/** Governance and allocation streams. */
internal object Violet {
    val `25` = Color(0xFFF8F5FF)
    val `50` = Color(0xFFEFE9FE)
    val `100` = Color(0xFFDCD0FC)
    val `200` = Color(0xFFC1AAF8)
    val `300` = Color(0xFFA182F1)
    val `400` = Color(0xFF8259E6)
    val `500` = Color(0xFF6B3FD4)
    val `600` = Color(0xFF6B3FD4)
    val `700` = Color(0xFF5630AE)
    val `800` = Color(0xFF45268A)
    val `900` = Color(0xFF371F6E)
    val `950` = Color(0xFF150B29)
}

/** Dex: pools, swaps, liquidity. */
internal object Sea {
    val `25` = Color(0xFFF0FBF9)
    val `50` = Color(0xFFDEF6F1)
    val `100` = Color(0xFFB5EBE1)
    val `200` = Color(0xFF83DACC)
    val `300` = Color(0xFF4CC3B2)
    val `400` = Color(0xFF25A897)
    val `500` = Color(0xFF128C7D)
    val `600` = Color(0xFF128C7D)
    val `700` = Color(0xFF0E7065)
    val `800` = Color(0xFF0C5A51)
    val `900` = Color(0xFF0A4842)
    val `950` = Color(0xFF031A17)
}

/** Scrims and press states, expressed as alpha over the darkest neutral. */
internal object Alpha {
    val `25` = Color(0x050F120E)
    val `50` = Color(0x0A0F120E)
    val `100` = Color(0x0F0F120E)
    val `200` = Color(0x170F120E)
    val `300` = Color(0x1F0F120E)
    val `400` = Color(0x290F120E)
    val `500` = Color(0x3D0F120E)
    val `600` = Color(0x520F120E)
    val `700` = Color(0x730F120E)
    val `800` = Color(0x990F120E)
    val `900` = Color(0xBF0F120E)
    val `950` = Color(0xE00F120E)
    val transparent = Color(0x000F120E)
}
