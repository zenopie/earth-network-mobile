package com.example.earthwallet.bridge.services;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.wallet.utils.WalletCrypto;
import com.example.earthwallet.Constants;

import org.bitcoinj.core.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Service for executing Secret Network contract transactions
 * Extracted from SecretExecuteActivity to work with TransactionActivity
 */
public class SecretExecuteService {
    private static final String TAG = "SecretExecuteService";
    private static final String PREF_FILE = "secret_wallet_prefs";
    
    public static String[] execute(Context context, Intent intent) throws Exception {
        // Parse intent parameters
        TransactionParams params = parseIntentParameters(intent);
        if (!validateParameters(params)) {
            throw new Exception("Invalid transaction parameters");
        }
        
        // Initialize services
        WalletCrypto.initialize(context);
        SharedPreferences securePrefs = createSecurePrefs(context);
        SecretNetworkService networkService = new SecretNetworkService();
        SecretCryptoService cryptoService = new SecretCryptoService();
        SecretProtobufService protobufService = new SecretProtobufService();
        
        // Get wallet information
        String mnemonic = getSelectedMnemonic(securePrefs);
        if (TextUtils.isEmpty(mnemonic)) {
            throw new Exception("No wallet mnemonic found");
        }
        
        ECKey walletKey = WalletCrypto.deriveKeyFromMnemonic(mnemonic);
        String senderAddress = WalletCrypto.getAddress(walletKey);

        // Fetch chain and account information
        String lcdUrl = Constants.DEFAULT_LCD_URL;
        String chainId = networkService.fetchChainId(lcdUrl);
        JSONObject accountData = networkService.fetchAccount(lcdUrl, senderAddress);
        if (accountData == null) {
            throw new Exception("Account not found: " + senderAddress);
        }
        
        String[] accountFields = networkService.parseAccountFields(accountData);
        String accountNumber = accountFields[0];
        String sequence = accountFields[1];

        // Encrypt contract message
        byte[] encryptedMessage = cryptoService.encryptContractMessage(
            params.codeHash, params.execJson, mnemonic);

        // Build protobuf transaction
        byte[] txBytes = protobufService.buildTransaction(senderAddress, params.contractAddr, 
                                                        params.codeHash, encryptedMessage, 
                                                        params.funds, params.memo, accountNumber, 
                                                        sequence, chainId, walletKey);

        // Broadcast transaction
        String response = networkService.broadcastTransactionModern(lcdUrl, txBytes);

        // Enhance response with detailed results
        String enhancedResponse = enhanceTransactionResponse(response, lcdUrl, networkService);
        
        // Validate response
        validateTransactionResponse(enhancedResponse);
        
        return new String[]{enhancedResponse, senderAddress};
    }
    
    private static TransactionParams parseIntentParameters(Intent intent) {
        return new TransactionParams(
            intent.getStringExtra("contract_address"),
            intent.getStringExtra("code_hash"),
            intent.getStringExtra("execute_json"),
            intent.getStringExtra("funds"),
            intent.getStringExtra("memo"),
            intent.getStringExtra("contract_encryption_key_b64")
        );
    }
    
    private static boolean validateParameters(TransactionParams params) {
        return !TextUtils.isEmpty(params.contractAddr) && !TextUtils.isEmpty(params.execJson);
    }
    
    private static String enhanceTransactionResponse(String initialResponse, String lcdUrl, SecretNetworkService networkService) {
        try {
            JSONObject response = new JSONObject(initialResponse);
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                String txHash = txResponse.optString("txhash", "");

                if (code == 0 && !txHash.isEmpty()) {
                    try {
                        Thread.sleep(2000);
                        String detailedResponse = networkService.queryTransactionByHash(lcdUrl, txHash);
                        if (detailedResponse != null) {
                            return detailedResponse;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to enhance response: " + e.getMessage());
        }
        
        return initialResponse;
    }
    
    private static void validateTransactionResponse(String response) throws Exception {
        JSONObject responseObj = new JSONObject(response);
        int code = -1;
        
        if (responseObj.has("tx_response")) {
            JSONObject txResponse = responseObj.getJSONObject("tx_response");
            code = txResponse.optInt("code", -1);
            String rawLog = txResponse.optString("raw_log", "");
            
            if (code != 0) {
                throw new Exception("Transaction failed: Code " + code + ". " + rawLog);
            }
        } else {
            throw new Exception("Invalid transaction response format");
        }
    }
    
    private static String getSelectedMnemonic(SharedPreferences securePrefs) {
        try {
            // Use multi-wallet system (wallets array + selected_wallet_index)
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int selectedIndex = securePrefs.getInt("selected_wallet_index", -1);
            
            if (arr.length() > 0) {
                if (selectedIndex >= 0 && selectedIndex < arr.length()) {
                    return arr.getJSONObject(selectedIndex).optString("mnemonic", "");
                } else {
                    // Default to first wallet if invalid selection
                    return arr.getJSONObject(0).optString("mnemonic", "");
                }
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get mnemonic from multi-wallet system", e);
            return null;
        }
    }
    
    private static SharedPreferences createSecurePrefs(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create secure preferences", e);
            throw new RuntimeException("Secure preferences initialization failed", e);
        }
    }
    
    private static class TransactionParams {
        final String contractAddr;
        final String codeHash;
        final String execJson;
        final String funds;
        final String memo;
        final String contractPubKeyB64;

        TransactionParams(String contractAddr, String codeHash, String execJson, 
                         String funds, String memo, String contractPubKeyB64) {
            this.contractAddr = contractAddr;
            this.codeHash = codeHash;
            this.execJson = execJson;
            this.funds = funds != null ? funds : "";
            this.memo = memo != null ? memo : "";
            this.contractPubKeyB64 = contractPubKeyB64;
        }
    }
}