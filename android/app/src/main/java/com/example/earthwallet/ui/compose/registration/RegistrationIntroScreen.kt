package network.erth.wallet.ui.compose.registration

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.compose.brandButtonColors
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthCard
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * What registration is, before it starts.
 *
 * Ported from the iOS registration sheet, which opens on this rather than on
 * the camera. Holding a passport to a phone is a bigger ask than any other
 * screen in the app makes, and the two questions someone has at that moment —
 * *what leaves my phone* and *how long is this* — were both answered somewhere
 * else: the privacy claim sat back on the personhood screen they had already
 * left, and the number of steps was not stated anywhere. Opening the camera
 * first asks for the passport before either has been answered.
 *
 * The privacy paragraph is deliberately specific about what the proof does not
 * carry. "Nothing leaves your phone" is the sort of claim every app makes; the
 * list of what is absent is checkable.
 */
@Composable
fun RegistrationIntroScreen(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space24))

        Image(
            modifier = Modifier.size(72.dp),
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
        )

        Spacer(Modifier.height(dimens.space16))
        Text(
            text = "Prove you are a unique human",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = "Your passport's chip signs a proof on this device. The proof shows " +
                "a government signed your document and that you have not registered " +
                "before — it does not carry your name, your photo, or your document " +
                "number, and nothing about the passport leaves the phone.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
        )

        Spacer(Modifier.height(dimens.space16))
        EarthCard(Modifier.fillMaxWidth()) {
            StepRow(
                number = 1,
                title = "Type three fields",
                detail = "Document number, date of birth, date of expiry — from the " +
                    "two lines at the bottom of the photo page.",
            )
            StepRow(
                number = 2,
                title = "Hold the passport to the phone",
                detail = "The chip is read over NFC.",
            )
            StepRow(
                number = 3,
                title = "Prove and register",
                detail = "About a second and a half of proving, then one transaction.",
            )
        }

        Spacer(Modifier.height(dimens.space24))
        EarthButton(
            text = "Start",
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = brandButtonColors(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}

/** One numbered step: the badge, what it is, and what it involves. */
@Composable
private fun StepRow(number: Int, title: String, detail: String) {
    val dimens = EarthTheme.dimens
    Row(
        Modifier.padding(vertical = dimens.space4),
    ) {
        Box(
            Modifier
                .size(dimens.space24)
                .background(EarthAccent.tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$number",
                style = EarthTypography.textXs,
                color = EarthAccent.ink,
            )
        }
        Spacer(Modifier.size(dimens.space12))
        Column {
            Text(
                text = title,
                style = EarthTypography.textMd,
                color = EarthColors.Text.textPrimary,
            )
            Spacer(Modifier.height(dimens.space2))
            Text(
                text = detail,
                style = EarthTypography.textSm,
                color = EarthColors.Text.textTertiary,
            )
        }
    }
}
