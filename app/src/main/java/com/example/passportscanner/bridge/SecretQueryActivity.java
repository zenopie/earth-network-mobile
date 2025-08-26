package com.example.passportscanner.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.passportscanner.wallet.SecretWallet;

import org.json.JSONObject;

/**
 * SecretQueryActivity
 *
 * Purpose:
 * - Reusable, headless Activity for running Secret Network contract queries via an invisible WebView
 *   that loads the secret_bridge.html asset (SecretJS in the WebView).
 * - Keeps SecretJS in the WebView, while Android orchestrates and returns the result via Activity result.
 *
 * Intent API:
 * - Input (Intent extras):
 *   - EXTRA_CONTRACT_ADDRESS (String, required)
 *   - EXTRA_CODE_HASH       (String, optional)
 *   - EXTRA_QUERY_JSON      (String, required; JSON of {"your_query_key": {...}} )
 *
 * - Result (onActivityResult):
 *   - RESULT_OK with:
 *       - EXTRA_RESULT_JSON (String)  - JSON string: {"success":true,"result":...}
 *     or
 *   - RESULT_CANCELED with:
 *       - EXTRA_ERROR       (String)  - error string
 *
 * Notes:
 * - This Activity is UI-less; it loads the bridge and returns as soon as the result is available.
 * - No secret material (mnemonic/private key) is ever passed into the WebView for queries.
 */
public class SecretQueryActivity extends AppCompatActivity {

    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.passportscanner.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.passportscanner.EXTRA_CODE_HASH";
    public static final String EXTRA_QUERY_JSON = "com.example.passportscanner.EXTRA_QUERY_JSON";

    public static final String EXTRA_RESULT_JSON = "com.example.passportscanner.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.passportscanner.EXTRA_ERROR";

    private static final String TAG = "SecretQueryActivity";
    private WebView hiddenWebView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize word list (harmless even if already initialized)
        try {
            SecretWallet.initialize(this);
        } catch (Throwable t) {
            Log.w(TAG, "SecretWallet.initialize failed (non-fatal): " + t.getMessage());
        }

        Intent intent = getIntent();
        String contract = intent != null ? intent.getStringExtra(EXTRA_CONTRACT_ADDRESS) : null;
        String codeHash = intent != null ? intent.getStringExtra(EXTRA_CODE_HASH) : null;
        String queryJson = intent != null ? intent.getStringExtra(EXTRA_QUERY_JSON) : null;

        if (isEmpty(contract) || isEmpty(queryJson)) {
            finishWithError("Missing required extras: contract and query JSON.");
            return;
        }

        initHiddenWebView();

        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try {
                    JSONObject queryObj = new JSONObject(queryJson);
                    final String jsExpr =
                            "(function(){try{ if(typeof window.runQuery==='function'){ window.runQuery("
                                    + JSONObject.quote(contract) + ","
                                    + JSONObject.quote(codeHash != null ? codeHash : "") + ","
                                    + "JSON.parse(" + JSONObject.quote(queryObj.toString()) + "),"
                                    + JSONObject.quote("") + ","
                                    + JSONObject.quote(SecretWallet.DEFAULT_LCD_URL)
                                    + "); } else { if(window.AndroidBridge && window.AndroidBridge.onQueryError) window.AndroidBridge.onQueryError('runQuery not defined'); }}"
                                    + "catch(e){ if(window.AndroidBridge && window.AndroidBridge.onQueryError) window.AndroidBridge.onQueryError(String(e)); }})();";

                    hiddenWebView.evaluateJavascript(jsExpr, null);
                } catch (Exception e) {
                    finishWithError("Invalid query JSON: " + e.getMessage());
                }
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
        ws.setAllowUniversalAccessFromFileURLs(true);
        hiddenWebView.addJavascriptInterface(new JSBridge(), "AndroidBridge");
    }

    private class JSBridge {
        @JavascriptInterface
        public void onQueryResult(String json) {
            try {
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT_JSON, json != null ? json : "{}");
                setResult(Activity.RESULT_OK, data);
            } catch (Exception e) {
                setResult(Activity.RESULT_CANCELED, new Intent().putExtra(EXTRA_ERROR, "Result packaging failed"));
            }
            finish();
        }

        @JavascriptInterface
        public void onQueryError(String err) {
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

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}