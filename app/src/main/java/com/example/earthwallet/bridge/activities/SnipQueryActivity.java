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
import com.example.earthwallet.wallet.constants.Tokens;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SnipQueryActivity
 *
 * General-purpose SNIP-20/SNIP-721 token query activity that handles:
 * - SNIP-20 balance queries with viewing keys
 * - SNIP-20 token info queries (name, symbol, decimals)
 * - SNIP-721 balance and token queries
 * - Generic SNIP contract queries
 * 
 * This activity abstracts the common SNIP query patterns found in TokenBalancesFragment
 * and provides a reusable interface for SNIP contract interactions.
 *
 * Intent API:
 * - Input (Intent extras):
 *   - EXTRA_TOKEN_SYMBOL     (String, optional) - Token symbol for automatic contract lookup
 *   - EXTRA_CONTRACT_ADDRESS (String, required if no symbol) - Contract address
 *   - EXTRA_CODE_HASH       (String, optional) - Contract code hash
 *   - EXTRA_QUERY_TYPE      (String, required) - Query type: "balance", "token_info", "generic"
 *   - EXTRA_WALLET_ADDRESS  (String, optional) - Wallet address for balance queries
 *   - EXTRA_VIEWING_KEY     (String, optional) - Viewing key for SNIP-20 balance queries
 *   - EXTRA_CUSTOM_QUERY    (String, optional) - Custom JSON query for generic queries
 *
 * - Result (onActivityResult):
 *   - RESULT_OK with:
 *       - EXTRA_RESULT_JSON (String)  - JSON string: {"success":true,"result":...}
 *       - EXTRA_TOKEN_SYMBOL (String) - Echo back token symbol if provided
 *       - EXTRA_QUERY_TYPE   (String) - Echo back query type
 *     or
 *   - RESULT_CANCELED with:
 *       - EXTRA_ERROR       (String)  - error string
 */
