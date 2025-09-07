package com.example.earthwallet.bridge.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.earthwallet.wallet.services.SecretWallet;

import org.json.JSONObject;

/**
 * SnipExecuteActivity
 *
 * Handles SNIP-20/SNIP-721 token execution using the "send" message format.
 * This matches the React web app's snip() function which uses:
 * {
 *   "send": {
 *     "recipient": "contract_address",
 *     "code_hash": "recipient_hash", 
 *     "amount": "amount_string",
 *     "msg": "base64_encoded_message"
 *   }
 * }
 *
 * Intent API:
 * - Input (Intent extras):
 *   - EXTRA_TOKEN_CONTRACT     (String, required) - SNIP token contract address
 *   - EXTRA_TOKEN_HASH         (String, required) - SNIP token contract hash
 *   - EXTRA_RECIPIENT          (String, required) - Recipient contract address
 *   - EXTRA_RECIPIENT_HASH     (String, required) - Recipient contract hash
 *   - EXTRA_AMOUNT             (String, required) - Amount to send
 *   - EXTRA_MESSAGE_JSON       (String, required) - JSON message to encode in base64
 *   - EXTRA_GAS_LIMIT          (Long, optional)   - Gas limit (default: 1000000)
 *
 * - Result (onActivityResult):
 *   - RESULT_OK with:
 *       - EXTRA_RESULT_JSON    (String) - JSON string: {"success":true,"result":...}
 *       - EXTRA_TX_HASH        (String) - Transaction hash if available
 *     or
 *   - RESULT_CANCELED with:
 *       - EXTRA_ERROR          (String) - error string
 */
public class SnipExecuteActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_TOKEN_CONTRACT = "com.example.earthwallet.EXTRA_TOKEN_CONTRACT";
    public static final String EXTRA_TOKEN_HASH = "com.example.earthwallet.EXTRA_TOKEN_HASH";
    public static final String EXTRA_RECIPIENT = "com.example.earthwallet.EXTRA_RECIPIENT";
    public static final String EXTRA_RECIPIENT_HASH = "com.example.earthwallet.EXTRA_RECIPIENT_HASH";
    public static final String EXTRA_AMOUNT = "com.example.earthwallet.EXTRA_AMOUNT";
    public static final String EXTRA_MESSAGE_JSON = "com.example.earthwallet.EXTRA_MESSAGE_JSON";
    public static final String EXTRA_GAS_LIMIT = "com.example.earthwallet.EXTRA_GAS_LIMIT";

    // Result extras
    public static final String EXTRA_RESULT_JSON = "com.example.earthwallet.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.earthwallet.EXTRA_ERROR";
    public static final String EXTRA_TX_HASH = "com.example.earthwallet.EXTRA_TX_HASH";

    private static final String TAG = "SnipExecuteActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final long DEFAULT_GAS_LIMIT = 1000000L;

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

        String tokenContract = intent.getStringExtra(EXTRA_TOKEN_CONTRACT);
        String tokenHash = intent.getStringExtra(EXTRA_TOKEN_HASH);
        String recipient = intent.getStringExtra(EXTRA_RECIPIENT);
        String recipientHash = intent.getStringExtra(EXTRA_RECIPIENT_HASH);
        String amount = intent.getStringExtra(EXTRA_AMOUNT);
        String messageJson = intent.getStringExtra(EXTRA_MESSAGE_JSON);
        long gasLimit = intent.getLongExtra(EXTRA_GAS_LIMIT, DEFAULT_GAS_LIMIT);

        // Validate required parameters
        if (TextUtils.isEmpty(tokenContract)) {
            finishWithError("Token contract is required");
            return;
        }
        if (TextUtils.isEmpty(tokenHash)) {
            finishWithError("Token hash is required");
            return;
        }
        if (TextUtils.isEmpty(recipient)) {
            finishWithError("Recipient is required");
            return;
        }
        // Recipient hash is optional - only required for contract-to-contract interactions
        // For wallet-to-wallet transfers, recipient hash can be empty
        if (TextUtils.isEmpty(amount)) {
            finishWithError("Amount is required");
            return;
        }
        if (TextUtils.isEmpty(messageJson)) {
            finishWithError("Message JSON is required");
            return;
        }

        // Build SNIP send message and delegate to SecretExecuteActivity
        buildSnipMessageAndDelegate(tokenContract, tokenHash, recipient, recipientHash, amount, messageJson, gasLimit);
    }

    private void buildSnipMessageAndDelegate(String tokenContract, String tokenHash, String recipient, 
                                           String recipientHash, String amount, String messageJson, long gasLimit) {
        try {
            Log.i(TAG, "Building SNIP send message");
            Log.i(TAG, "Token contract: " + tokenContract);
            Log.i(TAG, "Recipient: " + recipient);
            Log.i(TAG, "Amount: " + amount);
            Log.i(TAG, "Message JSON: " + messageJson);
            
            // Encode message JSON to base64
            String encodedMessage = Base64.encodeToString(messageJson.getBytes(), Base64.NO_WRAP);
            Log.i(TAG, "Encoded message: " + encodedMessage);
            
            // Build SNIP send message
            JSONObject sendMsg = new JSONObject();
            JSONObject sendData = new JSONObject();
            sendData.put("recipient", recipient);
            if (!TextUtils.isEmpty(recipientHash)) {
                sendData.put("code_hash", recipientHash);
            }
            sendData.put("amount", amount);
            sendData.put("msg", encodedMessage);
            sendMsg.put("send", sendData);
            
            Log.i(TAG, "SNIP send message: " + sendMsg.toString());
            
            // Delegate to SecretExecuteActivity
            Intent executeIntent = new Intent(this, SecretExecuteActivity.class);
            executeIntent.putExtra(SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, tokenContract);
            executeIntent.putExtra(SecretExecuteActivity.EXTRA_CODE_HASH, tokenHash);
            executeIntent.putExtra(SecretExecuteActivity.EXTRA_EXECUTE_JSON, sendMsg.toString());
            
            startActivityForResult(executeIntent, 1001);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to build SNIP message", e);
            finishWithError("Failed to build SNIP message: " + e.getMessage());
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 1001) {
            // Forward the result from SecretExecuteActivity
            Log.d(TAG, "Received result from SecretExecuteActivity - resultCode: " + resultCode);
            if (resultCode == RESULT_OK && data != null) {
                String json = data.getStringExtra(SecretExecuteActivity.EXTRA_RESULT_JSON);
                String txHash = data.getStringExtra(SecretExecuteActivity.EXTRA_SENDER_ADDRESS); // Might contain tx hash
                Log.d(TAG, "SecretExecuteActivity result JSON: " + json);
                Log.d(TAG, "SecretExecuteActivity txHash/senderAddress: " + txHash);
                finishWithSuccess(json, txHash);
            } else {
                String error = data != null ? data.getStringExtra(SecretExecuteActivity.EXTRA_ERROR) : "Unknown error";
                Log.e(TAG, "SecretExecuteActivity failed with error: " + error);
                finishWithError(error);
            }
        }
    }


    private void finishWithSuccess(String json, String txHash) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_JSON, json != null ? json : "{}");
            if (!TextUtils.isEmpty(txHash)) {
                data.putExtra(EXTRA_TX_HASH, txHash);
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

}