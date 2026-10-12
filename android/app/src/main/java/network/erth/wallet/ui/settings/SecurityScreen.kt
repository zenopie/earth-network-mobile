package network.erth.wallet.ui.settings

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
import androidx.compose.ui.unit.dp
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
import network.erth.wallet.ui.designsystem.component.EarthButton
import network.erth.wallet.ui.designsystem.theme.colors.EarthColors
import network.erth.wallet.ui.designsystem.theme.typography.EarthTypography
import network.erth.wallet.ui.onboarding.UnlockSetupFlow
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.wallet.BiometricVault
import network.erth.wallet.wallet.SessionManager
import network.erth.wallet.wallet.UnlockMethod

class SecurityViewModel(app: Application) : AndroidViewModel(app) {

    private val _method = MutableStateFlow(UnlockMethod.current(app))
    val method: StateFlow<UnlockMethod> = _method.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _changed = MutableStateFlow(false)
    val changed: StateFlow<Boolean> = _changed.asStateFlow()

    /**
     * The current secret, from a fresh unlock just before the change. Held
     * only while the flow is open.
     */
    private var confirmedSecret: String? = null

    fun confirmed(secret: String) { confirmedSecret = secret }

    fun cancel() { confirmedSecret = null }

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
            val old = confirmedSecret
            val ok = old != null && withContext(Dispatchers.IO) {
                runCatching {
                    SessionManager.changeSecret(ctx, old, secret)
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
            confirmedSecret = null
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
    var confirming by remember { mutableStateOf(false) }

    // A fresh unlock before the secret can change. The new
    // secret gates every other wallet's recovery phrase, so an unlocked
    // phone alone must not be able to choose it.
    if (confirming) {
        network.erth.wallet.ui.unlock.ConfirmUnlockDialog(
            onConfirmed = { secret ->
                confirming = false
                model.confirmed(secret)
                changing = true
            },
            onDismiss = { confirming = false },
        )
    }

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
            model.cancel()
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
        network.erth.wallet.ui.components.HeroColumn {
            network.erth.wallet.ui.components.HeroIcon(network.erth.wallet.R.drawable.ic_lock)
            network.erth.wallet.ui.components.HeroGap()
            network.erth.wallet.ui.components.HeroTitle(
                when (method) {
                    UnlockMethod.PIN -> "PIN"
                    UnlockMethod.BIOMETRIC -> "Biometrics"
                    UnlockMethod.BOTH -> "PIN and biometrics"
                },
            )
            network.erth.wallet.ui.components.HeroLine("Lost phone? Only your phrase recovers it.")
        }

        if (method.usesBiometric && !biometricsUsable) {
            Spacer(Modifier.height(dimens.space12))
            Text(
                text = "Biometrics unavailable · switch to a PIN now",
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

        Spacer(Modifier.height(32.dp))
        network.erth.wallet.ui.earn.PillButton("Change how you unlock") { confirming = true }
        Spacer(Modifier.height(dimens.space32))
    }
}
