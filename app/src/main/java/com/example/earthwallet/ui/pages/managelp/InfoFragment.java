package com.example.earthwallet.ui.pages.managelp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;

public class InfoFragment extends Fragment {

    private static final String TAG = "InfoFragment";

    private String tokenKey;

    private TextView totalSharesText;
    private TextView userSharesText;
    private TextView poolOwnershipText;
    private TextView unbondingPercentText;
    private TextView erthValueText;
    private TextView tokenValueText;
    private TextView tokenValueLabel;

    private LiquidityManagementComponent parentComponent;

    public static InfoFragment newInstance(String tokenKey) {
        InfoFragment fragment = new InfoFragment();
        Bundle args = new Bundle();
        args.putString("token_key", tokenKey);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            tokenKey = getArguments().getString("token_key");
        }
        Log.d(TAG, "InfoFragment created with tokenKey: " + tokenKey);

        // Get reference to parent component
        if (getParentFragment() instanceof LiquidityManagementComponent) {
            parentComponent = (LiquidityManagementComponent) getParentFragment();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tab_liquidity_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initializeViews(view);
        updateUI();
    }

    private void initializeViews(View view) {
        totalSharesText = view.findViewById(R.id.total_shares_text);
        userSharesText = view.findViewById(R.id.user_shares_text);
        poolOwnershipText = view.findViewById(R.id.pool_ownership_text);
        unbondingPercentText = view.findViewById(R.id.unbonding_percent_text);
        erthValueText = view.findViewById(R.id.erth_value_text);
        tokenValueText = view.findViewById(R.id.token_value_text);
        tokenValueLabel = view.findViewById(R.id.token_value_label);

        // Update token label
        if (tokenKey != null) {
            tokenValueLabel.setText(tokenKey + ":");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Update UI with latest data from parent
        Log.d(TAG, "InfoFragment resumed - updating UI from parent data");
        updateUI();
    }

    // Method called by parent when data changes
    public void refreshData() {
        updateUI();
    }

    private void updateUI() {
        if (parentComponent == null) {
            Log.d(TAG, "updateUI: parentComponent is null");
            return;
        }

        LiquidityManagementComponent.LiquidityData data = parentComponent.getLiquidityData();
        if (data == null) {
            Log.d(TAG, "updateUI: data is null");
            return;
        }

        Log.d(TAG, "updateUI: data available - userStakedShares: " + data.userStakedShares +
              ", totalShares: " + data.totalShares);

        try {
            // Update UI with data from parent
            if (totalSharesText != null) {
                totalSharesText.setText(String.format("Total Pool Shares: %,.0f", data.totalShares));
            }

            if (userSharesText != null) {
                userSharesText.setText(String.format("Your Shares: %,.0f", data.userStakedShares));
            }

            if (poolOwnershipText != null) {
                poolOwnershipText.setText(String.format("Pool Ownership: %.4f%%", data.poolOwnershipPercent));
            }

            if (unbondingPercentText != null) {
                unbondingPercentText.setText(String.format("%.4f%%", data.unbondingPercent));
            }

            if (erthValueText != null) {
                erthValueText.setText(String.format("%.6f", data.userErthValue));
            }

            if (tokenValueText != null) {
                tokenValueText.setText(String.format("%.6f", data.userTokenValue));
            }

            Log.d(TAG, "Updated InfoFragment UI with parent data");

        } catch (Exception e) {
            Log.e(TAG, "Error updating InfoFragment UI", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // No cleanup needed - parent manages all data
    }
}