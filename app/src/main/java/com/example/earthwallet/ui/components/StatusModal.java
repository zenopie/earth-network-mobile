package com.example.earthwallet.ui.components;

import android.app.Dialog;
import android.content.Context;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import android.os.Handler;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.example.earthwallet.R;

/**
 * A general-purpose status modal that can display loading, success, or error states
 * Similar to the React StatusModal component but adapted for Android
 */
public class StatusModal {
    private static final String TAG = "StatusModal";
    
    public enum State {
        LOADING, SUCCESS, ERROR
    }
    
    public enum Theme {
        LIGHT
    }
    
    public interface OnCloseListener {
        void onClose();
    }
    
    private Dialog dialog;
    private ImageView loadingSpinner;
    private ImageView successCheckmark;
    private ImageView errorCrossmark;
    private OnCloseListener closeListener;
    private Handler handler;
    private State currentState = State.LOADING;
    private boolean isShowing = false;
    private Context context;
    private Theme theme;
    
    public StatusModal(@NonNull Context context) {
        this(context, Theme.LIGHT); // Only light theme available
    }
    
    public StatusModal(@NonNull Context context, Theme theme) {
        this.context = context;
        this.theme = Theme.LIGHT; // Force light theme only
        this.handler = new Handler(Looper.getMainLooper());
        initDialog(context);
    }
    
    private void initDialog(@NonNull Context context) {
        dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_status_modal, null);
        dialog.setContentView(view);
        
        // Set overlay background color for light theme
        view.setBackgroundColor(0xCCFFFFFF); // Semi-transparent white
        Log.d(TAG, "Set light theme background");
        
        // Make dialog non-cancelable by default
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        
        Log.d(TAG, "Dialog initialized with theme: " + theme);
        
        // Find views
        loadingSpinner = view.findViewById(R.id.loading_spinner);
        successCheckmark = view.findViewById(R.id.success_checkmark);
        errorCrossmark = view.findViewById(R.id.error_crossmark);
        
        // Set up the loading animation based on theme
        setupLoadingAnimation();
        
