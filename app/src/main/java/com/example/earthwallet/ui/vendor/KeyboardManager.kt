/*
 * Vendored from Zodl (https://github.com/zodl-inc/zodl-android)
 * Copyright (c) 2024 Electric Coin Company. Licensed under the MIT License.
 *
 * Adapted for Earth: package renamed, Zashi -> Earth, the raw palette re-skinned
 * to the Sprout ramps, and the handful of Zcash-specific dependencies replaced
 * with platform equivalents. Zcash money types and the components built on them
 * are not included.
 */
package network.erth.wallet.ui.vendor

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

@Stable
class KeyboardManager(
    isOpen: Boolean,
    private val rootSoftwareKeyboardController: SoftwareKeyboardController?,
) {
    private var targetState = MutableStateFlow(isOpen)

    private var otherWindowsSoftwareKeyboardController by mutableStateOf<SoftwareKeyboardController?>(null)

    val controller by derivedStateOf { otherWindowsSoftwareKeyboardController ?: rootSoftwareKeyboardController }

    var isOpen by mutableStateOf(isOpen)
        private set

    suspend fun close() {
        if (targetState.value) {
            withTimeoutOrNull(.5.seconds) {
                controller?.hide()
                targetState.filter { !it }.first()
            }
        }
    }

    fun onKeyboardOpened() {
        targetState.update { true }
        isOpen = true
    }

    fun onKeyboardClosed() {
        targetState.update { false }
        isOpen = false
    }

    fun onDialogOpened(controller: SoftwareKeyboardController) {
        this.otherWindowsSoftwareKeyboardController = controller
    }

    fun onDialogClosed(controller: SoftwareKeyboardController) {
        if (otherWindowsSoftwareKeyboardController === controller) {
            this.otherWindowsSoftwareKeyboardController = null
        }
    }
}

@Composable
fun rememberKeyboardManager(): KeyboardManager {
    val isKeyboardOpen by rememberKeyboardState()
    val rootSoftwareKeyboardController = LocalSoftwareKeyboardController.current
    val keyboardManager =
        remember(rootSoftwareKeyboardController) {
            KeyboardManager(isKeyboardOpen, rootSoftwareKeyboardController)
        }
    LaunchedEffect(isKeyboardOpen, keyboardManager.controller) {
        if (isKeyboardOpen) {
            keyboardManager.onKeyboardOpened()
        } else {
            keyboardManager.onKeyboardClosed()
        }
    }
    return keyboardManager
}

@Composable
private fun rememberKeyboardState(): State<Boolean> {
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    return rememberUpdatedState(isImeVisible)
}

@Suppress("CompositionLocalAllowlist")
val LocalKeyboardManager =
    compositionLocalOf<KeyboardManager> {
        error("Keyboard manager not provided")
    }
