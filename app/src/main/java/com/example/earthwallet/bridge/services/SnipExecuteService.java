package com.example.earthwallet.bridge.services;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

/**
 * Service for executing SNIP-20/SNIP-721 token transactions
 * Extracted from SnipExecuteActivity to work with TransactionActivity
 * 
 * Handles SNIP token execution using the "send" message format:
 * {
 *   "send": {
 *     "recipient": "contract_address",
 *     "code_hash": "recipient_hash", 
 *     "amount": "amount_string",
 *     "msg": "base64_encoded_message"
 *   }
 * }
 */
public class SnipExecuteService {
    private static final String TAG = "SnipExecuteService";
    
    public static String[] execute(Context context, Intent intent) throws Exception {
        // Parse intent parameters
        String tokenContract = intent.getStringExtra("token_contract");
        String tokenHash = intent.getStringExtra("token_hash");
        String recipient = intent.getStringExtra("recipient");
        String recipientHash = intent.getStringExtra("recipient_hash");
        String amount = intent.getStringExtra("amount");
        String messageJson = intent.getStringExtra("message_json");
        
        // Validate required parameters
        if (TextUtils.isEmpty(tokenContract)) {
            throw new Exception("Token contract is required");
        }
        if (TextUtils.isEmpty(tokenHash)) {
            throw new Exception("Token hash is required");
        }
        if (TextUtils.isEmpty(recipient)) {
            throw new Exception("Recipient is required");
        }
        if (TextUtils.isEmpty(amount)) {
            throw new Exception("Amount is required");
        }
        if (TextUtils.isEmpty(messageJson)) {
            throw new Exception("Message JSON is required");
        }
        
        // Build SNIP send message
        String snipMessage = buildSnipSendMessage(recipient, recipientHash, amount, messageJson);
        
        // Create intent for SecretExecuteService
        Intent executeIntent = new Intent();
        executeIntent.putExtra("contract_address", tokenContract);
        executeIntent.putExtra("code_hash", tokenHash);
        executeIntent.putExtra("execute_json", snipMessage);
        
        // Delegate to SecretExecuteService
        return SecretExecuteService.execute(context, executeIntent);
    }
    
    private static String buildSnipSendMessage(String recipient, String recipientHash, 
                                              String amount, String messageJson) throws Exception {
        try {
            Log.i(TAG, "Building SNIP send message");
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
            
            String result = sendMsg.toString();
            Log.i(TAG, "SNIP send message: " + result);
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to build SNIP message", e);
            throw new Exception("Failed to build SNIP message: " + e.getMessage());
        }
    }
}