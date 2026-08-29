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
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import network.erth.wallet.R
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.wallet.utils.BiometricVault
import network.erth.wallet.wallet.utils.UnlockMethod

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
 * The lockout is not a UI decision — [network.erth.wallet.wallet.utils.UnlockAttempts]
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
    /** What to say when nothing has gone wrong yet. */
    prompt: String = "Enter your PIN to continue",
) {
    // Four digits are short enough to read off a shoulder-surfed screenshot.
    SecureScreen()
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
            text = lockoutMessage ?: error ?: prompt,
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

        Spacer(Modifier.height(dimens.space32))
    }
}

/**
 * Choosing the PIN, on first run.
 *
 * The same keypad as [UnlockScreen] rather than a text field, for the same
 * reason: what is being typed is a PIN, and the keypad is what says so. It
 * lives in this file so the keypad stays private to one place — two keypads
 * that drift apart would be two different-looking PIN screens either side of a
 * single install.
 *
 * The PIN seals the mnemonic — it is not a lock in front of a secret the
 * keystore already holds, it is the key the secret is encrypted with. So it is
 * asked twice: a typo here does not lock someone out of an account they can
 * reset, it encrypts their wallet under a PIN they do not know.
 */
@Composable
fun SetPinScreen(
    error: String?,
    onChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureScreen()
    val dimens = EarthTheme.dimens
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    fun press(digit: String) {
        if (pin.length >= 4) return
        mismatch = false
        pin += digit
        if (pin.length < 4) return

        val entered = pin
        pin = ""
        val chosen = first
        when {
            chosen == null -> first = entered
            chosen == entered -> onChosen(entered)
            else -> {
                // Start over rather than only clearing the second entry: the
                // one they meant is as likely to be the first as the second.
                mismatch = true
                first = null
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
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
            text = if (first == null) "Choose a PIN" else "Enter it again",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = when {
                error != null -> error
                mismatch -> "Those did not match. Start again."
                first == null -> "It encrypts your wallet on this device. There is no way to reset it."
                else -> "Confirm your PIN"
            },
            style = EarthTypography.textSm,
            color = if (error != null || mismatch) {
                EarthColors.Utility.ErrorRed.utilityError700
            } else {
                EarthColors.Text.textTertiary
            },
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(dimens.space24))
        PinDots(filled = pin.length, locked = false)

        Spacer(Modifier.weight(1f))
        Keypad(
            enabled = true,
            onDigit = ::press,
            onBackspace = { pin = pin.dropLast(1) },
        )
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


/**
 * The way in, whichever way this wallet was set up.
 *
 * The screens below only ever produce a PIN or a prompt result; this is where
 * those become the one secret the wallet is sealed with. Keeping the assembly
 * here means [UnlockViewModel] never learns there is more than one kind of
 * wallet, and the two-factor case cannot quietly degrade into either factor
 * alone — [UnlockMethod.BOTH] has no branch that submits without both halves.
 */
@Composable
fun UnlockGate(
    method: UnlockMethod,
    onSecret: (String) -> Unit,
    onFailure: (String) -> Unit,
    error: String?,
    lockoutMessage: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    fun withHalf(reason: String, then: (String) -> Unit) {
        val host = activity ?: return onFailure("Biometrics are not available here.")
        BiometricVault.retrieve(host, reason) { half ->
            when (half) {
                null -> onFailure(
                    if (BiometricVault.isEnrolled(host)) {
                        "Biometric unlock was cancelled."
                    } else {
                        // The key is gone, so the wallet cannot be opened this
                        // way again — say so plainly rather than leaving the
                        // prompt failing forever.
                        "Biometric unlock was cleared, probably by a new fingerprint or face. " +
                            "Restore this wallet from its recovery phrase."
                    },
                )
                else -> then(half)
            }
        }
    }

    when (method) {
        UnlockMethod.PIN -> UnlockScreen(
            onSubmit = onSecret,
            error = error,
            lockoutMessage = lockoutMessage,
            modifier = modifier,
        )

        UnlockMethod.BOTH -> UnlockScreen(
            // The PIN comes first and the prompt second, so the prompt is only
            // raised once per attempt and never for an unfinished PIN.
            onSubmit = { pin ->
                withHalf("Confirm to unlock") { half -> onSecret(UnlockMethod.combine(pin, half)) }
            },
            error = error,
            lockoutMessage = lockoutMessage,
            prompt = "Enter your PIN, then confirm",
            modifier = modifier,
        )

        UnlockMethod.BIOMETRIC -> BiometricUnlockScreen(
            onUnlock = { withHalf("Confirm to unlock", onSecret) },
            error = error,
            lockoutMessage = lockoutMessage,
            modifier = modifier,
        )
    }
}

/**
 * No keypad, because there is no PIN — the secret is behind the prompt.
 *
 * The prompt is raised on arrival rather than behind a button, since there is
 * nothing else on this screen to do. The button below it is for the second
 * try, after a cancel.
 */
@Composable
private fun BiometricUnlockScreen(
    onUnlock: () -> Unit,
    error: String?,
    lockoutMessage: String?,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
    val locked = lockoutMessage != null

    // Not while the backoff is running: raising a prompt that cannot lead
    // anywhere teaches the wrong thing about why the wallet will not open.
    LaunchedEffect(locked) { if (!locked) onUnlock() }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
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
            text = lockoutMessage ?: error ?: "Confirm it is you to continue",
            style = EarthTypography.textSm,
            color = if (locked || error != null) {
                EarthColors.Utility.ErrorRed.utilityError700
            } else {
                EarthColors.Text.textTertiary
            },
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
        EarthButton(
            onClick = onUnlock,
            enabled = !locked,
            text = "Unlock",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}
