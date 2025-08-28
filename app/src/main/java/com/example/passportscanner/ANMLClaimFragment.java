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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.wallet.SecretWallet;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// WebView + JS bridge imports
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebViewClient;
import android.os.Handler;
import android.os.Looper;
// UI helpers for showing response
import androidx.appcompat.app.AlertDialog;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
public class ANMLClaimFragment extends Fragment {
    private static final String TAG = "ANMLClaimFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";

    private static final String REGISTRATION_CONTRACT = "secret12q72eas34u8fyg68k6wnerk2nd6l5gaqppld6p";
    private static final String REGISTRATION_HASH = "12fad89bbc7f4c9051b7b5fa1c7af1c17480dcdee4b962cf6cb6ff668da02667";
    // Optional: provide contract encryption pubkey (compressed secp256k1, Base64). If empty, native flow will try LCD fetch.
    private static final String REGISTRATION_ENC_KEY_B64 = "";
    
    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int REQ_EXECUTE = 2002;
    private static final int REQ_EXECUTE_NATIVE = 2003;

    private ImageView loadingGif;
    private View registerBox;
    private View claimBox;
    private View completeBox;
    private TextView errorText;

    private Button btnOpenWallet;
    private Button btnClaim;
    private Button btnClaimNative;

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

        loadingGif = view.findViewById(R.id.anml_loading_gif);
        registerBox = view.findViewById(R.id.register_box);
        claimBox = view.findViewById(R.id.claim_box);
        completeBox = view.findViewById(R.id.complete_box);
        errorText = view.findViewById(R.id.anml_error_text);
 
        if (loadingGif != null) {
            Glide.with(requireContext())
                    .asGif()
                    .load(R.drawable.loading)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(loadingGif);
            loadingGif.setVisibility(View.GONE);
        }

