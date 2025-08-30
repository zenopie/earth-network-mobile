package com.example.earthwallet.bridge.services;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * SecretQueryService
 * 
 * Native implementation of Secret Network contract queries without webview dependency.
 * Provides the same functionality as SecretQueryActivity but using pure Java/Android HTTP and crypto.
 * 
 * This service replicates the SecretJS query flow:
 * 1. Fetch contract code hash (if not provided)
 * 2. Encrypt query using SecretJS-compatible encryption
 * 3. Send encrypted query to Secret Network LCD endpoint
 * 4. Decrypt and return response
 */
public class SecretQueryService {
    
    private static final String TAG = "SecretQueryService";
    
    private final SecretNetworkService networkService;
    private final SecretCryptoService cryptoService;
    
    public SecretQueryService() {
        this.networkService = new SecretNetworkService();
        this.cryptoService = new SecretCryptoService();
    }
    
    /**
     * Query a Secret Network contract natively (no webview)
     * 
     * @param lcdUrl LCD endpoint URL
     * @param contractAddress Contract address  
     * @param codeHash Contract code hash (optional, will be fetched if null)
     * @param queryJson Query JSON object
     * @param mnemonic Wallet mnemonic for encryption keys
     * @return Query result JSON object
     */
    public JSONObject queryContract(String lcdUrl, String contractAddress, String codeHash, 
                                   JSONObject queryJson, String mnemonic) throws Exception {
        Log.i(TAG, "Starting native Secret Network contract query");
        Log.i(TAG, "Contract: " + contractAddress);
        Log.i(TAG, "Code hash: " + (codeHash != null ? codeHash : "null (will fetch)"));
        Log.i(TAG, "Query: " + queryJson.toString());
        
        // Step 1: Get code hash if not provided (matches SecretJS ComputeQuerier.queryContract line 176)
        if (TextUtils.isEmpty(codeHash)) {
            Log.i(TAG, "Code hash not provided, fetching from network");
            codeHash = fetchContractCodeHash(lcdUrl, contractAddress);
        }
        
        // Clean code hash (remove 0x prefix, lowercase)
        codeHash = codeHash.replace("0x", "").toLowerCase();
        Log.i(TAG, "Using code hash: " + codeHash);
        
        // Step 2: Encrypt query message (matches SecretJS ComputeQuerier.queryContract line 182)
        byte[] encryptedQuery = cryptoService.encryptContractMessage(codeHash, queryJson.toString(), mnemonic);
        Log.i(TAG, "Query encrypted, length: " + encryptedQuery.length + " bytes");
        
        // Extract nonce for decryption (first 32 bytes of encrypted message)
        byte[] nonce = new byte[32];
        System.arraycopy(encryptedQuery, 0, nonce, 0, 32);
        
        // Step 3: Send encrypted query to Secret Network (matches SecretJS Query.QuerySecretContract)
        String encryptedResult = sendEncryptedQuery(lcdUrl, contractAddress, encryptedQuery);
        
        // Step 4: Decrypt response (matches SecretJS ComputeQuerier.queryContract line 197)
        JSONObject result = decryptQueryResult(encryptedResult, nonce, mnemonic);
        
        Log.i(TAG, "Native query completed successfully");
        return result;
    }
    
    /**
     * Fetch contract code hash from the network
     */
    private String fetchContractCodeHash(String lcdUrl, String contractAddress) throws Exception {
        String url = joinUrl(lcdUrl, "/compute/v1beta1/code_hash/by_contract_address/" + contractAddress);
        Log.i(TAG, "Fetching code hash from: " + url);
        
        String response = httpGet(url);
        JSONObject obj = new JSONObject(response);
        
        if (!obj.has("code_hash")) {
            throw new Exception("No code_hash in response: " + response);
        }
        
        String codeHash = obj.getString("code_hash");
        Log.i(TAG, "Retrieved code hash: " + codeHash);
        return codeHash;
    }
    
    /**
     * Send encrypted query to Secret Network compute endpoint
     * Uses GET method with query parameter like SecretJS does
     */
    private String sendEncryptedQuery(String lcdUrl, String contractAddress, byte[] encryptedQuery) throws Exception {
        // SecretJS uses GET method with query parameter (matches grpc_gateway/secret/compute/v1beta1/query.pb.ts:105)
        String encryptedQueryBase64 = Base64.encodeToString(encryptedQuery, Base64.NO_WRAP);
        String url = joinUrl(lcdUrl, "/compute/v1beta1/query/" + contractAddress + "?query=" + 
                            java.net.URLEncoder.encode(encryptedQueryBase64, "UTF-8"));
        Log.i(TAG, "Sending encrypted query to: " + url);
        
        String response = httpGet(url);
        Log.i(TAG, "Received encrypted response from network");
        
        // Parse response to extract encrypted data field
        JSONObject responseObj = new JSONObject(response);
        if (!responseObj.has("data")) {
            throw new Exception("No data field in query response: " + response);
        }
        
        return responseObj.getString("data");
    }
    
