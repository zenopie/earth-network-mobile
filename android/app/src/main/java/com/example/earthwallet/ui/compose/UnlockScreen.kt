package network.erth.wallet.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import network.erth.wallet.R
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthTextButton
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography

/**
 * Unlock.
 *
 * There is no Zodl screen to adapt here: they hand app access to the OS
 * biometric prompt and show a welcome animation behind it, because a Zcash
 * wallet's secret is protected by the keystore and the prompt is the whole
 * gate. Earth's mnemonic is sealed by the PIN itself, so the PIN has to be
 * typed — the OS prompt cannot produce it.
 *
 * What is borrowed is the composition around it: their centred mark, one line
 * of instruction, and no chrome at all. The keypad is Earth's, rebuilt on the
 * type and colour tokens so it matches everything past it.
 *
 * The lockout is not a UI decision — [network.erth.wallet.wallet.utils.PinSecurityManager]
 * owns the attempt count and the backoff, and this screen only renders what it
 * reports. Two places deciding when to lock out is one place too many.
 */
@Composable
fun UnlockScreen(
    onSubmit: (String) -> Unit,
    error: String?,
    /** Non-null while the backoff is running; the keypad is inert until it clears. */
    lockoutMessage: String?,
    modifier: Modifier = Modifier,
    onBiometric: (() -> Unit)? = null,
) {
    val dimens = EarthTheme.dimens
    var pin by remember { mutableStateOf("") }

    // The shake. Their error dialogs do not shake, but this screen has no room
    // for a dialog and the message alone is easy to miss when the eye is on the
    // dots — the movement is what says "that was wrong", the text says why.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(error) {
        if (error != null) {
            pin = ""
            listOf(-12f, 12f, -8f, 8f, 0f).forEach { shake.animateTo(it, tweenFast) }
        }
    }

    val locked = lockoutMessage != null

    fun press(digit: String) {
        if (locked || pin.length >= 6) return
        pin += digit
        if (pin.length == 4) onSubmit(pin)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(IntOffset(shake.value.toInt(), 0))
                }
            }
            .padding(horizontal = dimens.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Image(
            modifier = Modifier.size(88.dp),
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
        )

        Spacer(Modifier.height(dimens.space24))
        Text(
            text = "Welcome back",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = lockoutMessage ?: error ?: "Enter your PIN to continue",
            style = EarthTypography.textSm,
            color = when {
                lockoutMessage != null || error != null ->
                    EarthColors.Utility.ErrorRed.utilityError700
                else -> EarthColors.Text.textTertiary
            },
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(dimens.space24))
        PinDots(filled = pin.length, locked = locked)

        Spacer(Modifier.weight(1f))
        Keypad(
            enabled = !locked,
            onDigit = ::press,
            onBackspace = { pin = pin.dropLast(1) },
        )

        if (onBiometric != null) {
            EarthTextButton(onClick = onBiometric) {
                Text(text = "Use biometrics", style = EarthTypography.textMd)
            }
        }
        Spacer(Modifier.height(dimens.space32))
    }
}

private val tweenFast = androidx.compose.animation.core.tween<Float>(durationMillis = 45)

/**
 * Four dots, filled as digits arrive.
 *
 * Not a text field: a PIN that echoes its length is the point, and a field
 * would bring a cursor, a keyboard and a selection handle that all have to be
 * suppressed.
 */
@Composable
private fun PinDots(filled: Int, locked: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .background(
                        when {
                            locked -> EarthColors.Surfaces.strokeSecondary
                            i < filled -> EarthColors.Btns.Brand.btnBrandBg
                            else -> EarthColors.Surfaces.bgSecondary
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { key ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        when (key) {
                            "" -> Unit
                            "⌫" -> KeyCap(key, enabled) { onBackspace() }
                            else -> KeyCap(key, enabled) { onDigit(key) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            // Clip before clickable, or the ripple paints the square bounds
            // rather than the key.
            .clip(CircleShape)
            .background(EarthColors.Surfaces.bgSecondary)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = EarthTypography.header5,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                EarthColors.Text.textPrimary
            } else {
                EarthColors.Text.textTertiary
            },
        )
    }
}