        // Set up overlay click listener
        view.setOnClickListener(v -> {
            if (currentState != State.LOADING && closeListener != null) {
                close();
            }
        });
        
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }
    
    /**
     * Show the modal with the specified state
     */
    public void show(State state) {
        if (isShowing) {
            return;
        }
        
        Log.d(TAG, "Showing modal with state: " + state);
        currentState = state;
        isShowing = true;
        
        updateView(state);
        
        if (dialog != null && !dialog.isShowing()) {
            try {
                Log.d(TAG, "Attempting to show dialog...");
                dialog.show();
                Log.d(TAG, "Dialog shown successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error showing dialog", e);
                isShowing = false;
                return;
            }
        } else {
            Log.w(TAG, "Dialog is null or already showing - dialog: " + (dialog != null) + ", isShowing: " + (dialog != null ? dialog.isShowing() : "null"));
        }
        
        // Auto-close success and error states after delay
        if (state == State.SUCCESS || state == State.ERROR) {
            handler.postDelayed(() -> {
                if (isShowing && closeListener != null) {
                    close();
                }
            }, 1500);
        }
    }
    
    /**
     * Update the modal to show a different state
     */
    public void updateState(State state) {
        if (!isShowing || dialog == null || !dialog.isShowing()) {
            return;
        }
        
        Log.d(TAG, "Updating modal state to: " + state);
        currentState = state;
        updateView(state);
        
        // Auto-close success and error states after delay
        if (state == State.SUCCESS || state == State.ERROR) {
            handler.postDelayed(() -> {
                if (isShowing && closeListener != null) {
                    close();
                }
            }, 1500);
        }
    }
    
    private void updateView(State state) {
        // Loading spinner is always visible and running in the background
        // We only show/hide the overlay elements
        
        switch (state) {
            case LOADING:
                // Only loading spinner visible, no overlays
                successCheckmark.setVisibility(View.GONE);
                errorCrossmark.setVisibility(View.GONE);
                startLoadingAnimation();
                break;
            case SUCCESS:
                // Show success overlay on top of spinning loader
                successCheckmark.setVisibility(View.VISIBLE);
                errorCrossmark.setVisibility(View.GONE);
                startSuccessAnimation();
                break;
            case ERROR:
                // Show error overlay on top of spinning loader
                successCheckmark.setVisibility(View.GONE);
                errorCrossmark.setVisibility(View.VISIBLE);
                startErrorAnimation();
                break;
        }
    }
    
    private void setupLoadingAnimation() {
        Log.d(TAG, "Setting up loading animation - spinner: " + (loadingSpinner != null) + ", context: " + (context != null));
        
        if (loadingSpinner != null && context != null) {
            Log.d(TAG, "Setting up light theme animation with Glide");
            // Use GIF for light theme - scale it 2x larger
            Glide.with(context)
                    .asGif()
                    .load(R.drawable.loading)
                    .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop())
                    .override(1600, 1600) // Double the original size (800dp -> 1600dp)
                    .into(loadingSpinner);
        } else {
            Log.w(TAG, "Cannot setup loading animation - missing dependencies");
        }
    }
    
    private void startLoadingAnimation() {
        setupLoadingAnimation();
    }
    
    private void startSuccessAnimation() {
        if (successCheckmark != null) {
            // Start with 50% scale and invisible
            successCheckmark.setScaleX(0.5f);
            successCheckmark.setScaleY(0.5f);
            successCheckmark.setAlpha(1.0f);
            
            // Create circle scaling animation: 50% -> 110% -> 100%
            ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(successCheckmark, "scaleX", 0.5f, 1.1f);
            ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(successCheckmark, "scaleY", 0.5f, 1.1f);
            scaleUpX.setDuration(200);
            scaleUpY.setDuration(200);
            
            ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(successCheckmark, "scaleX", 1.1f, 1.0f);
            ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(successCheckmark, "scaleY", 1.1f, 1.0f);
            scaleDownX.setDuration(150);
            scaleDownY.setDuration(150);
            
            // Set interpolator for smooth animation
            AccelerateDecelerateInterpolator interpolator = new AccelerateDecelerateInterpolator();
            scaleUpX.setInterpolator(interpolator);
            scaleUpY.setInterpolator(interpolator);
            scaleDownX.setInterpolator(interpolator);
            scaleDownY.setInterpolator(interpolator);
            
            // Create animation sequence
            AnimatorSet scaleUpSet = new AnimatorSet();
            scaleUpSet.playTogether(scaleUpX, scaleUpY);
            
            AnimatorSet scaleDownSet = new AnimatorSet();
            scaleDownSet.playTogether(scaleDownX, scaleDownY);
            
            AnimatorSet circleAnimation = new AnimatorSet();
            circleAnimation.playSequentially(scaleUpSet, scaleDownSet);
            
            // No checkmark drawing animation - just the circle scaling
            
            circleAnimation.start();
        }
    }
    
    private void startErrorAnimation() {
        if (errorCrossmark != null && errorCrossmark.getDrawable() instanceof AnimatedVectorDrawable) {
            AnimatedVectorDrawable animatedDrawable = (AnimatedVectorDrawable) errorCrossmark.getDrawable();
            animatedDrawable.start();
        }
    }
    
    /**
     * Close the modal
     */
    public void close() {
        Log.d(TAG, "Closing modal");
        
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing dialog", e);
            }
        }
        
        isShowing = false;
        
        // Clear any pending auto-close callbacks
        handler.removeCallbacksAndMessages(null);
        
        if (closeListener != null) {
            closeListener.onClose();
        }
    }
    
    /**
     * Set a listener for when the modal is closed
     */
    public void setOnCloseListener(OnCloseListener listener) {
        this.closeListener = listener;
    }
    
    /**
     * Check if the modal is currently showing
     */
    public boolean isShowing() {
        return isShowing && dialog != null && dialog.isShowing();
    }
    
    /**
     * Get the current state
     */
    public State getCurrentState() {
        return currentState;
    }
    
    /**
     * Clean up resources
     */
    public void destroy() {
        Log.d(TAG, "Destroying modal");
        handler.removeCallbacksAndMessages(null);
        
        // Clear Glide resources - but only if activity is not destroyed
        if (loadingSpinner != null && context != null) {
            try {
                // Check if activity is still alive before using Glide
                if (context instanceof android.app.Activity) {
                    android.app.Activity activity = (android.app.Activity) context;
                    if (!activity.isDestroyed()) {
                        Glide.with(context).clear(loadingSpinner);
                    }
                } else {
                    Glide.with(context).clear(loadingSpinner);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not clear Glide resources: " + e.getMessage());
            }
        }
        
        if (dialog != null) {
            if (dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (Exception e) {
                    Log.e(TAG, "Error dismissing dialog during destroy", e);
                }
            }
            dialog = null;
        }
        closeListener = null;
        context = null;
        isShowing = false;
    }
}