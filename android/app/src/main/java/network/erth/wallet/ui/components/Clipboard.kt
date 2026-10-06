package network.erth.wallet.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Copying to the clipboard, marked sensitive and for a few minutes.
 *
 * What is copied here (a shielded address, a tx hash) is not a secret, but
 * a shielded address is the wallet's private identity: the system clipboard
 * preview would show it and the clipboard keeps it for whatever app is
 * opened next, indefinitely. Marked sensitive (ClipDescription
 * EXTRA_IS_SENSITIVE, honoured from Android 13: no preview, no
 * suggestion), and cleared after [LIFETIME_MS] if it still holds what was
 * copied. Nothing secret is ever copied (no phrase, no PIN). Mirrors iOS
 * Clipboard.swift (local-only, five-minute expiry).
 */
object Clipboard {
    const val LIFETIME_MS = 5 * 60_000L

    /** ClipDescription.EXTRA_IS_SENSITIVE (API 33): the same key, ignored below it. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var copied: String? = null
    private var copiedAt = 0L
    private var observing = false
    private val expire = Runnable { expireNow() }

    /** On the main thread (a click). */
    fun copy(context: Context, label: String, text: String) {
        val a = context.applicationContext
        val cm = a.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        app = a
        copied = text
        copiedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, LIFETIME_MS)
        if (!observing) {
            observing = true
            // A backgrounded app cannot read the clipboard (Android 10+), nor
            // does a frozen process run the timer: the check runs again on return.
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = expireNow()
            })
        }
    }

    private fun expireNow() {
        val text = copied ?: return
        val a = app ?: return
        if (SystemClock.elapsedRealtime() - copiedAt < LIFETIME_MS) return
        val cm = a.getSystemService(ClipboardManager::class.java) ?: return
        // Unreadable while in the background: kept for the next onStart.
        val now = runCatching { cm.primaryClip }.getOrNull() ?: return
        copied = null
        // Only what this app put there; something copied since is left alone.
        if (now.itemCount != 1 || now.getItemAt(0).text?.toString() != text) return
        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
