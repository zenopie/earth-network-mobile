package com.example.earthwallet.bridge.activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.bridge.services.SecretNetworkService;
import com.example.earthwallet.bridge.services.SecretCryptoService;
import com.example.earthwallet.ui.components.TransactionHandler;
import com.example.earthwallet.ui.components.TransactionConfirmationDialog;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.bitcoinj.core.ECKey;

/**
 * MultiMessageExecuteActivity
 *
 * Handles multi-message Secret Network transactions (like add liquidity with allowances).
 * Follows the same pattern as SecretExecuteActivity but supports multiple messages in one transaction.
 *
 * Intent API:
 * - Input (Intent extras):
 *   - EXTRA_MESSAGES_JSON     (String, required) - JSON array of message objects
 *   - EXTRA_GAS_LIMIT         (Long, optional)   - Gas limit (default: 1000000)
 *   - EXTRA_MEMO              (String, optional) - Transaction memo
 *   - EXTRA_LCD_URL           (String, optional) - LCD endpoint URL
 *
 * - Result (onActivityResult):
 *   - RESULT_OK with:
 *       - EXTRA_RESULT_JSON    (String) - JSON string: {"success":true,"result":...}
 *       - EXTRA_TX_HASH        (String) - Transaction hash if available
 *     or
 *   - RESULT_CANCELED with:
 *       - EXTRA_ERROR          (String) - error string
 */
