package network.erth.wallet.ui.unlock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.wallet.SessionManager
import network.erth.wallet.wallet.UnlockAttempts
import network.erth.wallet.wallet.UnlockMethod

/**
 * A fresh unlock in front of something an unlocked phone alone must not
 * get: another wallet's recovery phrase is that wallet, for good.
 * The same gate as the unlock screen (PIN, biometric or both, whichever seals
 * this wallet), checked against the open session's secret, and counted
 * against the same backoff: a guess here is a guess at the unlock.
 */
@Composable
fun ConfirmUnlockDialog(onConfirmed: (secret: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var lockout by remember { mutableStateOf(UnlockAttempts.status(context).message) }
    var checking by remember { mutableStateOf(false) }
    // The keypad is inert while the backoff runs, so re-read it until it clears.
    LaunchedEffect(lockout != null) {
        while (lockout != null) {
            delay(1_000)
            lockout = UnlockAttempts.status(context).message
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        // The PIN pad is not screenshot or recorded.
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
    ) {
        UnlockGate(
            method = UnlockMethod.current(context),
            onSecret = { secret ->
                if (checking) return@UnlockGate
                val status = UnlockAttempts.status(context)
                if (status.lockedOut) { lockout = status.message; return@UnlockGate }
                checking = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { SessionManager.verifySecret(secret) }
                    checking = false
                    if (ok) {
                        UnlockAttempts.recordSuccess(context)
                        onConfirmed(secret)
                    } else {
                        val after = UnlockAttempts.recordFailure(context)
                        lockout = after.message
                        error = if (after.lockedOut) null else "That did not match. ${after.attemptsLeft} attempts left."
                    }
                }
            },
            onFailure = { error = it },
            error = error,
            lockoutMessage = lockout,
        )
    }
}
