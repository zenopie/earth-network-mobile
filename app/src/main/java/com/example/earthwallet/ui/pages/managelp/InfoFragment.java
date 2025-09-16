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

    // Services
    private com.example.earthwallet.bridge.services.SecretQueryService queryService;
    private java.util.concurrent.ExecutorService executorService;

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

        queryService = new com.example.earthwallet.bridge.services.SecretQueryService(getContext());
        executorService = java.util.concurrent.Executors.newCachedThreadPool();
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
        refreshData();
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
        Log.d(TAG, "InfoFragment resumed - refreshing data");
        refreshData();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isResumed()) {
            Log.d(TAG, "InfoFragment became visible - refreshing data");
            refreshData();
        }
    }

    public void refreshData() {
        Log.d(TAG, "Refreshing pool info data for: " + tokenKey);
        loadPoolData();
    }

    private void loadPoolData() {
        if (tokenKey == null) return;

        executorService.execute(() -> {
            try {
                String userAddress = com.example.earthwallet.wallet.services.SecureWalletManager.getWalletAddress(getContext());
                if (userAddress == null) {
                    Log.w(TAG, "No user address available");
                    return;
                }

                String tokenContract = getTokenContract(tokenKey);
                if (tokenContract == null) {
                    Log.w(TAG, "No contract found for token: " + tokenKey);
                    return;
                }

                // Query pool data like other fragments do
                org.json.JSONObject queryMsg = new org.json.JSONObject();
                org.json.JSONObject queryUserInfo = new org.json.JSONObject();
                org.json.JSONArray poolsArray = new org.json.JSONArray();
                poolsArray.put(tokenContract);
                queryUserInfo.put("pools", poolsArray);
                queryUserInfo.put("user", userAddress);
                queryMsg.put("query_user_info", queryUserInfo);

                org.json.JSONObject result = queryService.queryContract(
                    com.example.earthwallet.Constants.EXCHANGE_CONTRACT,
                    com.example.earthwallet.Constants.EXCHANGE_HASH,
                    queryMsg
                );

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        processPoolData(result);
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error loading pool data", e);
            }
        });
    }

    private void processPoolData(org.json.JSONObject result) {
        try {
            if (result != null && result.has("data")) {
                org.json.JSONArray dataArray = result.getJSONArray("data");
                if (dataArray.length() > 0) {
                    org.json.JSONObject poolData = dataArray.getJSONObject(0);

                    org.json.JSONObject poolState = null;
                    org.json.JSONObject userInfo = null;

                    if (poolData.has("pool_info")) {
                        poolState = poolData.getJSONObject("pool_info");
                    }
                    if (poolData.has("user_info")) {
                        userInfo = poolData.getJSONObject("user_info");
                    }

                    updateUI(poolState, userInfo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing pool data", e);
        }
    }

    private void updateUI(org.json.JSONObject poolState, org.json.JSONObject userInfo) {
        if (poolState == null || userInfo == null) {
            Log.d(TAG, "Pool state or user info is null");
            return;
        }

        try {
            org.json.JSONObject state = poolState.optJSONObject("state");
            if (state != null) {
                // Calculate data like LiquidityManagementComponent does
                long totalSharesMicro = state.optLong("total_shares", 0);
                long userStakedMicro = userInfo.optLong("amount_staked", 0);
                long unbondingSharesMicro = state.optLong("unbonding_shares", 0);

                double totalShares = totalSharesMicro / 1000000.0;
                double userStaked = userStakedMicro / 1000000.0;
                double unbondingShares = unbondingSharesMicro / 1000000.0;

                double ownershipPercent = totalShares > 0 ? (userStaked / totalShares) * 100 : 0;
                double unbondingPercent = totalShares > 0 ? (unbondingShares / totalShares) * 100 : 0;

                // Update UI
                if (totalSharesText != null) {
                    totalSharesText.setText(String.format("Total Pool Shares: %,.0f", totalShares));
                }

                if (userSharesText != null) {
                    userSharesText.setText(String.format("Your Shares: %,.0f", userStaked));
                }

                if (poolOwnershipText != null) {
                    poolOwnershipText.setText(String.format("Pool Ownership: %.4f%%", ownershipPercent));
                }

                if (unbondingPercentText != null) {
                    unbondingPercentText.setText(String.format("%.4f%%", unbondingPercent));
                }

                // Calculate underlying values
                long erthReserveMicro = state.optLong("erth_reserve", 0);
                long tokenBReserveMicro = state.optLong("token_b_reserve", 0);

                double erthReserveMacro = erthReserveMicro / 1000000.0;
                double tokenBReserveMacro = tokenBReserveMicro / 1000000.0;

                double userErthValue = (erthReserveMacro * ownershipPercent) / 100.0;
                double userTokenBValue = (tokenBReserveMacro * ownershipPercent) / 100.0;

                if (erthValueText != null) {
                    erthValueText.setText(String.format("%.6f", userErthValue));
                }

                if (tokenValueText != null) {
                    tokenValueText.setText(String.format("%.6f", userTokenBValue));
                }

                Log.d(TAG, "Updated InfoFragment UI with fresh data");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating UI", e);
        }
    }

    private String getTokenContract(String tokenSymbol) {
        com.example.earthwallet.wallet.constants.Tokens.TokenInfo tokenInfo =
            com.example.earthwallet.wallet.constants.Tokens.getToken(tokenSymbol);
        return tokenInfo != null ? tokenInfo.contract : null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}