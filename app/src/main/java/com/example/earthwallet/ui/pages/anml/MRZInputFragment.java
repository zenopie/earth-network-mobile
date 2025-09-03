package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

        // Initialize UI elements
        passportNumberEditText = view.findViewById(R.id.passport_number);
        dateOfBirthEditText = view.findViewById(R.id.date_of_birth);
        dateOfExpiryEditText = view.findViewById(R.id.date_of_expiry);
        scanButton = view.findViewById(R.id.scan_button);
        
        // Set default values for testing
        if (passportNumberEditText != null) {
            passportNumberEditText.setText("A01077766");
        }
        if (dateOfBirthEditText != null) {
            dateOfBirthEditText.setText("900215");
        }
        if (dateOfExpiryEditText != null) {
            dateOfExpiryEditText.setText("320228");
        }
        
        // Set click listener for scan button
        if (scanButton != null) {
            scanButton.setOnClickListener(v -> {
                if (validateInput()) {
                    // Get MRZ data and notify parent activity
                    String passportNumber = getTextFromEditText(passportNumberEditText);
                    String dateOfBirth = getTextFromEditText(dateOfBirthEditText);
                    String dateOfExpiry = getTextFromEditText(dateOfExpiryEditText);
                    
                    // Do not log sensitive MRZ values in cleartext during demos
                    Log.d("MRZInputFragment", "Sending MRZ data to parent activity (values suppressed in logs)");
                    
                    if (listener != null) {
                        listener.onMRZDataEntered(passportNumber, dateOfBirth, dateOfExpiry);
                    }
                }
            });
        }

        // Open Secret Wallet screen - navigate through parent HostActivity
        Button openWallet = view.findViewById(R.id.open_wallet_button);
        if (openWallet != null) {
            openWallet.setOnClickListener(v -> {
                if (getActivity() instanceof com.example.earthwallet.ui.host.HostActivity) {
                    ((com.example.earthwallet.ui.host.HostActivity) getActivity()).showFragment("wallet");
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
}