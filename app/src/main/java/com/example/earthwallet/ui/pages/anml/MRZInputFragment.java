package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;

import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;

public class MRZInputFragment extends Fragment {
    
    // UI elements
    private TextInputEditText passportNumberEditText;
    private TextInputEditText dateOfBirthEditText;
    private TextInputEditText dateOfExpiryEditText;
    private Button scanButton;
    
    // Interface for communication with parent activity
    public interface MRZInputListener {
        void onMRZDataEntered(String passportNumber, String dateOfBirth, String dateOfExpiry);
    }
    
    private MRZInputListener listener;
    
    public MRZInputFragment() {}
    
    public static MRZInputFragment newInstance() {
        return new MRZInputFragment();
    }
    
    public void setMRZInputListener(MRZInputListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_mrz_input, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Hide bottom navigation and status bar
        if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
            com.example.earthwallet.ui.host.HostActivity hostActivity = 
                (com.example.earthwallet.ui.host.HostActivity) getActivity();
            hostActivity.hideBottomNavigation();
            
            // Hide status bar
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }

        // Initialize UI elements
        passportNumberEditText = view.findViewById(R.id.passport_number);
        dateOfBirthEditText = view.findViewById(R.id.date_of_birth);
        dateOfExpiryEditText = view.findViewById(R.id.date_of_expiry);
        scanButton = view.findViewById(R.id.scan_button);
        
        // Load captured MRZ data if available, otherwise clear fields
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("mrz_data", getContext().MODE_PRIVATE);
        String savedPassportNumber = prefs.getString("passportNumber", "");
        String savedDateOfBirth = prefs.getString("dateOfBirth", "");
        String savedDateOfExpiry = prefs.getString("dateOfExpiry", "");
        
        if (passportNumberEditText != null) {
            passportNumberEditText.setText(savedPassportNumber);
        }
        if (dateOfBirthEditText != null) {
            dateOfBirthEditText.setText(savedDateOfBirth);
        }
        if (dateOfExpiryEditText != null) {
            dateOfExpiryEditText.setText(savedDateOfExpiry);
        }
        
        // Set click listener for scan button
        if (scanButton != null) {
            scanButton.setOnClickListener(v -> {
                if (validateInput()) {
                    // Get MRZ data and save it to SharedPreferences for the scanner
                    String passportNumber = getTextFromEditText(passportNumberEditText);
                    String dateOfBirth = getTextFromEditText(dateOfBirthEditText);
                    String dateOfExpiry = getTextFromEditText(dateOfExpiryEditText);
                    
                    // Do not log sensitive MRZ values in cleartext during demos
                    Log.d("MRZInputFragment", "Saving MRZ data and navigating to scanner");
                    
                    // Save MRZ data to SharedPreferences for the scanner to use
                    android.content.SharedPreferences scannerPrefs = getContext().getSharedPreferences("mrz_data", getContext().MODE_PRIVATE);
                    android.content.SharedPreferences.Editor editor = scannerPrefs.edit();
                    editor.putString("passportNumber", passportNumber);
                    editor.putString("dateOfBirth", dateOfBirth);
                    editor.putString("dateOfExpiry", dateOfExpiry);
                    editor.apply();
                    
                    // Navigate to scanner fragment - let HostActivity handle UI state
                    if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                        com.example.earthwallet.ui.host.HostActivity hostActivity = 
                            (com.example.earthwallet.ui.host.HostActivity) getActivity();
                        hostActivity.showFragment("scanner");
                    }
                    
                    // Also notify listener if present (for embedded usage)
                    if (listener != null) {
                        listener.onMRZDataEntered(passportNumber, dateOfBirth, dateOfExpiry);
                    }
                }
            });
        }

    }
    
    private String getTextFromEditText(TextInputEditText editText) {
        if (editText != null && editText.getText() != null) {
            return editText.getText().toString().trim();
        }
        return "";
    }
    
    private boolean validateInput() {
        // Check if all required fields are filled
        if (isEmpty(passportNumberEditText)) {
            passportNumberEditText.setError("Passport number is required");
            return false;
        }
        
        if (isEmpty(dateOfBirthEditText)) {
            dateOfBirthEditText.setError("Date of birth is required");
            return false;
        }
        
        if (isEmpty(dateOfExpiryEditText)) {
            dateOfExpiryEditText.setError("Date of expiry is required");
            return false;
        }
        
        // Additional validation for specific fields
        String dateOfBirth = getTextFromEditText(dateOfBirthEditText);
        if (dateOfBirth != null && dateOfBirth.length() != 6) {
            dateOfBirthEditText.setError("Date of birth must be 6 characters (YYMMDD)");
            return false;
        }
        
        String dateOfExpiry = getTextFromEditText(dateOfExpiryEditText);
        if (dateOfExpiry != null && dateOfExpiry.length() != 6) {
            dateOfExpiryEditText.setError("Date of expiry must be 6 characters (YYMMDD)");
            return false;
        }
        
        return true;
    }
    
    private boolean isEmpty(TextInputEditText editText) {
        return editText == null || editText.getText() == null || TextUtils.isEmpty(editText.getText().toString().trim());
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Don't restore navigation here - let HostActivity manage it based on the target fragment
        // The HostActivity showFragment() method will properly set navigation state
    }
}