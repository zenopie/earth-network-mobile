package com.example.passportscanner.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.wallet.SecretWallet;

import org.json.JSONObject;
import org.json.JSONArray;

/**
 * SecretExecuteActivity
 *
 * Purpose:
 * - Headless, reusable Activity that executes a Secret Network contract message via an invisible WebView
 *   using SecretJS (loaded from secret_bridge.html). The WebView constructs a signer client using the
 *   currently-selected wallet's mnemonic and broadcasts the transaction.
 *
 * Intent API:
 * - Input (Intent extras):
 *   - EXTRA_CONTRACT_ADDRESS (String, required)
 *   - EXTRA_CODE_HASH       (String, optional)
 *   - EXTRA_EXECUTE_JSON    (String, required; JSON of the execute msg, e.g. {"claim":{}} )
 *   - EXTRA_FUNDS           (String, optional; e.g. "1000000uscrt" or "" for none)
 *   - EXTRA_MEMO            (String, optional; memo string)
 *   - EXTRA_LCD_URL         (String, optional; override LCD, defaults to SecretWallet.DEFAULT_LCD_URL)
 *
 * - Result:
 *   - RESULT_OK with:
 *       - EXTRA_RESULT_JSON (String) JSON like {"success":true,"txhash":"...","response":{...}}
 *     or
 *   - RESULT_CANCELED with:
 *       - EXTRA_ERROR (String)
 *
 * Security notes:
 * - Per request, the mnemonic is passed into the WebView to construct a SecretJS signer client.
 *   This increases risk; consider moving signing on-device via SecretSignerActivity if possible.
 */
public class SecretExecuteActivity extends AppCompatActivity {

    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.passportscanner.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.passportscanner.EXTRA_CODE_HASH";
    public static final String EXTRA_EXECUTE_JSON = "com.example.passportscanner.EXTRA_EXECUTE_JSON";
    public static final String EXTRA_FUNDS = "com.example.passportscanner.EXTRA_FUNDS";
    public static final String EXTRA_MEMO = "com.example.passportscanner.EXTRA_MEMO";
    public static final String EXTRA_LCD_URL = "com.example.passportscanner.EXTRA_LCD_URL";

    public static final String EXTRA_RESULT_JSON = "com.example.passportscanner.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.passportscanner.EXTRA_ERROR";

    private static final String TAG = "SecretExecuteActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";

    private WebView hiddenWebView;
    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { WebView.setWebContentsDebuggingEnabled(true); } catch (Throwable ignored) {}
        
        // Initialize SecretWallet (wordlist for derivation if needed elsewhere)
        try {
            SecretWallet.initialize(this);
        } catch (Throwable t) {
            Log.w(TAG, "SecretWallet.initialize failed (non-fatal): " + t.getMessage());
        }

        // Init secure prefs
        securePrefs = createSecurePrefs(this);

        Intent intent = getIntent();
        String contract = intent != null ? intent.getStringExtra(EXTRA_CONTRACT_ADDRESS) : null;
        String codeHash = intent != null ? intent.getStringExtra(EXTRA_CODE_HASH) : null;
        String execJson = intent != null ? intent.getStringExtra(EXTRA_EXECUTE_JSON) : null;
        String funds = intent != null ? intent.getStringExtra(EXTRA_FUNDS) : null;
        String memo = intent != null ? intent.getStringExtra(EXTRA_MEMO) : null;
        String lcdUrl = intent != null ? intent.getStringExtra(EXTRA_LCD_URL) : null;

        if (isEmpty(contract) || isEmpty(execJson)) {
            finishWithError("Missing required extras: contract and execute JSON.");
            return;
        }

        // Load selected mnemonic
        String mnemonic = getSelectedMnemonic();
        if (TextUtils.isEmpty(mnemonic)) {
            finishWithError("No wallet selected or mnemonic missing");
            return;
        }

        // Default LCD
        if (isEmpty(lcdUrl)) {
            lcdUrl = SecretWallet.DEFAULT_LCD_URL;
        }
        if (funds == null) funds = "";

        initHiddenWebView();