public class MultiMessageExecuteActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_MESSAGES_JSON = "com.example.earthwallet.EXTRA_MESSAGES_JSON";
    public static final String EXTRA_GAS_LIMIT = "com.example.earthwallet.EXTRA_GAS_LIMIT";
    public static final String EXTRA_MEMO = "com.example.earthwallet.EXTRA_MEMO";
    public static final String EXTRA_LCD_URL = "com.example.earthwallet.EXTRA_LCD_URL";

    // Result extras
    public static final String EXTRA_RESULT_JSON = "com.example.earthwallet.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.earthwallet.EXTRA_ERROR";
    public static final String EXTRA_TX_HASH = "com.example.earthwallet.EXTRA_TX_HASH";

    private static final String TAG = "MultiMessageExecuteActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final long DEFAULT_GAS_LIMIT = 1000000L;
    private static final String DEFAULT_LCD_URL = "https://lcd.erth.network";

    private SharedPreferences securePrefs;
    private SecretNetworkService networkService;
    private SecretCryptoService cryptoService;
    private TransactionHandler transactionHandler;

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

        String messagesJson = intent.getStringExtra(EXTRA_MESSAGES_JSON);
        long gasLimit = intent.getLongExtra(EXTRA_GAS_LIMIT, DEFAULT_GAS_LIMIT);
        String memo = intent.getStringExtra(EXTRA_MEMO);
        String lcdUrl = intent.getStringExtra(EXTRA_LCD_URL);

        // Validate required parameters
        if (TextUtils.isEmpty(messagesJson)) {
            finishWithError("Messages JSON is required");
            return;
        }

        // Set defaults
        if (TextUtils.isEmpty(memo)) {
            memo = "Multi-message transaction";
        }
        if (TextUtils.isEmpty(lcdUrl)) {
            lcdUrl = DEFAULT_LCD_URL;
        }

        // Parse and validate messages
        try {
            JSONArray messages = new JSONArray(messagesJson);
            if (messages.length() == 0) {
                finishWithError("No messages provided");
                return;
            }
            
            Log.i(TAG, "Executing multi-message transaction with " + messages.length() + " messages");
            executeMultiMessageTransactionWithConfirmation(messages, gasLimit, memo, lcdUrl);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse messages JSON", e);
            finishWithError("Invalid messages JSON: " + e.getMessage());
        }
    }

    /**
     * Execute the multi-message transaction with confirmation dialog and status modal
     */
    private void executeMultiMessageTransactionWithConfirmation(JSONArray messages, long gasLimit, String memo, String lcdUrl) {
        try {
            // Initialize services like SecretExecuteActivity
            initializeServices();
            
            // Get mnemonic like SecretExecuteActivity
            String mnemonic = getSelectedMnemonic();
            if (TextUtils.isEmpty(mnemonic)) {
                finishWithError("No wallet mnemonic found");
                return;
            }

            // Initialize transaction handler
            transactionHandler = new TransactionHandler(this);
            
            // Create transaction details for confirmation dialog
            TransactionConfirmationDialog.TransactionDetails details = createTransactionDetails(messages, memo);
            
            // Execute with confirmation and status modal
            transactionHandler.executeTransaction(
                details,
                () -> performMultiMessageTransaction(messages, mnemonic, memo, lcdUrl),
                new TransactionHandler.TransactionResultHandler() {
                    @Override
                    public void onSuccess(String result, String senderAddress) {
                        finishWithSuccess(result, extractTxHashFromJson(result));
                    }
                    
                    @Override
                    public void onError(String error) {
                        finishWithError(error);
                    }
                }
            );
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start multi-message transaction", e);
            finishWithError("Failed to start transaction: " + e.getMessage());
        }
    }
    
    /**
     * Create transaction details for the confirmation dialog
     */
    private TransactionConfirmationDialog.TransactionDetails createTransactionDetails(JSONArray messages, String memo) {
        try {
            StringBuilder messagesSummary = new StringBuilder();
            messagesSummary.append("Multi-message transaction with ").append(messages.length()).append(" messages:\n\n");
            
            for (int i = 0; i < Math.min(messages.length(), 3); i++) { // Show first 3 messages
                JSONObject messageObj = messages.getJSONObject(i);
                String contract = messageObj.optString("contract", "Unknown");
                JSONObject msg = messageObj.optJSONObject("msg");
                
                messagesSummary.append(i + 1).append(". Contract: ").append(truncateAddress(contract)).append("\n");
                if (msg != null) {
                    messagesSummary.append("   Message: ").append(msg.toString()).append("\n");
                }
                if (i < Math.min(messages.length(), 3) - 1) {
                    messagesSummary.append("\n");
                }
            }
            
            if (messages.length() > 3) {
                messagesSummary.append("\n... and ").append(messages.length() - 3).append(" more messages");
            }
            
            TransactionConfirmationDialog.TransactionDetails details = 
                new TransactionConfirmationDialog.TransactionDetails("Multiple Contracts", messagesSummary.toString())
                    .setContractLabel("Type:")
                    .setMemo(memo);
                    
            return details;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create transaction details", e);
            return new TransactionConfirmationDialog.TransactionDetails("Multiple Contracts", "Multi-message transaction")
                .setContractLabel("Type:")
                .setMemo(memo);
        }
    }
    
    private String truncateAddress(String address) {
        if (TextUtils.isEmpty(address)) return "";
        if (address.length() <= 20) return address;
        return address.substring(0, 10) + "..." + address.substring(address.length() - 6);
    }
    
    private String extractTxHashFromJson(String json) {
        try {
            JSONObject response = new JSONObject(json);
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                return txResponse.optString("txhash", "");
            }
            return response.optString("txhash", "");
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract tx hash", e);
            return "";
        }
    }
    
    /**
     * Initialize services like SecretExecuteActivity does
     */
    private void initializeServices() throws Exception {
        SecretWallet.initialize(this);
        securePrefs = createSecurePrefs(this);
        networkService = new SecretNetworkService();
        cryptoService = new SecretCryptoService();
    }
    
    /**
     * Get selected mnemonic exactly like SecretExecuteActivity
     */
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
    
    /**
     * Perform multi-message transaction following SecretExecuteActivity pattern
     */
    private void performMultiMessageTransaction(JSONArray messages, String mnemonic, String memo, String lcdUrl) throws Exception {
        // Get wallet information like SecretExecuteActivity
        ECKey walletKey = SecretWallet.deriveKeyFromMnemonic(mnemonic);
        String senderAddress = SecretWallet.getAddress(walletKey);

        // Fetch chain and account information like SecretExecuteActivity
        String chainId = networkService.fetchChainId(lcdUrl);
        JSONObject accountData = networkService.fetchAccount(lcdUrl, senderAddress);
        if (accountData == null) {
            throw new Exception("Account not found: " + senderAddress);
        }
        
        String[] accountFields = networkService.parseAccountFields(accountData);
        String accountNumber = accountFields[0];
        String sequence = accountFields[1];

        // Build multi-message protobuf transaction using the same crypto service
        byte[] txBytes = buildMultiMessageTransaction(messages, senderAddress, walletKey, 
                                                    accountNumber, sequence, chainId, memo, mnemonic);

        // Broadcast transaction like SecretExecuteActivity
        String response = networkService.broadcastTransactionModern(lcdUrl, txBytes);

        // Enhance response with detailed results like SecretExecuteActivity
        String enhancedResponse = enhanceTransactionResponse(response, lcdUrl);

        // Parse response to check for success/failure
        try {
            JSONObject responseObj = new JSONObject(enhancedResponse);
            if (responseObj.has("tx_response")) {
                JSONObject txResponse = responseObj.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                
                if (code == 0) {
                    // Transaction successful
                    transactionHandler.onTransactionSuccess(enhancedResponse, senderAddress);
                } else {
                    // Transaction failed
                    String rawLog = txResponse.optString("raw_log", "Unknown error");
                    transactionHandler.onTransactionError("Transaction failed (Code: " + code + "): " + rawLog);
                }
            } else {
                // Fallback: assume success if no error code
                transactionHandler.onTransactionSuccess(enhancedResponse, senderAddress);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse transaction response", e);
            transactionHandler.onTransactionError("Failed to parse transaction response: " + e.getMessage());
        }
    }
    
    /**
     * Extract transaction hash from result JSON
     */
    private String extractTxHash(JSONObject result) {
        try {
            if (result.has("tx_response")) {
                JSONObject txResponse = result.getJSONObject("tx_response");
                if (txResponse.has("txhash")) {
                    return txResponse.getString("txhash");
                }
            }
            if (result.has("txhash")) {
                return result.getString("txhash");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract transaction hash", e);
        }
        return null;
    }

    /**
     * Enhance transaction response by querying the transaction hash (like SecretExecuteActivity)
     */
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
                        SecretNetworkService networkService = new SecretNetworkService();
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

    /**
     * Build multi-message protobuf transaction following the same pattern as SecretExecuteActivity
     */
    private byte[] buildMultiMessageTransaction(JSONArray messages, String senderAddress, ECKey walletKey,
                                              String accountNumber, String sequence, String chainId, 
                                              String memo, String mnemonic) throws Exception {
        
        Log.i(TAG, "Building multi-message protobuf transaction with " + messages.length() + " messages");
        
        // Convert addresses to binary format (same as SecretProtobufService)
        byte[] senderBytes = decodeBech32Address(senderAddress);
        
        // Create TxBody builder
        cosmos.tx.v1beta1.Tx.TxBody.Builder txBodyBuilder = cosmos.tx.v1beta1.Tx.TxBody.newBuilder();
        
        // Add each message to the transaction
        for (int i = 0; i < messages.length(); i++) {
            JSONObject messageObj = messages.getJSONObject(i);
            
            String contract = messageObj.getString("contract");
            String codeHash = messageObj.getString("code_hash");
            JSONObject msg = messageObj.getJSONObject("msg");
            
            // Encrypt the message using the same crypto service as SecretExecuteActivity
            byte[] encryptedMsg = cryptoService.encryptContractMessage(codeHash, msg.toString(), mnemonic);
            
            // Convert contract address to binary
            byte[] contractBytes = decodeBech32Address(contract);
            
            // Create MsgExecuteContract (same as SecretProtobufService)
            secret.compute.v1beta1.MsgExecuteContract.Builder msgBuilder = 
                secret.compute.v1beta1.MsgExecuteContract.newBuilder()
                    .setSender(com.google.protobuf.ByteString.copyFrom(senderBytes))
                    .setContract(com.google.protobuf.ByteString.copyFrom(contractBytes))
                    .setMsg(com.google.protobuf.ByteString.copyFrom(encryptedMsg))
                    .setCallbackCodeHash("")
                    .setCallbackSig(com.google.protobuf.ByteString.EMPTY);
            
            // Add any funds (sent_funds should be empty for allowances/add_liquidity)
            if (messageObj.has("sent_funds")) {
                org.json.JSONArray sentFunds = messageObj.getJSONArray("sent_funds");
                for (int j = 0; j < sentFunds.length(); j++) {
                    JSONObject fund = sentFunds.getJSONObject(j);
                    cosmos.base.v1beta1.CoinOuterClass.Coin.Builder coinBuilder = 
                        cosmos.base.v1beta1.CoinOuterClass.Coin.newBuilder()
                            .setDenom(fund.getString("denom"))
                            .setAmount(fund.getString("amount"));
                    msgBuilder.addSentFunds(coinBuilder.build());
                }
            }
            
            secret.compute.v1beta1.MsgExecuteContract executeMsg = msgBuilder.build();
            
            // Add to transaction body
            txBodyBuilder.addMessages(com.google.protobuf.Any.newBuilder()
                .setTypeUrl("/secret.compute.v1beta1.MsgExecuteContract")
                .setValue(executeMsg.toByteString())
                .build());
        }
        
        // Set memo
        txBodyBuilder.setMemo(memo != null ? memo : "Multi-message transaction");
        
        cosmos.tx.v1beta1.Tx.TxBody txBody = txBodyBuilder.build();
        
        // Create AuthInfo (same as SecretProtobufService)
        cosmos.tx.v1beta1.Tx.Fee fee = cosmos.tx.v1beta1.Tx.Fee.newBuilder()
            .setGasLimit(5000000)  // Higher gas for multi-message
            .addAmount(cosmos.base.v1beta1.CoinOuterClass.Coin.newBuilder()
                .setDenom("uscrt")
                .setAmount("100000")
                .build())
            .build();
        
        cosmos.tx.v1beta1.Tx.SignerInfo signerInfo = cosmos.tx.v1beta1.Tx.SignerInfo.newBuilder()
            .setPublicKey(com.google.protobuf.Any.newBuilder()
                .setTypeUrl("/cosmos.crypto.secp256k1.PubKey")
                .setValue(cosmos.crypto.secp256k1.PubKey.newBuilder()
                    .setKey(com.google.protobuf.ByteString.copyFrom(walletKey.getPubKey()))
                    .build()
                    .toByteString())
                .build())
            .setModeInfo(cosmos.tx.v1beta1.Tx.ModeInfo.newBuilder()
                .setSingle(cosmos.tx.v1beta1.Tx.ModeInfo.Single.newBuilder()
                    .setMode(cosmos.tx.signing.v1beta1.SignMode.SIGN_MODE_DIRECT.getNumber())
                    .build())
                .build())
            .setSequence(Long.parseLong(sequence))
            .build();
        
        cosmos.tx.v1beta1.Tx.AuthInfo authInfo = cosmos.tx.v1beta1.Tx.AuthInfo.newBuilder()
            .addSignerInfos(signerInfo)
            .setFee(fee)
            .build();
        
        // Create SignDoc and sign (same as SecretProtobufService)
        cosmos.tx.v1beta1.Tx.SignDoc signDoc = cosmos.tx.v1beta1.Tx.SignDoc.newBuilder()
            .setBodyBytes(txBody.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(chainId)
            .setAccountNumber(Long.parseLong(accountNumber))
            .build();
        
        // Use the same transaction signer as SecretExecuteActivity
        byte[] txBytes = com.example.earthwallet.wallet.services.TransactionSigner.signTransaction(signDoc, walletKey);
        
        Log.i(TAG, "Multi-message protobuf transaction built, size: " + txBytes.length + " bytes");
        
        return txBytes;
    }
    
    /**
     * Decode bech32 address (same method as SecretProtobufService)
     */
    private byte[] decodeBech32Address(String bech32Address) throws Exception {
        if (!bech32Address.startsWith("secret1")) {
            throw new IllegalArgumentException("Invalid secret address: " + bech32Address);
        }
        
        String datapart = bech32Address.substring(7);
        byte[] decoded = bech32Decode(datapart);
        
        if (decoded.length != 20) {
            throw new IllegalArgumentException("Invalid address length: " + decoded.length + " (expected 20)");
        }
        
        return decoded;
    }
    
    /**
     * Simple bech32 decoder (same as SecretProtobufService)
     */
    private byte[] bech32Decode(String data) throws Exception {
        String charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        
        int[] values = new int[data.length()];
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int index = charset.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("Invalid bech32 character: " + c);
            }
            values[i] = index;
        }
        
        int dataLength = values.length - 6;
        int[] dataValues = new int[dataLength];
        System.arraycopy(values, 0, dataValues, 0, dataLength);
        
        return convertBits(dataValues, 5, 8, false);
    }
    
    /**
     * Convert between different bit group sizes (same as SecretProtobufService)
     */
    private byte[] convertBits(int[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        java.util.ArrayList<Byte> ret = new java.util.ArrayList<>();
        int maxv = (1 << toBits) - 1;
        int maxAcc = (1 << (fromBits + toBits - 1)) - 1;
        
        for (int value : data) {
            if (value < 0 || (value >> fromBits) != 0) {
                return null;
            }
            acc = ((acc << fromBits) | value) & maxAcc;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                ret.add((byte) ((acc >> bits) & maxv));
            }
        }
        
        if (pad) {
            if (bits > 0) {
                ret.add((byte) ((acc << (toBits - bits)) & maxv));
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            return null;
        }
        
        byte[] result = new byte[ret.size()];
        for (int i = 0; i < ret.size(); i++) {
            result[i] = ret.get(i);
        }
        return result;
    }

    /**
     * Create secure preferences exactly like SecretExecuteActivity
     */
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
    
    @Override
    protected void onDestroy() {
        // Clean up transaction handler
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
}