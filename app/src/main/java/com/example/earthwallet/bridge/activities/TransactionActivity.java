package com.example.earthwallet.bridge.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.earthwallet.R;
import com.example.earthwallet.ui.components.TransactionConfirmationDialog;
import com.example.earthwallet.ui.components.StatusModal;
import com.example.earthwallet.bridge.services.SecretExecuteService;
import com.example.earthwallet.bridge.services.NativeSendService;
import com.example.earthwallet.bridge.services.MultiMessageExecuteService;
import com.example.earthwallet.bridge.services.SnipExecuteService;
import com.example.earthwallet.bridge.services.PermitSigningService;

/**
 * Universal transaction activity that handles all transaction types
 * Shows confirmation dialog, executes via services, displays success animation
 */
public class TransactionActivity extends AppCompatActivity {
    private static final String TAG = "TransactionActivity";
    
    // Intent extras for transaction details
    public static final String EXTRA_TRANSACTION_TYPE = "transaction_type";
    public static final String EXTRA_TRANSACTION_DETAILS = "transaction_details";
    public static final String EXTRA_CONTRACT_ADDRESS = "contract_address";
    public static final String EXTRA_CODE_HASH = "code_hash";
    public static final String EXTRA_EXECUTE_JSON = "execute_json";
    public static final String EXTRA_FUNDS = "funds";
    public static final String EXTRA_MEMO = "memo";
    public static final String EXTRA_RECIPIENT_ADDRESS = "recipient_address";
    public static final String EXTRA_AMOUNT = "amount";
    public static final String EXTRA_MESSAGES = "messages";
    public static final String EXTRA_TOKEN_CONTRACT = "token_contract";
    public static final String EXTRA_TOKEN_HASH = "token_hash";
    public static final String EXTRA_RECIPIENT_HASH = "recipient_hash";
    public static final String EXTRA_MESSAGE_JSON = "message_json";
    
    // Result extras
    public static final String EXTRA_RESULT_JSON = "result_json";
    public static final String EXTRA_SENDER_ADDRESS = "sender_address";
    public static final String EXTRA_ERROR = "error";
    
    // Transaction types
    public static final String TYPE_SECRET_EXECUTE = "secret_execute";
    public static final String TYPE_NATIVE_SEND = "native_send";
    public static final String TYPE_MULTI_MESSAGE = "multi_message";
    public static final String TYPE_SNIP_EXECUTE = "snip_execute";
    public static final String TYPE_PERMIT_SIGNING = "permit_signing";
    
