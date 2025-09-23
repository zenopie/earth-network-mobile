package network.erth.wallet.ui.utils

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * WindowInsetsUtil
 *
 * Utility for managing window insets and fullscreen mode using modern APIs
 * instead of deprecated WindowManager flags.
 */
object WindowInsetsUtil {

    /**
     * Hide system bars (status bar and navigation bar) for fullscreen experience
     */
    fun hideSystemBars(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use WindowInsetsController for Android 11+
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Use deprecated flags for older versions
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
    }

    /**
     * Show system bars (exit fullscreen mode)
     */
    fun showSystemBars(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use WindowInsetsController for Android 11+
            window.setDecorFitsSystemWindows(true)
            val controller = window.insetsController
            controller?.show(WindowInsets.Type.systemBars())
        } else {
            // Use deprecated flags for older versions
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    /**
     * Hide system bars for a specific view (alternative approach)
     */
    fun hideSystemBars(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = view.windowInsetsController
            controller?.hide(WindowInsets.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Use deprecated system UI visibility for older versions
            @Suppress("DEPRECATION")
            view.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    /**
     * Show system bars for a specific view
     */
    fun showSystemBars(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = view.windowInsetsController
            controller?.show(WindowInsets.Type.systemBars())
        } else {
            // Clear deprecated system UI visibility for older versions
            @Suppress("DEPRECATION")
            view.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}