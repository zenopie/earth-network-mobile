package network.erth.wallet.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.wallet.services.SecureWalletManager

data class WalletsUiState(
    val wallets: List<SecureWalletManager.WalletInfo>,
    val selectedIndex: Int,
)

/**
 * The wallets in this install: listing, switching, creating, importing.
 *
 * All of it inside the existing session — a wallet is a mnemonic in the
 * session-encrypted store, so there is no separate PIN to set and nothing to
 * unlock again. Creating one while locked is impossible rather than merely
 * disallowed, which is why every method here assumes a session and the screen
 * is only reachable past the PIN.
 */
class WalletsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<WalletsUiState?>(null)
    val state: StateFlow<WalletsUiState?> = _state.asStateFlow()

    /** A freshly generated phrase, held only while it is being shown. */
    private val _draftMnemonic = MutableStateFlow<String?>(null)
    val draftMnemonic: StateFlow<String?> = _draftMnemonic.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.value = withContext(Dispatchers.IO) {
                WalletsUiState(
                    wallets = runCatching { SecureWalletManager.listWallets(ctx) }
                        .getOrDefault(emptyList()),
                    selectedIndex = runCatching {
                        SecureWalletManager.getSelectedWalletIndex(ctx)
                    }.getOrDefault(0),
                )
            }
        }
    }

    fun select(index: Int, onSwitched: () -> Unit) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.selectWallet(ctx, index) }
            }
            refresh()
            onSwitched()
        }
    }

    /** Generate a phrase to show. Nothing is stored until it is confirmed. */
    fun beginCreate() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _draftMnemonic.value = withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.generateMnemonic(ctx) }.getOrNull()
            }
            if (_draftMnemonic.value == null) _error.value = "Could not generate a phrase."
        }
    }

    fun discardDraft() {
        _draftMnemonic.value = null
    }

    /**
     * Store the draft under [name] and switch to it.
     *
     * createWallet already makes the new wallet the selected one, so there is
     * no second write to race with.
     */
    fun confirmCreate(name: String, onCreated: () -> Unit) {
        val mnemonic = _draftMnemonic.value ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    SecureWalletManager.createWallet(ctx, name.ifBlank { defaultName() }, mnemonic)
                }.isSuccess
            }
            if (!ok) {
                _error.value = "Could not save the wallet."
                return@launch
            }
            _draftMnemonic.value = null
            refresh()
            onCreated()
        }
    }

    /** Restore from a phrase the user already has. */
    fun import(name: String, phrase: String, onImported: () -> Unit) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val cleaned = phrase.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

            val valid = withContext(Dispatchers.IO) {
                runCatching { SecureWalletManager.validateMnemonic(ctx, cleaned) }
                    .getOrDefault(false)
            }
            if (!valid) {
                // Checked before storing rather than after: an invalid phrase
                // saved is a wallet whose address never matches and whose
                // funds appear to have vanished.
                _error.value = "That is not a valid recovery phrase."
                return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    SecureWalletManager.createWallet(ctx, name.ifBlank { defaultName() }, cleaned)
                }.isSuccess
            }
            if (!ok) {
                _error.value = "Could not save the wallet."
                return@launch
            }
            refresh()
            onImported()
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun defaultName(): String =
        "Wallet ${(_state.value?.wallets?.size ?: 0) + 1}"
}
