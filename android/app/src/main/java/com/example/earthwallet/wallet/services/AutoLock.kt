package network.erth.wallet.wallet.services

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Ends the session when the app has been away long enough.
 *
 * Without this a session opened once stayed open for the life of the process,
 * which on Android can be days, with the decrypted wallet and the secret in
 * memory the whole time and every transaction a tap away.
 *
 * Process-wide rather than per-activity, so moving between this app's own
 * activities — registration, the QR scanner, an ad — never counts as leaving.
 * The passport read, the MRZ camera and the biometric prompt all stay inside
 * a started activity and do not stop the process at all; the grace period is
 * for the user who glances at another app and comes straight back.
 *
 * Ending the session is the whole lock. ComposeAppActivity follows
 * [SessionManager.active] back to the unlock gate, and screens above it
 * finish themselves.
 */
object AutoLock {

    private const val GRACE_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Runnable { SessionManager.endSession() }

    // elapsedRealtime, because it keeps counting through deep sleep. The
    // delayed runnable does not — a handler's clock stops with the CPU, and a
    // cached process may be frozen outright — so the check on return is the
    // one that is guaranteed; the runnable only clears memory sooner when the
    // process happens to be awake.
    private var stoppedAt = 0L

    fun install(app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                stoppedAt = SystemClock.elapsedRealtime()
                handler.postDelayed(lock, GRACE_MS)
            }

            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(lock)
                if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt >= GRACE_MS) {
                    SessionManager.endSession()
                }
                stoppedAt = 0L
            }
        })

        // A screen turned off is a phone put down, so there is no grace. Only
        // deliverable to a receiver registered at runtime, which is why it is
        // here rather than in the manifest.
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    handler.removeCallbacks(lock)
                    SessionManager.endSession()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
