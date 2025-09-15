package com.example.earthwallet.bridge.services;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.bridge.utils.PermitManager;

import org.json.JSONObject;

/**
 * SnipQueryService
 *
 * Service wrapper for SNIP-20/SNIP-721 token queries that handles:
 * - SNIP-20 balance queries with viewing keys
 * - SNIP-20 token info queries (name, symbol, decimals)
 * - SNIP-721 balance and token queries
 * - Generic SNIP contract queries
 * 
 * This service abstracts the common SNIP query patterns and provides a direct
 * synchronous interface for SNIP contract interactions without Activity overhead.
 */
public class SnipQueryService {

    // Query types
    public static final String QUERY_TYPE_BALANCE = "balance";
    public static final String QUERY_TYPE_TOKEN_INFO = "token_info";
    public static final String QUERY_TYPE_GENERIC = "generic";

    private static final String TAG = "SnipQueryService";

    /**
     * Query a SNIP contract directly
     * 
     * @param context Android context for secure wallet access
     * @param tokenSymbol Token symbol for automatic contract lookup (optional)
     * @param contractAddress Contract address (required if no symbol)
     * @param codeHash Contract code hash (optional, will use token's hash if available)
     * @param queryType Query type: "balance", "token_info", "generic"
     * @param walletAddress Wallet address for balance queries (optional)
     * @param viewingKey Viewing key for SNIP-20 balance queries (optional)
     * @param customQuery Custom JSON query for generic queries (optional)
     * @return Query result JSON object with format: {"success":true,"result":...}
     */
    public static JSONObject queryContract(Context context, String tokenSymbol, String contractAddress, 
                                         String codeHash, String queryType, String walletAddress, 
                                         String viewingKey, String customQuery) throws Exception {
        Log.i(TAG, "Starting SNIP query service");
        Log.i(TAG, "Token symbol: " + (tokenSymbol != null ? tokenSymbol : "null"));
        Log.i(TAG, "Contract: " + contractAddress);
        Log.i(TAG, "Query type: " + queryType);
        
        // Validate required parameters
        if (TextUtils.isEmpty(queryType)) {
            throw new IllegalArgumentException("Query type is required");
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
            throw new IllegalArgumentException("Contract address is required (either directly or via token symbol)");
        }
        
        // Build query JSON based on type
        JSONObject queryObj = buildQuery(queryType, walletAddress, viewingKey, customQuery);
        if (queryObj == null) {
            throw new IllegalArgumentException("Failed to build query JSON");
        }
        
        Log.i(TAG, "Query JSON: " + queryObj.toString());
        
        // Execute native query with secure mnemonic handling
        // Use HostActivity context if available for centralized securePrefs access
        Context queryContext = context;
        if (context instanceof com.example.earthwallet.ui.host.HostActivity) {
            queryContext = context;
            Log.d(TAG, "Using HostActivity context for SecretQueryService");
        } else {
            Log.d(TAG, "Using provided context for SecretQueryService: " + context.getClass().getSimpleName());
        }
        
        SecretQueryService queryService = new SecretQueryService(queryContext);
        JSONObject result = queryService.queryContract(
            contractAddress,
            codeHash,
            queryObj
        );
        
        // Format result to match expected format
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("result", result);
        
        Log.i(TAG, "SNIP query completed successfully");
        return response;
    }

    /**
     * Convenience method for balance queries with viewing key (deprecated)
     * @deprecated Use queryBalanceWithPermit() instead
     */
    @Deprecated
    public static JSONObject queryBalance(Context context, String tokenSymbol, String walletAddress, String viewingKey) throws Exception {
        return queryContract(context, tokenSymbol, null, null, QUERY_TYPE_BALANCE, walletAddress, viewingKey, null);
    }

    /**
     * Query balance using SNIP-24 permit - CORRECTED structure from SecretJS analysis
     */
    public static JSONObject queryBalanceWithPermit(Context context, String tokenSymbol, String walletAddress) throws Exception {
        Log.d(TAG, "Querying " + tokenSymbol + " balance with permit");

        // Get permit manager and check for valid permit
        PermitManager permitManager = PermitManager.getInstance(context);

        // Get contract info from token symbol
        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
        if (tokenInfo == null) {
            throw new Exception("Unknown token: " + tokenSymbol);
        }

        String contractAddress = tokenInfo.contract;
        String codeHash = tokenInfo.hash;

        // Get valid permit for balance queries
        com.example.earthwallet.bridge.models.Permit permit = permitManager.getValidPermitForQuery(walletAddress, contractAddress, "balance");
        if (permit == null) {
            throw new Exception("No valid permit found for " + tokenSymbol);
        }

        // Create the CORRECT permit query structure per SNIP-24 spec
        JSONObject innerQuery = new JSONObject();
        JSONObject balanceQuery = new JSONObject();
        // NOTE: Address is NOT included in permit queries - it's derived from permit signature
        innerQuery.put("balance", balanceQuery);

        JSONObject withPermitQuery = permitManager.createWithPermitQuery(innerQuery, permit, "secret-4");

        // Execute the query
        SecretQueryService queryService = new SecretQueryService(context);
        JSONObject result = queryService.queryContract(contractAddress, codeHash, withPermitQuery);

        // Format result
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("result", result);

        Log.d(TAG, "Balance query completed for " + tokenSymbol);
        return response;
    }

    /**
     * Convenience method for token info queries
     */
    public static JSONObject queryTokenInfo(Context context, String tokenSymbol) throws Exception {
        return queryContract(context, tokenSymbol, null, null, QUERY_TYPE_TOKEN_INFO, null, null, null);
    }

    private static JSONObject buildQuery(String queryType, String walletAddress, String viewingKey, String customQuery) throws Exception {
        JSONObject query = new JSONObject();
        
        switch (queryType) {
            case QUERY_TYPE_BALANCE:
                if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(viewingKey)) {
                    throw new IllegalArgumentException("Balance queries require wallet address and viewing key");
                }
                JSONObject balanceQuery = new JSONObject();
                balanceQuery.put("address", walletAddress);
                balanceQuery.put("key", viewingKey);
                balanceQuery.put("time", System.currentTimeMillis());
                query.put("balance", balanceQuery);
                break;
                
            case QUERY_TYPE_TOKEN_INFO:
                query.put("token_info", new JSONObject());
                break;
                
            case QUERY_TYPE_GENERIC:
                if (TextUtils.isEmpty(customQuery)) {
                    throw new IllegalArgumentException("Generic queries require custom query JSON");
                }
                return new JSONObject(customQuery);
                
            default:
                throw new IllegalArgumentException("Unknown query type: " + queryType);
        }
        
        return query;
    }
}