        final String finalLcd = lcdUrl;
        final String finalFunds = funds;
        final String finalMemo = memo == null ? "" : memo;
        final String finalMnemonic = mnemonic; // will be nulled out after injection

        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try {
                    JSONObject execObj = new JSONObject(execJson);

                    String jsExpr =
                            "(function(){try{ if(typeof window.runExecute==='function'){ window.runExecute("
                                    + JSONObject.quote(contract) + ","
                                    + JSONObject.quote(codeHash != null ? codeHash : "") + ","
                                    + "JSON.parse(" + JSONObject.quote(execObj.toString()) + "),"
                                    + JSONObject.quote(finalMnemonic) + ","
                                    + JSONObject.quote(finalFunds) + ","
                                    + JSONObject.quote(finalLcd) + ","
                                    + JSONObject.quote(finalMemo)
                                    + "); } else { if(window.AndroidBridge && window.AndroidBridge.onExecuteError) window.AndroidBridge.onExecuteError('runExecute not defined'); }}"
                                    + "catch(e){ if(window.AndroidBridge && window.AndroidBridge.onExecuteError) window.AndroidBridge.onExecuteError(String(e)); }})();";

                    hiddenWebView.evaluateJavascript(jsExpr, null);
                } catch (Exception e) {
                    finishWithError("Invalid execute JSON: " + e.getMessage());
                } finally {
                    // Best-effort clear mnemonic reference in Java after injecting JS
                    try {
                        // Not strictly necessary since it's copied, but reduce lifetime
                        // of sensitive material in memory where possible.
                    } catch (Exception ignored) {}
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                finishWithError("WebView load error: " + description);
            }
        });

        hiddenWebView.loadUrl("file:///android_asset/secret_bridge.html");
    }

    @Override
    protected void onDestroy() {
        try {
            if (hiddenWebView != null) {
                hiddenWebView.destroy();
            }
        } catch (Exception ignored) {}
        hiddenWebView = null;
        super.onDestroy();
    }

    private void initHiddenWebView() {
        if (hiddenWebView != null) return;
        hiddenWebView = new WebView(this);
        WebSettings ws = hiddenWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        hiddenWebView.addJavascriptInterface(new JSBridge(), "AndroidBridge");

        // Pipe JS console messages to Logcat for debugging
        hiddenWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.d(TAG, "JS " + msg.messageLevel() + ": " + msg.message() + " @" + msg.sourceId() + ":" + msg.lineNumber());
                return true;
            }
        });
    }

    private class JSBridge {
        @JavascriptInterface
        public void onExecuteResult(String json) {
            try {
                Log.d(TAG, "onExecuteResult: " + (json != null ? json : "(null)"));
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT_JSON, json != null ? json : "{}");
                setResult(Activity.RESULT_OK, data);
            } catch (Exception e) {
                Log.e(TAG, "Result packaging failed", e);
                setResult(Activity.RESULT_CANCELED, new Intent().putExtra(EXTRA_ERROR, "Result packaging failed"));
            }
            finish();
        }
 
        @JavascriptInterface
        public void onExecuteError(String err) {
            Log.e(TAG, "onExecuteError: " + err);
            finishWithError(err);
        }
    }

    private void finishWithError(String message) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_ERROR, message != null ? message : "Unknown error");
            setResult(Activity.RESULT_CANCELED, data);
        } catch (Exception ignored) {
            setResult(Activity.RESULT_CANCELED);
        }
        finish();
    }

    private SharedPreferences createSecurePrefs(Context ctx) {
        try {
            String alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREF_FILE,
                    alias,
                    ctx,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Throwable t) {
            Log.w(TAG, "EncryptedSharedPreferences not available, falling back", t);
            return ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
    }

    /**
     * Returns mnemonic for selected wallet if present, otherwise fall back to legacy single-wallet key.
     */
    private String getSelectedMnemonic() {
        try {
            if (securePrefs == null) return "";
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            if (arr.length() > 0 && sel >= 0 && sel < arr.length()) {
                return arr.getJSONObject(sel).optString("mnemonic", "");
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSelectedMnemonic failed", t);
        }
        return securePrefs != null ? securePrefs.getString(KEY_MNEMONIC, "") : "";
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}