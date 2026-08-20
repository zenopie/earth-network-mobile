package network.erth.wallet.ui.compose

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SessionManager
import network.erth.wallet.wallet.utils.BiometricVault
import network.erth.wallet.wallet.utils.UnlockMethod

class SecurityViewModel(app: Application) : AndroidViewModel(app) {

    private val _method = MutableStateFlow(UnlockMethod.current(app))
    val method: StateFlow<UnlockMethod> = _method.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _changed = MutableStateFlow(false)
    val changed: StateFlow<Boolean> = _changed.asStateFlow()

    /**
     * Re-seal the wallet under a new secret and record how it was made.
     *
     * The order is the whole point. The new biometric half is already staged in
     * the spare slot but is not live, so until the re-encrypt succeeds the old
     * secret still opens the wallet. Only then is the pointer moved and the
     * method recorded. Every failure path here leaves the wallet exactly as it
     * was rather than sealed by a half that no longer exists.
     */
    fun apply(method: UnlockMethod, secret: String, stagedSlot: String?) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    SessionManager.changeSecret(ctx, secret)
                    SecureWalletManager.setPinHash(ctx, secret.sha256Hex())
                }.isSuccess
            }
            if (!ok) {
                // Nothing has moved: the staged slot was never live, so the old
                // secret and the old way in are both still exactly as they were.
                stagedSlot?.let { BiometricVault.discard(ctx, it) }
                _error.value = "Could not change how this wallet unlocks. " +
                    "Nothing changed — unlock the way you did before."
                return@launch
            }
            stagedSlot?.let { BiometricVault.commit(ctx, it) }
            UnlockMethod.set(ctx, method)
            // A wallet with no biometric half has no business holding a key
            // that could still release one.
            if (!method.usesBiometric) BiometricVault.forget(ctx)
            _method.value = method
            _error.value = null
            _changed.value = true
        }
    }

    fun reportError(message: String) {
        _error.value = message
    }

    fun acknowledge() {
        _changed.value = false
    }
}

/**
 * Changing how this wallet is unlocked, after it exists.
 *
 * The same flow as first run rather than a screen of its own, because it is the
 * same decision and the same consequences — a second copy of it would be a
 * second place for the two-factor case to go wrong.
 */
@Composable
fun SecurityScreen(modifier: Modifier = Modifier) {
    val model: SecurityViewModel = viewModel()
    val method by model.method.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val changed by model.changed.collectAsStateWithLifecycle()

    var changing by remember { mutableStateOf(false) }

    if (changing && !changed) {
        UnlockSetupFlow(
            error = error,
            onDone = model::apply,
            onFailed = model::reportError,
            modifier = modifier,
        )
        return
    }

    // In an effect, not in the composition: leaving the flow is a side effect
    // of the change landing, and doing it inline would write state during
    // composition and re-run on every recomposition afterwards.
    LaunchedEffect(changed) {
        if (changed) {
            changing = false
            model.acknowledge()
        }
    }

    val dimens = EarthTheme.dimens
    val context = LocalContext.current
    val biometricsUsable = remember(context) { BiometricVault.isAvailable(context) }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary)
            .padding(horizontal = dimens.gutter),
    ) {
        Spacer(Modifier.height(dimens.space24))
        Text(
            text = when (method) {
                UnlockMethod.PIN -> "PIN"
                UnlockMethod.BIOMETRIC -> "Biometrics"
                UnlockMethod.BOTH -> "PIN and biometrics"
            },
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space8))
        Text(
            text = when (method) {
                UnlockMethod.PIN ->
                    "Your PIN encrypts this wallet on this device."
                UnlockMethod.BIOMETRIC ->
                    "This wallet is sealed by a key your device only releases to " +
                        "your fingerprint or face."
                UnlockMethod.BOTH ->
                    "This wallet is sealed by your PIN and a key held behind the " +
                        "biometric prompt. Neither one opens it alone."
            },
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
        )

        if (method.usesBiometric && !biometricsUsable) {
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = "Biometrics are no longer available on this device. Change " +
                    "this to a PIN while the wallet is still open.",
                style = EarthTypography.textSm,
                color = EarthColors.Utility.ErrorRed.utilityError700,
            )
        }

        error?.let {
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = it,
                style = EarthTypography.textSm,
                color = EarthColors.Utility.ErrorRed.utilityError700,
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = "Changing this re-encrypts the wallet on this device. Your " +
                "recovery phrase does not change.",
            style = EarthTypography.textXs,
            color = EarthColors.Text.textTertiary,
        )
        Spacer(Modifier.height(dimens.space12))
        EarthButton(
            onClick = { changing = true },
            text = "Change how you unlock",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}
