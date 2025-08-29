package com.example.passportscanner.bridge;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * SecretNetworkService
 * 
 * Handles all Secret Network LCD API interactions:
 * - Chain information queries
 * - Account data fetching
 * - Transaction broadcasting
 * - Transaction status queries
 */
public class SecretNetworkService {
    
    private static final String TAG = "SecretNetworkService";
    
    // Transaction query constants
    private static final int TRANSACTION_QUERY_DELAY_MS = 5000; // 5 seconds initial delay
    private static final int TRANSACTION_QUERY_TIMEOUT_MS = 60000; // 60 seconds total timeout
    private static final int TRANSACTION_QUERY_MAX_RETRIES = 5; // Maximum retry attempts
    private static final int TRANSACTION_QUERY_RETRY_DELAY_MS = 3000; // 3 seconds between retries


    /**
     * Fetches the chain ID from the LCD endpoint
     */
    public String fetchChainId(String lcdUrl) throws Exception {
        Log.d(TAG, "Fetching chain ID from: " + lcdUrl);
        
        String url = joinUrl(lcdUrl, "/cosmos/base/tendermint/v1beta1/node_info");
        String response = httpGet(url);
        
        JSONObject obj = new JSONObject(response);
        JSONObject defaultNodeInfo = obj.getJSONObject("default_node_info");
        String chainId = defaultNodeInfo.getString("network");
        
        Log.i(TAG, "Retrieved chain ID: " + chainId);
        return chainId;
    }

    /**
     * Fetches account information from the LCD endpoint
     */
    public JSONObject fetchAccount(String lcdUrl, String address) throws Exception {
        Log.d(TAG, "Fetching account info for: " + address);
        
        String url = joinUrl(lcdUrl, "/cosmos/auth/v1beta1/accounts/" + address);
        
        try {
            String response = httpGet(url);
            JSONObject obj = new JSONObject(response);
            
            if (obj.has("account")) {
                JSONObject account = obj.getJSONObject("account");
                Log.i(TAG, "Successfully retrieved account data");
                return account;
            } else {
                Log.w(TAG, "No account field in response");
                return null;
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                Log.w(TAG, "Account not found (404) - may not exist or be funded");
                return null;
            }
            throw e;
        }
    }

    /**
     * Parses account number and sequence from account response
     */
    public String[] parseAccountFields(JSONObject account) throws Exception {
        String accountNumber = "0";
        String sequence = "0";
        
        // Try different account formats
        if (account.has("account_number")) {
            accountNumber = account.getString("account_number");
        }
        if (account.has("sequence")) {
            sequence = account.getString("sequence");
        }
        
        // Handle base_account nested structure
        if (account.has("base_account")) {
            JSONObject baseAccount = account.getJSONObject("base_account");
            if (baseAccount.has("account_number")) {
                accountNumber = baseAccount.getString("account_number");
            }
            if (baseAccount.has("sequence")) {
                sequence = baseAccount.getString("sequence");
            }
        }
        
        Log.d(TAG, "Parsed account - Number: " + accountNumber + ", Sequence: " + sequence);
        return new String[]{accountNumber, sequence};
    }

    /**
     * Fetches encryption key for Secret Network contracts (matches SecretJS behavior)
     * SecretJS uses the consensus IO public key for all contracts on mainnet
     */
    public String fetchContractEncryptionKey(String lcdUrl, String contractAddr) throws Exception {
        Log.d(TAG, "Getting consensus IO public key (matches SecretJS behavior)");
        
        // SecretJS hardcodes the mainnet consensus IO key instead of fetching contract-specific keys
        // This is the exact same key used in SecretJS encryption.ts
        String consensusIoKey = "79++5YOHfm0SwhlpUDClv7cuCjq9xBZlWqSjDJWkRG8=";
        
        Log.i(TAG, "Using hardcoded mainnet consensus IO key (matches SecretJS exactly)");
        return consensusIoKey;
    }

