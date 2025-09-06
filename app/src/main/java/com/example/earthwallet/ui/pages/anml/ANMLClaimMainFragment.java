package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.content.Intent;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
 
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AlertDialog;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.Constants;
import com.example.earthwallet.bridge.services.SecretQueryService;
import com.example.earthwallet.ui.pages.anml.ANMLRegisterFragment;
import com.example.earthwallet.ui.pages.anml.ANMLClaimFragment;
import com.example.earthwallet.ui.pages.anml.ANMLCompleteFragment;

import org.json.JSONObject;

public class ANMLClaimMainFragment extends Fragment implements ANMLRegisterFragment.ANMLRegisterListener, ANMLClaimFragment.ANMLClaimListener {
    private static final String TAG = "ANMLClaimFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";

    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private ImageView loadingGif;
    private TextView errorText;
    private View loadingOverlay;
    private View fragmentContainer;
    
    private SharedPreferences securePrefs;
    private boolean suppressNextQueryDialog = false;

    // Request codes for launching bridge Activities
    private static final int REQ_QUERY = 1001;
    private static final int REQ_EXECUTE = 1002;

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

        loadingGif = view.findViewById(R.id.anml_loading_gif);
        errorText = view.findViewById(R.id.anml_error_text);
        loadingOverlay = view.findViewById(R.id.loading_overlay);
        fragmentContainer = view.findViewById(R.id.anml_root);

        if (loadingGif != null) {
            // Load GIF using Glide (from drawable resource)
            Glide.with(this)
                    .asGif()
                    .load(R.drawable.loading)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(loadingGif);
            loadingGif.setVisibility(View.GONE);
        }

        initSecurePrefs();
        
        // Start status check
        checkStatus();
    }

    private void initSecurePrefs() {
        // Use centralized secure preferences from HostActivity
        securePrefs = ((com.example.earthwallet.ui.host.HostActivity) getActivity()).getSecurePrefs();
    }

    private void showLoading(boolean loading) {
        // Toggle the overlay that contains the spinner so the bottom nav remains visible.
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        }

        if (loadingGif != null) {
            loadingGif.setVisibility(loading ? View.VISIBLE : View.GONE);
        }

        if (loading) {
            if (errorText != null) errorText.setVisibility(View.GONE);
            hideStatusFragments();
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
        ANMLRegisterFragment fragment = ANMLRegisterFragment.newInstance();
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

            Intent ei = new Intent(getContext(), com.example.earthwallet.bridge.activities.SecretExecuteActivity.class);
            ei.putExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, Constants.REGISTRATION_CONTRACT);
            ei.putExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_CODE_HASH, Constants.REGISTRATION_HASH);
            ei.putExtra(com.example.earthwallet.bridge.activities.SecretExecuteActivity.EXTRA_EXECUTE_JSON, exec.toString());
            // Funds/memo/lcd are optional; default LCD is used in the bridge
            showLoading(true);
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
}