package com.example.earthwallet.ui.components

import android.app.Dialog
import android.content.Context
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.drawable.AnimatedVectorDrawable
import com.bumptech.glide.Glide
import android.os.Handler
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import com.example.earthwallet.R

/**
 * A general-purpose status modal that can display loading, success, or error states
 * Similar to the React StatusModal component but adapted for Android
 */
class StatusModal(private var context: Context?, theme: Theme = Theme.LIGHT) {

    companion object {
        private const val TAG = "StatusModal"
    }

    enum class State {
        LOADING, SUCCESS, ERROR
    }

    enum class Theme {
        LIGHT
    }

    interface OnCloseListener {
        fun onClose()
    }

    private var dialog: Dialog? = null
    private var loadingSpinner: ImageView? = null
    private var successCheckmark: ImageView? = null
    private var errorCrossmark: ImageView? = null
    private var closeListener: OnCloseListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentState = State.LOADING
    private var isShowing = false
    private val modalTheme = Theme.LIGHT // Force light theme only

    init {
        context?.let { initDialog(it) }
    }

    private fun initDialog(context: Context) {
        dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_status_modal, null)
        dialog?.setContentView(view)

        // Set overlay background color for light theme
        view.setBackgroundColor(0xCCFFFFFF.toInt()) // Semi-transparent white
        Log.d(TAG, "Set light theme background")

        // Make dialog non-cancelable by default
        dialog?.setCancelable(false)
        dialog?.setCanceledOnTouchOutside(false)

        Log.d(TAG, "Dialog initialized with theme: $modalTheme")

        // Find views
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        successCheckmark = view.findViewById(R.id.success_checkmark)
        errorCrossmark = view.findViewById(R.id.error_crossmark)

        // Set up the loading animation based on theme
        setupLoadingAnimation()

        // Set up overlay click listener
        view.setOnClickListener {
            if (currentState != State.LOADING && closeListener != null) {
                close()
            }
        }

        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /**
     * Show the modal with the specified state
     */
    fun show(state: State) {
        if (isShowing) {
            return
        }

        Log.d(TAG, "Showing modal with state: $state")
        currentState = state
        isShowing = true

        updateView(state)

        dialog?.let { dialog ->
            if (!dialog.isShowing()) {
                try {
                    Log.d(TAG, "Attempting to show dialog...")
                    dialog.show()
                    Log.d(TAG, "Dialog shown successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing dialog", e)
                    isShowing = false
                    return
                }
            } else {
                Log.w(TAG, "Dialog is already showing")
            }
        } ?: run {
            Log.w(TAG, "Dialog is null")
        }

        // Auto-close success and error states after delay
        if (state == State.SUCCESS || state == State.ERROR) {
            handler.postDelayed({
                if (isShowing && closeListener != null) {
                    close()
                }
            }, 1500)
        }
    }

    /**
     * Update the modal to show a different state
     */
    fun updateState(state: State) {
        if (!isShowing || dialog?.isShowing() != true) {
            return
        }

        Log.d(TAG, "Updating modal state to: $state")
        currentState = state
        updateView(state)

        // Auto-close success and error states after delay
        if (state == State.SUCCESS || state == State.ERROR) {
            handler.postDelayed({
                if (isShowing && closeListener != null) {
                    close()
                }
            }, 1500)
        }
    }

    private fun updateView(state: State) {
        // Loading spinner is always visible and running in the background
        // We only show/hide the overlay elements

        when (state) {
            State.LOADING -> {
                // Only loading spinner visible, no overlays
                successCheckmark?.visibility = View.GONE
                errorCrossmark?.visibility = View.GONE
                startLoadingAnimation()
            }
            State.SUCCESS -> {
                // Show success overlay on top of spinning loader
                successCheckmark?.visibility = View.VISIBLE
                errorCrossmark?.visibility = View.GONE
                startSuccessAnimation()
            }
            State.ERROR -> {
                // Show error overlay on top of spinning loader
                successCheckmark?.visibility = View.GONE
                errorCrossmark?.visibility = View.VISIBLE
                startErrorAnimation()
            }
        }
    }

