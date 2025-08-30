package com.example.earthwallet.ui.fragments;

import com.example.earthwallet.R;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
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
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

import net.sf.scuba.smartcards.CardService;

import com.example.earthwallet.wallet.services.SecretWallet;

public class PassportScannerFragment extends Fragment implements MRZInputFragment.MRZInputListener {
    private static final String TAG = "PassportScannerFragment";
    
    // Backend URL loaded at runtime from SharedPreferences or resources
    private String backendUrl;
    
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

    // Interface for NFC communication with parent activity
    public interface PassportScannerListener {
        void onNFCTagDetected(Tag tag);
        void requestNFCSetup();
    }
    
    private PassportScannerListener listener;
    
    public PassportScannerFragment() {}
    
    public static PassportScannerFragment newInstance() {
        return new PassportScannerFragment();
    }
    
    public void setPassportScannerListener(PassportScannerListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_main, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // First, try to retrieve MRZ data from SharedPreferences
        SharedPreferences prefs = requireActivity().getSharedPreferences("mrz_data", requireActivity().MODE_PRIVATE);
        String savedPassportNumber = prefs.getString("passportNumber", null);
        String savedDateOfBirth = prefs.getString("dateOfBirth", null);
        String savedDateOfExpiry = prefs.getString("dateOfExpiry", null);
        Log.d(TAG, "Retrieved MRZ data from SharedPreferences: passportNumber=" + savedPassportNumber +
            ", dateOfBirth=" + savedDateOfBirth + ", dateOfExpiry=" + savedDateOfExpiry);
        
        // Use saved data if available
        if (!isEmpty(savedPassportNumber) && !isEmpty(savedDateOfBirth) && !isEmpty(savedDateOfExpiry)) {
            passportNumber = savedPassportNumber;
            dateOfBirth = savedDateOfBirth;
            dateOfExpiry = savedDateOfExpiry;
            Log.d(TAG, "Using MRZ data from SharedPreferences");
        }
        
        // Check if from MRZ input activity intent
        if (getActivity() != null && getActivity().getIntent() != null) {
            Intent intent = getActivity().getIntent();
            if (intent.hasExtra("passportNumber") && intent.hasExtra("dateOfBirth") && intent.hasExtra("dateOfExpiry")) {
                String intentPassportNumber = intent.getStringExtra("passportNumber");
                String intentDateOfBirth = intent.getStringExtra("dateOfBirth");
                String intentDateOfExpiry = intent.getStringExtra("dateOfExpiry");
                Log.d(TAG, "Retrieved MRZ data from intent: passportNumber=" + intentPassportNumber +
                    ", dateOfBirth=" + intentDateOfBirth + ", dateOfExpiry=" + intentDateOfExpiry);
                
                if (!isEmpty(intentPassportNumber) && !isEmpty(intentDateOfBirth) && !isEmpty(intentDateOfExpiry)) {
                    passportNumber = intentPassportNumber;
                    dateOfBirth = intentDateOfBirth;
                    dateOfExpiry = intentDateOfExpiry;
                    Log.d(TAG, "Using MRZ data from intent");
                    
                    // Save to SharedPreferences for future use
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("passportNumber", passportNumber);
                    editor.putString("dateOfBirth", dateOfBirth);
                    editor.putString("dateOfExpiry", dateOfExpiry);
                    editor.apply();
                    Log.d(TAG, "Saved new MRZ data to SharedPreferences");
                }
            }
        }
        
        // Check if MRZ data is available
        Log.d(TAG, "Checking MRZ data: passportNumber=" + passportNumber + " (isEmpty=" + isEmpty(passportNumber) +
            "), dateOfBirth=" + dateOfBirth + " (isEmpty=" + isEmpty(dateOfBirth) +
            "), dateOfExpiry=" + dateOfExpiry + " (isEmpty=" + isEmpty(dateOfExpiry) + ")");
        if (!isMRZDataValid()) {
            Log.d(TAG, "MRZ data is incomplete, showing MRZ input fragment");
            // If MRZ data is not available, show MRZ input fragment
            showMRZInputFragment();
            return;
        }
        Log.d(TAG, "MRZ data is complete, proceeding with NFC setup");
        Log.d(TAG, "Current MRZ data: passportNumber=" + passportNumber +
            ", dateOfBirth=" + dateOfBirth + ", dateOfExpiry=" + dateOfExpiry);
        
        // Initialize UI elements
        progressBar = view.findViewById(R.id.progress_bar);
        resultContainer = view.findViewById(R.id.result_container);
        passportNumberText = view.findViewById(R.id.passport_number_text);
        nameText = view.findViewById(R.id.name_text);
        nationalityText = view.findViewById(R.id.nationality_text);
        dobText = view.findViewById(R.id.dob_text);
        genderText = view.findViewById(R.id.gender_text);
        expiryText = view.findViewById(R.id.expiry_text);
        countryText = view.findViewById(R.id.country_text);

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
        verifyStatusText = view.findViewById(R.id.verify_status_text);
        trustStatusText = view.findViewById(R.id.trust_status_text);
        trustReasonText = view.findViewById(R.id.trust_reason_text);
        sodStatusText = view.findViewById(R.id.sod_status_text);
        dg1StatusText = view.findViewById(R.id.dg1_status_text);
        rawResponseText = view.findViewById(R.id.raw_response_text);

        // Load backend URL preference or fallback to resource
        SharedPreferences appPrefs = requireActivity().getSharedPreferences("app_prefs", requireActivity().MODE_PRIVATE);
        String savedBackendUrl = appPrefs.getString("backend_url", null);
        backendUrl = savedBackendUrl != null ? savedBackendUrl : getString(R.string.backend_url);
        Log.d(TAG, "Backend URL = " + backendUrl);

        // Long-press title to configure backend URL
        TextView title = view.findViewById(R.id.title_text);
        if (title != null) {
            title.setOnLongClickListener(v -> {
                showBackendUrlDialog();
                return true;
            });
        }
        
        // Request NFC setup from parent activity
        if (listener != null) {
            listener.requestNFCSetup();
        }
        
        // Bottom navigation wiring
        View navWallet = view.findViewById(R.id.btn_nav_wallet);
        if (navWallet != null) {
            navWallet.setOnClickListener(v -> {
                Intent w = new Intent(requireActivity(), com.example.earthwallet.ui.activities.WalletActivity.class);
                startActivity(w);
            });
        }
        View navActions = view.findViewById(R.id.btn_nav_actions);
        if (navActions != null) {
            navActions.setOnClickListener(v -> {
                Intent a = new Intent(requireActivity(), com.example.earthwallet.ui.activities.ActionsActivity.class);
                startActivity(a);
            });
        }
    }
    
