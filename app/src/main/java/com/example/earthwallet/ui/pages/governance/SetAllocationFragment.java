package com.example.earthwallet.ui.pages.governance;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.example.earthwallet.Constants;
import com.example.earthwallet.R;
import com.example.earthwallet.ui.components.PieChartView;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.bridge.activities.SecretExecuteActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment for setting allocation preferences
 * Based on the web app AllocationFund.js pattern
 */
public class SetAllocationFragment extends Fragment {
    
    private static final String TAG = "SetAllocationFragment";
    
    public static final String ARG_FUND_TYPE = "fund_type";
    public static final String ARG_FUND_TITLE = "fund_title";
    public static final String FUND_TYPE_CARETAKER = "caretaker";
    public static final String FUND_TYPE_DEFLATION = "deflation";
    
    // UI Components
    private TextView titleText;
    private TextView totalPercentageText;
    private LinearLayout allocationInputsContainer;
    private LinearLayout availableAllocationsContainer;
    private Button setAllocationButton;
    
    // Data
    private List<AllocationInput> selectedAllocations;
    private List<AllocationOption> allocationOptions;
    private int totalPercentage = 0;
    private String fundType;
    private String fundTitle;
    
    // Services
    private SecretQueryService queryService;
    private ExecutorService executorService;
    
    // Data classes
    private static class AllocationInput {
        public int allocationId;
        public String name;
        public int percentage;
        
        public AllocationInput(int allocationId, String name, int percentage) {
            this.allocationId = allocationId;
            this.name = name;
            this.percentage = percentage;
        }
    }
    
    private static class AllocationOption {
        public int id;
        public String name;
        
        public AllocationOption(int id, String name) {
            this.id = id;
            this.name = name;
        }
        
        @Override
        public String toString() {
            return name;
        }
    }

    public static SetAllocationFragment newInstance(String fundType, String fundTitle) {
        SetAllocationFragment fragment = new SetAllocationFragment();
        Bundle args = new Bundle();
        args.putString(ARG_FUND_TYPE, fundType);
        args.putString(ARG_FUND_TITLE, fundTitle);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getArguments() != null) {
            fundType = getArguments().getString(ARG_FUND_TYPE);
            fundTitle = getArguments().getString(ARG_FUND_TITLE);
        }
        
        // Initialize services
        queryService = new SecretQueryService(getContext());
        executorService = Executors.newCachedThreadPool();
        