    /**
     * Broadcasts a transaction using the modern endpoint
     */
    public String broadcastTransactionModern(String lcdUrl, byte[] txBytes) throws Exception {
        String modernUrl = joinUrl(lcdUrl, "/cosmos/tx/v1beta1/txs");
        Log.i(TAG, "Broadcasting to modern endpoint: " + modernUrl);
        
        String txBytesBase64 = Base64.encodeToString(txBytes, Base64.NO_WRAP);
        
        JSONObject txBody = new JSONObject();
        txBody.put("tx_bytes", txBytesBase64);
        txBody.put("mode", "BROADCAST_MODE_SYNC");
        
        Log.d(TAG, "Transaction size: " + txBytes.length + " bytes");
        return httpPostJson(modernUrl, txBody.toString());
    }

    /**
     * Broadcasts a transaction using the legacy endpoint
     */
    public String broadcastTransactionLegacy(String lcdUrl, JSONObject signedTx) throws Exception {
        String legacyUrl = joinUrl(lcdUrl, "/txs");
        Log.i(TAG, "Broadcasting to legacy endpoint: " + legacyUrl);
        
        JSONObject txBody = new JSONObject();
        txBody.put("tx", signedTx);
        txBody.put("mode", "sync");
        
        return httpPostJson(legacyUrl, txBody.toString());
    }

    /**
     * Queries transaction details by hash with retry logic
     */
    public String queryTransactionByHash(String lcdUrl, String txHash) {
        Log.i(TAG, "Querying transaction: " + txHash);
        
        long startTime = System.currentTimeMillis();
        int retryCount = 0;
        
        while (retryCount < TRANSACTION_QUERY_MAX_RETRIES && 
               (System.currentTimeMillis() - startTime) < TRANSACTION_QUERY_TIMEOUT_MS) {
            
            try {
                String queryUrl = joinUrl(lcdUrl, "/cosmos/tx/v1beta1/txs/" + txHash);
                Log.d(TAG, "Query attempt " + (retryCount + 1) + "/" + TRANSACTION_QUERY_MAX_RETRIES + 
                          " for txhash: " + txHash);
                
                String response = httpGet(queryUrl);
                
                // Validate response has transaction data
                JSONObject responseObj = new JSONObject(response);
                if (responseObj.has("tx_response")) {
                    JSONObject txResponse = responseObj.getJSONObject("tx_response");
                    
                    // Check if we have execution data
                    String rawLog = txResponse.optString("raw_log", "");
                    JSONArray logs = txResponse.optJSONArray("logs");
                    String data = txResponse.optString("data", "");
                    JSONArray events = txResponse.optJSONArray("events");
                    
                    if (rawLog.length() > 0 || (logs != null && logs.length() > 0) ||
                        data.length() > 0 || (events != null && events.length() > 0)) {
                        Log.i(TAG, "Transaction query successful with execution data");
                        return response;
                    } else {
                        Log.d(TAG, "Transaction found but no execution data yet");
                    }
                } else {
                    Log.w(TAG, "Response missing tx_response field");
                }
                
            } catch (Exception e) {
                Log.w(TAG, "Query attempt " + (retryCount + 1) + " failed: " + e.getMessage());
                
                if (e.getMessage() != null && e.getMessage().contains("404")) {
                    Log.d(TAG, "Transaction not found yet (404) - will retry");
                }
            }
            
            retryCount++;
            
            if (retryCount < TRANSACTION_QUERY_MAX_RETRIES) {
                try {
                    Log.d(TAG, "Waiting " + TRANSACTION_QUERY_RETRY_DELAY_MS + "ms before retry...");
                    Thread.sleep(TRANSACTION_QUERY_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Query retry sleep interrupted");
                    break;
                }
            }
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        Log.w(TAG, "Transaction query timed out after " + totalTime + "ms and " + 
                   retryCount + " attempts");
        return null;
    }

    // HTTP utility methods

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SecretExecuteNative/1.0");
        
        int responseCode = conn.getResponseCode();
        Log.d(TAG, "HTTP GET " + urlStr + " -> " + responseCode);
        
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
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SecretExecuteNative/1.0");
        conn.setDoOutput(true);
        
        // Send request body
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        
        int responseCode = conn.getResponseCode();
        Log.d(TAG, "HTTP POST " + urlStr + " -> " + responseCode + 
                   " (body length: " + jsonBody.length() + ")");
        
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