package com.example.earthwallet.ui.components

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.example.earthwallet.R

/**
 * A reusable loading overlay component that displays a loading gif
 * Can be used throughout the app by adding it to layouts or programmatically
 */
class LoadingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "LoadingOverlay"
    }

    private var loadingGif: ImageView? = null
    private var isInitialized = false

    init {
        init(context)
    }

    private fun init(context: Context) {
        // Inflate the loading overlay layout
        LayoutInflater.from(context).inflate(R.layout.component_loading_overlay, this, true)

        // Find views
        loadingGif = findViewById(R.id.loading_gif)

        // Initially hidden
        visibility = View.GONE

        Log.d(TAG, "LoadingOverlay initialized")
    }

    /**
     * Initialize the loading gif with Glide
     * Must be called with a Fragment or Activity context for Glide to work properly
     */
    fun initializeWithFragment(fragment: Fragment?) {
        loadingGif?.let { gif ->
            fragment?.let { frag ->
                try {
                    Glide.with(frag)
                        .asGif()
                        .load(R.drawable.loading)
                        .transform(CenterCrop())
                        .override(1600, 1600) // Double the original size (800dp -> 1600dp)
                        .into(gif)
                    isInitialized = true
                    Log.d(TAG, "Loading gif initialized with fragment at 2x size")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize loading gif with fragment", e)
                }
            }
        }
    }

    /**
     * Initialize the loading gif with Glide using Activity context
     */
    fun initializeWithActivity(activity: AppCompatActivity?) {
        loadingGif?.let { gif ->
            activity?.let { act ->
                try {
                    Glide.with(act)
                        .asGif()
                        .load(R.drawable.loading)
                        .transform(CenterCrop())
                        .override(1600, 1600) // Double the original size (800dp -> 1600dp)
                        .into(gif)
                    isInitialized = true
                    Log.d(TAG, "Loading gif initialized with activity at 2x size")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize loading gif with activity", e)
                }
            }
        }
    }

    /**
     * Show the loading overlay
     */
    fun show() {
        if (!isInitialized) {
            Log.w(TAG, "LoadingOverlay not initialized with Glide - call initializeWithFragment() or initializeWithActivity() first")
        }

        visibility = View.VISIBLE
        // Make sure the loading gif is visible
        loadingGif?.visibility = View.VISIBLE
        Log.d(TAG, "Loading overlay shown")
    }

    /**
     * Hide the loading overlay
     */
    fun hide() {
        visibility = View.GONE
        // Hide the loading gif as well
        loadingGif?.visibility = View.GONE
        Log.d(TAG, "Loading overlay hidden")
    }

    /**
     * Toggle the loading overlay visibility
     */
    fun toggle() {
        if (visibility == View.VISIBLE) {
            hide()
        } else {
            show()
        }
    }

    /**
     * Check if the loading overlay is currently visible
     */
    fun isShowing(): Boolean = visibility == View.VISIBLE

    /**
     * Clean up resources when the overlay is no longer needed
     */
    @JvmOverloads
    fun cleanup(context: Context? = null) {
        loadingGif?.let { gif ->
            try {
                Glide.with(this.context).clear(gif)
                Log.d(TAG, "LoadingOverlay resources cleaned up")
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up LoadingOverlay resources", e)
            }
        }
    }
}