        // Initialize data
        selectedAllocations = new ArrayList<>();
        allocationOptions = new ArrayList<>();
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_set_allocation, container, false);
    }
    
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize views
        initializeViews(view);
        
        // Setup allocation options
        setupAllocationOptions();
        
        // Load existing user preferences
        loadUserPreferences();
    }
    
    private void initializeViews(View view) {
        titleText = view.findViewById(R.id.title_text);
        totalPercentageText = view.findViewById(R.id.total_percentage_text);
        allocationInputsContainer = view.findViewById(R.id.allocation_inputs_container);
        availableAllocationsContainer = view.findViewById(R.id.available_allocations_container);
        setAllocationButton = view.findViewById(R.id.set_allocation_button);
        
        // Set title
        titleText.setText("Set " + fundTitle + " Preferences");
        
        // Setup set allocation button
        setAllocationButton.setOnClickListener(v -> setAllocation());
        
        // Initial state
        updateUI();
        updateAllocationInputs();
        updateAvailableAllocations();
    }
    
    private void setupAllocationOptions() {
        if (FUND_TYPE_CARETAKER.equals(fundType)) {
            // Caretaker Fund has only one option
            allocationOptions.add(new AllocationOption(1, "Caretaker Fund"));
        } else if (FUND_TYPE_DEFLATION.equals(fundType)) {
            // Deflation Fund options
            allocationOptions.add(new AllocationOption(1, "LP Rewards"));
            allocationOptions.add(new AllocationOption(2, "SCRT Labs"));
            allocationOptions.add(new AllocationOption(3, "ERTH Labs"));
        }
        
        // Create allocation option cards
        updateAvailableAllocations();
    }
    
    private void addSelectedAllocation(AllocationOption option) {
        // Check if already added
        for (AllocationInput existing : selectedAllocations) {
            if (existing.allocationId == option.id) {
                Toast.makeText(getContext(), "Allocation already added", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        // Add new allocation with 0%
        selectedAllocations.add(new AllocationInput(option.id, option.name, 0));
        
        // Update UI components
        updateUI();
        updateAllocationInputs();
        updateAvailableAllocations();
    }
    
    private void updateAvailableAllocations() {
        availableAllocationsContainer.removeAllViews();
        
        // Create flowing layout for chips
        LinearLayout currentRow = null;
        int currentRowWidth = 0;
        int maxRowWidth = getResources().getDisplayMetrics().widthPixels - 64; // Account for margins
        
        for (AllocationOption option : allocationOptions) {
            // Check if this option is already selected
            boolean isAlreadySelected = false;
            for (AllocationInput existing : selectedAllocations) {
                if (existing.allocationId == option.id) {
                    isAlreadySelected = true;
                    break;
                }
            }
            
            if (!isAlreadySelected) {
                Button chip = createAllocationChip(option);
                
                // Measure chip width (approximate)
                int chipWidth = (int) (option.name.length() * 12 + 80); // Rough estimate
                
                // Start new row if needed
                if (currentRow == null || currentRowWidth + chipWidth > maxRowWidth) {
                    currentRow = new LinearLayout(getContext());
                    currentRow.setOrientation(LinearLayout.HORIZONTAL);
                    currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                    availableAllocationsContainer.addView(currentRow);
                    currentRowWidth = 0;
                }
                
                currentRow.addView(chip);
                currentRowWidth += chipWidth;
            }
        }
    }
    
    private Button createAllocationChip(AllocationOption option) {
        Button chip = new Button(getContext());
        chip.setText(option.name);
        chip.setTextSize(14);
        chip.setBackgroundColor(0xFFE0E0E0);
        chip.setTextColor(0xFF333333);
        chip.setPadding(16, 8, 16, 8);
        
        // Set margins
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        chipParams.setMargins(4, 4, 4, 4);
        chip.setLayoutParams(chipParams);
        
        // Add rounded corners and ripple effect
        chip.setStateListAnimator(null);
        chip.setElevation(2);
        
        chip.setOnClickListener(v -> {
            // Add visual feedback
            chip.setBackgroundColor(0xFF4CAF50);
            chip.setTextColor(0xFFFFFFFF);
            chip.postDelayed(() -> addSelectedAllocation(option), 150);
        });
        
        return chip;
    }
    
    private void removeAllocation(int allocationId) {
        selectedAllocations.removeIf(alloc -> alloc.allocationId == allocationId);
        
        // Update UI components
        updateUI();
        updateAllocationInputs();
        updateAvailableAllocations();
    }
    
    private void updateAllocationPercentage(int allocationId, int percentage) {
        for (AllocationInput allocation : selectedAllocations) {
            if (allocation.allocationId == allocationId) {
                allocation.percentage = percentage;
                break;
            }
        }
        
        // Only update the summary, don't rebuild the input views
        updateTotalAndButton();
    }
    
    private void updateTotalAndButton() {
        // Calculate total percentage
        totalPercentage = 0;
        for (AllocationInput allocation : selectedAllocations) {
            totalPercentage += allocation.percentage;
        }
        
        // Update total percentage display
        totalPercentageText.setText("Total: " + totalPercentage + "%");
        totalPercentageText.setTextColor(totalPercentage == 100 ? 0xFF4CAF50 : 0xFFFF0000);
        
        // Update set button
        setAllocationButton.setEnabled(totalPercentage == 100 && !selectedAllocations.isEmpty());
        setAllocationButton.setText(totalPercentage == 100 ? "Set Allocation" : 
            "Total must equal 100% (" + totalPercentage + "%)");
    }
    
    private void updateUI() {
        updateTotalAndButton();
    }
    
    
    private void updateAllocationInputs() {
        allocationInputsContainer.removeAllViews();
        
        for (AllocationInput allocation : selectedAllocations) {
            View inputView = createAllocationInputView(allocation);
            allocationInputsContainer.addView(inputView);
        }
    }
    
    private View createAllocationInputView(AllocationInput allocation) {
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(20, 15, 20, 15);
        container.setBackgroundColor(0xFFE3F2FD);
        
        // Set margins and rounded corners
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        containerParams.setMargins(16, 8, 16, 8);
        container.setLayoutParams(containerParams);
        
        // Allocation name
        TextView nameText = new TextView(getContext());
        nameText.setText(allocation.name);
        nameText.setTextSize(16);
        nameText.setLayoutParams(new LinearLayout.LayoutParams(0, 
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        
        // Percentage input
        EditText percentageInput = new EditText(getContext());
        percentageInput.setText(String.valueOf(allocation.percentage));
        percentageInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        percentageInput.setHint("%");
        percentageInput.setLayoutParams(new LinearLayout.LayoutParams(100, 
            ViewGroup.LayoutParams.WRAP_CONTENT));
        
        percentageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    int percentage = Integer.parseInt(s.toString());
                    updateAllocationPercentage(allocation.allocationId, Math.max(0, Math.min(100, percentage)));
                } catch (NumberFormatException e) {
                    updateAllocationPercentage(allocation.allocationId, 0);
                }
            }
        });
        
        // Remove button
        Button removeButton = new Button(getContext());
        removeButton.setText("-");
        removeButton.setLayoutParams(new LinearLayout.LayoutParams(80, 
            ViewGroup.LayoutParams.WRAP_CONTENT));
        removeButton.setOnClickListener(v -> removeAllocation(allocation.allocationId));
        
        container.addView(nameText);
        container.addView(percentageInput);
        container.addView(removeButton);
        
        return container;
    }
    
    
    private void loadUserPreferences() {
        executorService.execute(() -> {
            try {
                String contractAddress = FUND_TYPE_CARETAKER.equals(fundType) 
                    ? Constants.REGISTRATION_CONTRACT 
                    : Constants.STAKING_CONTRACT;
                String contractHash = FUND_TYPE_CARETAKER.equals(fundType)
                    ? Constants.REGISTRATION_HASH
                    : Constants.STAKING_HASH;
                
                JSONObject queryMsg = new JSONObject();
                JSONObject userQuery = new JSONObject();
                userQuery.put("address", "secret1wvha45m7qgr6lc96sqatdq87hu3t25l9fcfex9"); // TODO: Get actual wallet address
                queryMsg.put("query_user_allocations", userQuery);
                
                JSONObject result = queryService.queryContract(contractAddress, contractHash, queryMsg);
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            // Process user preferences (same logic as fragments)
                            if (result.has("data") && result.getJSONArray("data").length() > 0) {
                                JSONArray dataArray = result.getJSONArray("data");
                                
                                selectedAllocations.clear();
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject item = dataArray.getJSONObject(i);
                                    int allocationId = item.optInt("allocation_id", 0);
                                    int percentage = Integer.parseInt(item.optString("percentage", "0"));
                                    
                                    String name = getAllocationName(allocationId);
                                    selectedAllocations.add(new AllocationInput(allocationId, name, percentage));
                                }
                                
                                updateUI();
                                updateAllocationInputs();
                                updateAvailableAllocations();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing user preferences", e);
                        }
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading user preferences", e);
            }
        });
    }
    
    private String getAllocationName(int allocationId) {
        for (AllocationOption option : allocationOptions) {
            if (option.id == allocationId) {
                return option.name;
            }
        }
        return "Unknown (" + allocationId + ")";
    }
    
    private void setAllocation() {
        if (totalPercentage != 100 || selectedAllocations.isEmpty()) {
            Toast.makeText(getContext(), "Total must equal 100%", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // Build the contract execution message matching web app format
            JSONObject executeMsg = new JSONObject();
            JSONObject setAllocation = new JSONObject();
            JSONArray percentages = new JSONArray();
            
            // Format allocations as expected by the contracts (matching web app)
            for (AllocationInput allocation : selectedAllocations) {
                JSONObject allocItem = new JSONObject();
                allocItem.put("allocation_id", allocation.allocationId);
                allocItem.put("percentage", String.valueOf(allocation.percentage));
                percentages.put(allocItem);
            }
            
            setAllocation.put("percentages", percentages);
            executeMsg.put("set_allocation", setAllocation);
            
            Log.d(TAG, "Setting " + fundTitle + " allocations: " + executeMsg.toString());
            
            if (FUND_TYPE_CARETAKER.equals(fundType)) {
                // Execute on registration contract
                Intent intent = new Intent(getActivity(), SecretExecuteActivity.class);
                intent.putExtra(SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, Constants.REGISTRATION_CONTRACT);
                intent.putExtra(SecretExecuteActivity.EXTRA_CODE_HASH, Constants.REGISTRATION_HASH);
                intent.putExtra(SecretExecuteActivity.EXTRA_EXECUTE_JSON, executeMsg.toString());
                
                startActivityForResult(intent, 1001); // Request code for caretaker fund
                
            } else if (FUND_TYPE_DEFLATION.equals(fundType)) {
                // Execute on staking contract
                Intent intent = new Intent(getActivity(), SecretExecuteActivity.class);
                intent.putExtra(SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, Constants.STAKING_CONTRACT);
                intent.putExtra(SecretExecuteActivity.EXTRA_CODE_HASH, Constants.STAKING_HASH);
                intent.putExtra(SecretExecuteActivity.EXTRA_EXECUTE_JSON, executeMsg.toString());
                
                startActivityForResult(intent, 1002); // Request code for deflation fund
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error building allocation message", e);
            Toast.makeText(getContext(), "Error setting allocation: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 1001 || requestCode == 1002) { // Caretaker or Deflation fund allocation
            if (resultCode == getActivity().RESULT_OK) {
                // Success - allocation was set
                Toast.makeText(getContext(), "Allocation preferences set successfully!", Toast.LENGTH_SHORT).show();
                
                // Go back to previous fragment
                if (getActivity() != null && getActivity().getSupportFragmentManager() != null) {
                    getActivity().getSupportFragmentManager().popBackStack();
                }
            } else {
                // Handle errors
                String error = "Unknown error";
                if (data != null) {
                    error = data.getStringExtra(SecretExecuteActivity.EXTRA_ERROR);
                    if (error == null || error.isEmpty()) {
                        error = "Transaction cancelled or failed";
                    }
                }
                
                Log.e(TAG, "Error setting allocation: " + error);
                Toast.makeText(getContext(), "Error setting allocation: " + error, Toast.LENGTH_LONG).show();
            }
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