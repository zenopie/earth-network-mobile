package network.erth.wallet.ui.compose

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps the screen out of screenshots and out of the recents thumbnail for as
 * long as it is composed.
 *
 * The recents half is the one that bites. A wallet showing a recovery phrase
 * gets backgrounded like any other app — a call arrives, the user switches away
 * to write the words down — and the system snapshots whatever is on screen to
 * draw the app-switcher card. Without FLAG_SECURE that snapshot is the phrase,
 * held by the system, surviving until the task is dismissed and visible to
 * anyone who picks the phone up.
 *
 * Applied per screen rather than to the whole activity on purpose. This is a
 * single-activity Compose app, so setting the flag on ComposeAppActivity would
 * blank every screenshot in the wallet — including an address or a transaction
 * someone has a good reason to capture. The flag belongs on the screens where
 * capture is the attack: the phrase, and the PIN.
 *
 * Cleared on dispose rather than left set, because the flag is a property of
 * the window and not of the composable — leaving it on would silently make the
 * per-screen choice above into the app-wide one it is avoiding.
 */
@Composable
fun SecureScreen() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
