package com.example.earthwallet.bridge.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.bridge.services.SecretQueryService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SecretQueryActivity
 *
 * Native Secret Network contract query activity that eliminates webview dependency.
 * Uses pure Java/Android networking and crypto to perform Secret Network contract queries.
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
 * Advantages over previous webview approach:
 * - Faster execution (no JS engine startup)  
 * - Lower memory usage
 * - Better error handling
 * - No network security config conflicts
 * - Direct access to Android crypto libraries
 */
public class SecretQueryActivity extends AppCompatActivity {

    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.earthwallet.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.earthwallet.EXTRA_CODE_HASH";
    public static final String EXTRA_QUERY_JSON = "com.example.earthwallet.EXTRA_QUERY_JSON";

    public static final String EXTRA_RESULT_JSON = "com.example.earthwallet.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.earthwallet.EXTRA_ERROR";

    private static final String TAG = "SecretQueryActivity";

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

        // Execute query in background thread
        new QueryTask().execute(contract, codeHash, queryJson);
    }

    private class QueryTask extends AsyncTask<String, Void, QueryResult> {
        
        @Override
        protected QueryResult doInBackground(String... params) {
            String contractAddress = params[0];
            String codeHash = params[1];
            String queryJson = params[2];
            
            try {
                Log.i(TAG, "Starting native Secret Network query");
                Log.i(TAG, "Contract: " + contractAddress);
                Log.i(TAG, "Code hash: " + (codeHash != null ? codeHash : "null"));
                Log.i(TAG, "Query JSON: " + queryJson);
                
                // Get wallet mnemonic from secure preferences
                String mnemonic = getMnemonic();
                if (isEmpty(mnemonic)) {
                    return new QueryResult(false, "No wallet found - mnemonic is empty", null);
                }
                
                // Parse query JSON
                JSONObject queryObj = new JSONObject(queryJson);
                
                // Execute native query
                SecretQueryService queryService = new SecretQueryService();
                JSONObject result = queryService.queryContract(
                    SecretWallet.DEFAULT_LCD_URL,
                    contractAddress,
                    codeHash,
                    queryObj,
                    mnemonic
                );
                
                // Format result to match SecretJS response format
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("result", result);
                
                Log.i(TAG, "Native query completed successfully");
                return new QueryResult(true, null, response.toString());
                
            } catch (Exception e) {
                Log.e(TAG, "Native query failed", e);
                return new QueryResult(false, "Query failed: " + e.getMessage(), null);
            }
        }
        
        @Override
        protected void onPostExecute(QueryResult result) {
            if (result.success) {
                finishWithSuccess(result.data);
            } else {
                finishWithError(result.error);
            }
        }
    }
    
    private static class QueryResult {
        final boolean success;
        final String error;
        final String data;
        
        QueryResult(boolean success, String error, String data) {
            this.success = success;
            this.error = error;
            this.data = data;
        }
    }
    
    private String getMnemonic() {
        try {
            // Use the same secure preferences logic as other activities (matches WalletMainFragment.getMnemonic)
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    "secret_wallet_prefs",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            
            // Get mnemonic for the selected wallet (wallets stored as JSON array)
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            
            if (arr.length() > 0) {
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "");
                } else if (arr.length() == 1) {
                    return arr.getJSONObject(0).optString("mnemonic", "");
                }
            }
            
            // Fallback to legacy top-level mnemonic
            return securePrefs.getString("mnemonic", "");
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to get wallet mnemonic from secure prefs, trying fallback: " + e.getMessage());
            
            // Fallback to plain SharedPreferences if secure prefs fail
            try {
                SharedPreferences flatPrefs = getSharedPreferences("secret_wallet_prefs", Context.MODE_PRIVATE);
                String walletsJson = flatPrefs.getString("wallets", "[]");
                JSONArray arr = new JSONArray(walletsJson);
                
                if (arr.length() > 0) {
                    int sel = flatPrefs.getInt("selected_wallet_index", -1);
                    if (sel >= 0 && sel < arr.length()) {
                        return arr.getJSONObject(sel).optString("mnemonic", "");
                    } else {
                        return arr.getJSONObject(0).optString("mnemonic", "");
                    }
                }
                
                return flatPrefs.getString("mnemonic", "");
            } catch (Exception e2) {
                Log.e(TAG, "Both secure and fallback mnemonic retrieval failed", e2);
                return null;
            }
        }
    }

    private void finishWithSuccess(String json) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_JSON, json != null ? json : "{}");
            setResult(Activity.RESULT_OK, data);
        } catch (Exception e) {
            setResult(Activity.RESULT_CANCELED, new Intent().putExtra(EXTRA_ERROR, "Result packaging failed"));
        }
        finish();
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