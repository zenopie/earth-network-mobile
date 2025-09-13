package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;

import com.example.earthwallet.Constants;
import com.example.earthwallet.ui.host.HostActivity;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;

public class ANMLClaimFragment extends Fragment {
    
    private static final String TAG = "ANMLClaimFragment";
    
    // Interface for communication with parent activity
    public interface ANMLClaimListener {
        void onClaimRequested();
    }
    
    private ANMLClaimListener listener;
    private TextView anmlPriceText;
    private OkHttpClient httpClient;
    private SecretQueryService queryService;
    private boolean isHighStaker = false;
    private LinearLayout adFreeIndicatorContainer;
    private TextView adFreeStatusText;
    private double currentStakedAmount = 0.0;
    
    public ANMLClaimFragment() {}
    
    public static ANMLClaimFragment newInstance() {
        return new ANMLClaimFragment();
    }
    
    public void setANMLClaimListener(ANMLClaimListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anml_claim, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        anmlPriceText = view.findViewById(R.id.anml_price_amount);
        adFreeIndicatorContainer = view.findViewById(R.id.ad_free_indicator_container);
        adFreeStatusText = view.findViewById(R.id.ad_free_status_text);
        
        // Set up click listener for ad-free indicator
        if (adFreeIndicatorContainer != null) {
            adFreeIndicatorContainer.setOnClickListener(v -> showAdFreeExplanation());
        }
        
        // Initialize query service
        queryService = new SecretQueryService(getContext());
        
        // Check staking status and fetch ANML price when fragment is created
        checkStakingStatus();
        fetchAnmlPriceAndUpdateDisplay();
        
        Button btnClaim = view.findViewById(R.id.btn_claim);
        if (btnClaim != null) {
            // Ensure any theme tinting is cleared so the drawable renders as-designed
            try {
                btnClaim.setBackgroundTintList(null);
                btnClaim.setTextColor(getResources().getColor(R.color.anml_button_text));
            } catch (Exception ignored) {}
            
            btnClaim.setOnClickListener(v -> {
                if (isHighStaker) {
                    // High stakers (>=250K ERTH staked) skip the ad
                    Log.d(TAG, "User is high staker, skipping ad");
                    if (listener != null) {
                        listener.onClaimRequested();
                    }
                } else {
                    // Show interstitial ad and start transaction confirmation simultaneously
                    if (getActivity() instanceof HostActivity) {
                        HostActivity hostActivity = (HostActivity) getActivity();
                        
                        // Start the transaction confirmation immediately (while ad is showing)
                        if (listener != null) {
                            listener.onClaimRequested();
                        }
                        
                        // Show ad with a no-op callback since transaction is already started
                        hostActivity.showInterstitialAdThen(() -> {
                            // Transaction confirmation is already showing, nothing to do here
                        });
                    } else {
                        // Fallback if not in HostActivity
                        if (listener != null) {
                            listener.onClaimRequested();
                        }
                    }
                }
            });
        }
    }
    
