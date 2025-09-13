package com.example.earthwallet.bridge.activities;

import com.example.earthwallet.bridge.services.SecretCryptoService;
import com.example.earthwallet.bridge.services.SecretNetworkService;
import com.example.earthwallet.bridge.services.SecretProtobufService;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.ui.components.TransactionHandler;
import com.example.earthwallet.ui.components.TransactionConfirmationDialog;

import org.bitcoinj.core.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SecretExecuteActivity
 *
 * Handles Secret Network contract execution using integrated crypto and network services.
 * Builds protobuf transactions for secure contract interaction.
 */
public class SecretExecuteActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.earthwallet.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.earthwallet.EXTRA_CODE_HASH";
    public static final String EXTRA_EXECUTE_JSON = "com.example.earthwallet.EXTRA_EXECUTE_JSON";
    public static final String EXTRA_FUNDS = "com.example.earthwallet.EXTRA_FUNDS";
    public static final String EXTRA_MEMO = "com.example.earthwallet.EXTRA_MEMO";
    public static final String EXTRA_LCD_URL = "com.example.earthwallet.EXTRA_LCD_URL";
    public static final String EXTRA_CONTRACT_ENCRYPTION_KEY_B64 = "com.example.earthwallet.EXTRA_CONTRACT_ENCRYPTION_KEY_B64";

    // Result extras
    public static final String EXTRA_RESULT_JSON = "com.example.earthwallet.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.earthwallet.EXTRA_ERROR";
    public static final String EXTRA_SENDER_ADDRESS = "com.example.earthwallet.EXTRA_SENDER_ADDRESS";

    private static final String TAG = "SecretExecuteActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";

    private SharedPreferences securePrefs;
    private SecretNetworkService networkService;
    private SecretCryptoService cryptoService;
    private SecretProtobufService protobufService;
    private TransactionHandler transactionHandler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            initializeServices();
            executeTransaction();
        } catch (Exception e) {
            Log.e(TAG, "Initialization failed", e);
            finishWithError("Initialization failed: " + e.getMessage());
        }
    }

    private void initializeServices() throws Exception {
        SecretWallet.initialize(this);
        securePrefs = createSecurePrefs(this);
        networkService = new SecretNetworkService();
        cryptoService = new SecretCryptoService();
        protobufService = new SecretProtobufService();
        transactionHandler = new TransactionHandler(this);
    }


    private void executeTransaction() {
        TransactionParams params = parseIntentParameters();
        if (!validateParameters(params)) {
            return;
        }

        String mnemonic = getSelectedMnemonic();
        if (TextUtils.isEmpty(mnemonic)) {
            finishWithError("No wallet mnemonic found");
            return;
        }

        // Use TransactionHandler for confirmation and execution
        executeWithTransactionHandler(params, mnemonic);
    }

    private TransactionParams parseIntentParameters() {
        Intent intent = getIntent();
        return new TransactionParams(
            intent.getStringExtra(EXTRA_CONTRACT_ADDRESS),
            intent.getStringExtra(EXTRA_CODE_HASH),
            intent.getStringExtra(EXTRA_EXECUTE_JSON),
            intent.getStringExtra(EXTRA_FUNDS),
            intent.getStringExtra(EXTRA_MEMO),
            intent.getStringExtra(EXTRA_CONTRACT_ENCRYPTION_KEY_B64)
        );
    }

    private boolean validateParameters(TransactionParams params) {
        if (TextUtils.isEmpty(params.contractAddr) || TextUtils.isEmpty(params.execJson)) {
            finishWithError("Missing required parameters");
            return false;
        }
        return true;
    }

    private void executeWithTransactionHandler(TransactionParams params, String mnemonic) {
        // Create transaction details for the confirmation dialog
        TransactionConfirmationDialog.TransactionDetails details = new TransactionConfirmationDialog.TransactionDetails(
            params.contractAddr,
            formatJsonForDisplay(params.execJson)
        ).setContractLabel(isSnipMessage(params.execJson) ? "Token Contract:" : "Contract:")
         .setFunds(params.funds)
         .setMemo(params.memo);
        
        // Use TransactionHandler to execute the transaction
        transactionHandler.executeTransaction(details, () -> {
            try {
                performTransaction(params, mnemonic);
            } catch (Exception e) {
                Log.e(TAG, "Transaction failed", e);
                transactionHandler.onTransactionError("Transaction failed: " + e.getMessage());
            }
        }, new TransactionHandler.TransactionResultHandler() {
            @Override
            public void onSuccess(String result, String senderAddress) {
                finishWithSuccess(result, senderAddress);
            }
            
            @Override
            public void onError(String error) {
                finishWithError(error);
            }
        });
    }

    private String truncateAddress(String address) {
        if (TextUtils.isEmpty(address)) return "";
        if (address.length() <= 20) return address;
        return address.substring(0, 10) + "..." + address.substring(address.length() - 6);
    }

    private boolean isSnipMessage(String json) {
        if (TextUtils.isEmpty(json)) return false;
        try {
            JSONObject jsonObj = new JSONObject(json);
            return jsonObj.has("send");
        } catch (Exception e) {
            return false;
        }
    }

    private String formatJsonForDisplay(String json) {
        if (TextUtils.isEmpty(json)) return "";
        try {
            JSONObject jsonObj = new JSONObject(json);
            
            // Check if this is a SNIP message with base64 encoded data
            if (jsonObj.has("send")) {
                JSONObject sendObj = jsonObj.getJSONObject("send");
                if (sendObj.has("msg")) {
                    String encodedMsg = sendObj.getString("msg");
                    try {
                        // Try to decode the base64 message
                        byte[] decoded = Base64.decode(encodedMsg, Base64.NO_WRAP);
                        String decodedMsg = new String(decoded);
                        
                        // Try to parse the decoded message as JSON
                        JSONObject decodedJson = new JSONObject(decodedMsg);
                        
                        // Create a more readable version
                        JSONObject readableObj = new JSONObject();
                        JSONObject readableSend = new JSONObject();
                        readableSend.put("recipient", sendObj.optString("recipient", ""));
                        if (sendObj.has("code_hash")) {
                            readableSend.put("code_hash", sendObj.getString("code_hash"));
                        }
                        readableSend.put("amount", sendObj.optString("amount", ""));
                        readableSend.put("decoded_message", decodedJson);
                        readableObj.put("send", readableSend);
                        
                        return readableObj.toString(2); // Pretty print with 2 space indentation
                    } catch (Exception decodeEx) {
                        // If decoding fails, fall back to original formatting
                        Log.d(TAG, "Failed to decode SNIP message, using original: " + decodeEx.getMessage());
                    }
                }
            }
            
            return jsonObj.toString(2); // Pretty print with 2 space indentation
        } catch (Exception e) {
            return json; // Return original if parsing fails
        }
    }

    private void performTransaction(TransactionParams params, String mnemonic) throws Exception {
        // Get wallet information
        ECKey walletKey = SecretWallet.deriveKeyFromMnemonic(mnemonic);
        String senderAddress = SecretWallet.getAddress(walletKey);

        // Fetch chain and account information
        String lcdUrl = SecretWallet.DEFAULT_LCD_URL;
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
        String enhancedResponse = enhanceTransactionResponse(response, lcdUrl);

        // Show result on UI thread
        runOnUiThread(() -> {
            handleTransactionResult(enhancedResponse, senderAddress);
        });
    }


    private String enhanceTransactionResponse(String initialResponse, String lcdUrl) {
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

    // Utility methods
    private void handleTransactionResult(String enhancedResponse, String senderAddress) {
        try {
            // Parse the response to check success/failure
            JSONObject response = new JSONObject(enhancedResponse);
            int code = -1; // Default to failed
            
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                code = txResponse.optInt("code", -1);
                String rawLog = txResponse.optString("raw_log", "");
                
                if (code == 0) {
                    // Transaction successful - notify TransactionHandler
                    transactionHandler.onTransactionSuccess(enhancedResponse, senderAddress);
                } else {
                    // Transaction failed - notify TransactionHandler
                    transactionHandler.onTransactionError("Transaction failed: Code " + code + ". " + rawLog);
                }
            } else {
                // No tx_response field - treat as error
                transactionHandler.onTransactionError("Invalid transaction response format");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to process transaction response", e);
            transactionHandler.onTransactionError("Failed to process transaction response: " + e.getMessage());
        }
    }

    private String getSelectedMnemonic() {
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

    private void finishWithSuccess(String resultJson, String senderAddress) {
        Intent result = new Intent();
        result.putExtra(EXTRA_RESULT_JSON, resultJson);
        result.putExtra(EXTRA_SENDER_ADDRESS, senderAddress);
        setResult(RESULT_OK, result);
        finish();
    }

    private void finishWithError(String error) {
        Intent result = new Intent();
        result.putExtra(EXTRA_ERROR, error);
        setResult(RESULT_CANCELED, result);
        finish();
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

    @Override
    protected void onDestroy() {
        // Clean up transaction handler before calling super.onDestroy()
        if (transactionHandler != null) {
            try {
                transactionHandler.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error destroying transaction handler", e);
            }
            transactionHandler = null;
        }
        super.onDestroy();
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