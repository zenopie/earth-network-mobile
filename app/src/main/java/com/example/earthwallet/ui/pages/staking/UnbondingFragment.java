package com.example.earthwallet.ui.pages.staking;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.activities.TransactionActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fragment for managing unbonding tokens
 * Corresponds to the "Unbonding" tab in the React component
 */
public class UnbondingFragment extends Fragment {
    
    private static final String TAG = "UnbondingFragment";
    private static final int REQ_CLAIM_UNBONDED = 4004;
    private static final int REQ_CANCEL_UNBOND = 4005;
    
    // UI Components
    private LinearLayout unbondingEntriesContainer;
    private LinearLayout noUnbondingText;
    
    // Data
    private List<UnbondingEntry> unbondingEntries = new ArrayList<>();
    
    public static UnbondingFragment newInstance() {
        return new UnbondingFragment();
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_unbonding, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        
        // Load initial data
        refreshData();
    }
    
    private void initializeViews(View view) {
        unbondingEntriesContainer = view.findViewById(R.id.unbonding_entries_container);
        noUnbondingText = view.findViewById(R.id.no_unbonding_text);
    }
    
    /**
     * Refresh unbonding data from parent
     */
    public void refreshData() {
        Log.d(TAG, "Refreshing unbonding data");
        
        // Find the StakeEarthFragment through fragment manager
        StakeEarthFragment stakeEarthFragment = findStakeEarthFragment();
        if (stakeEarthFragment != null) {
            stakeEarthFragment.queryUserStakingInfo(new StakeEarthFragment.UserStakingCallback() {
                @Override
                public void onStakingDataReceived(JSONObject data) {
                    try {
                        parseUnbondingEntries(data);
                        updateUI();
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing unbonding entries", e);
                    }
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Error querying unbonding data: " + error);
                }
            });
        } else {
            Log.e(TAG, "Could not find StakeEarthFragment");
        }
    }
    
    /**
     * Find the StakeEarthFragment in the fragment hierarchy
     */
    private StakeEarthFragment findStakeEarthFragment() {
        // For ViewPager2, we need to go up to find the actual parent
        Fragment fragment = this;
        while (fragment != null) {
            if (fragment instanceof StakeEarthFragment) {
                return (StakeEarthFragment) fragment;
            }
            fragment = fragment.getParentFragment();
        }
        
        // If we can't find it through parent hierarchy, try through the activity's fragment manager
        if (getActivity() != null && getActivity().getSupportFragmentManager() != null) {
            for (Fragment f : getActivity().getSupportFragmentManager().getFragments()) {
                if (f instanceof StakeEarthFragment) {
                    return (StakeEarthFragment) f;
                }
            }
        }
        
        return null;
    }
    
    private void parseUnbondingEntries(JSONObject data) {
        unbondingEntries.clear();
        
        try {
            // Handle potential decryption_error format like other fragments
            JSONObject dataObj = data;
            if (data.has("error") && data.has("decryption_error")) {
                String decryptionError = data.getString("decryption_error");
                Log.d(TAG, "Processing decryption_error for unbonding entries");
                
                // Extract JSON from error message if needed
                String jsonMarker = "base64=Value ";
                int jsonIndex = decryptionError.indexOf(jsonMarker);
                if (jsonIndex != -1) {
                    int startIndex = jsonIndex + jsonMarker.length();
                    int endIndex = decryptionError.indexOf(" of type", startIndex);
                    if (endIndex != -1) {
                        String jsonString = decryptionError.substring(startIndex, endIndex);
                        try {
                            dataObj = new JSONObject(jsonString);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing JSON from decryption_error", e);
                        }
                    }
                }
            } else if (data.has("data")) {
                dataObj = data.getJSONObject("data");
            }
            
            // Parse unbonding entries
            if (dataObj.has("unbonding_entries") && !dataObj.isNull("unbonding_entries")) {
                JSONArray entries = dataObj.getJSONArray("unbonding_entries");
                
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    
                    long amountMicro = entry.getLong("amount");
                    long unbondingTimeNanos = entry.getLong("unbonding_time");
                    
                    // Convert amount from micro to macro units
                    double amount = amountMicro / 1_000_000.0;
                    
                    // Convert time from nanoseconds to milliseconds
                    long unbondingTimeMillis = unbondingTimeNanos / 1_000_000;
                    
                    unbondingEntries.add(new UnbondingEntry(
                        amountMicro, // Keep original for contract calls
                        amount, 
                        unbondingTimeNanos, // Keep original for contract calls
                        unbondingTimeMillis
                    ));
                    
                    Log.d(TAG, "Unbonding entry: " + amount + " ERTH, available at " + new Date(unbondingTimeMillis));
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error parsing unbonding entries", e);
        }
    }
    
    private void updateUI() {
        if (getActivity() == null) return;
        
        getActivity().runOnUiThread(() -> {
            // Clear existing views
            unbondingEntriesContainer.removeAllViews();
            
            if (unbondingEntries.isEmpty()) {
                noUnbondingText.setVisibility(View.VISIBLE);
                unbondingEntriesContainer.setVisibility(View.GONE);
            } else {
                noUnbondingText.setVisibility(View.GONE);
                unbondingEntriesContainer.setVisibility(View.VISIBLE);
                
                // Add entry views
                for (UnbondingEntry entry : unbondingEntries) {
                    addUnbondingEntryView(entry);
                }
            }
        });
    }
    
    private void addUnbondingEntryView(UnbondingEntry entry) {
        LayoutInflater inflater = getLayoutInflater();
        View entryView = inflater.inflate(R.layout.item_unbonding_entry, unbondingEntriesContainer, false);
        
        // Set data
        TextView amountText = entryView.findViewById(R.id.unbonding_amount_text);
        TextView dateText = entryView.findViewById(R.id.unbonding_date_text);
        Button actionButton = entryView.findViewById(R.id.unbonding_action_button);
        
        amountText.setText(String.format(Locale.getDefault(), "%,.2f ERTH", entry.amount));
        
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
        String dateString = dateFormat.format(new Date(entry.unbondingTimeMillis));
        dateText.setText("Available: " + dateString);
        
        // Check if entry is matured
        boolean isMatured = System.currentTimeMillis() >= entry.unbondingTimeMillis;
        
        if (isMatured) {
            // Show claim button
            actionButton.setText("Claim");
            actionButton.setBackgroundResource(R.drawable.green_button_bg);
            actionButton.setOnClickListener(v -> handleClaimUnbonded());
        } else {
            // Show cancel button
            actionButton.setText("Cancel");
            actionButton.setBackgroundResource(R.drawable.address_box_bg);
            actionButton.setOnClickListener(v -> handleCancelUnbond(entry));
        }
        
        unbondingEntriesContainer.addView(entryView);
    }
    
    private void handleClaimUnbonded() {
        Log.d(TAG, "Claiming unbonded tokens");
        
        try {
            // Create claim unbonded message: { claim_unbonded: {} }
            JSONObject claimMsg = new JSONObject();
            claimMsg.put("claim_unbonded", new JSONObject());
            
            // Use SecretExecuteActivity for claiming unbonded tokens
            Intent intent = new Intent(getActivity(), TransactionActivity.class);
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.STAKING_CONTRACT);
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.STAKING_HASH);
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, claimMsg.toString());
            
