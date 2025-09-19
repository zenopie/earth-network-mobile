package com.example.earthwallet.bridge.services;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.wallet.utils.WalletCrypto;
import com.example.earthwallet.Constants;

import org.bitcoinj.core.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Service for multi-message transactions
 * Builds a single transaction with multiple messages instead of sequential transactions
 */
public class MultiMessageExecuteService {
    private static final String TAG = "MultiMessageExecuteService";
    private static final String PREF_FILE = "secret_wallet_prefs";

    public static String[] execute(Context context, Intent intent) throws Exception {
        Log.i(TAG, "Starting multi-message transaction execution");

        // Parse intent parameters
        String messagesJson = intent.getStringExtra("messages");
        String memo = intent.getStringExtra("memo");

        if (TextUtils.isEmpty(messagesJson)) {
            throw new Exception("No messages provided for multi-message transaction");
        }

        // Parse messages array
        JSONArray messages = new JSONArray(messagesJson);
        if (messages.length() == 0) {
            throw new Exception("Empty messages array provided");
        }

        Log.i(TAG, "Processing " + messages.length() + " messages in single transaction");

        // Initialize services
        WalletCrypto.initialize(context);
        SharedPreferences securePrefs = createSecurePrefs(context);
        // Use static methods from SecretNetworkService Kotlin object
        // Use static methods from SecretCryptoService Kotlin object
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
        String chainId = SecretNetworkService.fetchChainIdSync(lcdUrl);
        JSONObject accountData = SecretNetworkService.fetchAccountSync(lcdUrl, senderAddress);
        if (accountData == null) {
            throw new Exception("Account not found: " + senderAddress);
        }

        String[] accountFields = SecretNetworkService.parseAccountFieldsAsArray(accountData);
        String accountNumber = accountFields[0];
        String sequence = accountFields[1];

        // Prepare encrypted messages for all contract executions
        JSONArray encryptedMessages = new JSONArray();

        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String contract = message.getString("contract");
            String codeHash = message.getString("code_hash");
            JSONObject msg = message.getJSONObject("msg");

            // Encrypt each contract message
            byte[] encryptedMsg = SecretCryptoService.encryptContractMessageSync(codeHash, msg.toString(), mnemonic);

            // Create encrypted message object for protobuf service
            JSONObject encryptedMessage = new JSONObject();
            encryptedMessage.put("sender", senderAddress);
            encryptedMessage.put("contract", contract);
            encryptedMessage.put("code_hash", codeHash);
            encryptedMessage.put("encrypted_msg", android.util.Base64.encodeToString(encryptedMsg, android.util.Base64.NO_WRAP));
            encryptedMessage.put("sent_funds", message.optJSONArray("sent_funds"));

            encryptedMessages.put(encryptedMessage);
        }

        // Build multi-message protobuf transaction using unified SecretProtobufService
        byte[] txBytes = protobufService.buildMultiMessageTransaction(
            encryptedMessages, memo != null ? memo : "",
            accountNumber, sequence, chainId, walletKey);

        // Broadcast transaction
        String response = SecretNetworkService.broadcastTransactionModernSync(lcdUrl, txBytes);

        // Enhance response with detailed results
        String enhancedResponse = enhanceTransactionResponse(response, lcdUrl);

        // Validate response
        validateTransactionResponse(enhancedResponse);

        Log.i(TAG, "Multi-message transaction completed successfully");
        return new String[]{enhancedResponse, senderAddress};
    }

    private static String enhanceTransactionResponse(String initialResponse, String lcdUrl) {
        try {
            JSONObject response = new JSONObject(initialResponse);
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                String txHash = txResponse.optString("txhash", "");

                if (code == 0 && !txHash.isEmpty()) {
                    try {
                        Thread.sleep(2000);
                        String detailedResponse = SecretNetworkService.queryTransactionByHashSync(lcdUrl, txHash);
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
                throw new Exception("Multi-message transaction failed: Code " + code + ". " + rawLog);
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
}