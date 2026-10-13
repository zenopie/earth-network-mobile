package network.erth.wallet.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.theme.EarthAccent

/*
 * The one-hero layout every detail screen shares: a mark, one big thing,
 * at most one short line, then big pills. As the Earn stake page.
 */

/** The whole in display size, the fraction one step down: the wallet's balance. */
@Composable
fun HeroAmount(amount: String, visible: Boolean = true) {
    if (!visible) {
        Text("-----", style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold), color = EarthColors.Text.textPrimary)
        return
    }
    Row {
        Text(
            text = amount.substringBefore('.'),
            style = EarthTypography.header2.copy(fontWeight = FontWeight.SemiBold),
            color = EarthColors.Text.textPrimary,
            maxLines = 1,
        )
        val frac = amount.substringAfter('.', "")
        if (frac.isNotEmpty()) {
            Text(
                text = ".$frac",
                style = EarthTypography.textXs.copy(fontWeight = FontWeight.SemiBold),
                color = EarthColors.Text.textPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A big bold name or status, centred. */
@Composable
fun HeroTitle(text: String, color: Color = EarthColors.Text.textPrimary) {
    FigureText(
        text = text,
        style = EarthTypography.header5.copy(fontWeight = FontWeight.Bold),
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The one short line under a hero. */
@Composable
fun HeroLine(text: String, color: Color = EarthColors.Text.textTertiary, icon: Int? = null, iconTint: Color = color) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(painterResource(icon), null, Modifier.size(13.dp), colorFilter = ColorFilter.tint(iconTint))
            Spacer(Modifier.width(5.dp))
        }
        FigureText(text, style = EarthTypography.textSm, color = color, textAlign = TextAlign.Center)
    }
}

/** An icon on the accent tint in a circle: the mark for a screen with no coin or validator. */
@Composable
fun HeroIcon(icon: Int, size: Dp = 64.dp, muted: Boolean = false) {
    Box(
        Modifier.size(size).background(if (muted) EarthColors.Surfaces.bgTertiary else EarthAccent.tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(icon), null, Modifier.size(size * 0.45f),
            colorFilter = ColorFilter.tint(if (muted) EarthColors.Text.textTertiary else EarthAccent.ink),
        )
    }
}

/** A letter in a circle: a wallet or a validator, which have no logos. */
@Composable
fun HeroInitial(name: String, size: Dp = 64.dp) {
    Box(Modifier.size(size).background(EarthAccent.tint, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.take(1).uppercase().ifEmpty { "?" },
            style = EarthTypography.textMd.copy(fontSize = (size.value * 0.4f).sp),
            fontWeight = FontWeight.Bold,
            color = EarthAccent.ink,
        )
    }
}

/** The top of a hero screen: mark, then the rest, centred. */
@Composable
fun HeroColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}

@Composable
fun HeroGap(height: Dp = 12.dp) = Spacer(Modifier.height(height))

/** A pushed screen whose data has not been read yet: [LOADING], where the hero would be. */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
        HeroTitle(LOADING, color = EarthColors.Text.textTertiary)
    }
}
