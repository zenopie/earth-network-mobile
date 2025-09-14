package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
 
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AlertDialog;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.bridge.activities.TransactionActivity;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.Constants;
import com.example.earthwallet.ui.components.LoadingOverlay;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.ui.pages.anml.ANMLRegisterFragment;
import com.example.earthwallet.ui.pages.anml.ANMLClaimFragment;
import com.example.earthwallet.ui.pages.anml.ANMLCompleteFragment;

import org.json.JSONObject;

public class ANMLClaimMainFragment extends Fragment implements ANMLRegisterFragment.ANMLRegisterListener, ANMLClaimFragment.ANMLClaimListener {
    private static final String TAG = "ANMLClaimFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";

    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private TextView errorText;
    private LoadingOverlay loadingOverlay;
    private View fragmentContainer;
    
    private SharedPreferences securePrefs;
    private boolean suppressNextQueryDialog = false;
    private String currentRegistrationReward;

    // Request codes for launching bridge Activities
    private static final int REQ_QUERY = 1001;
    private static final int REQ_EXECUTE = 1002;

    // Broadcast receiver for transaction success
    private BroadcastReceiver transactionSuccessReceiver;

    public ANMLClaimMainFragment() {}
    
    public static ANMLClaimMainFragment newInstance() {
        return new ANMLClaimMainFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anml_claim_main, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            SecretWallet.initialize(getContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SecretWallet", e);
        }

        errorText = view.findViewById(R.id.anml_error_text);
        loadingOverlay = view.findViewById(R.id.loading_overlay);
        fragmentContainer = view.findViewById(R.id.anml_root);

        // Initialize the loading overlay with this fragment for Glide
        if (loadingOverlay != null) {
            loadingOverlay.initializeWithFragment(this);
        }

        initSecurePrefs();
        setupBroadcastReceiver();
        registerBroadcastReceiver();

        // Start status check
        checkStatus();
    }

    private void initSecurePrefs() {
        // Use centralized secure preferences from HostActivity
        securePrefs = ((com.example.earthwallet.ui.host.HostActivity) getActivity()).getSecurePrefs();
    }

