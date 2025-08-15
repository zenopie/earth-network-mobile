package com.example.passportscanner;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;

import com.google.android.material.textfield.TextInputEditText;

public class MRZInputActivity extends AppCompatActivity {
    
    // UI elements
    private TextInputEditText passportNumberEditText;
    private TextInputEditText dateOfBirthEditText;
    private TextInputEditText dateOfExpiryEditText;
    private Button scanButton;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mrz_input);
        
        // Initialize UI elements
        passportNumberEditText = findViewById(R.id.passport_number);
        dateOfBirthEditText = findViewById(R.id.date_of_birth);
        dateOfExpiryEditText = findViewById(R.id.date_of_expiry);
        scanButton = findViewById(R.id.scan_button);
        
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
            scanButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (validateInput()) {
                        // Create intent to pass MRZ data to MainActivity
                        String passportNumber = getTextFromEditText(passportNumberEditText);
                        String dateOfBirth = getTextFromEditText(dateOfBirthEditText);
                        String dateOfExpiry = getTextFromEditText(dateOfExpiryEditText);
                        
                        Log.d("MRZInputActivity", "Sending MRZ data: passportNumber=" + passportNumber +
                            ", dateOfBirth=" + dateOfBirth + ", dateOfExpiry=" + dateOfExpiry);
                        
                        Intent intent = new Intent(MRZInputActivity.this, MainActivity.class);
                        intent.putExtra("passportNumber", passportNumber);
                        intent.putExtra("dateOfBirth", dateOfBirth);
                        intent.putExtra("dateOfExpiry", dateOfExpiry);
                        startActivity(intent);
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
}