package com.example.passportscanner;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.icao.DG1File;
import org.jmrtd.lds.icao.MRZInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

import net.sf.scuba.smartcards.CardService;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PassportScanner";
    // Change this URL to point to your backend endpoint that will verify the DG1 and SOD.
    private static final String BACKEND_URL = "https://example.com/verify";

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private String[][] techLists;
    
    // UI elements
    private ProgressBar progressBar;
    private TextView statusText;
    private ScrollView resultContainer;
    private TextView passportNumberText;
    private TextView nameText;
    private TextView nationalityText;
    private TextView dobText;
    private TextView genderText;
    private TextView expiryText;
    private TextView countryText;
    
    // MRZ data from intent
    private String passportNumber;
    private String dateOfBirth;
    private String dateOfExpiry;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // First, try to retrieve MRZ data from SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("mrz_data", MODE_PRIVATE);
        String savedPassportNumber = prefs.getString("passportNumber", null);
        String savedDateOfBirth = prefs.getString("dateOfBirth", null);
        String savedDateOfExpiry = prefs.getString("dateOfExpiry", null);
        Log.d(TAG, "Retrieved MRZ data from SharedPreferences: passportNumber=" + savedPassportNumber +
            ", dateOfBirth=" + savedDateOfBirth + ", dateOfExpiry=" + savedDateOfExpiry);
        
        // Get MRZ data from intent
        Intent intent = getIntent();
        if (intent != null) {
            String intentPassportNumber = intent.getStringExtra("passportNumber");
            String intentDateOfBirth = intent.getStringExtra("dateOfBirth");
            String intentDateOfExpiry = intent.getStringExtra("dateOfExpiry");
            Log.d(TAG, "Received MRZ data from intent: passportNumber=" + intentPassportNumber +
                ", dateOfBirth=" + intentDateOfBirth + ", dateOfExpiry=" + intentDateOfExpiry);
            
            // Use intent data if it's valid, otherwise use saved data
            if (!isEmpty(intentPassportNumber) && !isEmpty(intentDateOfBirth) && !isEmpty(intentDateOfExpiry)) {
                passportNumber = intentPassportNumber;
                dateOfBirth = intentDateOfBirth;
                dateOfExpiry = intentDateOfExpiry;
                
                // Save new MRZ data to SharedPreferences
                android.content.SharedPreferences.Editor editor = prefs.edit();
                editor.putString("passportNumber", passportNumber);
                editor.putString("dateOfBirth", dateOfBirth);
                editor.putString("dateOfExpiry", dateOfExpiry);
                editor.apply();
                Log.d(TAG, "Saved new MRZ data to SharedPreferences");
            } else if (!isEmpty(savedPassportNumber) && !isEmpty(savedDateOfBirth) && !isEmpty(savedDateOfExpiry)) {
                // Use saved data if intent data is not complete but saved data is
                passportNumber = savedPassportNumber;
                dateOfBirth = savedDateOfBirth;
                dateOfExpiry = savedDateOfExpiry;
                Log.d(TAG, "Using MRZ data from SharedPreferences (intent data was incomplete)");
            }
        } else {
            // No intent, use saved data if available
            if (!isEmpty(savedPassportNumber) && !isEmpty(savedDateOfBirth) && !isEmpty(savedDateOfExpiry)) {
                passportNumber = savedPassportNumber;
                dateOfBirth = savedDateOfBirth;
                dateOfExpiry = savedDateOfExpiry;
                Log.d(TAG, "Using MRZ data from SharedPreferences (no intent)");
            }
        }
        
        // Log current MRZ data validity
        Log.d(TAG, "Final MRZ data: passportNumber=" + passportNumber +
            ", dateOfBirth=" + dateOfBirth + ", dateOfExpiry=" + dateOfExpiry);
        
        // Check if MRZ data is available
        Log.d(TAG, "Checking MRZ data: passportNumber=" + passportNumber + " (isEmpty=" + isEmpty(passportNumber) +
            "), dateOfBirth=" + dateOfBirth + " (isEmpty=" + isEmpty(dateOfBirth) +
            "), dateOfExpiry=" + dateOfExpiry + " (isEmpty=" + isEmpty(dateOfExpiry) + ")");
        if (!isMRZDataValid()) {
            Log.d(TAG, "MRZ data is incomplete, redirecting to MRZ input activity");
            // If MRZ data is not available, redirect to MRZ input activity
            Intent mrzIntent = new Intent(this, MRZInputActivity.class);
            startActivity(mrzIntent);
            finish();
            return;
        }
        Log.d(TAG, "MRZ data is complete, proceeding with NFC setup");
        Log.d(TAG, "Current MRZ data: passportNumber=" + passportNumber +
            ", dateOfBirth=" + dateOfBirth + ", dateOfExpiry=" + dateOfExpiry);
        
        // Initialize UI elements
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
        resultContainer = findViewById(R.id.result_container);
        passportNumberText = findViewById(R.id.passport_number_text);
        nameText = findViewById(R.id.name_text);
        nationalityText = findViewById(R.id.nationality_text);
        dobText = findViewById(R.id.dob_text);
        genderText = findViewById(R.id.gender_text);
        expiryText = findViewById(R.id.expiry_text);
        countryText = findViewById(R.id.country_text);
        
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        
        // Create pending intent for NFC discovery
        Intent nfcIntent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, PendingIntent.FLAG_MUTABLE);
        
        // Use tech lists for foreground dispatch so IsoDep-based passports are delivered.
        // Leave intentFilters null (we'll rely on tech lists instead).
        intentFilters = null;
        techLists = new String[][] { new String[] { IsoDep.class.getName() } };
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent called with intent: " + intent);
        if (intent != null) {
            // Check if this is an NFC intent
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                Log.d(TAG, "NFC tag discovered, starting ReadPassportTask");
                new ReadPassportTask().execute(tag);
            } else {
                // This might be a regular intent, check for MRZ data
                String newPassportNumber = intent.getStringExtra("passportNumber");
                String newDateOfBirth = intent.getStringExtra("dateOfBirth");
                String newDateOfExpiry = intent.getStringExtra("dateOfExpiry");
                
                Log.d(TAG, "Received MRZ data in onNewIntent: passportNumber=" + newPassportNumber +
                    ", dateOfBirth=" + newDateOfBirth + ", dateOfExpiry=" + newDateOfExpiry);
                
                // Check if we received valid MRZ data
                if (!isEmpty(newPassportNumber) && !isEmpty(newDateOfBirth) && !isEmpty(newDateOfExpiry)) {
                    Log.d(TAG, "Received valid MRZ data in onNewIntent");
                    passportNumber = newPassportNumber;
                    dateOfBirth = newDateOfBirth;
                    dateOfExpiry = newDateOfExpiry;
                    
                    // Save to SharedPreferences
                    android.content.SharedPreferences prefs = getSharedPreferences("mrz_data", MODE_PRIVATE);
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("passportNumber", passportNumber);
                    editor.putString("dateOfBirth", dateOfBirth);
                    editor.putString("dateOfExpiry", dateOfExpiry);
                    editor.apply();
                    Log.d(TAG, "Saved new MRZ data to SharedPreferences");
                } else {
                    // Only log that MRZ data is incomplete, don't redirect
                    Log.d(TAG, "MRZ data is incomplete in onNewIntent, keeping existing data");
                    // Log current MRZ data to see what we have
                    Log.d(TAG, "Current MRZ data: passportNumber=" + passportNumber +
                        ", dateOfBirth=" + dateOfBirth + ", dateOfExpiry=" + dateOfExpiry);
                }
            }
        }
    }
    
    private class ReadPassportTask extends AsyncTask<Tag, Void, PassportData> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // Show progress UI
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            }
            if (statusText != null) {
                statusText.setVisibility(View.VISIBLE);
            }
            if (resultContainer != null) {
                resultContainer.setVisibility(View.GONE);
            }
        }
        
        @Override
        protected PassportData doInBackground(Tag... params) {
            if (params == null || params.length == 0 || params[0] == null) {
                return null;
            }
            Tag tag = params[0];
            return readPassport(tag);
        }
        
        @Override
        protected void onPostExecute(PassportData passportData) {
            super.onPostExecute(passportData);
            Log.d(TAG, "onPostExecute called with passportData=" + passportData);
            // Hide progress UI
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            if (statusText != null) {
                statusText.setVisibility(View.GONE);
            }
            
            if (passportData != null) {
                Log.d(TAG, "Passport data read successfully");
                // Display passport data
                displayPassportData(passportData);
                // Also show raw DG1 / MRZ fields in a simple dialog for debugging/inspection
                Log.d(TAG, "Showing raw DG1/MRZ dialog");
                showRawDG1Dialog(passportData);
            } else {
                Log.d(TAG, "Failed to read passport data");
                Toast.makeText(MainActivity.this, "Failed to read passport", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    private PassportData readPassport(Tag tag) {
        try {
            Log.d(TAG, "Starting passport reading process");
            // Connect to the passport using ISO 14443-4
            IsoDep isoDep = IsoDep.get(tag);
            if (isoDep == null) {
                Log.d(TAG, "isoDep is null");
                return null;
            }
            Log.d(TAG, "Connecting to passport");
            isoDep.connect();
            isoDep.setTimeout(5000);

            // Create card service
            Log.d(TAG, "Creating card service");
            CardService cardService = CardService.getInstance(isoDep);
            cardService.open();

            // Create passport service with correct parameters
            Log.d(TAG, "Creating passport service");
            PassportService passportService = new PassportService(cardService,
                    PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                    PassportService.DEFAULT_MAX_BLOCKSIZE,
                    false,
                    false);
            passportService.open();

            // Select passport application with correct parameter
            Log.d(TAG, "Selecting passport application");
            passportService.sendSelectApplet(false);

            // Create BAC key from user-provided MRZ data
            Log.d(TAG, "Creating BAC key with documentNumber=" + passportNumber +
                    ", dateOfBirth=" + dateOfBirth + ", dateOfExpiry=" + dateOfExpiry);

            // Validate MRZ data before creating BAC key
            if (isEmpty(passportNumber) || isEmpty(dateOfBirth) || isEmpty(dateOfExpiry)) {
                Log.e(TAG, "MRZ data is incomplete, cannot create BAC key");
                return null;
            }

            String documentNumber = passportNumber;
            String birthDate = dateOfBirth;
            String expiryDate = dateOfExpiry;

            BACKeySpec bacKey = new BACKey(documentNumber, birthDate, expiryDate);

            // Perform BAC
            Log.d(TAG, "Performing BAC");
            passportService.doBAC(bacKey);

            // Read DG1 (basic information) - capture raw bytes so we can send them to backend
            Log.d(TAG, "Reading DG1");
            InputStream dg1In = passportService.getInputStream(PassportService.EF_DG1);
            if (dg1In == null) {
                Log.d(TAG, "dg1In is null");
                return null;
            }
            byte[] dg1Bytes = readAllBytes(dg1In);
            DG1File dg1File = new DG1File(new ByteArrayInputStream(dg1Bytes));
            MRZInfo mrzInfo = dg1File.getMRZInfo();
            Log.d(TAG, "MRZ info retrieved: " + mrzInfo);

            // Read SOD (to verify signatures server-side)
            Log.d(TAG, "Reading SOD");
            InputStream sodIn = passportService.getInputStream(PassportService.EF_SOD);
            byte[] sodBytes = null;
            if (sodIn != null) {
                sodBytes = readAllBytes(sodIn);
            } else {
                Log.d(TAG, "sodIn is null");
            }

            // Create passport data object
            PassportData passportData = new PassportData();
            if (mrzInfo != null) {
                passportData.setDocumentNumber(mrzInfo.getDocumentNumber());
                passportData.setPrimaryIdentifier(mrzInfo.getPrimaryIdentifier());
                passportData.setSecondaryIdentifier(mrzInfo.getSecondaryIdentifier());
                passportData.setNationality(mrzInfo.getNationality());
                passportData.setDateOfBirth(mrzInfo.getDateOfBirth());
                passportData.setGender(mrzInfo.getGender().toString());
                passportData.setDateOfExpiry(mrzInfo.getDateOfExpiry());
                passportData.setIssuingState(mrzInfo.getIssuingState());
                Log.d(TAG, "Passport data set successfully");
            } else {
                Log.d(TAG, "mrzInfo is null");
            }

            // Send DG1 and SOD to backend for signature verification (this is run on background thread)
            try {
                Log.d(TAG, "Sending DG1 and SOD to backend for verification");
                String verificationResponse = sendToBackend(dg1Bytes, sodBytes);
                Log.d(TAG, "Backend verification response: " + verificationResponse);
            } catch (Exception e) {
                Log.e(TAG, "Error sending data to backend", e);
            }

            // Close connections
            try {
                Log.d(TAG, "Closing passport service");
                passportService.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing passport service", e);
            }
            try {
                Log.d(TAG, "Closing card service");
                cardService.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing card service", e);
            }
            try {
                Log.d(TAG, "Closing ISO-DEP");
                isoDep.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing ISO-DEP", e);
            }

            Log.d(TAG, "Passport reading completed successfully");
            return passportData;
        } catch (Exception e) {
            Log.e(TAG, "Error reading passport", e);
            return null;
        }
    }

    /**
     * Helper to read all bytes from an InputStream.
     */
    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buffer.write(tmp, 0, n);
        }
        try {
            in.close();
        } catch (Exception ignored) {}
        return buffer.toByteArray();
    }

    /**
     * Sends DG1 and SOD to the backend as a JSON POST request with base64-encoded fields:
     * { "dg1": "...", "sod": "..." }
     *
     * Runs synchronously (this method is called from doInBackground), and returns the backend response as string.
     */
    private String sendToBackend(byte[] dg1Bytes, byte[] sodBytes) throws Exception {
        if (dg1Bytes == null) {
            throw new IllegalArgumentException("dg1Bytes is required");
        }

        String dg1B64 = android.util.Base64.encodeToString(dg1Bytes, android.util.Base64.NO_WRAP);
        String sodB64 = sodBytes != null ? android.util.Base64.encodeToString(sodBytes, android.util.Base64.NO_WRAP) : "";

        JSONObject payload = new JSONObject();
        payload.put("dg1", dg1B64);
        payload.put("sod", sodB64);

        URL url = new URL(BACKEND_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] out = payload.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(out.length);
            conn.connect();

            OutputStream os = conn.getOutputStream();
            os.write(out);
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            InputStream responseStream = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = "";
            if (responseStream != null) {
                byte[] respBytes = readAllBytes(responseStream);
                responseBody = new String(respBytes, "UTF-8");
            }
            return "HTTP " + responseCode + ": " + responseBody;
        } finally {
            conn.disconnect();
        }
    }
    
    private void displayPassportData(PassportData passportData) {
        if (resultContainer != null) {
            resultContainer.setVisibility(View.VISIBLE);
        }
        
        if (passportNumberText != null && passportData.getDocumentNumber() != null) {
            passportNumberText.setText(passportData.getDocumentNumber());
        }
        if (nameText != null && passportData.getPrimaryIdentifier() != null && passportData.getSecondaryIdentifier() != null) {
            nameText.setText(passportData.getPrimaryIdentifier() + " " + passportData.getSecondaryIdentifier());
        }
        if (nationalityText != null && passportData.getNationality() != null) {
            nationalityText.setText(passportData.getNationality());
        }
        if (dobText != null && passportData.getDateOfBirth() != null) {
            dobText.setText(formatDate(passportData.getDateOfBirth()));
        }
        if (genderText != null && passportData.getGender() != null) {
            genderText.setText(passportData.getGender());
        }
        if (expiryText != null && passportData.getDateOfExpiry() != null) {
            expiryText.setText(formatDate(passportData.getDateOfExpiry()));
        }
        if (countryText != null && passportData.getIssuingState() != null) {
            countryText.setText(passportData.getIssuingState());
        }
    }

    /**
     * Shows a simple AlertDialog with the raw DG1 / MRZ fields parsed from the passport.
     * This is useful for debugging or when you want to inspect the exact MRZ values read.
     */
    private void showRawDG1Dialog(PassportData passportData) {
        if (passportData == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Document Number: ").append(passportData.getDocumentNumber() != null ? passportData.getDocumentNumber() : "").append("\n");
        sb.append("Primary Identifier: ").append(passportData.getPrimaryIdentifier() != null ? passportData.getPrimaryIdentifier() : "").append("\n");
        sb.append("Secondary Identifier: ").append(passportData.getSecondaryIdentifier() != null ? passportData.getSecondaryIdentifier() : "").append("\n");
        sb.append("Nationality: ").append(passportData.getNationality() != null ? passportData.getNationality() : "").append("\n");
        sb.append("Date of Birth (YYMMDD): ").append(passportData.getDateOfBirth() != null ? passportData.getDateOfBirth() : "").append("\n");
        sb.append("Gender: ").append(passportData.getGender() != null ? passportData.getGender() : "").append("\n");
        sb.append("Date of Expiry (YYMMDD): ").append(passportData.getDateOfExpiry() != null ? passportData.getDateOfExpiry() : "").append("\n");
        sb.append("Issuing State: ").append(passportData.getIssuingState() != null ? passportData.getIssuingState() : "").append("\n");

        new AlertDialog.Builder(this)
                .setTitle("Raw DG1 / MRZ Data")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }
    
    private String formatDate(String date) {
        if (date == null) {
            return "";
        }
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyMMdd", Locale.getDefault());
            SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            return outputFormat.format(inputFormat.parse(date));
        } catch (ParseException e) {
            return date;
        }
    }
    
    private boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    private boolean isMRZDataValid() {
        return !isEmpty(passportNumber) && !isEmpty(dateOfBirth) && !isEmpty(dateOfExpiry);
    }
    
    // Simple class to hold passport data
    private static class PassportData {
        private String documentNumber;
        private String primaryIdentifier;
        private String secondaryIdentifier;
        private String nationality;
        private String dateOfBirth;
        private String gender;
        private String dateOfExpiry;
        private String issuingState;
        
        // Getters and setters
        public String getDocumentNumber() {
            return documentNumber;
        }
        
        public void setDocumentNumber(String documentNumber) {
            this.documentNumber = documentNumber;
        }
        
        public String getPrimaryIdentifier() {
            return primaryIdentifier;
        }
        
        public void setPrimaryIdentifier(String primaryIdentifier) {
            this.primaryIdentifier = primaryIdentifier;
        }
        
        public String getSecondaryIdentifier() {
            return secondaryIdentifier;
        }
        
        public void setSecondaryIdentifier(String secondaryIdentifier) {
            this.secondaryIdentifier = secondaryIdentifier;
        }
        
        public String getNationality() {
            return nationality;
        }
        
        public void setNationality(String nationality) {
            this.nationality = nationality;
        }
        
        public String getDateOfBirth() {
            return dateOfBirth;
        }
        
        public void setDateOfBirth(String dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
        }
        
        public String getGender() {
            return gender;
        }
        
        public void setGender(String gender) {
            this.gender = gender;
        }
        
        public String getDateOfExpiry() {
            return dateOfExpiry;
        }
        
        public void setDateOfExpiry(String dateOfExpiry) {
            this.dateOfExpiry = dateOfExpiry;
        }
        
        public String getIssuingState() {
            return issuingState;
        }
        
        public void setIssuingState(String issuingState) {
            this.issuingState = issuingState;
        }
    }
}