    private void setupBroadcastReceiver() {
        transactionSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Received TRANSACTION_SUCCESS broadcast - refreshing ANML status immediately");

                // Start multiple refresh attempts to ensure UI updates during animation
                checkStatus(); // First immediate refresh

                // Stagger additional refreshes to catch the UI during animation
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Secondary refresh during animation");
                    checkStatus();
                }, 100); // 100ms delay

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Third refresh during animation");
                    checkStatus();
                }, 500); // 500ms delay
            }
        };
    }

    private void registerBroadcastReceiver() {
        if (getActivity() != null && transactionSuccessReceiver != null) {
            IntentFilter filter = new IntentFilter("com.example.earthwallet.TRANSACTION_SUCCESS");
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().getApplicationContext().registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    requireActivity().getApplicationContext().registerReceiver(transactionSuccessReceiver, filter);
                }
                Log.d(TAG, "Registered transaction success receiver");
            } catch (Exception e) {
                Log.e(TAG, "Failed to register broadcast receiver", e);
            }
        }
    }

    private void showLoading(boolean loading) {
        // Use the reusable LoadingOverlay component
        if (loadingOverlay != null) {
            if (loading) {
                loadingOverlay.show();
                if (errorText != null) errorText.setVisibility(View.GONE);
                hideStatusFragments();
            } else {
                loadingOverlay.hide();
            }
        }
    }

    // Debug helpers to surface responses in-app
    private void showAlert(String title, String json) {
        try {
            showJsonDialog(title != null ? title : "Response", json);
        } catch (Exception ignored) {}
    }

    private void showJsonDialog(String title, String json) {
        try {
            new AlertDialog.Builder(getContext())
                    .setTitle(title != null ? title : "Response")
                    .setMessage(json != null ? json : "(empty)")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {}
    }

    // Show dialog and run a callback after it's dismissed (used to defer follow-up actions)
    private void showJsonDialogThen(String title, String json, Runnable then) {
        try {
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle(title != null ? title : "Response")
                    .setMessage(json != null ? json : "(empty)")
                    .setPositiveButton("OK", null)
                    .create();
            dialog.setOnDismissListener(d -> {
                try { if (then != null) then.run(); } catch (Exception ignored) {}
            });
            dialog.show();
        } catch (Exception ignored) {}
    }

    private void hideStatusFragments() {
        FragmentManager fm = getChildFragmentManager();
        Fragment current = fm.findFragmentById(R.id.anml_root);
        if (current != null) {
            fm.beginTransaction().remove(current).commit();
        }
    }

    private void showRegisterFragment() {
        hideStatusFragments();
        ANMLRegisterFragment fragment;
        if (currentRegistrationReward != null) {
            fragment = ANMLRegisterFragment.newInstance(currentRegistrationReward);
        } else {
            fragment = ANMLRegisterFragment.newInstance();
        }
        fragment.setANMLRegisterListener(this);
        getChildFragmentManager().beginTransaction()
                .replace(R.id.anml_root, fragment)
                .commit();
    }

    private void showClaimFragment() {
        hideStatusFragments();
        ANMLClaimFragment fragment = ANMLClaimFragment.newInstance();
        fragment.setANMLClaimListener(this);
        getChildFragmentManager().beginTransaction()
                .replace(R.id.anml_root, fragment)
                .commit();
    }

    private void showCompleteFragment() {
        hideStatusFragments();
        ANMLCompleteFragment fragment = ANMLCompleteFragment.newInstance();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.anml_root, fragment)
                .commit();
    }

    @Override
    public void onRegisterRequested() {
        Intent i = new Intent(getContext(), com.example.earthwallet.ui.host.HostActivity.class);
        i.putExtra("fragment_to_show", "camera_mrz_scanner");
        startActivity(i);
    }

    @Override
    public void onClaimRequested() {
        try {
            org.json.JSONObject exec = new org.json.JSONObject();
            exec.put("claim_anml", new org.json.JSONObject());

            Intent ei = new Intent(getContext(), TransactionActivity.class);
            ei.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SECRET_EXECUTE);
            ei.putExtra(TransactionActivity.EXTRA_CONTRACT_ADDRESS, Constants.REGISTRATION_CONTRACT);
            ei.putExtra(TransactionActivity.EXTRA_CODE_HASH, Constants.REGISTRATION_HASH);
            ei.putExtra(TransactionActivity.EXTRA_EXECUTE_JSON, exec.toString());
            // Funds/memo/lcd are optional; default LCD is used in the bridge
            startActivityForResult(ei, REQ_EXECUTE);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Failed to start claim: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // Launch the reusable query activity to check registration/claim status
    private void checkStatus() {
        try {
            showLoading(true);

            // Get wallet address using secure just-in-time mnemonic access
            String address;
            try {
                if (!com.example.earthwallet.wallet.services.SecureWalletManager.isWalletAvailable(getContext())) {
                    showLoading(false);
                    showRegisterFragment();
                    return;
                }
                
                address = com.example.earthwallet.wallet.services.SecureWalletManager.getWalletAddress(getContext());
                if (TextUtils.isEmpty(address)) {
                    showLoading(false);
                    showRegisterFragment();
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get wallet address: " + e.getMessage());
                showLoading(false);
                showRegisterFragment();
                return;
            }

            // Build query object for the bridge (no proxy)
            JSONObject q = new JSONObject();
            JSONObject inner = new JSONObject();
            inner.put("address", address);
            q.put("query_registration_status", inner);

            // Use SecretQueryService directly in background thread
            new Thread(() -> {
                try {
                    // Check wallet availability without retrieving mnemonic
                    if (!com.example.earthwallet.wallet.services.SecureWalletManager.isWalletAvailable(getContext())) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                showLoading(false);
                                if (errorText != null) {
                                    errorText.setText("No wallet found");
                                    errorText.setVisibility(View.VISIBLE);
                                }
                            });
                        }
                        return;
                    }
                    
                    SecretQueryService queryService = new SecretQueryService(getContext());
                    JSONObject result = queryService.queryContract(
                        Constants.REGISTRATION_CONTRACT,
                        Constants.REGISTRATION_HASH,
                        q
                    );
                    
                    // Format result to match expected format
                    JSONObject response = new JSONObject();
                    response.put("success", true);
                    response.put("result", result);
                    
                    // Handle result on UI thread
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            handleRegistrationQueryResult(response.toString());
                        });
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Registration status query failed", e);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            showLoading(false);
                            if (errorText != null) {
                                errorText.setText("Failed to check status: " + e.getMessage());
                                errorText.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "checkStatus failed", e);
            showLoading(false);
            if (errorText != null) {
                errorText.setText("Failed to check status: " + e.getMessage());
                errorText.setVisibility(View.VISIBLE);
            }
        }
    }

    private void handleRegistrationQueryResult(String json) {
        showLoading(false);
        boolean wasSuppressed = suppressNextQueryDialog;
        suppressNextQueryDialog = false;
        
        try {
            if (TextUtils.isEmpty(json)) {
                if (errorText != null) {
                    errorText.setText("No response from bridge.");
                    errorText.setVisibility(View.VISIBLE);
                }
                return;
            }
            
            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);
            if (!success) {
                if (errorText != null) {
                    errorText.setText("Bridge returned error.");
                    errorText.setVisibility(View.VISIBLE);
                }
                return;
            }
            JSONObject result = root.optJSONObject("result");
            if (result == null) {
                result = root;
            }

            // Extract registration reward if available
            currentRegistrationReward = result.optString("registration_reward", null);

            if ("no_wallet".equals(result.optString("status", ""))) {
                showRegisterFragment();
                return;
            }

            boolean registered = result.optBoolean("registration_status", false);
            if (!registered) {
                showRegisterFragment();
                return;
            }

            long lastClaim = result.optLong("last_claim", 0L);
            long nextClaimMillis = (lastClaim / 1000000L) + ONE_DAY_MILLIS;
            long now = System.currentTimeMillis();
            if (now > nextClaimMillis) {
                showClaimFragment();
            } else {
                showCompleteFragment();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse bridge result", e);
            if (errorText != null) {
                errorText.setText("Invalid result from bridge.");
                errorText.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_EXECUTE) {
            // Hide loading screen that might be showing
            showLoading(false);

            if (resultCode == getActivity().RESULT_OK) {
                Log.d(TAG, "ANML claim transaction succeeded");
                // Transaction successful - navigate optimistically to complete screen
                showCompleteFragment();
                // Also refresh status (broadcast receiver will handle additional refreshes)
                checkStatus();
            } else {
                String error = data != null ? data.getStringExtra(TransactionActivity.EXTRA_ERROR) : "Unknown error";
                Log.e(TAG, "ANML claim transaction failed: " + error);
                // Transaction failed or was cancelled - refresh to ensure UI is correct
                checkStatus();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Cleanup loading overlay
        if (loadingOverlay != null && getContext() != null) {
            loadingOverlay.cleanup(getContext());
        }

        // Unregister broadcast receiver
        if (transactionSuccessReceiver != null && getContext() != null) {
            try {
                requireActivity().getApplicationContext().unregisterReceiver(transactionSuccessReceiver);
                Log.d(TAG, "Unregistered transaction success receiver");
            } catch (IllegalArgumentException e) {
                // Receiver was not registered, ignore
                Log.d(TAG, "Receiver was not registered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }
    }
}