package com.example.earthwallet.ui.pages.governance;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;

/**
 * Simplified governance fragment - now navigation goes directly to individual fund fragments
 * This serves as a fallback if somehow accessed directly
 */
public class GovernanceFragment extends Fragment {
    
    private static final String TAG = "GovernanceFragment";
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Create a simple layout programmatically since we don't need the full layout anymore
        TextView textView = new TextView(getContext());
        textView.setText("Please use the governance options from the Actions menu.");
        textView.setPadding(32, 32, 32, 32);
        textView.setTextSize(16);
        
        Log.d(TAG, "GovernanceFragment accessed directly - should navigate via Actions menu instead");
        
        return textView;
    }
}