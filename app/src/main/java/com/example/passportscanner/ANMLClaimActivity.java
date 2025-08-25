package com.example.passportscanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
 
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.wallet.SecretWallet;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ANML Claim activity:
 * - Reads saved mnemonic (EncryptedSharedPreferences fallback)
 * - Derives secret address
 * - Calls the contract query proxy to determine registration/claim status
 * - Shows the appropriate UI box: register / claim / complete
 *
 * Note: executing the claim transaction from mobile is out-of-scope for this change;
 * the "Claim" button opens the Wallet screen where the user can perform transactions.
 */
public class ANMLClaimActivity extends AppCompatActivity {
    private static final String TAG = "ANMLClaimActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";

    // Registration contract details (from desktop app)
    private static final String REGISTRATION_CONTRACT = "secret12q72eas34u8fyg68k6wnerk2nd6l5gaqppld6p";
    private static final String REGISTRATION_HASH = "12fad89bbc7f4c9051b7b5fa1c7af1c17480dcdee4b962cf6cb6ff668da02667";

    // Proxy endpoint
    private static final String PROXY_URL = "http://192.168.1.145:8000/api/contract_query";
    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private ImageView loadingGif;
    private View registerBox;
    private View claimBox;
    private View completeBox;
    private TextView errorText;
    private View loadingOverlay;

    private Button btnOpenWallet;
    private Button btnClaim;

    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_anml_claim);

        try {
            SecretWallet.initialize(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SecretWallet", e);
        }

        loadingGif = findViewById(R.id.anml_loading_gif);
        registerBox = findViewById(R.id.register_box);
        claimBox = findViewById(R.id.claim_box);
        completeBox = findViewById(R.id.complete_box);
        errorText = findViewById(R.id.anml_error_text);
        loadingOverlay = findViewById(R.id.loading_overlay);
 
        if (loadingGif != null) {
            // Load GIF using Glide (from drawable resource)
            Glide.with(this)
                    .asGif()
                    .load(R.drawable.loading)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(loadingGif);
            loadingGif.setVisibility(View.GONE);
        }

        btnOpenWallet = findViewById(R.id.btn_open_wallet);
        btnClaim = findViewById(R.id.btn_claim);
        
        btnOpenWallet.setOnClickListener(v -> {
            // Open the existing passport scan flow (MRZInputActivity) for registration
            Intent i = new Intent(ANMLClaimActivity.this, MRZInputActivity.class);
            startActivity(i);
        });

        btnClaim.setOnClickListener(v -> {
            // For now open Wallet to perform claim (claim execution is not implemented in mobile)
            Intent i = new Intent(ANMLClaimActivity.this, com.example.passportscanner.wallet.WalletActivity.class);
            startActivity(i);
            Toast.makeText(ANMLClaimActivity.this, "Open Wallet to perform claim", Toast.LENGTH_SHORT).show();
        });

        initSecurePrefs();
        
        // Wire bottom navigation (reuse existing view and behavior)
        View navWallet = findViewById(R.id.btn_nav_wallet);
        if (navWallet != null) {
            navWallet.setSelected(false);
            navWallet.setOnClickListener(v -> {
                Intent w = new Intent(ANMLClaimActivity.this, com.example.passportscanner.wallet.WalletActivity.class);
                startActivity(w);
            });
        }
        View navActions = findViewById(R.id.btn_nav_actions);
        if (navActions != null) {
            // Mark Actions as selected for styling and prevent redundant clicks
            navActions.setSelected(true);
            navActions.setOnClickListener(v -> {
                // no-op: already on Actions / ANML screen
            });
        }

        // Start status check
        new CheckStatusTask().execute();
    }

    private void initSecurePrefs() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.w(TAG, "EncryptedSharedPreferences not available, falling back", e);
            securePrefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
        }
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
                // Prefer wallets array (multi-wallet support). Fall back to legacy top-level mnemonic.
                String mnemonic = "";
                try {
                    String walletsJson = securePrefs.getString("wallets", "[]");
                    org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
                    int sel = securePrefs.getInt("selected_wallet_index", -1);
                    if (arr.length() > 0) {
                        if (sel >= 0 && sel < arr.length()) {
                            mnemonic = arr.getJSONObject(sel).optString("mnemonic", "");
                        } else if (arr.length() == 1) {
                            // if only one wallet exists, use it
                            mnemonic = arr.getJSONObject(0).optString("mnemonic", "");
                        }
                    }
                } catch (Exception ignored) {}
                if (TextUtils.isEmpty(mnemonic)) {
                    mnemonic = securePrefs.getString(KEY_MNEMONIC, "");
                }
                if (TextUtils.isEmpty(mnemonic)) {
                    // No wallet configured
                    JSONObject res = new JSONObject();
                    res.put("status", "no_wallet");
                    return res;
                }

                String address = SecretWallet.getAddressFromMnemonic(mnemonic);
                if (TextUtils.isEmpty(address)) {
                    JSONObject res = new JSONObject();
                    res.put("status", "no_wallet");
                    return res;
                }

                // Build proxy payload
                JSONObject payload = new JSONObject();
                payload.put("contract_address", REGISTRATION_CONTRACT);
                JSONObject q = new JSONObject();
                JSONObject inner = new JSONObject();
                inner.put("address", address);
                q.put("query_registration_status", inner);
                payload.put("query", q);
                payload.put("code_hash", REGISTRATION_HASH);

                // POST to proxy
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
                        // Some proxies might return the raw value directly
                        // Return root as fallback
                        return root;
                    }
                    // attach address for logging/debug
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
                errorText.setText("Failed to check status: " + error.getMessage());
                errorText.setVisibility(View.VISIBLE);
                return;
            }

            if (result == null) {
                errorText.setText("No response from server.");
                errorText.setVisibility(View.VISIBLE);
                return;
            }

            try {
                if ("no_wallet".equals(result.optString("status", ""))) {
                    registerBox.setVisibility(View.VISIBLE);
                    return;
                }

                boolean registered = result.optBoolean("registration_status", false);
                if (!registered) {
                    registerBox.setVisibility(View.VISIBLE);
                    return;
                }

                long lastClaim = result.optLong("last_claim", 0L);
                long nextClaimMillis = (lastClaim / 1000000L) + ONE_DAY_MILLIS;
                long now = System.currentTimeMillis();
                if (now > nextClaimMillis) {
                    claimBox.setVisibility(View.VISIBLE);
                } else {
                    completeBox.setVisibility(View.VISIBLE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse result", e);
                errorText.setText("Invalid result from server.");
                errorText.setVisibility(View.VISIBLE);
            }
        }
    }
}