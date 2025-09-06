package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraMRZScannerFragment extends Fragment {
    private static final String TAG = "CameraMRZScanner";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 200;
    
    private PreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private TextRecognizer textRecognizer;
    private ImageAnalysis imageAnalysis;
    
    // MRZ parsing patterns
    private static final Pattern MRZ_LINE1_PATTERN = Pattern.compile("P<([A-Z]{3})([A-Z<]+)");
    private static final Pattern MRZ_LINE2_PATTERN = Pattern.compile("([A-Z0-9<]{9})([0-9]{1})([A-Z]{3})([0-9]{6})([0-9]{1})([MF<]{1})([0-9]{6})([0-9]{1})([A-Z0-9<]{14})([0-9]{1})([0-9]{1})");
    
    public CameraMRZScannerFragment() {}
    
    public static CameraMRZScannerFragment newInstance() {
        return new CameraMRZScannerFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera_mrz_scanner, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        Log.d(TAG, "onViewCreated called");
        
        // Hide bottom navigation and status bar - keep portrait orientation
        try {
            if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                Log.d(TAG, "Hiding navigation and status bar");
                com.example.earthwallet.ui.host.HostActivity hostActivity = 
                    (com.example.earthwallet.ui.host.HostActivity) getActivity();
                hostActivity.hideBottomNavigation();
                
                // Hide status bar
                getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                
                Log.d(TAG, "UI setup completed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up UI", e);
        }
        
        previewView = view.findViewById(R.id.camera_preview);
        Button backButton = view.findViewById(R.id.btn_back);
        Button manualEntryButton = view.findViewById(R.id.btn_manual_entry);
        
        Log.d(TAG, "Views found - previewView: " + (previewView != null) + 
                   ", backButton: " + (backButton != null) + 
                   ", manualEntryButton: " + (manualEntryButton != null));
        
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    com.example.earthwallet.ui.host.HostActivity hostActivity = 
                        (com.example.earthwallet.ui.host.HostActivity) getActivity();
                    hostActivity.showBottomNavigation();
                    
                    // Show status bar
                    getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                    
                    hostActivity.showFragment("anml");
                }
            });
        }
        
        if (manualEntryButton != null) {
            manualEntryButton.setOnClickListener(v -> {
                Log.d(TAG, "Manual entry button clicked");
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    com.example.earthwallet.ui.host.HostActivity hostActivity = 
                        (com.example.earthwallet.ui.host.HostActivity) getActivity();
                    
                    // Show status bar before navigating
                    getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                    
                    hostActivity.showFragment("mrz_input");
                }
            });
        }
        
        // Initialize ML Kit Text Recognizer
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        
        // Check camera permission
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called");
        
        // Check if we need to start camera (in case permission was granted while paused)
        if (allPermissionsGranted() && previewView != null && cameraProviderFuture == null) {
            Log.d(TAG, "Permission available on resume, starting camera");
            startCamera();
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause called");
    }
    
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                Log.d(TAG, "Camera permission granted, starting camera...");
                // Add a small delay to ensure views are ready
                if (getView() != null) {
                    getView().post(() -> {
                        if (isAdded() && !isDetached() && getContext() != null) {
                            startCamera();
                        } else {
                            Log.w(TAG, "Fragment not ready to start camera after permission grant");
                        }
                    });
                } else {
                    Log.w(TAG, "View not available after permission grant");
                }
            } else {
                Toast.makeText(getContext(), "Camera permission is required to scan passport", Toast.LENGTH_LONG).show();
                // Go back to ANML screen and restore UI
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    com.example.earthwallet.ui.host.HostActivity hostActivity = 
                        (com.example.earthwallet.ui.host.HostActivity) getActivity();
                    hostActivity.showBottomNavigation();
                    
                    // Show status bar
                    getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                    
                    hostActivity.showFragment("anml");
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    
    private void startCamera() {
        Log.d(TAG, "Starting camera...");
        if (getContext() == null || previewView == null) {
            Log.e(TAG, "Context or previewView is null, cannot start camera");
            return;
        }
        
        if (!allPermissionsGranted()) {
            Log.e(TAG, "Camera permission not granted, cannot start camera");
            return;
        }
        
        try {
            cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
            cameraProviderFuture.addListener(() -> {
                try {
                    // Double-check that we're still in a valid state
                    if (getContext() == null || previewView == null || !isAdded()) {
                        Log.w(TAG, "Fragment not in valid state when camera provider ready");
                        return;
                    }
                    
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    Log.d(TAG, "Camera provider obtained, binding preview...");
                    bindPreview(cameraProvider);
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Error starting camera", e);
                }
            }, ContextCompat.getMainExecutor(requireContext()));
        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera provider", e);
        }
    }
    
    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Log.d(TAG, "Binding camera preview...");
        
        if (getContext() == null || previewView == null) {
            Log.e(TAG, "Context or preview view is null, cannot bind camera");
            return;
        }
        
        try {
            Preview preview = new Preview.Builder().build();
            
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();
            
            Log.d(TAG, "Setting surface provider...");
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            
            imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            
            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(requireContext()), new MRZAnalyzer());
            
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll();
            
            Log.d(TAG, "Binding to lifecycle...");
            Camera camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis);
            Log.d(TAG, "Camera successfully bound!");
        } catch (Exception e) {
            Log.e(TAG, "Error binding camera", e);
        }
    }
    
    private class MRZAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull androidx.camera.core.ImageProxy imageProxy) {
            @SuppressWarnings("UnsafeOptInUsageError")
            android.media.Image mediaImage = imageProxy.getImage();
            if (mediaImage != null) {
                InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                
                textRecognizer.process(image)
                        .addOnSuccessListener(new OnSuccessListener<Text>() {
                            @Override
                            public void onSuccess(Text visionText) {
                                processMRZ(visionText.getText());
                            }
                        })
                        .addOnFailureListener(new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception e) {
                                Log.e(TAG, "Text recognition failed", e);
                            }
                        })
                        .addOnCompleteListener(task -> imageProxy.close());
            } else {
                imageProxy.close();
            }
        }
    }
    
    private void processMRZ(String text) {
        String[] lines = text.split("\n");
        
        for (int i = 0; i < lines.length - 1; i++) {
            String line1 = lines[i].replaceAll("\\s", "").toUpperCase();
            String line2 = lines[i + 1].replaceAll("\\s", "").toUpperCase();
            
            // Look for MRZ pattern
            if (line1.startsWith("P<") && line2.length() >= 36) {
                MRZInfo mrzInfo = parseMRZ(line1, line2);
                if (mrzInfo != null && mrzInfo.isValid()) {
                    Log.d(TAG, "MRZ detected and parsed successfully");
                    onMRZDetected(mrzInfo);
                    return;
                }
            }
        }
    }
    
    private MRZInfo parseMRZ(String line1, String line2) {
        try {
            // Parse passport number, date of birth, and expiry date from line2
            if (line2.length() >= 36) {
                String passportNumber = line2.substring(0, 9).replace('<', ' ').trim();
                String dobStr = line2.substring(13, 19); // YYMMDD
                String expiryStr = line2.substring(21, 27); // YYMMDD
                
                return new MRZInfo(passportNumber, dobStr, expiryStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing MRZ", e);
        }
        return null;
    }
    
    private void onMRZDetected(MRZInfo mrzInfo) {
        // Stop camera analysis
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        
        // Save MRZ data and navigate to manual entry screen
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("mrz_data", getContext().MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        editor.putString("passportNumber", mrzInfo.passportNumber);
        editor.putString("dateOfBirth", mrzInfo.dateOfBirth);
        editor.putString("dateOfExpiry", mrzInfo.dateOfExpiry);
        editor.apply();
        
        Log.d(TAG, "MRZ data saved, navigating to manual entry");
        
        // Navigate to manual entry screen with captured data
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    com.example.earthwallet.ui.host.HostActivity hostActivity = 
                        (com.example.earthwallet.ui.host.HostActivity) getActivity();
                    
                    // Show status bar before navigating
                    getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                    
                    hostActivity.showFragment("mrz_input");
                }
            });
        }
    }
    
    private void stopCamera() {
        Log.d(TAG, "Attempting to stop camera...");
        try {
            if (imageAnalysis != null) {
                Log.d(TAG, "Clearing image analyzer");
                imageAnalysis.clearAnalyzer();
                imageAnalysis = null;
            }
            if (cameraProviderFuture != null && cameraProviderFuture.isDone()) {
                Log.d(TAG, "Unbinding camera provider");
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();
                Log.d(TAG, "Camera successfully stopped and unbound");
            } else {
                Log.w(TAG, "Camera provider future is null or not done");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping camera", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (textRecognizer != null) {
            textRecognizer.close();
        }
    }
    
    // Helper class to store MRZ information
    private static class MRZInfo {
        final String passportNumber;
        final String dateOfBirth;
        final String dateOfExpiry;
        
        MRZInfo(String passportNumber, String dateOfBirth, String dateOfExpiry) {
            this.passportNumber = passportNumber;
            this.dateOfBirth = dateOfBirth;
            this.dateOfExpiry = dateOfExpiry;
        }
        
        boolean isValid() {
            return passportNumber != null && !passportNumber.trim().isEmpty() &&
                   dateOfBirth != null && dateOfBirth.length() == 6 &&
                   dateOfExpiry != null && dateOfExpiry.length() == 6;
        }
    }
}