    private void fetchAnmlPriceAndUpdateDisplay() {
        Log.d(TAG, "fetchAnmlPriceAndUpdateDisplay called");
        
        if (httpClient == null) {
            httpClient = new OkHttpClient();
        }
        
        String url = Constants.BACKEND_BASE_URL + "/anml-price";
        Log.d(TAG, "Fetching ANML price from: " + url);
        
        Request request = new Request.Builder()
                .url(url)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch ANML price from: " + url, e);
                // Update UI on main thread with error message
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (anmlPriceText != null) {
                            anmlPriceText.setText("Price unavailable");
                        }
                    });
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "ANML price response received with code: " + response.code());
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseBody = response.body().string();
                        Log.d(TAG, "ANML price response body: " + responseBody);
                        JSONObject json = new JSONObject(responseBody);
                        double price = json.getDouble("price");
                        Log.d(TAG, "Parsed ANML price: " + price);
                        
                        // Update UI on main thread
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                updateAnmlPriceDisplay(price);
                            });
                        } else {
                            Log.w(TAG, "Activity is null, cannot update UI");
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse ANML price response", e);
                        // Update UI on main thread with error message
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (anmlPriceText != null) {
                                    anmlPriceText.setText("Price unavailable");
                                }
                            });
                        }
                    }
                } else {
                    Log.w(TAG, "ANML price request failed with code: " + response.code());
                    // Update UI on main thread with error message
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (anmlPriceText != null) {
                                anmlPriceText.setText("Price unavailable");
                            }
                        });
                    }
                }
                response.close();
            }
        });
    }
    
    private void updateAnmlPriceDisplay(double price) {
        if (anmlPriceText == null) {
            return;
        }
        
        try {
            // Format ANML price with appropriate decimal places
            DecimalFormat priceFormat;
            if (price < 0.01) {
                // For very small values, show more decimal places
                priceFormat = new DecimalFormat("$#,##0.######");
            } else {
                priceFormat = new DecimalFormat("$#,##0.####");
            }
            
            String priceDisplay = priceFormat.format(price);
            Log.d(TAG, "Displaying ANML price: " + priceDisplay);
            anmlPriceText.setText(priceDisplay);
            
        } catch (Exception e) {
            Log.e(TAG, "Error formatting ANML price", e);
            anmlPriceText.setText("Price unavailable");
        }
    }
    
    /**
     * Check if user has >= 250K ERTH staked to determine if they should see ads
     */
    private void checkStakingStatus() {
        new Thread(() -> {
            try {
                String userAddress = SecureWalletManager.getWalletAddress(getContext());
                if (userAddress == null || userAddress.isEmpty()) {
                    Log.w(TAG, "No user address available for staking check");
                    return;
                }
                
                // Create query message: { get_user_info: { address: "secret1..." } }
                JSONObject queryMsg = new JSONObject();
                JSONObject getUserInfo = new JSONObject();
                getUserInfo.put("address", userAddress);
                queryMsg.put("get_user_info", getUserInfo);
                
                Log.d(TAG, "Checking staking status for address: " + userAddress);
                
                JSONObject result = queryService.queryContract(
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH,
                    queryMsg
                );
                
                Log.d(TAG, "Staking query result: " + result.toString());
                
                // Parse staked amount
                if (result.has("user_info") && !result.isNull("user_info")) {
                    JSONObject userInfo = result.getJSONObject("user_info");
                    if (userInfo.has("staked_amount")) {
                        long stakedAmountMicro = userInfo.getLong("staked_amount");
                        double stakedAmountMacro = stakedAmountMicro / 1_000_000.0; // Convert to macro units
                        
                        Log.d(TAG, "User has " + stakedAmountMacro + " ERTH staked");
                        
                        // Check if user has >= 250K ERTH staked
                        if (stakedAmountMacro >= 250_000.0) {
                            isHighStaker = true;
                            Log.d(TAG, "User is high staker (>=250K ERTH), will skip ads");
                        } else {
                            isHighStaker = false;
                            Log.d(TAG, "User is not high staker, will show ads");
                        }
                        
                        // Update UI on main thread
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> updateAdFreeIndicator());
                        }
                    } else {
                        Log.d(TAG, "No staked_amount found, user is not staking");
                        isHighStaker = false;
                    }
                } else {
                    Log.d(TAG, "No user_info found, user is not staking");
                    isHighStaker = false;
                    
                    // Update UI on main thread
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateAdFreeIndicator());
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error checking staking status", e);
                isHighStaker = false; // Default to showing ads on error
                
                // Update UI on main thread
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> updateAdFreeIndicator());
                }
            }
        }).start();
    }
    
    /**
     * Update the ad-free indicator text based on staking status
     */
    private void updateAdFreeIndicator() {
        if (adFreeStatusText != null) {
            if (isHighStaker) {
                adFreeStatusText.setText("Ad-Free Experience");
                adFreeStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                Log.d(TAG, "User is ad-free");
            } else {
                adFreeStatusText.setText("Ads Active");
                adFreeStatusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                Log.d(TAG, "User will see ads");
            }
        }
    }
    
    /**
     * Show explanation dialog for ad-free functionality
     */
    private void showAdFreeExplanation() {
        if (getContext() == null) return;
        
        if (isHighStaker) {
            // Dialog for users who already have ad-free
            new AlertDialog.Builder(getContext())
                .setTitle("✨ Ad-Free Experience")
                .setMessage("Congratulations! You have staked 250,000+ ERTH tokens and qualify for an ad-free experience.\n\n" +
                           "Benefits:\n" +
                           "• Skip all advertisements\n" +
                           "• Faster transaction flow\n" +
                           "• Premium user experience\n\n" +
                           "Thank you for being a valued staker! 🚀")
                .setPositiveButton("Got it!", null)
                .show();
        } else {
            // Dialog for users who don't have ad-free yet
            new AlertDialog.Builder(getContext())
                .setTitle("🚀 Unlock Ad-Free Experience")
                .setMessage("Want to skip ads and get a premium experience?\n\n" +
                           "Stake 250,000+ ERTH tokens to unlock:\n" +
                           "• Skip all advertisements\n" +
                           "• Faster transaction flow\n" +
                           "• Premium user experience\n\n" +
                           "Visit the Staking page to stake your ERTH tokens and join our premium users! ✨")
                .setPositiveButton("Got it!", null)
                .setNegativeButton("Go to Staking", (dialog, which) -> {
                    // Navigate to staking page
                    if (getActivity() instanceof HostActivity) {
                        ((HostActivity) getActivity()).showFragment("staking");
                    }
                })
                .show();
        }
    }
}