        btnOpenWallet = view.findViewById(R.id.btn_open_wallet);
        btnClaim = view.findViewById(R.id.btn_claim);
        btnClaimNative = view.findViewById(R.id.btn_claim_native);

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
            if (btnClaimNative != null) {
                btnClaimNative.setBackgroundTintList(null);
                btnClaimNative.setTextColor(getResources().getColor(R.color.anml_button_text));
            }
        } catch (Exception ignored) {}

        btnOpenWallet.setOnClickListener(v -> {
            // Launch existing passport scan flow
            Intent i = new Intent(requireActivity(), MRZInputActivity.class);
            startActivity(i);
        });

        btnClaim.setOnClickListener(v -> {
            try {
                org.json.JSONObject exec = new org.json.JSONObject();
                exec.put("claim_anml", new org.json.JSONObject());
 
                Intent ei = new Intent(requireActivity(), com.example.passportscanner.bridge.SecretExecuteActivity.class);
                ei.putExtra(com.example.passportscanner.bridge.SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, REGISTRATION_CONTRACT);
                ei.putExtra(com.example.passportscanner.bridge.SecretExecuteActivity.EXTRA_CODE_HASH, REGISTRATION_HASH);
                ei.putExtra(com.example.passportscanner.bridge.SecretExecuteActivity.EXTRA_EXECUTE_JSON, exec.toString());
                showLoading(true);
                startActivityForResult(ei, REQ_EXECUTE);
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Failed to start claim: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        
        if (btnClaimNative != null) {
            btnClaimNative.setOnClickListener(v -> {
                try {
                    org.json.JSONObject exec = new org.json.JSONObject();
                    exec.put("claim_anml", new org.json.JSONObject());
 
                    Intent ni = new Intent(requireActivity(), com.example.passportscanner.bridge.SecretExecuteNativeActivity.class);
                    ni.putExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_CONTRACT_ADDRESS, REGISTRATION_CONTRACT);
                    ni.putExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_CODE_HASH, REGISTRATION_HASH);
                    ni.putExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_EXECUTE_JSON, exec.toString());
                    // Optionally pass lcd/funds/memo if desired
                    // ni.putExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_LCD_URL, com.example.passportscanner.wallet.SecretWallet.DEFAULT_LCD_URL);
                    // ni.putExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_FUNDS, "");
                    // ni.putExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_MEMO, "");
                    if (!android.text.TextUtils.isEmpty(REGISTRATION_ENC_KEY_B64)) {
                        ni.putExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_CONTRACT_ENCRYPTION_KEY_B64, REGISTRATION_ENC_KEY_B64);
                    }
                    showLoading(true);
                    startActivityForResult(ni, REQ_EXECUTE_NATIVE);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Failed to start native claim: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        initSecurePrefs();
        initHiddenWebView(requireContext());
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
// Helper: return mnemonic for the currently-selected wallet (if any), otherwise fall back to legacy KEY_MNEMONIC.
private String getSelectedMnemonic() {
    try {
        if (securePrefs == null) return "";
        String walletsJson = securePrefs.getString("wallets", "[]");
        org.json.JSONArray arr = new org.json.JSONArray(walletsJson);
        int sel = securePrefs.getInt("selected_wallet_index", -1);
        if (arr.length() > 0 && sel >= 0 && sel < arr.length()) {
            return arr.getJSONObject(sel).optString("mnemonic", "");
        }
    } catch (Exception e) {
        Log.w(TAG, "getSelectedMnemonic failed", e);
    }
    // fallback to legacy top-level mnemonic key
    return securePrefs != null ? securePrefs.getString(KEY_MNEMONIC, "") : "";
}

// Invisible WebView used to run SecretJS (or the bridge HTML) for contract queries.
private WebView hiddenWebView;
// Shared state used when issuing a synchronous query through the WebView bridge.
private final AtomicReference<String> webViewResult = new AtomicReference<>();
private final AtomicReference<String> webViewError = new AtomicReference<>();
private volatile CountDownLatch webViewLatch;
// Latch that signals when the bridge HTML has finished loading in the WebView.
private volatile CountDownLatch pageLoadLatch;

private class JSBridge {
    @JavascriptInterface
    public void onQueryResult(String json) {
        webViewResult.set(json);

        // Show full response in an alert dialog on the main thread.
        try {
            Handler h = new Handler(Looper.getMainLooper());
            h.post(() -> {
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("SecretJS response")
                            .setMessage(json != null ? json : "(empty)")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}

        CountDownLatch l = webViewLatch;
        if (l != null) l.countDown();
    }

    @JavascriptInterface
    public void onQueryError(String err) {
        webViewError.set(err);

        // Show full error in an alert dialog on the main thread.
        try {
            Handler h = new Handler(Looper.getMainLooper());
            h.post(() -> {
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("SecretJS error")
                            .setMessage(err != null ? err : "(empty)")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}

        CountDownLatch l = webViewLatch;
        if (l != null) l.countDown();
    }
}

private void initHiddenWebView(android.content.Context ctx) {
    try {
        if (hiddenWebView != null) return;
        hiddenWebView = new WebView(ctx);
        WebSettings ws = hiddenWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        // Allow loading local asset and cross-origin if needed by proxy (fetch will go to proxy URL)
        ws.setAllowUniversalAccessFromFileURLs(true);

        // Reset and create a page-load latch so callers can wait until the bridge is ready.
        pageLoadLatch = new CountDownLatch(1);

        hiddenWebView.addJavascriptInterface(new JSBridge(), "AndroidBridge");
        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try {
                    CountDownLatch l = pageLoadLatch;
                    if (l != null) l.countDown();
                } catch (Exception ignored) {}
            }
        });
        // Load the bridge HTML from assets (created earlier)
        hiddenWebView.loadUrl("file:///android_asset/secret_bridge.html");
    } catch (Exception e) {
        Log.w(TAG, "Failed to initialize hidden WebView: " + e.getMessage(), e);
        hiddenWebView = null;
        pageLoadLatch = null;
    }
}

/**
 * Attempt to run a contract query via the hidden WebView bridge.
 * This method posts the JS call on the main thread and waits (with timeout) for a JS callback.
 * Returns parsed JSONObject on success, null if no result and no error (caller may fallback).
 * Throws Exception when a JS error callback is received.
 */
private JSONObject runQueryViaWebView(final JSONObject payload) throws Exception {
    if (hiddenWebView == null) return null;
    // prepare callback state
    webViewResult.set(null);
    webViewError.set(null);
    webViewLatch = new CountDownLatch(1);

    final String contract = payload.optString("contract_address", "");
    final String codeHash = payload.optString("code_hash", "");
    final JSONObject queryObj = payload.optJSONObject("query");
    final String queryJson = (queryObj == null) ? "{}" : queryObj.toString();

    // Build JS expression safely using JSONObject.quote to produce JS string literals
    final String jsExpr = "(function(){try{ if(typeof window.runQuery==='function'){ window.runQuery(" +
            JSONObject.quote(contract) + "," +
            JSONObject.quote(codeHash) + ",JSON.parse(" + JSONObject.quote(queryJson) + ")," +
            JSONObject.quote("") + "," +
            JSONObject.quote(com.example.passportscanner.wallet.SecretWallet.DEFAULT_LCD_URL) + "); } else { if(window.AndroidBridge && window.AndroidBridge.onQueryError) window.AndroidBridge.onQueryError('runQuery not defined'); } }catch(e){ if(window.AndroidBridge && window.AndroidBridge.onQueryError) window.AndroidBridge.onQueryError(String(e)); }})();";

    // Wait for the bridge page to finish loading (avoid calling JS before runQuery is defined)
    try {
        CountDownLatch l = pageLoadLatch;
        if (l != null) {
            boolean loaded = l.await(5, TimeUnit.SECONDS);
            if (!loaded) {
                Log.w(TAG, "Hidden WebView bridge page did not finish loading before JS call");
            }
        }
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
    }

    Handler mainHandler = new Handler(Looper.getMainLooper());
    try {
        mainHandler.post(() -> {
            try {
                hiddenWebView.evaluateJavascript(jsExpr, null);
            } catch (Exception e) {
                // If evaluate fails, set error and count down so caller doesn't block forever
                webViewError.set("evaluateJavascript failed: " + e.getMessage());
                CountDownLatch l = webViewLatch;
                if (l != null) l.countDown();
            }
        });

        // Wait up to 15 seconds for JS to respond
        boolean ok = webViewLatch.await(15, TimeUnit.SECONDS);
        webViewLatch = null;
        if (!ok) {
            // timeout - no result from JS
            return null;
        }
        if (webViewError.get() != null) {
            throw new Exception("WebView error: " + webViewError.get());
        }
        String res = webViewResult.get();
        if (res == null || res.isEmpty()) return null;
        return new JSONObject(res);
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new Exception("Interrupted waiting for WebView result", ie);
    } finally {
        webViewLatch = null;
    }
}

    private void showLoading(boolean loading) {
        if (loadingGif != null) loadingGif.setVisibility(loading ? View.VISIBLE : View.GONE);
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
                String mnemonic = getSelectedMnemonic();
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

                // Run the query through the hidden WebView (SecretJS). No proxy fallback.
                JSONObject root = runQueryViaWebView(payload);
                if (root == null) {
                    throw new Exception("No response from WebView bridge (SecretJS).");
                }
                boolean success = root.optBoolean("success", false);
                if (!success) {
                    throw new Exception("Bridge returned error: " + root.toString());
                }
                JSONObject result = root.optJSONObject("result");
                if (result == null) {
                    // return root as-is if no result object
                    return root;
                }
                result.put("queried_address", address);
                return result;
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

    @Override
    @SuppressWarnings("deprecation")
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXECUTE) {
            showLoading(false);
            if (resultCode != android.app.Activity.RESULT_OK) {
                String err = (data != null) ? data.getStringExtra(com.example.passportscanner.bridge.SecretExecuteActivity.EXTRA_ERROR) : "Execution canceled";
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Execute error")
                            .setMessage(err != null ? err : "(empty)")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {
                    Toast.makeText(requireContext(), "Execute error: " + (err != null ? err : "(empty)"), Toast.LENGTH_LONG).show();
                }
                return;
            }
            try {
                String json = (data != null) ? data.getStringExtra(com.example.passportscanner.bridge.SecretExecuteActivity.EXTRA_RESULT_JSON) : null;
                String txhash = null;
                if (!android.text.TextUtils.isEmpty(json)) {
                    org.json.JSONObject root = new org.json.JSONObject(json);
                    txhash = root.optString("txhash", null);
                }
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Execute response")
                            .setMessage(json != null ? json : "(empty)")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {
                    String msg = txhash != null ? "Claim submitted: " + txhash : "Claim submitted";
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                }
                // Re-check status to refresh UI after claim
                new CheckStatusTask().execute();
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse execute result", e);
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Execute response")
                            .setMessage("Claim submitted")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {
                    Toast.makeText(requireContext(), "Claim submitted", Toast.LENGTH_LONG).show();
                }
                new CheckStatusTask().execute();
            }
        } else if (requestCode == REQ_EXECUTE_NATIVE) {
            showLoading(false);
            if (resultCode != android.app.Activity.RESULT_OK) {
                String err = (data != null) ? data.getStringExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_ERROR) : "Execution canceled";
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Execute (Native) error")
                            .setMessage(err != null ? err : "(empty)")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {
                    Toast.makeText(requireContext(), "Execute (Native) error: " + (err != null ? err : "(empty)"), Toast.LENGTH_LONG).show();
                }
                return;
            }
            try {
                String json = (data != null) ? data.getStringExtra(com.example.passportscanner.bridge.SecretExecuteNativeActivity.EXTRA_RESULT_JSON) : null;
                String txhash = null;
                if (!android.text.TextUtils.isEmpty(json)) {
                    org.json.JSONObject root = new org.json.JSONObject(json);
                    txhash = root.optString("txhash", null);
                }
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Execute (Native) response")
                            .setMessage(json != null ? json : "(empty)")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {
                    String msg = txhash != null ? "Claim submitted: " + txhash : "Claim submitted";
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                }
                // Re-check status to refresh UI after claim
                new CheckStatusTask().execute();
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse native execute result", e);
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Execute (Native) response")
                            .setMessage("Claim submitted")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception ignored) {
                    Toast.makeText(requireContext(), "Claim submitted", Toast.LENGTH_LONG).show();
                }
                new CheckStatusTask().execute();
            }
        }
    }
}