    private fun setupLoadingAnimation() {
        Log.d(TAG, "Setting up loading animation - spinner: ${loadingSpinner != null}, context: ${context != null}")

        if (loadingSpinner != null && context != null) {
            Log.d(TAG, "Setting up light theme animation with Glide")
            // Use GIF for light theme - scale it 2x larger
            Glide.with(context!!)
                .asGif()
                .load(R.drawable.loading)
                .transform(com.bumptech.glide.load.resource.bitmap.CenterCrop())
                .override(1600, 1600) // Double the original size (800dp -> 1600dp)
                .into(loadingSpinner!!)
        } else {
            Log.w(TAG, "Cannot setup loading animation - missing dependencies")
        }
    }

    private fun startLoadingAnimation() {
        setupLoadingAnimation()
    }

    private fun startSuccessAnimation() {
        successCheckmark?.let { checkmark ->
            // Start with 50% scale and invisible
            checkmark.scaleX = 0.5f
            checkmark.scaleY = 0.5f
            checkmark.alpha = 1.0f

            // Create circle scaling animation: 50% -> 110% -> 100%
            val scaleUpX = ObjectAnimator.ofFloat(checkmark, "scaleX", 0.5f, 1.1f)
            val scaleUpY = ObjectAnimator.ofFloat(checkmark, "scaleY", 0.5f, 1.1f)
            scaleUpX.duration = 200
            scaleUpY.duration = 200

            val scaleDownX = ObjectAnimator.ofFloat(checkmark, "scaleX", 1.1f, 1.0f)
            val scaleDownY = ObjectAnimator.ofFloat(checkmark, "scaleY", 1.1f, 1.0f)
            scaleDownX.duration = 150
            scaleDownY.duration = 150

            // Set interpolator for smooth animation
            val interpolator = AccelerateDecelerateInterpolator()
            scaleUpX.interpolator = interpolator
            scaleUpY.interpolator = interpolator
            scaleDownX.interpolator = interpolator
            scaleDownY.interpolator = interpolator

            // Create animation sequence
            val scaleUpSet = AnimatorSet()
            scaleUpSet.playTogether(scaleUpX, scaleUpY)

            val scaleDownSet = AnimatorSet()
            scaleDownSet.playTogether(scaleDownX, scaleDownY)

            val circleAnimation = AnimatorSet()
            circleAnimation.playSequentially(scaleUpSet, scaleDownSet)

            // No checkmark drawing animation - just the circle scaling

            circleAnimation.start()
        }
    }

    private fun startErrorAnimation() {
        errorCrossmark?.let { crossmark ->
            val drawable = crossmark.drawable
            if (drawable is AnimatedVectorDrawable) {
                drawable.start()
            }
        }
    }

    /**
     * Close the modal
     */
    fun close() {
        Log.d(TAG, "Closing modal")

        dialog?.let { dialog ->
            if (dialog.isShowing()) {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.e(TAG, "Error dismissing dialog", e)
                }
            }
        }

        isShowing = false

        // Clear any pending auto-close callbacks
        handler.removeCallbacksAndMessages(null)

        closeListener?.onClose()
    }

    /**
     * Set a listener for when the modal is closed
     */
    fun setOnCloseListener(listener: OnCloseListener?) {
        this.closeListener = listener
    }

    /**
     * Check if the modal is currently showing
     */
    fun isShowing(): Boolean {
        return isShowing && dialog?.isShowing() == true
    }

    /**
     * Get the current state
     */
    fun getCurrentState(): State {
        return currentState
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        Log.d(TAG, "Destroying modal")
        handler.removeCallbacksAndMessages(null)

        // Clear Glide resources - but only if activity is not destroyed
        if (loadingSpinner != null && context != null) {
            try {
                // Check if activity is still alive before using Glide
                val ctx = context!!
                if (ctx is android.app.Activity) {
                    if (!ctx.isDestroyed) {
                        Glide.with(ctx).clear(loadingSpinner!!)
                    }
                } else {
                    Glide.with(ctx).clear(loadingSpinner!!)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear Glide resources: ${e.message}")
            }
        }

        dialog?.let { dialog ->
            if (dialog.isShowing()) {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.e(TAG, "Error dismissing dialog during destroy", e)
                }
            }
        }
        dialog = null
        closeListener = null
        context = null
        isShowing = false
    }
}