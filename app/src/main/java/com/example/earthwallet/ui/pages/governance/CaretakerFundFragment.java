package com.example.earthwallet.ui.pages.governance;

import android.content.Context;
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

import com.google.android.material.tabs.TabLayout;

import com.example.earthwallet.Constants;
import com.example.earthwallet.R;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment for managing Caretaker Fund allocation voting
 * Voting is 1 person 1 vote (requires passport registration)
 */
public class CaretakerFundFragment extends Fragment {
    
    private static final String TAG = "CaretakerFundFragment";
    
    // UI Components  
    private View rootView;
    private LinearLayout actualAllocationSection;
    private LinearLayout preferredAllocationSection;
    private TabLayout tabLayout;
    private TextView titleTextView;
    
    // Services
    private SecretQueryService queryService;
    private ExecutorService executorService;
    
    // Data
    private JSONArray currentAllocations;
    private JSONArray userAllocations;
    
    // Allocation options for Caretaker Fund  
    private static final String[] ALLOCATION_NAMES = {
        "Registration Rewards"  // allocation_id: 1
    };
    
    public static CaretakerFundFragment newInstance() {
        return new CaretakerFundFragment();
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_caretaker_fund, container, false);
        return rootView;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize services
        queryService = new SecretQueryService(getContext());
        executorService = Executors.newCachedThreadPool();
        
        initializeViews(view);
        
