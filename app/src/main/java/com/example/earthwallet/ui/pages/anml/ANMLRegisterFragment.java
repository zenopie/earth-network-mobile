package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.fragment.app.Fragment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.io.IOException;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.example.earthwallet.Constants;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;

public class ANMLRegisterFragment extends Fragment {
    
    private static final String TAG = "ANMLRegisterFragment";
    
    // Interface for communication with parent activity
    public interface ANMLRegisterListener {
        void onRegisterRequested();
    }
    
    private ANMLRegisterListener listener;
    private String registrationReward;
    private TextView rewardAmountText;
    private EditText affiliateAddressInput;
    private ActivityResultLauncher<ScanOptions> qrScannerLauncher;
    private OkHttpClient httpClient;
    private Double currentErthPrice;
    
    public ANMLRegisterFragment() {}
    
    public static ANMLRegisterFragment newInstance() {
        return new ANMLRegisterFragment();
    }
    
    public static ANMLRegisterFragment newInstance(String registrationReward) {
        ANMLRegisterFragment fragment = new ANMLRegisterFragment();
        Bundle args = new Bundle();
        args.putString("registration_reward", registrationReward);
        fragment.setArguments(args);
        return fragment;
    }
    
    public void setANMLRegisterListener(ANMLRegisterListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Initialize QR scanner launcher
        qrScannerLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) {
                handleQRScanResult(result.getContents());
            }
        });
        
        return inflater.inflate(R.layout.fragment_anml_register, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Get registration reward from arguments
        Bundle args = getArguments();
        if (args != null) {
            registrationReward = args.getString("registration_reward");
        }
        
        rewardAmountText = view.findViewById(R.id.registration_reward_amount);
        affiliateAddressInput = view.findViewById(R.id.affiliate_address_input);
        updateRewardDisplay();
        
        // Fetch ERTH price when fragment is created
        fetchErthPriceAndUpdateDisplay();
        
        ImageButton scanQrButton = view.findViewById(R.id.scan_affiliate_qr_button);
        if (scanQrButton != null) {
            scanQrButton.setOnClickListener(v -> launchQRScanner());
        }
        
        Button btnOpenWallet = view.findViewById(R.id.btn_open_wallet);
        if (btnOpenWallet != null) {
            // Ensure any theme tinting is cleared so the drawable renders as-designed
            try {
                btnOpenWallet.setBackgroundTintList(null);
                btnOpenWallet.setTextColor(getResources().getColor(R.color.anml_button_text));
            } catch (Exception ignored) {}
            
            btnOpenWallet.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRegisterRequested();
                }
            });
        }
    }
    
    private void updateRewardDisplay() {
        if (rewardAmountText == null) {
            return;
        }
        
        try {
            if (registrationReward == null || registrationReward.isEmpty()) {
                rewardAmountText.setText("Loading...");
                return;
            }
            
            // Convert from micro units (1,000,000 per token) to macro units
            BigDecimal microPoolAmount = new BigDecimal(registrationReward);
            BigDecimal macroPoolAmount = microPoolAmount.divide(new BigDecimal("1000000"), 8, RoundingMode.DOWN);
            
            // Calculate 1% of the registration reward pool (user's actual reward)
            BigDecimal actualReward = macroPoolAmount.multiply(new BigDecimal("0.01")).setScale(6, RoundingMode.HALF_UP);
            
            // Format for display - round to 2 decimal places
            DecimalFormat df = new DecimalFormat("#,##0.##");
            String rewardDisplay = df.format(actualReward) + " ERTH";
            
            // Add USD value if ERTH price is available
            Log.d(TAG, "Current ERTH price: " + currentErthPrice);
            if (currentErthPrice != null && currentErthPrice > 0) {
                BigDecimal usdValue = actualReward.multiply(new BigDecimal(currentErthPrice.toString()));
                DecimalFormat usdFormat = new DecimalFormat("$#,##0.##");
                String usdDisplay = usdFormat.format(usdValue);
                Log.d(TAG, "Calculated USD value: " + usdDisplay);
                rewardDisplay += " (" + usdDisplay + ")";
            } else {
                Log.d(TAG, "No ERTH price available, showing ERTH only");
            }
            
            rewardAmountText.setText(rewardDisplay);
            
        } catch (Exception e) {
            rewardAmountText.setText("Error calculating reward");
        }
    }
    
    public void setRegistrationReward(String registrationReward) {
        this.registrationReward = registrationReward;
        updateRewardDisplay();
    }
    
    private void fetchErthPriceAndUpdateDisplay() {
        Log.d(TAG, "fetchErthPriceAndUpdateDisplay called");
        
        if (httpClient == null) {
            httpClient = new OkHttpClient();
        }
        
        String url = Constants.BACKEND_BASE_URL + "/erth-price";
        Log.d(TAG, "Fetching ERTH price from: " + url);
        
        Request request = new Request.Builder()
                .url(url)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch ERTH price", e);
                // Don't show error to user, just continue without USD display
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseBody = response.body().string();
                        JSONObject json = new JSONObject(responseBody);
                        double price = json.getDouble("price");
                        Log.d(TAG, "Parsed ERTH price: " + price);
                        
                        // Update UI on main thread
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                currentErthPrice = price;
                                updateRewardDisplay();
                            });
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse ERTH price response", e);
                    }
                } else {
                    Log.w(TAG, "ERTH price request failed with code: " + response.code());
                }
                response.close();
            }
        });
    }
    
    private void launchQRScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan QR code to get affiliate address");
        options.setBeepEnabled(true);
        options.setBarcodeImageEnabled(true);
        options.setOrientationLocked(true);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setCameraId(0); // Use rear camera
        
        qrScannerLauncher.launch(options);
    }
    
    private void handleQRScanResult(String scannedContent) {
        String content = scannedContent.trim();
        
        // Validate that the scanned content is a valid Secret Network address
        if (content.startsWith("secret1") && content.length() >= 45) {
            affiliateAddressInput.setText(content);
            Toast.makeText(getContext(), "Affiliate address scanned successfully!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Invalid Secret Network address in QR code", Toast.LENGTH_LONG).show();
        }
    }
    
    public String getAffiliateAddress() {
        if (affiliateAddressInput != null) {
            String address = affiliateAddressInput.getText().toString().trim();
            return address.isEmpty() ? null : address;
        }
        return null;
    }
}