            startActivityForResult(intent, REQ_CLAIM_UNBONDED);
            
        } catch (Exception e) {
            Log.e(TAG, "Error claiming unbonded tokens", e);
            Toast.makeText(getContext(), "Failed to claim unbonded tokens: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void handleCancelUnbond(UnbondingEntry entry) {
        Log.d(TAG, "Canceling unbonding for " + entry.amount + " ERTH");
        
        try {
            // Create cancel unbond message: { cancel_unbond: { amount: "123456", unbonding_time: 1234567890 } }
            JSONObject cancelMsg = new JSONObject();
            JSONObject cancelUnbond = new JSONObject();
            cancelUnbond.put("amount", String.valueOf(entry.amountMicro)); // Use original micro units
            cancelUnbond.put("unbonding_time", entry.unbondingTimeNanos); // Use original nanoseconds
            cancelMsg.put("cancel_unbond", cancelUnbond);
            
            Log.d(TAG, "Cancel unbond message: " + cancelMsg.toString());
            
            // Use SecretExecuteActivity for canceling unbond
            Intent intent = new Intent(getActivity(), TransactionActivity.class);
            intent.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.STAKING_CONTRACT);
            intent.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.STAKING_HASH);
            intent.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, cancelMsg.toString());
            
            startActivityForResult(intent, REQ_CANCEL_UNBOND);
            
        } catch (Exception e) {
            Log.e(TAG, "Error canceling unbond", e);
            Toast.makeText(getContext(), "Failed to cancel unbond: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_CLAIM_UNBONDED) {
            if (resultCode == getActivity().RESULT_OK) {
                Toast.makeText(getContext(), "Unbonded tokens claimed successfully!", Toast.LENGTH_SHORT).show();
                refreshData(); // Refresh to update unbonding list
            } else {
                String error = data != null ? data.getStringExtra("error") : "Unknown error";
                Toast.makeText(getContext(), "Failed to claim unbonded tokens: " + error, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_CANCEL_UNBOND) {
            if (resultCode == getActivity().RESULT_OK) {
                Toast.makeText(getContext(), "Unbonding canceled successfully!", Toast.LENGTH_SHORT).show();
                refreshData(); // Refresh to update unbonding list
            } else {
                String error = data != null ? data.getStringExtra("error") : "Unknown error";
                Toast.makeText(getContext(), "Failed to cancel unbonding: " + error, Toast.LENGTH_LONG).show();
            }
        }
    }
    
    /**
     * Data class for unbonding entries
     */
    private static class UnbondingEntry {
        final long amountMicro; // For contract calls
        final double amount; // For display
        final long unbondingTimeNanos; // For contract calls
        final long unbondingTimeMillis; // For display
        
        UnbondingEntry(long amountMicro, double amount, long unbondingTimeNanos, long unbondingTimeMillis) {
            this.amountMicro = amountMicro;
            this.amount = amount;
            this.unbondingTimeNanos = unbondingTimeNanos;
            this.unbondingTimeMillis = unbondingTimeMillis;
        }
    }
}