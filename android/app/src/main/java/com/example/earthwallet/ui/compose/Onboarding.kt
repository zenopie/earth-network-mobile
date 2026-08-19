package network.erth.wallet.ui.compose

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.ui.theme.EarthTheme
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SessionManager
import network.erth.wallet.wallet.utils.UnlockAttempts

/**
 * First run: choose a PIN, then make a wallet.
 *
 * This is the branch HostActivity had and the Compose rewrite did not carry
 * over. Without it a fresh install opens straight onto the PIN screen, and
 * because nothing had ever called setPinHash there was no PIN that could
 * work — three attempts, then a lockout, and no way to reach the wallet
 * screens because they live behind the same gate.
 *
 * The wallet screens themselves are the existing ones. Only the way in is new,
 * so there is one create screen and one import screen in the app rather than a
 * first-run copy of each that could drift.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    /** No PIN stored, so nothing can be unlocked and nothing can be created. */
    private val _needsPin = MutableStateFlow(!hasPin())
    val needsPin: StateFlow<Boolean> = _needsPin.asStateFlow()

    /**
     * A session is open but holds no wallet.
     *
     * Reachable if someone sets a PIN and leaves before creating a wallet.
     * Without this they would land on an empty wallet tab with the way to fix
     * it buried in settings.
     */
    private val _needsWallet = MutableStateFlow(false)
    val needsWallet: StateFlow<Boolean> = _needsWallet.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun hasPin(): Boolean =
        runCatching { SecureWalletManager.hasPinSet(getApplication()) }.getOrDefault(false)

    /**
     * Open a session with the PIN, then record its hash.
     *
     * The order is forced and it is also the safe one. setPinHash writes
     * through session preferences and throws without a session, so the session
     * has to come first — and startSession works with nothing stored, which is
     * exactly the first-run case. Writing the hash last also means hasPinSet
     * only becomes true once a session can actually be opened by that PIN, so
     * an interrupted setup comes back here rather than to a PIN screen
     * guarding nothing.
     */
    fun choosePin(pin: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    SessionManager.startSession(ctx, pin)
                    SecureWalletManager.setPinHash(ctx, pin.sha256Hex())
                }.isSuccess
            }
            if (!ok) {
                _error.value = "Could not set that PIN. Try again."
                return@launch
            }
            // Anyone who met the broken build burned attempts against a PIN
            // that could not exist, and may be sitting in a lockout for it.
            UnlockAttempts.recordSuccess(ctx)
            _error.value = null
            _needsPin.value = false
            _needsWallet.value = true
        }
    }

    /** Called once a session is open, to catch the no-wallet state. */
    fun checkWallets() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val count = withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.getWalletCount(ctx) }.getOrDefault(0)
            }
            _needsWallet.value = count == 0
        }
    }

    fun walletReady() {
        _needsWallet.value = false
    }
}

/**
 * Edge-to-edge chrome for the screens that come before the app.
 *
 * Inside [EarthApp] the status bar is absorbed by the top bar, which consumes
 * the top inset itself. These screens have no top bar and the window is
 * edge-to-edge, so without this they draw under the clock and the camera
 * cutout — which is exactly what the first line of the recovery phrase did.
 *
 * The background is on the outer box rather than the inset content, so the
 * strip behind the status bar is painted too instead of showing through.
 */
@Composable
fun PreAppScreen(content: @Composable (Modifier) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary),
    ) {
        content(
            Modifier.windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout),
            ),
        )
    }
}

/**
 * The wallet step of first run: create or import, on the existing screens.
 */
@Composable
fun FirstWalletFlow(
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wallets: WalletsViewModel = viewModel()
    val draftMnemonic by wallets.draftMnemonic.collectAsStateWithLifecycle()
    val error by wallets.error.collectAsStateWithLifecycle()

    var choice by remember { mutableStateOf<Choice?>(null) }

    when (choice) {
        null -> FirstWalletChoice(
            onCreate = {
                wallets.beginCreate()
                choice = Choice.Create
            },
            onImport = {
                wallets.clearError()
                choice = Choice.Import
            },
            modifier = modifier,
        )

        Choice.Create -> CreateWalletScreen(
            mnemonic = draftMnemonic,
            onConfirm = { name -> wallets.confirmCreate(name) { onReady() } },
            modifier = modifier,
        )

        Choice.Import -> ImportWalletScreen(
            error = error,
            onImport = { name, phrase -> wallets.import(name, phrase) { onReady() } },
            modifier = modifier,
        )
    }
}

private enum class Choice { Create, Import }

/**
 * Create or restore, before anything else exists.
 *
 * Restore is given equal weight rather than hidden behind "advanced": the
 * people most likely to arrive here with a phrase in hand are the ones
 * reinstalling after losing a device, and burying it is how a wallet loses
 * somebody's funds.
 */
@Composable
private fun FirstWalletChoice(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = EarthTheme.dimens
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
            text = "Set up your wallet",
            style = EarthTypography.header5,
            color = EarthColors.Text.textPrimary,
        )
        Spacer(Modifier.height(dimens.space4))
        Text(
            text = "A new wallet gives you a recovery phrase to write down. " +
                "If you already have one, restore it here.",
            style = EarthTypography.textSm,
            color = EarthColors.Text.textTertiary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        EarthButton(
            onClick = onCreate,
            text = "Create a new wallet",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(dimens.space12))
        EarthButton(
            onClick = onImport,
            text = "I have a recovery phrase",
            colors = EarthButtonDefaults.secondaryColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(dimens.space32))
    }
}