    // Called by parent activity when NFC tag is detected
    public void handleNfcTag(Tag tag) {
        if (tag != null) {
            Log.d(TAG, "NFC tag discovered, starting ReadPassportTask");
            new ReadPassportTask().execute(tag);
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
                // Then display verification and raw response
                displayVerification(passportData);
                Log.d(TAG, "Displayed verification and adjusted result rows");
            } else {
                Log.d(TAG, "Failed to read passport data");
                Toast.makeText(requireContext(), "Failed to read passport", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    // Fragment handling methods
    private void showMRZInputFragment() {
        MRZInputFragment fragment = MRZInputFragment.newInstance();
        fragment.setMRZInputListener(this);
        
        FragmentManager fm = getChildFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.passport_scanner_content, fragment, "mrz_input");
        ft.commit();
    }

    @Override
    public void onMRZDataEntered(String passportNumber, String dateOfBirth, String dateOfExpiry) {
        // Update MRZ data and remove fragment
        this.passportNumber = passportNumber;
        this.dateOfBirth = dateOfBirth;
        this.dateOfExpiry = dateOfExpiry;
        
        // Save to SharedPreferences
        SharedPreferences prefs = requireActivity().getSharedPreferences("mrz_data", requireActivity().MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("passportNumber", passportNumber);
        editor.putString("dateOfBirth", dateOfBirth);
        editor.putString("dateOfExpiry", dateOfExpiry);
        editor.apply();
        
        // Remove fragment and show main scanning UI
        FragmentManager fm = getChildFragmentManager();
        Fragment fragment = fm.findFragmentByTag("mrz_input");
        if (fragment != null) {
            FragmentTransaction ft = fm.beginTransaction();
            ft.remove(fragment);
            ft.commit();
        }
        
        // Request NFC setup from parent activity
        if (listener != null) {
            listener.requestNFCSetup();
        }
    }

    // All the passport reading logic remains the same
    private PassportData readPassport(Tag tag) {
        try {
            Log.d(TAG, "Starting passport reading process");
            IsoDep isoDep = IsoDep.get(tag);
            if (isoDep == null) {
                Log.d(TAG, "isoDep is null");
                return null;
            }
            Log.d(TAG, "Connecting to passport");
            isoDep.connect();
            isoDep.setTimeout(5000);

            CardService cardService = CardService.getInstance(isoDep);
            cardService.open();

            Log.d(TAG, "Creating passport service");
            PassportService passportService = new PassportService(cardService,
                    PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                    PassportService.DEFAULT_MAX_BLOCKSIZE,
                    false,
                    false);
            passportService.open();

            Log.d(TAG, "Selecting passport application");
            passportService.sendSelectApplet(false);

            Log.d(TAG, "Creating BAC key with documentNumber=" + passportNumber +
                    ", dateOfBirth=" + dateOfBirth + ", dateOfExpiry=" + dateOfExpiry);

            if (isEmpty(passportNumber) || isEmpty(dateOfBirth) || isEmpty(dateOfExpiry)) {
                Log.e(TAG, "MRZ data is incomplete, cannot create BAC key");
                return null;
            }

            String documentNumber = passportNumber;
            String birthDate = dateOfBirth;
            String expiryDate = dateOfExpiry;

            BACKeySpec bacKey = new BACKey(documentNumber, birthDate, expiryDate);

            Log.d(TAG, "Performing BAC");
            passportService.doBAC(bacKey);

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

            Log.d(TAG, "Reading SOD");
            InputStream sodIn = passportService.getInputStream(PassportService.EF_SOD);
            byte[] sodBytes = null;
            if (sodIn != null) {
                sodBytes = readAllBytes(sodIn);
            } else {
                Log.d(TAG, "sodIn is null");
            }

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
        android.widget.TableRow passportRow = getView().findViewById(R.id.passport_row);
        android.widget.TableRow nameRow = getView().findViewById(R.id.name_row);
        android.widget.TableRow nationalityRow = getView().findViewById(R.id.nationality_row);
        android.widget.TableRow dobRow = getView().findViewById(R.id.dob_row);
        android.widget.TableRow genderRow = getView().findViewById(R.id.gender_row);
        android.widget.TableRow expiryRow = getView().findViewById(R.id.expiry_row);
        android.widget.TableRow issuingCountryRow = getView().findViewById(R.id.issuing_country_row);

        // Display passport data with proper visibility management
        if (passportData.getDocumentNumber() != null && !passportData.getDocumentNumber().trim().isEmpty()) {
            if (passportNumberText != null) passportNumberText.setText(passportData.getDocumentNumber());
            if (passportRow != null) passportRow.setVisibility(View.VISIBLE);
        } else {
            if (passportRow != null) passportRow.setVisibility(View.GONE);
        }

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

        if (passportData.getNationality() != null && !passportData.getNationality().trim().isEmpty()) {
            if (nationalityText != null) nationalityText.setText(passportData.getNationality());
            if (nationalityRow != null) nationalityRow.setVisibility(View.VISIBLE);
        } else {
            if (nationalityRow != null) nationalityRow.setVisibility(View.GONE);
        }

        if (passportData.getDateOfBirth() != null && !passportData.getDateOfBirth().trim().isEmpty()) {
            if (dobText != null) dobText.setText(formatDate(passportData.getDateOfBirth()));
            if (dobRow != null) dobRow.setVisibility(View.VISIBLE);
        } else {
            if (dobRow != null) dobRow.setVisibility(View.GONE);
        }

        if (passportData.getGender() != null && !passportData.getGender().trim().isEmpty()) {
            if (genderText != null) genderText.setText(passportData.getGender());
            if (genderRow != null) genderRow.setVisibility(View.VISIBLE);
        } else {
            if (genderRow != null) genderRow.setVisibility(View.GONE);
        }

        if (passportData.getDateOfExpiry() != null && !passportData.getDateOfExpiry().trim().isEmpty()) {
            if (expiryText != null) expiryText.setText(formatDate(passportData.getDateOfExpiry()));
            if (expiryRow != null) expiryRow.setVisibility(View.VISIBLE);
        } else {
            if (expiryRow != null) expiryRow.setVisibility(View.GONE);
        }

        if (passportData.getIssuingState() != null && !passportData.getIssuingState().trim().isEmpty()) {
            if (countryText != null) countryText.setText(passportData.getIssuingState());
            if (issuingCountryRow != null) issuingCountryRow.setVisibility(View.VISIBLE);
        } else {
            if (issuingCountryRow != null) issuingCountryRow.setVisibility(View.GONE);
        }
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

        if (dg1StatusText != null) {
            dg1StatusText.setVisibility(View.GONE);
        }

        if (rawResponseText != null) {
            String raw = pd.getBackendRawResponse();
            if (raw != null && !raw.trim().isEmpty()) {
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
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://host/verify or http://<LAN>:<port>/verify");
        input.setText(backendUrl != null ? backendUrl : "");

        new AlertDialog.Builder(requireContext())
                .setTitle("Set Backend URL")
                .setMessage("This app will POST DG1, SOD Base64, and wallet address to this endpoint.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    backendUrl = input.getText() != null ? input.getText().toString().trim() : "";
                    SharedPreferences appPrefs = requireActivity().getSharedPreferences("app_prefs", requireActivity().MODE_PRIVATE);
                    appPrefs.edit().putString("backend_url", backendUrl).apply();
                    Toast.makeText(requireContext(), "Backend URL saved", Toast.LENGTH_SHORT).show();
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
    
    private String getCurrentWalletAddress() {
        Log.d(TAG, "WALLET: Getting current wallet address...");
        try {
            SecretWallet.initialize(requireContext());
            Log.d(TAG, "WALLET: SecretWallet initialized");
            
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    "secret_wallet_prefs",
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            Log.d(TAG, "WALLET: Encrypted preferences accessed");
            
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