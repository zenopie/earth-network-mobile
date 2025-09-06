package com.example.earthwallet.ui.pages.anml;

import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;

public class ScanFailureFragment extends Fragment {
    private static final String TAG = "ScanFailureFragment";
    private static final String ARG_REASON = "failure_reason";
    private static final String ARG_DETAILS = "failure_details";
    
    private String failureReason;
    private String failureDetails;
    
    public ScanFailureFragment() {}
    
    public static ScanFailureFragment newInstance(String reason, String details) {
        ScanFailureFragment fragment = new ScanFailureFragment();
        Bundle args = new Bundle();
        args.putString(ARG_REASON, reason);
        args.putString(ARG_DETAILS, details);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            failureReason = getArguments().getString(ARG_REASON);
            failureDetails = getArguments().getString(ARG_DETAILS);
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan_failure, container, false);
    }
    
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        Log.d(TAG, "ScanFailureFragment created with reason: " + failureReason);
        
        // Hide bottom navigation and status bar
        if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
            com.example.earthwallet.ui.host.HostActivity hostActivity = 
                (com.example.earthwallet.ui.host.HostActivity) getActivity();
            hostActivity.hideBottomNavigation();
            
            // Hide status bar
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }
        
        // Set failure reason and details
        TextView reasonText = view.findViewById(R.id.failure_reason);
        TextView detailsText = view.findViewById(R.id.failure_details);
        
        if (reasonText != null && failureReason != null) {
            reasonText.setText(failureReason);
        }
        
        if (detailsText != null && failureDetails != null) {
            detailsText.setText(failureDetails);
        }
        
        // Start error animation
        ImageView errorIcon = view.findViewById(R.id.error_icon);
        if (errorIcon != null && errorIcon.getDrawable() instanceof AnimatedVectorDrawable) {
            AnimatedVectorDrawable animatedDrawable = (AnimatedVectorDrawable) errorIcon.getDrawable();
            animatedDrawable.start();
        }
        
        // Set up buttons
        Button tryAgainButton = view.findViewById(R.id.btn_try_again);
        Button backToAnmlButton = view.findViewById(R.id.btn_back_to_anml);
        
        if (tryAgainButton != null) {
            tryAgainButton.setOnClickListener(v -> {
                Log.d(TAG, "Try Again button clicked");
                navigateToScanner();
            });
        }
        
        if (backToAnmlButton != null) {
            backToAnmlButton.setOnClickListener(v -> {
                Log.d(TAG, "Back to ANML button clicked");
                navigateBackToANML();
            });
        }
    }
    
    private void navigateToScanner() {
        if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
            com.example.earthwallet.ui.host.HostActivity hostActivity = 
                (com.example.earthwallet.ui.host.HostActivity) getActivity();
            
            // Navigate to scanner (which will handle the UI state)
            hostActivity.showFragment("scanner");
        }
    }
    
    private void navigateBackToANML() {
        if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
            com.example.earthwallet.ui.host.HostActivity hostActivity = 
                (com.example.earthwallet.ui.host.HostActivity) getActivity();
            
            // Show bottom navigation and status bar
            hostActivity.showBottomNavigation();
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            
            // Navigate to ANML page
            hostActivity.showFragment("anml");
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // UI state will be managed by the target fragment
    }
}