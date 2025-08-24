package com.example.passportscanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.wallet.SecretWallet;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ANMLClaimFragment extends Fragment {
    private static final String TAG = "ANMLClaimFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";

    private static final String REGISTRATION_CONTRACT = "secret12q72eas34u8fyg68k6wnerk2nd6l5gaqppld6p";
    private static final String REGISTRATION_HASH = "12fad89bbc7f4c9051b7b5fa1c7af1c17480dcdee4b962cf6cb6ff668da02667";
    private static final String PROXY_URL = "http://10.135.34.13:8000/api/contract_query";
    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private ProgressBar progress;
    private TextView statusText;
    private View registerBox;
    private View claimBox;
    private View completeBox;
    private TextView errorText;

    private Button btnOpenWallet;
    private Button btnClaim;

    private SharedPreferences securePrefs;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Use a fragment-specific layout (created below) that does not include the bottom navigation.
        return inflater.inflate(R.layout.fragment_anml_claim, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            SecretWallet.initialize(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SecretWallet", e);
        }

        progress = view.findViewById(R.id.anml_progress);
        statusText = view.findViewById(R.id.anml_status_text);
        registerBox = view.findViewById(R.id.register_box);
        claimBox = view.findViewById(R.id.claim_box);
        completeBox = view.findViewById(R.id.complete_box);
        errorText = view.findViewById(R.id.anml_error_text);

        btnOpenWallet = view.findViewById(R.id.btn_open_wallet);
        btnClaim = view.findViewById(R.id.btn_claim);

        // Ensure any theme tinting is cleared so the drawable renders as-designed
        try {
            if (btnOpenWallet != null) {
                btnOpenWallet.setBackgroundTintList(null);
                btnOpenWallet.setTextColor(getResources().getColor(R.color.anml_button_text));
            }
        } catch (Exception ignored) {}

        btnOpenWallet.setOnClickListener(v -> {
            // Launch existing passport scan flow
            Intent i = new Intent(requireActivity(), MRZInputActivity.class);
            startActivity(i);
        });

        btnClaim.setOnClickListener(v -> {
            Intent i = new Intent(requireActivity(), com.example.passportscanner.wallet.WalletActivity.class);
            startActivity(i);
            Toast.makeText(requireContext(), "Open Wallet to perform claim", Toast.LENGTH_SHORT).show();
        });

        initSecurePrefs();
        new CheckStatusTask().execute();
    }

    private void initSecurePrefs() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.w(TAG, "EncryptedSharedPreferences not available, falling back", e);
            securePrefs = requireActivity().getSharedPreferences(PREF_FILE, requireActivity().MODE_PRIVATE);
        }
    }

    private void showLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (statusText != null) statusText.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            if (statusText != null) statusText.setText("Checking ANML status...");
            if (errorText != null) errorText.setVisibility(View.GONE);
            if (registerBox != null) registerBox.setVisibility(View.GONE);
            if (claimBox != null) claimBox.setVisibility(View.GONE);
            if (completeBox != null) completeBox.setVisibility(View.GONE);
        }
    }

    private class CheckStatusTask extends AsyncTask<Void, Void, JSONObject> {
        private Exception error;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            showLoading(true);
        }

        @Override
        protected JSONObject doInBackground(Void... voids) {
            try {
                String mnemonic = securePrefs.getString(KEY_MNEMONIC, "");
                if (TextUtils.isEmpty(mnemonic)) {
                    JSONObject res = new JSONObject();
                    res.put("status", "no_wallet");
                    return res;
                }

                String address = com.example.passportscanner.wallet.SecretWallet.getAddressFromMnemonic(mnemonic);
                if (TextUtils.isEmpty(address)) {
                    JSONObject res = new JSONObject();
                    res.put("status", "no_wallet");
                    return res;
                }

                JSONObject payload = new JSONObject();
                payload.put("contract_address", REGISTRATION_CONTRACT);
                JSONObject q = new JSONObject();
                JSONObject inner = new JSONObject();
                inner.put("address", address);
                q.put("query_registration_status", inner);
                payload.put("query", q);
                payload.put("code_hash", REGISTRATION_HASH);

                URL url = new URL(PROXY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                try {
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setDoOutput(true);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                    byte[] out = payload.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(out.length);
                    conn.connect();

                    OutputStream os = conn.getOutputStream();
                    os.write(out);
                    os.flush();
                    os.close();

                    InputStream in = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    if (in == null) throw new Exception("Empty response from proxy");
                    java.util.Scanner s = new java.util.Scanner(in).useDelimiter("\\A");
                    String respBody = s.hasNext() ? s.next() : "";
                    in.close();

                    JSONObject root = new JSONObject(respBody);
                    boolean success = root.optBoolean("success", false);
                    if (!success) {
                        throw new Exception("Proxy returned error: " + root.toString());
                    }

                    JSONObject result = root.optJSONObject("result");
                    if (result == null) {
                        return root;
                    }
                    result.put("queried_address", address);
                    return result;
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                error = e;
                Log.e(TAG, "CheckStatusTask failed", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(JSONObject result) {
            showLoading(false);
            if (error != null) {
                if (errorText != null) {
                    errorText.setText("Failed to check status: " + error.getMessage());
                    errorText.setVisibility(View.VISIBLE);
                }
                return;
            }

            if (result == null) {
                if (errorText != null) {
                    errorText.setText("No response from server.");
                    errorText.setVisibility(View.VISIBLE);
                }
                return;
            }

            try {
                if ("no_wallet".equals(result.optString("status", ""))) {
                    if (registerBox != null) registerBox.setVisibility(View.VISIBLE);
                    return;
                }

                boolean registered = result.optBoolean("registration_status", false);
                if (!registered) {
                    if (registerBox != null) registerBox.setVisibility(View.VISIBLE);
                    return;
                }

                long lastClaim = result.optLong("last_claim", 0L);
                long nextClaimMillis = (lastClaim / 1000000L) + ONE_DAY_MILLIS;
                long now = System.currentTimeMillis();
                if (now > nextClaimMillis) {
                    if (claimBox != null) claimBox.setVisibility(View.VISIBLE);
                } else {
                    if (completeBox != null) completeBox.setVisibility(View.VISIBLE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse result", e);
                if (errorText != null) {
                    errorText.setText("Invalid result from server.");
                    errorText.setVisibility(View.VISIBLE);
                }
            }
        }
    }
}