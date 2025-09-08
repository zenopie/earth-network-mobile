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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;

import com.example.earthwallet.Constants;
import com.example.earthwallet.ui.components.PieChartView;
import com.example.earthwallet.R;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.ArrayList;

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
                // First check if we have a wallet
                String walletAddress = SecureWalletManager.getWalletAddress(getContext());
                Log.d(TAG, "Loading allocations with wallet: " + (walletAddress != null ? walletAddress : "null"));
                
                if (walletAddress == null || walletAddress.isEmpty()) {
                    Log.w(TAG, "No wallet address available - query may fail");
                }
                // Query current allocations from registration contract
                JSONObject queryMsg = new JSONObject();
                queryMsg.put("query_allocation_options", new JSONObject());
                
                Log.d(TAG, "Querying caretaker fund allocations from: " + Constants.REGISTRATION_CONTRACT);
                Log.d(TAG, "Using hash: " + Constants.REGISTRATION_HASH);
                Log.d(TAG, "Query message: " + queryMsg.toString());
                
                JSONObject result = queryService.queryContract(
                    Constants.REGISTRATION_CONTRACT,
                    Constants.REGISTRATION_HASH, 
                    queryMsg
                );
                
                Log.d(TAG, "Allocation query result: " + result.toString());
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            // Handle different response formats matching what we see in logs
                            currentAllocations = new JSONArray();
                            
                            if (result.has("data") && result.getJSONArray("data").length() > 0) {
                                // SecretQueryService wrapped response
                                JSONArray dataArray = result.getJSONArray("data");
                                Log.d(TAG, "Processing SecretQueryService wrapped response with " + dataArray.length() + " items");
                                
                                // First pass: collect all raw amounts to calculate total
                                long totalAmount = 0;
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject item = dataArray.getJSONObject(i);
                                    if (item.has("state")) {
                                        JSONObject state = item.getJSONObject("state");
                                        totalAmount += state.optLong("amount_allocated", 0);
                                    } else {
                                        totalAmount += item.optLong("amount_allocated", 0);
                                    }
                                }
                                
                                Log.d(TAG, "Caretaker fund total amount: " + totalAmount);
                                
                                // Second pass: calculate percentages
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject item = dataArray.getJSONObject(i);
                                    JSONObject transformed = new JSONObject();
                                    
                                    int allocationId = 0;
                                    long rawAmount = 0;
                                    
                                    if (item.has("state")) {
                                        // PublicBenefitFund style with state wrapper (Caretaker Fund format)
                                        JSONObject state = item.getJSONObject("state");
                                        allocationId = state.optInt("allocation_id", 0);
                                        rawAmount = state.optLong("amount_allocated", 0);
                                    } else {
                                        // Direct format (shouldn't happen for caretaker fund but handle it)
                                        allocationId = item.optInt("allocation_id", 0);
                                        rawAmount = item.optLong("amount_allocated", 0);
                                    }
                                    
                                    // Calculate percentage
                                    int percentage = totalAmount > 0 ? (int) Math.round((rawAmount * 100.0) / totalAmount) : 100;
                                    
                                    transformed.put("allocation_id", allocationId);
                                    transformed.put("amount_allocated", percentage);
                                    
                                    Log.d(TAG, "Caretaker fund item: allocation_id=" + allocationId + ", raw_amount=" + rawAmount + " -> " + percentage + "%");
                                    currentAllocations.put(transformed);
                                }
                            } else if (result.toString().startsWith("[")) {
                                // Direct array response (fallback)
                                JSONArray directArray = new JSONArray(result.toString());
                                Log.d(TAG, "Processing direct array response with " + directArray.length() + " items");
                                currentAllocations = directArray;
                            } else if (result.has("allocations")) {
                                // DeflationFund style response (old format)
                                currentAllocations = result.getJSONArray("allocations");
                            }
                            
                            Log.d(TAG, "Processed allocations: " + currentAllocations.toString());
                            updateActualAllocationsUI();
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing allocation data", e);
                            Toast.makeText(getContext(), "Error loading allocation data", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading actual allocations", e);
                Log.e(TAG, "Exception type: " + e.getClass().getSimpleName());
                Log.e(TAG, "Exception message: " + e.getMessage());
                e.printStackTrace();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Error loading allocations: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        // Also update the UI to show the error
                        if (actualAllocationSection != null) {
                            actualAllocationSection.removeAllViews();
                            TextView errorText = new TextView(getContext());
                            errorText.setText("Query Error: " + e.getMessage() + "\n\nContract: " + Constants.REGISTRATION_CONTRACT + "\nHash: " + Constants.REGISTRATION_HASH);
                            errorText.setTextSize(14);
                            errorText.setTextColor(0xFFFF0000);
                            errorText.setPadding(20, 20, 20, 20);
                            actualAllocationSection.addView(errorText);
                        }
                    });
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
                            // Handle user allocation response - SecretQueryService wrapped with "percentage" fields
                            userAllocations = new JSONArray();
                            
                            if (result.has("data") && result.getJSONArray("data").length() > 0) {
                                // SecretQueryService wrapped response with percentage field
                                JSONArray dataArray = result.getJSONArray("data");
                                Log.d(TAG, "Processing user allocations with " + dataArray.length() + " items");
                                
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject item = dataArray.getJSONObject(i);
                                    JSONObject transformed = new JSONObject();
                                    
                                    // User allocation format has allocation_id and percentage fields
                                    int allocationId = item.optInt("allocation_id", 0);
                                    String percentageStr = item.optString("percentage", "0");
                                    
                                    int percentage = 0;
                                    try {
                                        percentage = Integer.parseInt(percentageStr);
                                    } catch (NumberFormatException e) {
                                        Log.w(TAG, "Invalid percentage format: " + percentageStr);
                                    }
                                    
                                    transformed.put("allocation_id", allocationId);
                                    transformed.put("amount_allocated", percentage);
                                    
                                    Log.d(TAG, "User allocation item: allocation_id=" + allocationId + ", percentage=" + percentage + "%");
                                    userAllocations.put(transformed);
                                }
                            } else if (result.toString().startsWith("[")) {
                                // Fallback: direct array response format
                                userAllocations = new JSONArray(result.toString());
                                Log.d(TAG, "Processing user allocations array with " + userAllocations.length() + " items");
                            } else if (result.has("percentages")) {
                                // Fallback for older format
                                userAllocations = result.getJSONArray("percentages");
                            }
                            
                            Log.d(TAG, "Processed user allocations: " + userAllocations.toString());
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
            noDataText.setText("No allocation data available from registration contract.\n\nThis could mean:\n• The contract hasn't been configured yet\n• No allocations have been set\n• Contract query failed");
            noDataText.setTextSize(14);
            noDataText.setTextColor(0xFF666666);
            noDataText.setPadding(20, 20, 20, 20);
            actualAllocationSection.addView(noDataText);
            return;
        }
        
        try {
            
            // Create and add actual pie chart
            PieChartView pieChart = createPieChart(currentAllocations);
            if (pieChart != null) {
                LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    600 // Bigger height
                );
                chartParams.setMargins(40, 20, 40, 20);
                chartParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                pieChart.setLayoutParams(chartParams);
                actualAllocationSection.addView(pieChart);
            }
            
            // Add list view of allocations with better visual styling
            for (int i = 0; i < currentAllocations.length(); i++) {
                JSONObject allocation = currentAllocations.getJSONObject(i);
                View allocationView = createAllocationItemView(allocation, false);
                actualAllocationSection.addView(allocationView);
            }
            
            // Add total percentage check
            int totalPercentage = 0;
            for (int i = 0; i < currentAllocations.length(); i++) {
                JSONObject allocation = currentAllocations.getJSONObject(i);
                totalPercentage += allocation.optInt("amount_allocated", 0);
            }
            
            TextView totalLabel = new TextView(getContext());
            totalLabel.setText("Total: " + totalPercentage + "%");
            totalLabel.setTextSize(14);
            totalLabel.setTextColor(totalPercentage == 100 ? 0xFF4CAF50 : 0xFFFF9800);
            totalLabel.setPadding(20, 10, 20, 10);
            totalLabel.setTypeface(totalLabel.getTypeface(), android.graphics.Typeface.BOLD);
            actualAllocationSection.addView(totalLabel);
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating actual allocations UI", e);
        }
    }
    
    private void updatePreferredAllocationsUI() {
        if (preferredAllocationSection == null) return;
        
        // Clear existing views
        preferredAllocationSection.removeAllViews();
        
        
        // Show current user allocations if available
        if (userAllocations != null && userAllocations.length() > 0) {
            // Create and add pie chart for user preferences
            PieChartView userPieChart = createPieChart(userAllocations);
            if (userPieChart != null) {
                LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    600 // Same size as actual allocation chart
                );
                chartParams.setMargins(40, 20, 40, 20);
                chartParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                userPieChart.setLayoutParams(chartParams);
                preferredAllocationSection.addView(userPieChart);
            }
            
            try {
                for (int i = 0; i < userAllocations.length(); i++) {
                    JSONObject allocation = userAllocations.getJSONObject(i);
                    View allocationView = createAllocationItemView(allocation, false);
                    preferredAllocationSection.addView(allocationView);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error displaying user allocations", e);
            }
        } else {
            TextView noPrefsText = new TextView(getContext());
            noPrefsText.setText("No preferences set yet.");
            noPrefsText.setTextSize(14);
            noPrefsText.setTextColor(0xFF666666);
            noPrefsText.setPadding(20, 10, 20, 10);
            preferredAllocationSection.addView(noPrefsText);
        }
        
        // Add note about 1 person 1 vote
        TextView noteText = new TextView(getContext());
        noteText.setText("Caretaker Fund uses 1 person 1 vote. Passport registration required.");
        noteText.setTextSize(14);
        noteText.setTypeface(noteText.getTypeface(), android.graphics.Typeface.ITALIC);
        noteText.setTextColor(0xFF666666);
        noteText.setPadding(20, 20, 20, 10);
        preferredAllocationSection.addView(noteText);
        
        // Add placeholder for future allocation setting UI
        TextView comingSoonText = new TextView(getContext());
        comingSoonText.setText("Allocation setting interface coming soon...");
        comingSoonText.setTextSize(16);
        comingSoonText.setTextColor(0xFF1976D2);
        comingSoonText.setPadding(20, 10, 20, 20);
        preferredAllocationSection.addView(comingSoonText);
    }
    
    private View createAllocationItemView(JSONObject allocation, boolean isEditable) {
        try {
            LinearLayout itemLayout = new LinearLayout(getContext());
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setPadding(20, 15, 20, 15);
            itemLayout.setBackgroundColor(0x10000000); // Light gray background
            
            // Get allocation data
            int allocationId = allocation.optInt("allocation_id", 0);
            int value = allocation.optInt("amount_allocated", 0);
            
            // Also check for percentage field for user allocations
            if (value == 0 && allocation.has("percentage")) {
                value = allocation.optInt("percentage", 0);
            }
            
            String name = getAllocationName(allocationId);
            
            // Add a color indicator for pie chart effect
            View colorIndicator = new View(getContext());
            int color = getPieChartColor(allocationId);
            colorIndicator.setBackgroundColor(color);
            LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(20, ViewGroup.LayoutParams.MATCH_PARENT);
            colorParams.setMargins(0, 0, 12, 0);
            colorIndicator.setLayoutParams(colorParams);
            
            TextView nameText = new TextView(getContext());
            nameText.setText(name);
            nameText.setTextSize(16);
            nameText.setTextColor(0xFF333333);
            nameText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            
            TextView valueText = new TextView(getContext());
            valueText.setText(value + "%");
            valueText.setTextSize(16);
            valueText.setTextColor(color); // Use the same color as indicator
            valueText.setPadding(10, 0, 0, 0);
            valueText.setTypeface(valueText.getTypeface(), android.graphics.Typeface.BOLD);
            
            itemLayout.addView(colorIndicator);
            itemLayout.addView(nameText);
            itemLayout.addView(valueText);
            
            // Add some margin between items
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layoutParams.setMargins(0, 5, 0, 5);
            itemLayout.setLayoutParams(layoutParams);
            
            return itemLayout;
        } catch (Exception e) {
            Log.e(TAG, "Error creating allocation item view", e);
            TextView errorText = new TextView(getContext());
            errorText.setText("Error displaying allocation");
            errorText.setTextColor(0xFFFF0000);
            return errorText;
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
    
    private int getPieChartColor(int allocationId) {
        // Pie chart colors similar to web app
        int[] colors = {
            0xFF4CAF50, // Green
            0xFF8BC34A, // Light Green  
            0xFFFF9800, // Orange
            0xFFCDDC39, // Lime
            0xFF009688, // Teal
            0xFF795548  // Brown
        };
        return colors[(allocationId - 1) % colors.length];
    }
    
    private PieChartView createPieChart(JSONArray allocations) {
        try {
            PieChartView pieChart = new PieChartView(getContext());
            List<PieChartView.PieSlice> slices = new ArrayList<>();
            
            for (int i = 0; i < allocations.length(); i++) {
                JSONObject allocation = allocations.getJSONObject(i);
                int allocationId = allocation.optInt("allocation_id", 0);
                int percentage = allocation.optInt("amount_allocated", 0);
                String name = getAllocationName(allocationId);
                int color = getPieChartColor(allocationId);
                
                slices.add(new PieChartView.PieSlice(name, percentage, color));
            }
            
            pieChart.setData(slices);
            return pieChart;
        } catch (Exception e) {
            Log.e(TAG, "Error creating pie chart", e);
            return null;
        }
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