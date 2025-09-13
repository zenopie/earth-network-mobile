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
import com.example.earthwallet.ui.components.PieChartView;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.ArrayList;

/**
 * Fragment for managing Deflation Fund allocation voting
 * Voting is weighted by staked ERTH tokens
 */
public class DeflationFundFragment extends Fragment {
    
    private static final String TAG = "DeflationFundFragment";
    
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
    
    // Allocation options for Deflation Fund
    private static final String[] ALLOCATION_NAMES = {
        "LP Rewards",      // allocation_id: 1
        "SCRT Labs",       // allocation_id: 2  
        "ERTH Labs"        // allocation_id: 3
    };
    
    public static DeflationFundFragment newInstance() {
        return new DeflationFundFragment();
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_deflation_fund, container, false);
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
        titleTextView.setText("Deflation Fund");
        
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
                // Query current allocations from staking contract
                JSONObject queryMsg = new JSONObject();
                queryMsg.put("query_allocation_options", new JSONObject());
                
                Log.d(TAG, "Querying deflation fund allocations from: " + Constants.STAKING_CONTRACT);
                Log.d(TAG, "Using hash: " + Constants.STAKING_HASH);
                Log.d(TAG, "Query message: " + queryMsg.toString());
                
                JSONObject result = queryService.queryContract(
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH, 
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
                                    String amountStr = item.optString("amount_allocated", "0");
                                    try {
                                        totalAmount += Long.parseLong(amountStr);
                                    } catch (NumberFormatException e) {
                                        Log.w(TAG, "Invalid amount format: " + amountStr);
                                    }
                                }
                                
                                Log.d(TAG, "Deflation fund total amount: " + totalAmount);
                                
                                // Second pass: calculate percentages with proper rounding to ensure 100% total
                                long[] rawAmounts = new long[dataArray.length()];
                                int[] allocationIds = new int[dataArray.length()];
                                
