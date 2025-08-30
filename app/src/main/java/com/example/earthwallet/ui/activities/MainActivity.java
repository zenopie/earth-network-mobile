package com.example.earthwallet.ui.activities;

import com.example.earthwallet.R;

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
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.text.InputType;
import android.content.SharedPreferences;
import android.text.TextUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

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

import com.example.earthwallet.wallet.services.SecretWallet;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PassportScanner";
    // Backend URL loaded at runtime from SharedPreferences or resources
    private String backendUrl;

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private String[][] techLists;
    
    // UI elements
    private ImageView progressBar;
    private TextView statusText;
    private ScrollView resultContainer;
    private TextView passportNumberText;
    private TextView nameText;
    private TextView nationalityText;
    private TextView dobText;
    private TextView genderText;
    private TextView expiryText;
    private TextView countryText;

    // Verification result UI
    private TextView verifyStatusText;
    private TextView trustStatusText;
    private TextView trustReasonText;
    private TextView sodStatusText;
    private TextView dg1StatusText;
    private TextView rawResponseText;
    
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
        resultContainer = findViewById(R.id.result_container);
        passportNumberText = findViewById(R.id.passport_number_text);
        nameText = findViewById(R.id.name_text);
        nationalityText = findViewById(R.id.nationality_text);
        dobText = findViewById(R.id.dob_text);
        genderText = findViewById(R.id.gender_text);
        expiryText = findViewById(R.id.expiry_text);
        countryText = findViewById(R.id.country_text);
 
        // Load GIF into the ImageView using Glide and keep it hidden until needed
        if (progressBar != null) {
            Glide.with(this)
                    .asGif()
                    .load(R.drawable.loading)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(progressBar);
            progressBar.setVisibility(View.GONE);
        }

        // Verification results UI
        verifyStatusText = findViewById(R.id.verify_status_text);
        trustStatusText = findViewById(R.id.trust_status_text);
        trustReasonText = findViewById(R.id.trust_reason_text);
        sodStatusText = findViewById(R.id.sod_status_text);
        dg1StatusText = findViewById(R.id.dg1_status_text);
        rawResponseText = findViewById(R.id.raw_response_text);

        // Load backend URL preference or fallback to resource
        android.content.SharedPreferences appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String savedBackendUrl = appPrefs.getString("backend_url", null);
        backendUrl = savedBackendUrl != null ? savedBackendUrl : getString(R.string.backend_url);
        Log.d(TAG, "Backend URL = " + backendUrl);

        // Long-press title to configure backend URL
        TextView title = findViewById(R.id.title_text);
        if (title != null) {
            title.setOnLongClickListener(v -> {
                showBackendUrlDialog();
                return true;
            });
        }
        
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

        // Bottom navigation wiring
        View navWallet = findViewById(R.id.btn_nav_wallet);
        if (navWallet != null) {
            navWallet.setOnClickListener(v -> {
                Intent w = new Intent(MainActivity.this, com.example.earthwallet.ui.activities.WalletActivity.class);
                startActivity(w);
            });
        }
        View navActions = findViewById(R.id.btn_nav_actions);
        if (navActions != null) {
            navActions.setOnClickListener(v -> {
                Intent a = new Intent(MainActivity.this, ActionsActivity.class);
                startActivity(a);
            });
        }
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
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            if (resultContainer != null) resultContainer.setVisibility(View.GONE);
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
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            
            if (passportData != null) {
                Log.d(TAG, "Passport data read successfully");
                // Ensure the result container is visible
                if (resultContainer != null) {
                    resultContainer.setVisibility(View.VISIBLE);
                }
                // Update the result rows (this will hide any empty rows)
                displayPassportData(passportData);
                // Then display verification and raw response (DG1/MRZ fields remain suppressed)
                displayVerification(passportData);
                Log.d(TAG, "Displayed verification and adjusted result rows (DG1/MRZ suppressed)");
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

            // Send DG1, SOD, and address to backend for signature verification (this is run on background thread)
            try {
                Log.d(TAG, "Sending DG1, SOD, and address to backend for verification");
                String walletAddress = getCurrentWalletAddress();
                Log.d(TAG, "Using wallet address: " + walletAddress);
                BackendResult verification = sendToBackend(dg1Bytes, sodBytes, walletAddress);
                if (verification != null) {
                    Log.d(TAG, "Backend verification response: HTTP " + verification.code + ": " + verification.body);
                    passportData.setBackendHttpCode(verification.code);
                    passportData.setBackendRawResponse(verification.body);
                    parseAndAttachVerification(passportData, verification.body);
                }
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
     * Sends DG1, SOD, and address to the backend as a JSON POST request with base64-encoded fields:
     * { "dg1": "...", "sod": "...", "address": "..." }
     *
     * Runs synchronously (this method is called from doInBackground), and returns the backend response as string.
     */
    private BackendResult sendToBackend(byte[] dg1Bytes, byte[] sodBytes, String address) throws Exception {
        if (dg1Bytes == null) {
            throw new IllegalArgumentException("dg1Bytes is required");
        }
        if (backendUrl == null || backendUrl.trim().isEmpty()) {
            Log.w(TAG, "Backend URL not set. Skipping verification call.");
            return null;
        }

        String dg1B64 = android.util.Base64.encodeToString(dg1Bytes, android.util.Base64.NO_WRAP);
        String sodB64 = sodBytes != null ? android.util.Base64.encodeToString(sodBytes, android.util.Base64.NO_WRAP) : "";

        JSONObject payload = new JSONObject();
        payload.put("dg1", dg1B64);
        payload.put("sod", sodB64);
        payload.put("address", address != null ? address : "");
        
        Log.d(TAG, "BACKEND REQUEST: URL = " + backendUrl);
        Log.d(TAG, "BACKEND REQUEST: DG1 size = " + dg1Bytes.length + " bytes");
        Log.d(TAG, "BACKEND REQUEST: SOD size = " + (sodBytes != null ? sodBytes.length : 0) + " bytes");
        Log.d(TAG, "BACKEND REQUEST: Address = " + (address != null ? address : "null"));
        Log.d(TAG, "BACKEND REQUEST: Full payload = " + payload.toString());

        URL url = new URL(backendUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
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
            Log.d(TAG, "BACKEND RESPONSE: HTTP status = " + responseCode);
            
            InputStream responseStream = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = "";
            if (responseStream != null) {
                byte[] respBytes = readAllBytes(responseStream);
                responseBody = new String(respBytes, "UTF-8");
            }
            
            Log.d(TAG, "BACKEND RESPONSE: Body length = " + responseBody.length());
            Log.d(TAG, "BACKEND RESPONSE: Body = " + responseBody);
            
            BackendResult result = new BackendResult();
            result.code = responseCode;
            result.body = responseBody;
            result.ok = responseCode >= 200 && responseCode < 300;
            return result;
        } finally {
            conn.disconnect();
        }
    }
    
    private void displayPassportData(PassportData passportData) {
        if (resultContainer != null) {
            resultContainer.setVisibility(View.VISIBLE);
        }
        Log.d(TAG, "displayPassportData: doc=" + passportData.getDocumentNumber() +
                " primary=" + passportData.getPrimaryIdentifier() +
                " secondary=" + passportData.getSecondaryIdentifier() +
                " nat=" + passportData.getNationality() +
                " dob=" + passportData.getDateOfBirth() +
                " gender=" + passportData.getGender() +
                " expiry=" + passportData.getDateOfExpiry() +
                " issuing=" + passportData.getIssuingState());

        // Locate rows so we can hide empty ones
        android.widget.TableRow passportRow = findViewById(R.id.passport_row);
        android.widget.TableRow nameRow = findViewById(R.id.name_row);
        android.widget.TableRow nationalityRow = findViewById(R.id.nationality_row);
        android.widget.TableRow dobRow = findViewById(R.id.dob_row);
        android.widget.TableRow genderRow = findViewById(R.id.gender_row);
        android.widget.TableRow expiryRow = findViewById(R.id.expiry_row);
        android.widget.TableRow issuingCountryRow = findViewById(R.id.issuing_country_row);

        // Passport number
        if (passportData.getDocumentNumber() != null && !passportData.getDocumentNumber().trim().isEmpty()) {
            if (passportNumberText != null) passportNumberText.setText(passportData.getDocumentNumber());
            if (passportRow != null) passportRow.setVisibility(View.VISIBLE);
        } else {
            if (passportRow != null) passportRow.setVisibility(View.GONE);
        }

        // Name (primary + secondary)
        String fullName = null;
        if (passportData.getPrimaryIdentifier() != null && passportData.getSecondaryIdentifier() != null) {
            fullName = (passportData.getPrimaryIdentifier() + " " + passportData.getSecondaryIdentifier()).trim();
        } else if (passportData.getPrimaryIdentifier() != null) {
            fullName = passportData.getPrimaryIdentifier();
        } else if (passportData.getSecondaryIdentifier() != null) {
            fullName = passportData.getSecondaryIdentifier();
        }
        if (fullName != null && !fullName.isEmpty()) {
            if (nameText != null) nameText.setText(fullName);
            if (nameRow != null) nameRow.setVisibility(View.VISIBLE);
        } else {
            if (nameRow != null) nameRow.setVisibility(View.GONE);
        }

        // Nationality
        if (passportData.getNationality() != null && !passportData.getNationality().trim().isEmpty()) {
            if (nationalityText != null) nationalityText.setText(passportData.getNationality());
            if (nationalityRow != null) nationalityRow.setVisibility(View.VISIBLE);
        } else {
            if (nationalityRow != null) nationalityRow.setVisibility(View.GONE);
        }

        // Date of birth
        if (passportData.getDateOfBirth() != null && !passportData.getDateOfBirth().trim().isEmpty()) {
            if (dobText != null) dobText.setText(formatDate(passportData.getDateOfBirth()));
            if (dobRow != null) dobRow.setVisibility(View.VISIBLE);
        } else {
            if (dobRow != null) dobRow.setVisibility(View.GONE);
        }

        // Gender
        if (passportData.getGender() != null && !passportData.getGender().trim().isEmpty()) {
            if (genderText != null) genderText.setText(passportData.getGender());
            if (genderRow != null) genderRow.setVisibility(View.VISIBLE);
        } else {
            if (genderRow != null) genderRow.setVisibility(View.GONE);
        }

        // Expiry
        if (passportData.getDateOfExpiry() != null && !passportData.getDateOfExpiry().trim().isEmpty()) {
            if (expiryText != null) expiryText.setText(formatDate(passportData.getDateOfExpiry()));
            if (expiryRow != null) expiryRow.setVisibility(View.VISIBLE);
        } else {
            if (expiryRow != null) expiryRow.setVisibility(View.GONE);
        }

        // Issuing country/state
        if (passportData.getIssuingState() != null && !passportData.getIssuingState().trim().isEmpty()) {
            if (countryText != null) countryText.setText(passportData.getIssuingState());
            if (issuingCountryRow != null) issuingCountryRow.setVisibility(View.VISIBLE);
        } else {
            if (issuingCountryRow != null) issuingCountryRow.setVisibility(View.GONE);
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
    
    private void parseAndAttachVerification(PassportData pd, String jsonBody) {
        if (pd == null || jsonBody == null) return;
        try {
            JSONObject root = new JSONObject(jsonBody);
            pd.setPassiveAuthenticationPassed(root.optBoolean("passive_authentication_passed", false));
            JSONObject details = root.optJSONObject("details");
            if (details != null) {
                JSONObject trust = details.optJSONObject("trust_chain");
                if (trust != null) {
                    pd.setTrustChainStatus(trust.optString("status", null));
                    pd.setTrustChainFailureReason(trust.optString("failure_reason", null));
                }
                JSONObject sodSig = details.optJSONObject("sod_signature");
                if (sodSig != null) {
                    pd.setSodSignatureStatus(sodSig.optString("status", null));
                }
                JSONObject dg1 = details.optJSONObject("dg1_hash_integrity");
                if (dg1 != null) {
                    pd.setDg1IntegrityStatus(dg1.optString("status", null));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse backend JSON", e);
        }
    }

    private void displayVerification(PassportData pd) {
        if (pd == null) return;
        if (verifyStatusText != null) {
            String status = (pd.getPassiveAuthenticationPassed() != null && pd.getPassiveAuthenticationPassed()) ? "VALID" : "INVALID";
            verifyStatusText.setText("Passive Authentication: " + status);
        }
        if (trustStatusText != null) {
            String s = pd.getTrustChainStatus();
            trustStatusText.setText("Trust Chain: " + (s != null ? s : "N/A"));
        }
        if (trustReasonText != null) {
            String r = pd.getTrustChainFailureReason();
            trustReasonText.setText("Trust Failure Reason: " + (r != null && !r.isEmpty() ? r : "None"));
        }
        if (sodStatusText != null) {
            String s = pd.getSodSignatureStatus();
            sodStatusText.setText("SOD Signature: " + (s != null ? s : "N/A"));
        }

        // Hide DG1 status (we don't show DG1/MRZ info in the UI)
        if (dg1StatusText != null) {
            dg1StatusText.setVisibility(View.GONE);
        }

        // Show backend raw response if available; otherwise hide that block entirely
        if (rawResponseText != null) {
            String raw = pd.getBackendRawResponse();
            if (raw != null && !raw.trim().isEmpty()) {
                // Limit size for display but include most content for demo
                String display = raw.length() > 4000 ? raw.substring(0, 4000) + " …" : raw;
                rawResponseText.setText(display);
                rawResponseText.setVisibility(View.VISIBLE);
            } else {
                rawResponseText.setText("");
                rawResponseText.setVisibility(View.GONE);
            }
        }

        Log.d(TAG, "Verification displayed (DG1 suppressed, raw response handled)");
    }

    private void showBackendUrlDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://host/verify or http://<LAN>:<port>/verify");
        input.setText(backendUrl != null ? backendUrl : "");

        new AlertDialog.Builder(this)
                .setTitle("Set Backend URL")
                .setMessage("This app will POST DG1, SOD Base64, and wallet address to this endpoint.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    backendUrl = input.getText() != null ? input.getText().toString().trim() : "";
                    android.content.SharedPreferences appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                    appPrefs.edit().putString("backend_url", backendUrl).apply();
                    Toast.makeText(this, "Backend URL saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
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
    
    /**
     * Get the current wallet address from encrypted shared preferences.
     * Returns null if no wallet is available.
     */
    private String getCurrentWalletAddress() {
        Log.d(TAG, "WALLET: Getting current wallet address...");
        try {
            // Initialize SecretWallet if needed
            SecretWallet.initialize(this);
            Log.d(TAG, "WALLET: SecretWallet initialized");
            
            // Try to get the address from encrypted shared preferences
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    "secret_wallet_prefs",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            Log.d(TAG, "WALLET: Encrypted preferences accessed");
            
            // Prefer wallets array (multi-wallet support). Fall back to legacy top-level mnemonic.
            String mnemonic = "";
            try {
                String walletsJson = securePrefs.getString("wallets", "[]");
                Log.d(TAG, "WALLET: Retrieved wallets JSON: " + walletsJson);
                
                org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
                int sel = securePrefs.getInt("selected_wallet_index", -1);
                Log.d(TAG, "WALLET: Found " + arr.length() + " wallets, selected index: " + sel);
                
                if (arr.length() > 0) {
                    if (sel >= 0 && sel < arr.length()) {
                        mnemonic = arr.getJSONObject(sel).optString("mnemonic", "");
                        Log.d(TAG, "WALLET: Using selected wallet at index " + sel);
                    } else if (arr.length() == 1) {
                        mnemonic = arr.getJSONObject(0).optString("mnemonic", "");
                        Log.d(TAG, "WALLET: Using single wallet (no selection)");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "WALLET: Error reading wallet JSON", e);
            }
            
            if (!TextUtils.isEmpty(mnemonic)) {
                Log.d(TAG, "WALLET: Mnemonic found, deriving address...");
                String address = SecretWallet.getAddressFromMnemonic(mnemonic);
                Log.d(TAG, "WALLET: Derived address: " + address);
                return address;
            } else {
                Log.w(TAG, "WALLET: No mnemonic found");
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "WALLET: Failed to get wallet address", e);
            return null;
        }
    }
    
    // Backend HTTP result holder
    private static class BackendResult {
        int code;
        String body;
        boolean ok;
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

        // Backend verification fields
        private Integer backendHttpCode;
        private String backendRawResponse;
        private Boolean passiveAuthenticationPassed;
        private String trustChainStatus;
        private String trustChainFailureReason;
        private String sodSignatureStatus;
        private String dg1IntegrityStatus;

        public String getDocumentNumber() { return documentNumber; }
        public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }

        public String getPrimaryIdentifier() { return primaryIdentifier; }
        public void setPrimaryIdentifier(String primaryIdentifier) { this.primaryIdentifier = primaryIdentifier; }

        public String getSecondaryIdentifier() { return secondaryIdentifier; }
        public void setSecondaryIdentifier(String secondaryIdentifier) { this.secondaryIdentifier = secondaryIdentifier; }

        public String getNationality() { return nationality; }
        public void setNationality(String nationality) { this.nationality = nationality; }

        public String getDateOfBirth() { return dateOfBirth; }
        public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }

        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }

        public String getDateOfExpiry() { return dateOfExpiry; }
        public void setDateOfExpiry(String dateOfExpiry) { this.dateOfExpiry = dateOfExpiry; }

        public String getIssuingState() { return issuingState; }
        public void setIssuingState(String issuingState) { this.issuingState = issuingState; }

        public Integer getBackendHttpCode() { return backendHttpCode; }
        public void setBackendHttpCode(Integer backendHttpCode) { this.backendHttpCode = backendHttpCode; }

        public String getBackendRawResponse() { return backendRawResponse; }
        public void setBackendRawResponse(String backendRawResponse) { this.backendRawResponse = backendRawResponse; }

        public Boolean getPassiveAuthenticationPassed() { return passiveAuthenticationPassed; }
        public void setPassiveAuthenticationPassed(Boolean passiveAuthenticationPassed) { this.passiveAuthenticationPassed = passiveAuthenticationPassed; }

        public String getTrustChainStatus() { return trustChainStatus; }
        public void setTrustChainStatus(String trustChainStatus) { this.trustChainStatus = trustChainStatus; }

        public String getTrustChainFailureReason() { return trustChainFailureReason; }
        public void setTrustChainFailureReason(String trustChainFailureReason) { this.trustChainFailureReason = trustChainFailureReason; }

        public String getSodSignatureStatus() { return sodSignatureStatus; }
        public void setSodSignatureStatus(String sodSignatureStatus) { this.sodSignatureStatus = sodSignatureStatus; }

        public String getDg1IntegrityStatus() { return dg1IntegrityStatus; }
        public void setDg1IntegrityStatus(String dg1IntegrityStatus) { this.dg1IntegrityStatus = dg1IntegrityStatus; }
    }
}