    /**
     * Decrypt query result using the same nonce from encryption
     * Matches SecretJS flow exactly: JSON.parse(fromUtf8(fromBase64(fromUtf8(decryptedBase64Result))))
     */
    private JSONObject decryptQueryResult(String encryptedResultBase64, byte[] nonce, String mnemonic) throws Exception {
        Log.i(TAG, "Decrypting query result using SecretJS-compatible flow");
        Log.i(TAG, "Encrypted result base64: " + encryptedResultBase64);
        
        // Step 1: Decode base64 encrypted result from network (fromBase64)
        byte[] encryptedResult = Base64.decode(encryptedResultBase64, Base64.NO_WRAP);
        Log.i(TAG, "Decoded encrypted result length: " + encryptedResult.length + " bytes");
        
        try {
            // Step 2: Decrypt using SecretCryptoService (matches SecretJS decrypt() method)
            byte[] decryptedBytes = cryptoService.decryptQueryResponse(encryptedResult, nonce, mnemonic);
            Log.i(TAG, "Decrypted " + decryptedBytes.length + " bytes from response");
            
            // Step 3: Handle SecretJS response decoding flow
            // The decryptedBytes should contain the actual JSON response, but let's check both cases:
            
            // Case 1: Direct JSON (most common)
            String directJson = new String(decryptedBytes, StandardCharsets.UTF_8);
            Log.i(TAG, "Trying direct JSON: " + directJson);
            
            try {
                // Handle empty results (Secret Network can return empty bytes)
                if (directJson.trim().isEmpty()) {
                    Log.i(TAG, "Empty result from contract, returning empty JSON object");
                    return new JSONObject("{}");
                }
                
                return new JSONObject(directJson);
            } catch (Exception e1) {
                Log.w(TAG, "Direct JSON parse failed, trying base64 decode: " + e1.getMessage());
                
                // Case 2: Base64-encoded JSON (matches complex SecretJS flow)
                try {
                    byte[] finalJsonBytes = Base64.decode(directJson, Base64.NO_WRAP);
                    String finalJson = new String(finalJsonBytes, StandardCharsets.UTF_8);
                    Log.i(TAG, "Base64-decoded JSON: " + finalJson);
                    
                    if (finalJson.trim().isEmpty()) {
                        return new JSONObject("{}");
                    }
                    
                    return new JSONObject(finalJson);
                } catch (Exception e2) {
                    Log.e(TAG, "Both direct and base64-decoded JSON parsing failed");
                    throw new Exception("Failed to parse decrypted response as JSON: direct=" + e1.getMessage() + ", base64=" + e2.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "SecretJS-style decryption failed, checking for encrypted errors: " + e.getMessage());
            
            // Check if this is an encrypted error message (matches SecretJS error handling)
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("encrypted:")) {
                Log.i(TAG, "Found encrypted error in message, attempting to decrypt");
                // TODO: Implement encrypted error decryption like SecretJS does
                // For now, return the raw error
                JSONObject errorResult = new JSONObject();
                errorResult.put("error", "Encrypted error (decryption not implemented)");
                errorResult.put("raw_error", errorMessage);
                return errorResult;
            }
            
            // Fallback: try to parse as plaintext JSON (some endpoints might return unencrypted data)
            try {
                String resultString = new String(encryptedResult, StandardCharsets.UTF_8);
                Log.i(TAG, "Trying as plaintext: " + resultString);
                return new JSONObject(resultString);
            } catch (Exception e2) {
                Log.e(TAG, "All decryption attempts failed", e2);
                
                // Return detailed error information
                JSONObject errorResult = new JSONObject();
                errorResult.put("error", "Failed to decrypt or parse response");
                errorResult.put("decryption_error", e.getMessage());
                errorResult.put("plaintext_error", e2.getMessage());
                errorResult.put("raw_data", encryptedResultBase64);
                return errorResult;
            }
        }
    }
    
    // HTTP utility methods
    
    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SecretQuery/1.0");
        
        int responseCode = conn.getResponseCode();
        
        InputStream inputStream;
        if (responseCode >= 200 && responseCode < 300) {
            inputStream = conn.getInputStream();
        } else {
            inputStream = conn.getErrorStream();
        }
        
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        
        String response = result.toString("UTF-8");
        
        if (responseCode >= 400) {
            throw new Exception("HTTP " + responseCode + ": " + response);
        }
        
        return response;
    }
    
    private String httpPostJson(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SecretQuery/1.0");
        conn.setDoOutput(true);
        
        // Send request body
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        
        int responseCode = conn.getResponseCode();
        
        InputStream inputStream;
        if (responseCode >= 200 && responseCode < 300) {
            inputStream = conn.getInputStream();
        } else {
            inputStream = conn.getErrorStream();
        }
        
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        
        String response = result.toString("UTF-8");
        
        if (responseCode >= 400) {
            throw new Exception("HTTP " + responseCode + ": " + response);
        }
        
        return response;
    }
    
    private String joinUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        } else {
            return base + path;
        }
    }
}