                                // Collect raw data first
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject item = dataArray.getJSONObject(i);
                                    allocationIds[i] = item.optInt("allocation_id", 0);
                                    String amountStr = item.optString("amount_allocated", "0");
                                    try {
                                        rawAmounts[i] = Long.parseLong(amountStr);
                                    } catch (NumberFormatException e) {
                                        Log.w(TAG, "Invalid amount format: " + amountStr);
                                        rawAmounts[i] = 0;
                                    }
                                }
                                
                                // Calculate percentages with proper distribution to ensure 100% total
                                int[] percentages = new int[dataArray.length()];
                                int totalCalculatedPercentage = 0;
                                
                                if (totalAmount > 0) {
                                    // First calculate raw percentages
                                    double[] exactPercentages = new double[dataArray.length()];
                                    for (int i = 0; i < dataArray.length(); i++) {
                                        exactPercentages[i] = (rawAmounts[i] * 100.0) / totalAmount;
                                        percentages[i] = (int) Math.floor(exactPercentages[i]); // Use floor to avoid over-allocation
                                        totalCalculatedPercentage += percentages[i];
                                    }
                                    
                                    // Distribute remaining percentage to items with highest fractional parts
                                    int remaining = 100 - totalCalculatedPercentage;
                                    if (remaining > 0) {
                                        // Create array of indices sorted by fractional part (descending)
                                        Integer[] indices = new Integer[dataArray.length()];
                                        for (int i = 0; i < dataArray.length(); i++) {
                                            indices[i] = i;
                                        }
                                        
                                        // Sort by fractional part descending
                                        java.util.Arrays.sort(indices, (a, b) -> {
                                            double fracA = exactPercentages[a] - Math.floor(exactPercentages[a]);
                                            double fracB = exactPercentages[b] - Math.floor(exactPercentages[b]);
                                            return Double.compare(fracB, fracA);
                                        });
                                        
                                        // Add 1% to the items with highest fractional parts
                                        for (int i = 0; i < remaining && i < indices.length; i++) {
                                            percentages[indices[i]]++;
                                        }
                                    }
                                }
                                
                                // Create transformed objects
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject transformed = new JSONObject();
                                    transformed.put("allocation_id", allocationIds[i]);
                                    transformed.put("amount_allocated", percentages[i]);
                                    
                                    Log.d(TAG, "Deflation fund item: allocation_id=" + allocationIds[i] + ", raw_amount=" + rawAmounts[i] + " -> " + percentages[i] + "%");
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
                            errorText.setText("Query Error: " + e.getMessage() + "\n\nContract: " + Constants.STAKING_CONTRACT + "\nHash: " + Constants.STAKING_HASH);
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
                    Constants.STAKING_CONTRACT,
                    Constants.STAKING_HASH,
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
        
        // Add pie chart if we have data
        if (currentAllocations != null && currentAllocations.length() > 0) {
            PieChartView pieChart = createPieChart(currentAllocations);
            if (pieChart != null) {
                LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    600 // Consistent height
                );
                chartParams.setMargins(40, 20, 40, 20);
                chartParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                pieChart.setLayoutParams(chartParams);
                actualAllocationSection.addView(pieChart);
            }
        }
        
        if (currentAllocations == null || currentAllocations.length() == 0) {
            TextView noDataText = new TextView(getContext());
            noDataText.setText("No allocation data available from staking contract.\n\nThis could mean:\n• The contract hasn't been configured yet\n• No allocations have been set\n• Contract query failed");
            noDataText.setTextSize(14);
            noDataText.setTextColor(0xFF666666);
            noDataText.setPadding(20, 20, 20, 20);
            actualAllocationSection.addView(noDataText);
            return;
        }
        
        // Add list view of allocations with better visual styling if we have data
        if (currentAllocations != null && currentAllocations.length() > 0) {
            try {
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
    }
    
    private void updatePreferredAllocationsUI() {
        if (preferredAllocationSection == null) return;
        
        // Clear existing views
        preferredAllocationSection.removeAllViews();
        
        // Add pie chart for user data
        if (userAllocations != null && userAllocations.length() > 0) {
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
        }
        
        // Show current user allocations if available
        if (userAllocations != null && userAllocations.length() > 0) {
            
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
            
            TextView comingSoonText = new TextView(getContext());
            comingSoonText.setText("Setting preferences coming soon!");
            comingSoonText.setTextSize(14);
            comingSoonText.setTextColor(0xFF1976D2);
            comingSoonText.setPadding(20, 10, 20, 20);
            preferredAllocationSection.addView(comingSoonText);
        }
        
        // Add Set Preferences button (styled like swap button)
        Button setPrefsButton = new Button(getContext());
        setPrefsButton.setText(userAllocations != null && userAllocations.length() > 0 
            ? "Update Preferences" : "Set Preferences");
        setPrefsButton.setTextColor(0xFFFFFFFF);
        setPrefsButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50)); // Green like swap button
        setPrefsButton.setTextSize(18); // Match swap button text size
        setPrefsButton.setTypeface(setPrefsButton.getTypeface(), android.graphics.Typeface.BOLD);
        
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            (int) (56 * getResources().getDisplayMetrics().density) // 56dp height like swap button
        );
        buttonParams.setMargins(
            (int) (20 * getResources().getDisplayMetrics().density), // 20dp margins
            (int) (20 * getResources().getDisplayMetrics().density), 
            (int) (20 * getResources().getDisplayMetrics().density), 
            (int) (10 * getResources().getDisplayMetrics().density)
        );
        setPrefsButton.setLayoutParams(buttonParams);
        setPrefsButton.setOnClickListener(v -> openSetAllocationActivity());
        preferredAllocationSection.addView(setPrefsButton);
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
            valueText.setTextColor(0xFF1976D2);
            valueText.setTypeface(valueText.getTypeface(), android.graphics.Typeface.BOLD);
            
            itemLayout.addView(colorIndicator);
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
        percentText.setText("0%");
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
    
    private PieChartView createLoadingPieChart() {
        try {
            PieChartView pieChart = new PieChartView(getContext());
            List<PieChartView.PieSlice> slices = new ArrayList<>();
            
            // Add 100% loading slice
            slices.add(new PieChartView.PieSlice("Loading...", 100, 0xFFB0B0B0));
            
            pieChart.setData(slices);
            return pieChart;
        } catch (Exception e) {
            Log.e(TAG, "Error creating loading pie chart", e);
            return null;
        }
    }
    
    private PieChartView createEmptyPreferencesPieChart() {
        try {
            PieChartView pieChart = new PieChartView(getContext());
            List<PieChartView.PieSlice> slices = new ArrayList<>();
            
            // Add 100% "No Preferences Set" slice
            slices.add(new PieChartView.PieSlice("No Preferences Set", 100, 0xFFE0E0E0));
            
            pieChart.setData(slices);
            return pieChart;
        } catch (Exception e) {
            Log.e(TAG, "Error creating empty preferences pie chart", e);
            return null;
        }
    }
    
    private PieChartView createPieChart(JSONArray allocations) {
        try {
            PieChartView pieChart = new PieChartView(getContext());
            List<PieChartView.PieSlice> slices = new ArrayList<>();
            
            for (int i = 0; i < allocations.length(); i++) {
                JSONObject allocation = allocations.getJSONObject(i);
                int allocationId = allocation.optInt("allocation_id", 0);
                int percentage = allocation.optInt("amount_allocated", 0);
                
                if (percentage > 0) {
                    String name = getAllocationName(allocationId);
                    int color = getPieChartColor(allocationId);
                    slices.add(new PieChartView.PieSlice(name, percentage, color));
                }
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
    
    
    private void openSetAllocationActivity() {
        SetAllocationFragment fragment = SetAllocationFragment.newInstance(
            SetAllocationFragment.FUND_TYPE_DEFLATION, 
            "Deflation Fund"
        );
        
        if (getActivity() != null && getActivity().getSupportFragmentManager() != null) {
            getActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.host_content, fragment)
                .addToBackStack(null)
                .commit();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}