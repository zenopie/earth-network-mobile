package com.example.passportscanner;

import android.content.Intent;
import android.content.SharedPreferences;

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







/**
 * ANML Claim activity:
 * - Reads saved mnemonic (EncryptedSharedPreferences fallback)
 * - Derives secret address
 * - Uses SecretQueryActivity to run a contract query via an invisible WebView (SecretJS)
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
        
        // Ensure any theme tinting is cleared so the drawable renders as-designed
        try {
            if (btnOpenWallet != null) {
                btnOpenWallet.setBackgroundTintList(null);
                btnOpenWallet.setTextColor(getResources().getColor(R.color.anml_button_text));
            }
            if (btnClaim != null) {
                btnClaim.setBackgroundTintList(null);
                btnClaim.setTextColor(getResources().getColor(R.color.anml_button_text));
            }
        } catch (Exception ignored) {}
        
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
        checkStatus();
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

    // Request code for launching SecretQueryActivity
    private static final int REQ_QUERY = 1001;

    // Launch the reusable query activity to check registration/claim status
    private void checkStatus() {
        try {
            showLoading(true);

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
                        mnemonic = arr.getJSONObject(0).optString("mnemonic", "");
                    }
                }
            } catch (Exception ignored) {}
            if (TextUtils.isEmpty(mnemonic)) {
                showLoading(false);
                if (registerBox != null) registerBox.setVisibility(View.VISIBLE);
                return;
            }

            String address = SecretWallet.getAddressFromMnemonic(mnemonic);
            if (TextUtils.isEmpty(address)) {
                showLoading(false);
                if (registerBox != null) registerBox.setVisibility(View.VISIBLE);
                return;
            }

            // Build query object for the bridge (no proxy)
            JSONObject q = new JSONObject();
            JSONObject inner = new JSONObject();
            inner.put("address", address);
            q.put("query_registration_status", inner);

            Intent qi = new Intent(ANMLClaimActivity.this, com.example.passportscanner.bridge.SecretQueryActivity.class);
            qi.putExtra(com.example.passportscanner.bridge.SecretQueryActivity.EXTRA_CONTRACT_ADDRESS, REGISTRATION_CONTRACT);
            qi.putExtra(com.example.passportscanner.bridge.SecretQueryActivity.EXTRA_CODE_HASH, REGISTRATION_HASH);
            qi.putExtra(com.example.passportscanner.bridge.SecretQueryActivity.EXTRA_QUERY_JSON, q.toString());
            startActivityForResult(qi, REQ_QUERY);
        } catch (Exception e) {
            Log.e(TAG, "checkStatus failed", e);
            showLoading(false);
            if (errorText != null) {
                errorText.setText("Failed to check status: " + e.getMessage());
                errorText.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_QUERY) {
            showLoading(false);
            if (resultCode != RESULT_OK) {
                String err = (data != null) ? data.getStringExtra(com.example.passportscanner.bridge.SecretQueryActivity.EXTRA_ERROR) : "Query canceled";
                if (errorText != null) {
                    errorText.setText("Failed to check status: " + err);
                    errorText.setVisibility(View.VISIBLE);
                }
                return;
            }
            try {
                String json = (data != null) ? data.getStringExtra(com.example.passportscanner.bridge.SecretQueryActivity.EXTRA_RESULT_JSON) : null;
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
                Log.e(TAG, "Failed to parse bridge result", e);
                if (errorText != null) {
                    errorText.setText("Invalid result from bridge.");
                    errorText.setVisibility(View.VISIBLE);
                }
            }
        }
    }
}