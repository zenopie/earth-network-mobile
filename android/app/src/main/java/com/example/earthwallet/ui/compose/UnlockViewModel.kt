package network.erth.wallet.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SessionManager
import network.erth.wallet.wallet.utils.UnlockAttempts

/**
 * The unlock attempt, and the backoff around it.
 *
 * Ported from PinEntryFragment rather than rewritten: the hash is SHA-256 of
 * the PIN's UTF-8 bytes, lowercase hex, and changing that would lock every
 * existing wallet out of its own mnemonic. The fragment did this on the main
 * thread; here it is on IO, because the keystore read behind
 * verifyPinHashWithoutSession is not instant on a cold start.
 */
class UnlockViewModel(app: Application) : AndroidViewModel(app) {

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lockout = MutableStateFlow<String?>(null)
    val lockout: StateFlow<String?> = _lockout.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

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
        viewModelScope.launch {
            val ctx = getApplication<Application>()

            val status = UnlockAttempts.status(ctx)
            if (status.lockedOut) {
                _lockout.value = status.message
                return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    SecureWalletManager.verifyPinHashWithoutSession(ctx, secret.sha256Hex())
                }.getOrDefault(false)
            }

            if (!ok) {
                val after = UnlockAttempts.recordFailure(ctx)
                _lockout.value = after.message
                _error.value = if (after.lockedOut) {
                    null
                } else {
                    "That did not unlock the wallet. ${after.attemptsLeft} attempts left."
                }
                return@launch
            }

            val started = withContext(Dispatchers.IO) {
                runCatching { SessionManager.startSession(ctx, secret) }.isSuccess
            }
            if (!started) {
                _error.value = "Could not unlock. Try again."
                return@launch
            }

            UnlockAttempts.recordSuccess(ctx)
            _error.value = null
            _lockout.value = null
            _unlocked.value = true
        }
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

/**
 * The stored hash's format. Do not change without a migration.
 *
 * Shared with [OnboardingViewModel], which writes the hash this verifies. Two
 * copies of it would be two ways to spell the same PIN, and the wallet is
 * encrypted under it.
 */
internal fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