        // Load initial data
        loadActualAllocations();
    }
    
    private void initializeViews(View view) {
        actualAllocationSection = view.findViewById(R.id.actual_allocation_section);
        preferredAllocationSection = view.findViewById(R.id.preferred_allocation_section);
        tabLayout = view.findViewById(R.id.tab_layout);
        titleTextView = view.findViewById(R.id.title_text);
        
        // Set title
        titleTextView.setText("Caretaker Fund");
        
        // Setup tabs
        setupTabs();
        
        // Initially show actual allocations
        showActualAllocations();
    }
    
    private void setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Actual Allocation"));
        tabLayout.addTab(tabLayout.newTab().setText("Preferred Allocation"));
        
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0:
                        showActualAllocations();
                        break;
                    case 1:
                        showPreferredAllocations();
                        break;
                }
            }
            
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }
    
    private void showActualAllocations() {
        // Show/hide sections
        actualAllocationSection.setVisibility(View.VISIBLE);
        preferredAllocationSection.setVisibility(View.GONE);
        
        // Load data if needed
        if (currentAllocations == null) {
            loadActualAllocations();
        }
    }
    
    private void showPreferredAllocations() {
        // Show/hide sections
        actualAllocationSection.setVisibility(View.GONE);
        preferredAllocationSection.setVisibility(View.VISIBLE);
        
        // Load user allocations
        loadUserAllocations();
    }
    
    private void loadActualAllocations() {
        executorService.execute(() -> {
            try {
                // Query current allocations from registration contract
                JSONObject queryMsg = new JSONObject();
                queryMsg.put("query_allocation_options", new JSONObject());
                
                Log.d(TAG, "Querying caretaker fund allocations from: " + Constants.REGISTRATION_CONTRACT);
                
                JSONObject result = queryService.queryContract(
                    Constants.REGISTRATION_CONTRACT,
                    Constants.REGISTRATION_HASH, 
                    queryMsg
                );
                
                Log.d(TAG, "Allocation query result: " + result.toString());
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            // Handle different response formats
                            if (result.toString().startsWith("[")) {
                                // Direct array response
                                currentAllocations = new JSONArray(result.toString());
                            } else if (result.has("allocations")) {
                                currentAllocations = result.getJSONArray("allocations");
                            } else {
                                // Handle PublicBenefitFund style response with state wrapper
                                currentAllocations = new JSONArray();
                                if (result.length() > 0) {
                                    currentAllocations.put(result);
                                }
                            }
                            updateActualAllocationsUI();
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing allocation data", e);
                            Toast.makeText(getContext(), "Error loading allocation data", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading actual allocations", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> 
                        Toast.makeText(getContext(), "Error loading allocations: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
    }
    
    private void loadUserAllocations() {
        executorService.execute(() -> {
            try {
                String userAddress = SecureWalletManager.getWalletAddress(getContext());
                if (userAddress == null || userAddress.isEmpty()) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> 
                            Toast.makeText(getContext(), "Wallet address not available", Toast.LENGTH_SHORT).show()
                        );
                    }
                    return;
                }
                
                // Query user's preferred allocations
                JSONObject queryMsg = new JSONObject();
                JSONObject userQuery = new JSONObject();
                userQuery.put("address", userAddress);
                queryMsg.put("query_user_allocations", userQuery);
                
                Log.d(TAG, "Querying user allocations for: " + userAddress);
                
                JSONObject result = queryService.queryContract(
                    Constants.REGISTRATION_CONTRACT,
                    Constants.REGISTRATION_HASH,
                    queryMsg
                );
                
                Log.d(TAG, "User allocation query result: " + result.toString());
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            if (result.toString().startsWith("[")) {
                                userAllocations = new JSONArray(result.toString());
                            } else {
                                userAllocations = result.optJSONArray("percentages");
                            }
                            updatePreferredAllocationsUI();
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing user allocation data", e);
                            Toast.makeText(getContext(), "Error loading user preferences", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading user allocations", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> 
                        Toast.makeText(getContext(), "Error loading user preferences: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
    }
    
    private void updateActualAllocationsUI() {
        if (actualAllocationSection == null) return;
        
        // Clear existing views
        actualAllocationSection.removeAllViews();
        
        if (currentAllocations == null || currentAllocations.length() == 0) {
            TextView noDataText = new TextView(getContext());
            noDataText.setText("No allocation data available");
            noDataText.setTextSize(16);
            noDataText.setPadding(20, 20, 20, 20);
            actualAllocationSection.addView(noDataText);
            return;
        }
        
        try {
            for (int i = 0; i < currentAllocations.length(); i++) {
                JSONObject allocation = currentAllocations.getJSONObject(i);
                
                View allocationView = createAllocationItemView(allocation, false);
                actualAllocationSection.addView(allocationView);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating actual allocations UI", e);
        }
    }
    
    private void updatePreferredAllocationsUI() {
        if (preferredAllocationSection == null) return;
        
        // Clear existing views
        preferredAllocationSection.removeAllViews();
        
        // Add note about 1 person 1 vote
        TextView noteText = new TextView(getContext());
        noteText.setText("Caretaker Fund uses 1 person 1 vote. Passport registration required.");
        noteText.setTextSize(14);
        noteText.setPadding(20, 20, 20, 10);
        preferredAllocationSection.addView(noteText);
        
        // Add UI for setting user preferences
        TextView instructionText = new TextView(getContext());
        instructionText.setText("Set your preferred allocation percentages (must total 100%)");
        instructionText.setTextSize(16);
        instructionText.setPadding(20, 10, 20, 20);
        preferredAllocationSection.addView(instructionText);
        
        // Add controls for each allocation option
        for (int i = 0; i < ALLOCATION_NAMES.length; i++) {
            View prefView = createPreferenceItemView(i + 1, ALLOCATION_NAMES[i]);
            preferredAllocationSection.addView(prefView);
        }
        
        // Add submit button
        Button submitButton = new Button(getContext());
        submitButton.setText("Set Allocation");
        submitButton.setOnClickListener(v -> submitUserAllocations());
        preferredAllocationSection.addView(submitButton);
    }
    
    private View createAllocationItemView(JSONObject allocation, boolean isEditable) {
        try {
            LinearLayout itemLayout = new LinearLayout(getContext());
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setPadding(20, 10, 20, 10);
            
            TextView nameText = new TextView(getContext());
            
            // Handle different allocation object structures
            int allocationId = 0;
            int value = 0;
            
            if (allocation.has("state")) {
                // PublicBenefitFund style with state wrapper
                JSONObject state = allocation.getJSONObject("state");
                allocationId = state.optInt("allocation_id", 0);
                value = state.optInt("amount_allocated", 0);
            } else {
                // Direct format
                allocationId = allocation.optInt("allocation_id", 0);
                value = allocation.optInt("amount_allocated", 0);
            }
            
            String name = getAllocationName(allocationId);
            nameText.setText(name);
            nameText.setTextSize(16);
            nameText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            
            TextView valueText = new TextView(getContext());
            valueText.setText(value + "%");
            valueText.setTextSize(16);
            
            itemLayout.addView(nameText);
            itemLayout.addView(valueText);
            
            return itemLayout;
        } catch (Exception e) {
            Log.e(TAG, "Error creating allocation item view", e);
            return new View(getContext());
        }
    }
    
    private View createPreferenceItemView(int allocationId, String name) {
        LinearLayout itemLayout = new LinearLayout(getContext());
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(20, 10, 20, 10);
        
        TextView nameText = new TextView(getContext());
        nameText.setText(name);
        nameText.setTextSize(16);
        nameText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        
        // Add EditText for percentage input (simplified for now)
        TextView percentText = new TextView(getContext());
        percentText.setText("100%"); // Default to 100% since there's only one option
        percentText.setTextSize(16);
        percentText.setTag(allocationId); // Store allocation ID for later use
        
        itemLayout.addView(nameText);
        itemLayout.addView(percentText);
        
        return itemLayout;
    }
    
    private String getAllocationName(int allocationId) {
        if (allocationId >= 1 && allocationId <= ALLOCATION_NAMES.length) {
            return ALLOCATION_NAMES[allocationId - 1];
        }
        return "Unknown (" + allocationId + ")";
    }
    
    private void submitUserAllocations() {
        // Simplified submit - in full implementation would collect percentages from UI
        Toast.makeText(getContext(), "Allocation submission not fully implemented", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}