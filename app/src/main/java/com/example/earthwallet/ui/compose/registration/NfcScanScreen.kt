package network.erth.wallet.ui.compose.registration

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.theme.EarthAccent
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/** Where the passport read has got to. */
sealed interface NfcStage {
    /** Waiting for the passport to touch the phone. */
    data object Waiting : NfcStage

    /** Chip open, reading and proving. Cannot be interrupted. */
    data object Reading : NfcStage

    data class Failed(val message: String, val canRetry: Boolean) : NfcStage
}

/**
 * Hold the passport against the phone.
 *
 * Two states worth distinguishing and one screen for both, because they are a
 * continuous action: the difference between "not touching yet" and "do not move
 * it" is exactly what the person holding it needs to know, and switching
 * screens under their hand at the moment contact is made is the wrong moment to
 * move anything.
 *
 * The pulse stops when reading starts. An animation that keeps inviting motion
 * while the chip is being read is telling the user to do the one thing that
 * breaks it.
 */
@Composable
fun NfcScanScreen(
    stage: NfcStage,
    onRetry: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        val pulse = rememberInfiniteTransition(label = "nfc")
        val scale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = if (stage is NfcStage.Waiting) 1.12f else 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        )

        Box(
            Modifier
                .size(140.dp)
                .scale(scale)
                .background(EarthAccent.tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                modifier = Modifier.size(64.dp),
                painter = painterResource(R.drawable.ic_zk_proof),
                colorFilter = ColorFilter.tint(EarthAccent.ink),
                contentDescription = null,
            )
        }

        Spacer(Modifier.height(32.dp))

        val (title, detail) = when (stage) {
            NfcStage.Waiting -> "Hold your passport to the phone" to
                "The chip is usually in the back cover. Rest the passport flat " +
                    "against the top of the phone and leave it there."
            NfcStage.Reading -> "Reading" to
                "Keep the passport still. This takes a few seconds — the proof " +
                    "is built on this device, and nothing about the passport " +
                    "leaves it."
            is NfcStage.Failed -> "Could not read it" to stage.message
        }

        Text(
            text = title,
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = EarthTypography.textSm,
            color = EarthColors.Text.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        if (stage is NfcStage.Failed) {
            if (stage.canRetry) {
                EarthButton(
                    text = "Try again",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = network.erth.wallet.ui.compose.brandButtonColors(),
                )
                Spacer(Modifier.height(8.dp))
            }
            EarthButton(
                text = "Check the passport details",
                onClick = onManualEntry,
                modifier = Modifier.fillMaxWidth(),
                colors = EarthButtonDefaults.secondaryColors(),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
