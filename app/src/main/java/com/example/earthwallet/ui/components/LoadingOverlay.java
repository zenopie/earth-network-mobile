package com.example.earthwallet.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.earthwallet.R;

/**
 * A reusable loading overlay component that displays a loading gif
 * Can be used throughout the app by adding it to layouts or programmatically
 */
public class LoadingOverlay extends FrameLayout {
    private static final String TAG = "LoadingOverlay";

    private ImageView loadingGif;
    private boolean isInitialized = false;

    public LoadingOverlay(@NonNull Context context) {
        super(context);
        init(context);
    }

    public LoadingOverlay(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public LoadingOverlay(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // Inflate the loading overlay layout
        LayoutInflater.from(context).inflate(R.layout.component_loading_overlay, this, true);

        // Find views
        loadingGif = findViewById(R.id.loading_gif);

        // Initially hidden
        setVisibility(GONE);

        Log.d(TAG, "LoadingOverlay initialized");
    }

    /**
     * Initialize the loading gif with Glide
     * Must be called with a Fragment or Activity context for Glide to work properly
     */
    public void initializeWithFragment(Fragment fragment) {
        if (loadingGif != null && fragment != null) {
            try {
                Glide.with(fragment)
                        .asGif()
                        .load(R.drawable.loading)
                        .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop())
                        .override(1600, 1600) // Double the original size (800dp -> 1600dp)
                        .into(loadingGif);
                isInitialized = true;
                Log.d(TAG, "Loading gif initialized with fragment at 2x size");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize loading gif with fragment", e);
            }
        }
    }

    /**
     * Initialize the loading gif with Glide using Activity context
     */
    public void initializeWithContext(Context context) {
        if (loadingGif != null && context != null) {
            try {
                Glide.with(context)
                        .asGif()
                        .load(R.drawable.loading)
                        .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop())
                        .override(1600, 1600) // Double the original size (800dp -> 1600dp)
                        .into(loadingGif);
                isInitialized = true;
                Log.d(TAG, "Loading gif initialized with context at 2x size");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize loading gif with context", e);
            }
        }
    }

    /**
     * Show the loading overlay
     */
    public void show() {
        if (!isInitialized) {
            Log.w(TAG, "LoadingOverlay not initialized. Call initializeWithFragment() or initializeWithContext() first");
        }

        setVisibility(VISIBLE);
        if (loadingGif != null) {
            loadingGif.setVisibility(VISIBLE);
        }
        Log.d(TAG, "Loading overlay shown");
    }

    /**
     * Hide the loading overlay
     */
    public void hide() {
        setVisibility(GONE);
        if (loadingGif != null) {
            loadingGif.setVisibility(GONE);
        }
        Log.d(TAG, "Loading overlay hidden");
    }

    /**
     * Check if the overlay is currently showing
     */
    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /**
     * Clean up Glide resources
     * Should be called in onDestroy() or similar lifecycle methods
     */
    public void cleanup(Context context) {
        if (loadingGif != null && context != null) {
            try {
                if (context instanceof android.app.Activity) {
                    android.app.Activity activity = (android.app.Activity) context;
                    if (!activity.isDestroyed()) {
                        Glide.with(context).clear(loadingGif);
                    }
                } else {
                    Glide.with(context).clear(loadingGif);
                }
                Log.d(TAG, "Cleaned up Glide resources");
            } catch (Exception e) {
                Log.w(TAG, "Could not clean up Glide resources: " + e.getMessage());
            }
        }
        isInitialized = false;
    }
}