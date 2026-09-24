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
import network.erth.wallet.wallet.services.SessionManager
import network.erth.wallet.wallet.utils.UnlockAttempts

/**
 * The unlock attempt, and the backoff around it.
 *
 * The secret is checked by decrypting the wallet with it, not against a stored
 * hash. Earlier builds kept an unsalted SHA-256 of it in plain preferences,
 * which for four digits is the PIN itself. On IO, because the decrypt runs
 * PBKDF2 at 600,000 rounds and takes the better part of a second.
 */
class UnlockViewModel(app: Application) : AndroidViewModel(app) {

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lockout = MutableStateFlow<String?>(null)
    val lockout: StateFlow<String?> = _lockout.asStateFlow()

    private var checking = false

    init {
        refreshLockout()
    }

    fun refreshLockout() {
        _lockout.value = UnlockAttempts.status(getApplication()).message
    }

    /**
     * Try the assembled sealing secret.
     *
     * What the secret is made of is the caller's business — a PIN, the value
     * the biometric prompt released, or the two folded together. Only one of
     * them ever reaches the wallet, so only one of them is checked here.
     */
    fun submitSecret(secret: String) {
        // One attempt at a time. The decrypt is slow enough to submit twice
        // into, and a failed startSession clears whatever session is open —
        // including one the other attempt just opened.
        if (checking) return
        checking = true
        viewModelScope.launch {
            try {
                attempt(secret)
            } finally {
                checking = false
            }
        }
    }

    private suspend fun attempt(secret: String) {
        val ctx = getApplication<Application>()

        val status = UnlockAttempts.status(ctx)
        if (status.lockedOut) {
            _lockout.value = status.message
            return
        }

        // With no blob, startSession opens an empty session under any
        // secret — right for first run, and never right here.
        if (!SessionManager.hasSealedStorage(ctx)) {
            _error.value = "Could not unlock. Try again."
            return
        }

        val result = withContext(Dispatchers.IO) {
            runCatching { SessionManager.startSession(ctx, secret) }
        }

        if (result.exceptionOrNull() is SessionManager.WrongSecretException) {
            val after = UnlockAttempts.recordFailure(ctx)
            _lockout.value = after.message
            _error.value = if (after.lockedOut) {
                null
            } else {
                "That did not unlock the wallet. ${after.attemptsLeft} attempts left."
            }
            return
        }

        if (result.isFailure) {
            _error.value = "Could not unlock. Try again."
            return
        }

        UnlockAttempts.recordSuccess(ctx)
        _error.value = null
        _lockout.value = null
    }

    /**
     * Something went wrong before a secret existed — a cancelled prompt, or a
     * biometric key the OS invalidated.
     *
     * Deliberately not an attempt. The backoff exists to make guessing a
     * four-digit PIN expensive, and a refused prompt is not a guess; counting
     * it would let a stray tap lock somebody out of their own wallet.
     */
    fun reportFailure(message: String) {
        _error.value = message
    }
}