public class SnipQueryActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_TOKEN_SYMBOL = "com.example.earthwallet.EXTRA_TOKEN_SYMBOL";
    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.earthwallet.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.earthwallet.EXTRA_CODE_HASH";
    public static final String EXTRA_QUERY_TYPE = "com.example.earthwallet.EXTRA_QUERY_TYPE";
    public static final String EXTRA_WALLET_ADDRESS = "com.example.earthwallet.EXTRA_WALLET_ADDRESS";
    public static final String EXTRA_VIEWING_KEY = "com.example.earthwallet.EXTRA_VIEWING_KEY";
    public static final String EXTRA_CUSTOM_QUERY = "com.example.earthwallet.EXTRA_CUSTOM_QUERY";

    // Result extras
    public static final String EXTRA_RESULT_JSON = "com.example.earthwallet.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.earthwallet.EXTRA_ERROR";

    // Query types
    public static final String QUERY_TYPE_BALANCE = "balance";
    public static final String QUERY_TYPE_TOKEN_INFO = "token_info";
    public static final String QUERY_TYPE_GENERIC = "generic";

    private static final String TAG = "SnipQueryActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize SecretWallet
        try {
            SecretWallet.initialize(this);
        } catch (Throwable t) {
            Log.w(TAG, "SecretWallet.initialize failed (non-fatal): " + t.getMessage());
        }

        Intent intent = getIntent();
        if (intent == null) {
            finishWithError("Intent is null");
            return;
        }

        String tokenSymbol = intent.getStringExtra(EXTRA_TOKEN_SYMBOL);
        String contractAddress = intent.getStringExtra(EXTRA_CONTRACT_ADDRESS);
        String codeHash = intent.getStringExtra(EXTRA_CODE_HASH);
        String queryType = intent.getStringExtra(EXTRA_QUERY_TYPE);
        String walletAddress = intent.getStringExtra(EXTRA_WALLET_ADDRESS);
        String viewingKey = intent.getStringExtra(EXTRA_VIEWING_KEY);
        String customQuery = intent.getStringExtra(EXTRA_CUSTOM_QUERY);

        // Validate required parameters
        if (TextUtils.isEmpty(queryType)) {
            finishWithError("Query type is required");
            return;
        }

        // Get contract info from token symbol if provided
        Tokens.TokenInfo tokenInfo = null;
        if (!TextUtils.isEmpty(tokenSymbol)) {
            tokenInfo = Tokens.getToken(tokenSymbol);
            if (tokenInfo != null) {
                contractAddress = tokenInfo.contract;
                if (TextUtils.isEmpty(codeHash)) {
                    codeHash = tokenInfo.hash;
                }
            }
        }

        if (TextUtils.isEmpty(contractAddress)) {
            finishWithError("Contract address is required (either directly or via token symbol)");
            return;
        }

        // Execute query in background thread and pass the original intent for extra data
        new SnipQueryTask(intent).execute(
            tokenSymbol,
            contractAddress, 
            codeHash,
            queryType,
            walletAddress,
            viewingKey,
            customQuery
        );
    }

    private class SnipQueryTask extends AsyncTask<String, Void, SnipQueryResult> {
        private final Intent originalIntent;
        
        public SnipQueryTask(Intent intent) {
            this.originalIntent = intent;
        }
        
        @Override
        protected SnipQueryResult doInBackground(String... params) {
            String tokenSymbol = params[0];
            String contractAddress = params[1];
            String codeHash = params[2];
            String queryType = params[3];
            String walletAddress = params[4];
            String viewingKey = params[5];
            String customQuery = params[6];
            
            try {
                Log.i(TAG, "Starting SNIP query");
                Log.i(TAG, "Token symbol: " + (tokenSymbol != null ? tokenSymbol : "null"));
                Log.i(TAG, "Contract: " + contractAddress);
                Log.i(TAG, "Query type: " + queryType);
                
                // Build query JSON based on type
                JSONObject queryObj = buildQuery(queryType, walletAddress, viewingKey, customQuery);
                if (queryObj == null) {
                    return new SnipQueryResult(false, "Failed to build query JSON", null, tokenSymbol, queryType, originalIntent);
                }
                
                Log.i(TAG, "Query JSON: " + queryObj.toString());
                
                // Get wallet mnemonic from secure preferences
                String mnemonic = getMnemonic();
                if (TextUtils.isEmpty(mnemonic)) {
                    return new SnipQueryResult(false, "No wallet found - mnemonic is empty", null, tokenSymbol, queryType, originalIntent);
                }
                
                // Execute native query
                SecretQueryService queryService = new SecretQueryService();
                JSONObject result = queryService.queryContract(
                    SecretWallet.DEFAULT_LCD_URL,
                    contractAddress,
                    codeHash,
                    queryObj,
                    mnemonic
                );
                
                // Format result to match expected format
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("result", result);
                
                Log.i(TAG, "SNIP query completed successfully");
                return new SnipQueryResult(true, null, response.toString(), tokenSymbol, queryType, originalIntent);
                
            } catch (Exception e) {
                Log.e(TAG, "SNIP query failed", e);
                return new SnipQueryResult(false, "Query failed: " + e.getMessage(), null, tokenSymbol, queryType, originalIntent);
            }
        }
        
        @Override
        protected void onPostExecute(SnipQueryResult result) {
            if (result.success) {
                finishWithSuccess(result.data, result.tokenSymbol, result.queryType, result.originalIntent);
            } else {
                finishWithError(result.error);
            }
        }
    }

    /**
     * Build query JSON object based on query type and parameters
     */
    private JSONObject buildQuery(String queryType, String walletAddress, String viewingKey, String customQuery) {
        try {
            JSONObject query = new JSONObject();
            
            switch (queryType) {
                case QUERY_TYPE_BALANCE:
                    if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(viewingKey)) {
                        Log.e(TAG, "Balance query requires wallet address and viewing key");
                        return null;
                    }
                    JSONObject balanceQuery = new JSONObject();
                    balanceQuery.put("address", walletAddress);
                    balanceQuery.put("key", viewingKey);
                    balanceQuery.put("time", System.currentTimeMillis());
                    query.put("balance", balanceQuery);
                    break;
                    
                case QUERY_TYPE_TOKEN_INFO:
                    JSONObject tokenInfoQuery = new JSONObject();
                    query.put("token_info", tokenInfoQuery);
                    break;
                    
                case QUERY_TYPE_GENERIC:
                    if (TextUtils.isEmpty(customQuery)) {
                        Log.e(TAG, "Generic query requires custom query JSON");
                        return null;
                    }
                    return new JSONObject(customQuery);
                    
                default:
                    Log.e(TAG, "Unknown query type: " + queryType);
                    return null;
            }
            
            return query;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to build query JSON", e);
            return null;
        }
    }

    /**
     * Retrieve wallet mnemonic from secure preferences
     */
    private String getMnemonic() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            
            // Get mnemonic for the selected wallet
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
            
            // Fallback to legacy mnemonic
            return securePrefs.getString("mnemonic", "");
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to get wallet mnemonic from secure prefs, trying fallback: " + e.getMessage());
            
            // Fallback to plain SharedPreferences
            try {
                SharedPreferences flatPrefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
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

    /**
     * Retrieve viewing key for a contract from secure preferences
     */
    private String getViewingKey(String contractAddress, String walletAddress) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return "";
        }
        
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            
            return securePrefs.getString("viewing_key_" + walletAddress + "_" + contractAddress, "");
        } catch (Exception e) {
            Log.e(TAG, "Failed to get viewing key", e);
            return "";
        }
    }

    private void finishWithSuccess(String json, String tokenSymbol, String queryType, Intent originalIntent) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_JSON, json != null ? json : "{}");
            if (!TextUtils.isEmpty(tokenSymbol)) {
                data.putExtra(EXTRA_TOKEN_SYMBOL, tokenSymbol);
            }
            if (!TextUtils.isEmpty(queryType)) {
                data.putExtra(EXTRA_QUERY_TYPE, queryType);
            }
            
            // Copy all extras from original intent to preserve caller's additional data
            if (originalIntent != null && originalIntent.getExtras() != null) {
                for (String key : originalIntent.getExtras().keySet()) {
                    // Don't overwrite our standard result keys
                    if (!EXTRA_RESULT_JSON.equals(key) && 
                        !EXTRA_TOKEN_SYMBOL.equals(key) && 
                        !EXTRA_QUERY_TYPE.equals(key) &&
                        !EXTRA_CONTRACT_ADDRESS.equals(key) &&
                        !EXTRA_CODE_HASH.equals(key) &&
                        !EXTRA_WALLET_ADDRESS.equals(key) &&
                        !EXTRA_VIEWING_KEY.equals(key) &&
                        !EXTRA_CUSTOM_QUERY.equals(key)) {
                        
                        Object value = originalIntent.getExtras().get(key);
                        if (value instanceof String) {
                            data.putExtra(key, (String) value);
                        } else if (value instanceof Boolean) {
                            data.putExtra(key, (Boolean) value);
                        } else if (value instanceof Integer) {
                            data.putExtra(key, (Integer) value);
                        } else if (value instanceof Long) {
                            data.putExtra(key, (Long) value);
                        } else if (value instanceof Double) {
                            data.putExtra(key, (Double) value);
                        }
                        // Add other types as needed
                    }
                }
            }
            
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

    private static class SnipQueryResult {
        final boolean success;
        final String error;
        final String data;
        final String tokenSymbol;
        final String queryType;
        final Intent originalIntent;
        
        SnipQueryResult(boolean success, String error, String data, String tokenSymbol, String queryType, Intent originalIntent) {
            this.success = success;
            this.error = error;
            this.data = data;
            this.tokenSymbol = tokenSymbol;
            this.queryType = queryType;
            this.originalIntent = originalIntent;
        }
    }
}