    private TransactionConfirmationDialog confirmationDialog;
    private StatusModal statusModal;
    private String transactionType;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction);
        
        transactionType = getIntent().getStringExtra(EXTRA_TRANSACTION_TYPE);
        if (transactionType == null) {
            finishWithError("No transaction type specified");
            return;
        }
        
        confirmationDialog = new TransactionConfirmationDialog(this);
        statusModal = new StatusModal(this);
        
        // Start transaction flow
        showConfirmationDialog();
    }
    
    private void showConfirmationDialog() {
        TransactionConfirmationDialog.TransactionDetails details = buildTransactionDetails();
        if (details == null) {
            finishWithError("Invalid transaction details");
            return;
        }
        
        confirmationDialog.show(details, new TransactionConfirmationDialog.OnConfirmationListener() {
            @Override
            public void onConfirmed() {
                // User confirmed - show loading and execute transaction
                statusModal.show(StatusModal.State.LOADING);
                executeTransaction();
            }
            
            @Override
            public void onCancelled() {
                // User cancelled
                finishWithError("Transaction cancelled");
            }
        });
    }
    
    private TransactionConfirmationDialog.TransactionDetails buildTransactionDetails() {
        Intent intent = getIntent();
        
        switch (transactionType) {
            case TYPE_SECRET_EXECUTE:
                String contractAddress = intent.getStringExtra(EXTRA_CONTRACT_ADDRESS);
                String executeJson = intent.getStringExtra(EXTRA_EXECUTE_JSON);
                if (contractAddress == null || executeJson == null) return null;
                
                return new TransactionConfirmationDialog.TransactionDetails(
                    contractAddress,
                    formatJsonForDisplay(executeJson)
                ).setContractLabel(isSnipMessage(executeJson) ? "Token Contract:" : "Contract:")
                 .setFunds(intent.getStringExtra(EXTRA_FUNDS))
                 .setMemo(intent.getStringExtra(EXTRA_MEMO));
                
            case TYPE_NATIVE_SEND:
                String recipient = intent.getStringExtra(EXTRA_RECIPIENT_ADDRESS);
                String amount = intent.getStringExtra(EXTRA_AMOUNT);
                if (recipient == null || amount == null) return null;
                
                return new TransactionConfirmationDialog.TransactionDetails(
                    recipient,
                    "Send " + amount + " SCRT"
                ).setContractLabel("Recipient:");
                
            case TYPE_MULTI_MESSAGE:
                String messages = intent.getStringExtra(EXTRA_MESSAGES);
                if (messages == null) return null;
                
                return new TransactionConfirmationDialog.TransactionDetails(
                    "Multiple Messages",
                    messages
                ).setContractLabel("Messages:");
                
            case TYPE_SNIP_EXECUTE:
                String tokenContract = intent.getStringExtra(EXTRA_TOKEN_CONTRACT);
                String snipRecipient = intent.getStringExtra(EXTRA_RECIPIENT_ADDRESS);
                String snipRecipientHash = intent.getStringExtra(EXTRA_RECIPIENT_HASH);
                String snipAmount = intent.getStringExtra(EXTRA_AMOUNT);
                String messageJson = intent.getStringExtra(EXTRA_MESSAGE_JSON);
                if (tokenContract == null || snipRecipient == null || snipAmount == null || messageJson == null) return null;
                
                try {
                    // Show the SNIP send structure that will be created with the actual message
                    org.json.JSONObject sendMsg = new org.json.JSONObject();
                    org.json.JSONObject sendData = new org.json.JSONObject();
                    sendData.put("recipient", snipRecipient);
                    if (snipRecipientHash != null && !snipRecipientHash.isEmpty()) {
                        sendData.put("code_hash", snipRecipientHash);
                    }
                    sendData.put("amount", snipAmount);
                    sendData.put("msg", messageJson);
                    sendMsg.put("send", sendData);
                    
                    return new TransactionConfirmationDialog.TransactionDetails(
                        tokenContract,
                        formatJsonForDisplay(sendMsg.toString())
                    ).setContractLabel("Token Contract:");
                    
                } catch (Exception e) {
                    // Fallback to simple display if JSON building fails
                    return new TransactionConfirmationDialog.TransactionDetails(
                        tokenContract,
                        "SNIP Execute: " + formatJsonForDisplay(messageJson)
                    ).setContractLabel("Token Contract:");
                }

            case TYPE_PERMIT_SIGNING:
                String permitName = intent.getStringExtra("permit_name");
                String allowedTokens = intent.getStringExtra("allowed_tokens");
                String permissions = intent.getStringExtra("permissions");
                if (permitName == null || allowedTokens == null || permissions == null) return null;

                return new TransactionConfirmationDialog.TransactionDetails(
                    permitName,
                    "Create SNIP-24 Query Permit\n" +
                    "Tokens: " + allowedTokens.replace(",", ", ") + "\n" +
                    "Permissions: " + permissions.replace(",", ", ")
                ).setContractLabel("Permit Name:");

            default:
                return null;
        }
    }
    
    private void executeTransaction() {
        new Thread(() -> {
            try {
                String result;
                String senderAddress;
                
                switch (transactionType) {
                    case TYPE_SECRET_EXECUTE:
                        String[] secretResult = SecretExecuteService.execute(this, getIntent());
                        result = secretResult[0];
                        senderAddress = secretResult[1];
                        break;
                        
                    case TYPE_NATIVE_SEND:
                        String[] nativeResult = NativeSendService.execute(this, getIntent());
                        result = nativeResult[0];
                        senderAddress = nativeResult[1];
                        break;
                        
                    case TYPE_MULTI_MESSAGE:
                        String[] multiResult = MultiMessageExecuteService.execute(this, getIntent());
                        result = multiResult[0];
                        senderAddress = multiResult[1];
                        break;
                        
                    case TYPE_SNIP_EXECUTE:
                        // Create properly mapped intent for SnipExecuteService
                        Intent snipIntent = new Intent();
                        snipIntent.putExtra("token_contract", getIntent().getStringExtra(EXTRA_TOKEN_CONTRACT));
                        snipIntent.putExtra("token_hash", getIntent().getStringExtra(EXTRA_TOKEN_HASH));
                        snipIntent.putExtra("recipient", getIntent().getStringExtra(EXTRA_RECIPIENT_ADDRESS));
                        snipIntent.putExtra("recipient_hash", getIntent().getStringExtra(EXTRA_RECIPIENT_HASH));
                        snipIntent.putExtra("amount", getIntent().getStringExtra(EXTRA_AMOUNT));
                        snipIntent.putExtra("message_json", getIntent().getStringExtra(EXTRA_MESSAGE_JSON));

                        String[] snipResult = SnipExecuteService.execute(this, snipIntent);
                        result = snipResult[0];
                        senderAddress = snipResult[1];
                        break;

                    case TYPE_PERMIT_SIGNING:
                        String[] permitResult = PermitSigningService.execute(this, getIntent());
                        result = permitResult[0];
                        senderAddress = permitResult[1];
                        break;

                    default:
                        throw new Exception("Unknown transaction type: " + transactionType);
                }
                
                // Transaction succeeded
                runOnUiThread(() -> handleTransactionSuccess(result, senderAddress));
                
            } catch (Exception e) {
                Log.e(TAG, "Transaction failed", e);
                runOnUiThread(() -> handleTransactionError("Transaction failed: " + e.getMessage()));
            }
        }).start();
    }
    
    private void handleTransactionSuccess(String result, String senderAddress) {
        // Send immediate broadcast for any listening fragments to refresh during animation
        Intent broadcast = new Intent("com.example.earthwallet.TRANSACTION_SUCCESS");
        broadcast.putExtra("result", result);
        broadcast.putExtra("senderAddress", senderAddress);
        Log.d(TAG, "Sending TRANSACTION_SUCCESS broadcast immediately");
        sendBroadcast(broadcast);
        
        // Show success animation
        statusModal.updateState(StatusModal.State.SUCCESS);
        
        // Delay finish to allow animation to play while UI updates
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            finishWithSuccess(result, senderAddress);
        }, 1600); // 1600ms to ensure animation completes
    }
    
    private void handleTransactionError(String error) {
        statusModal.updateState(StatusModal.State.ERROR);
        
        // Delay finish to allow error animation to play
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            finishWithError(error);
        }, 1600);
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
    
    private boolean isSnipMessage(String json) {
        if (json == null) return false;
        try {
            return json.contains("\"send\"");
        } catch (Exception e) {
            return false;
        }
    }
    
    private String truncateAddress(String address) {
        if (address == null || address.isEmpty()) return "";
        if (address.length() <= 20) return address;
        return address.substring(0, 10) + "..." + address.substring(address.length() - 6);
    }
    
    private String formatJsonForDisplay(String json) {
        if (json == null) return "";
        try {
            org.json.JSONObject jsonObj = new org.json.JSONObject(json);
            return jsonObj.toString(2); // Pretty print with 2 space indentation
        } catch (Exception e) {
            return json; // Return original if parsing fails
        }
    }
    
    @Override
    protected void onDestroy() {
        if (confirmationDialog != null) {
            confirmationDialog.dismiss();
        }
        if (statusModal != null) {
            statusModal.destroy();
        }
        super.onDestroy();
    }
}