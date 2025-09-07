package com.example.earthwallet.bridge.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.bridge.services.SecretNetworkService;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.wallet.services.TransactionSigner;

import org.bitcoinj.core.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.protobuf.ByteString;
import cosmos.tx.v1beta1.Tx;
import cosmos.bank.v1beta1.MsgSend;
import cosmos.base.v1beta1.CoinOuterClass;

/**
 * NativeSendActivity
 *
 * Handles native token (SCRT) transfers using cosmos.bank.v1beta1.MsgSend
 */
public class NativeSendActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_RECIPIENT = "com.example.earthwallet.EXTRA_RECIPIENT";
    public static final String EXTRA_AMOUNT = "com.example.earthwallet.EXTRA_AMOUNT";
    public static final String EXTRA_DENOM = "com.example.earthwallet.EXTRA_DENOM";
    public static final String EXTRA_MEMO = "com.example.earthwallet.EXTRA_MEMO";

    // Result extras
    public static final String EXTRA_RESULT_JSON = "com.example.earthwallet.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.earthwallet.EXTRA_ERROR";
    public static final String EXTRA_SENDER_ADDRESS = "com.example.earthwallet.EXTRA_SENDER_ADDRESS";

    private static final String TAG = "NativeSendActivity";
    private static final String PREF_FILE = "secret_wallet_prefs";

    private SharedPreferences securePrefs;
    private SecretNetworkService networkService;

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

        // Execute on background thread
        new Thread(() -> {
            try {
                performNativeTransfer(params, mnemonic);
            } catch (Exception e) {
                Log.e(TAG, "Native transfer failed", e);
                runOnUiThread(() -> finishWithError("Transfer failed: " + e.getMessage()));
            }
        }).start();
    }

    private TransactionParams parseIntentParameters() {
        Intent intent = getIntent();
        return new TransactionParams(
            intent.getStringExtra(EXTRA_RECIPIENT),
            intent.getStringExtra(EXTRA_AMOUNT),
            intent.getStringExtra(EXTRA_DENOM),
            intent.getStringExtra(EXTRA_MEMO)
        );
    }

    private boolean validateParameters(TransactionParams params) {
        if (TextUtils.isEmpty(params.recipient) || TextUtils.isEmpty(params.amount) || TextUtils.isEmpty(params.denom)) {
            finishWithError("Missing required parameters");
            return false;
        }
        
        if (!params.recipient.startsWith("secret1")) {
            finishWithError("Invalid recipient address");
            return false;
        }
        
        try {
            long amount = Long.parseLong(params.amount);
            if (amount <= 0) {
                finishWithError("Amount must be positive");
                return false;
            }
        } catch (NumberFormatException e) {
            finishWithError("Invalid amount format");
            return false;
        }
        
        return true;
    }

    private void performNativeTransfer(TransactionParams params, String mnemonic) throws Exception {
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

        // Build transaction
        byte[] txBytes = buildNativeTransferTransaction(
            senderAddress, params.recipient, params.amount, params.denom, params.memo,
            accountNumber, sequence, chainId, walletKey
        );

        // Broadcast transaction
        String response = networkService.broadcastTransactionModern(lcdUrl, txBytes);

        // Show result on UI thread
        runOnUiThread(() -> {
            showResponseAlert(response, senderAddress);
        });
    }

    private byte[] buildNativeTransferTransaction(String sender, String recipient, 
                                               String amount, String denom, String memo,
                                               String accountNumber, String sequence, String chainId,
                                               ECKey walletKey) throws Exception {
        
        Log.i(TAG, "Building native token transfer transaction");
        
        try {
            // Validate wallet matches sender
            TransactionSigner.validateWalletMatchesSender(sender, walletKey);
            
            // Create MsgSend message
            MsgSend.Builder msgBuilder = MsgSend.newBuilder()
                .setFromAddress(sender)
                .setToAddress(recipient);
            
            // Add coin amount
            CoinOuterClass.Coin coin = CoinOuterClass.Coin.newBuilder()
                .setDenom(denom)
                .setAmount(amount)
                .build();
            msgBuilder.addAmount(coin);
            
            MsgSend msgSend = msgBuilder.build();
            
            // Create TxBody with the MsgSend
            cosmos.tx.v1beta1.Tx.TxBody.Builder txBodyBuilder = 
                cosmos.tx.v1beta1.Tx.TxBody.newBuilder()
                    .addMessages(com.google.protobuf.Any.newBuilder()
                        .setTypeUrl("/cosmos.bank.v1beta1.MsgSend")
                        .setValue(msgSend.toByteString())
                        .build());
                    
            if (memo != null && !memo.isEmpty()) {
                txBodyBuilder.setMemo(memo);
            }
            
            cosmos.tx.v1beta1.Tx.TxBody txBody = txBodyBuilder.build();
            
            // Create AuthInfo with fee and signer info
            cosmos.tx.v1beta1.Tx.AuthInfo authInfo = createAuthInfo(walletKey, sequence);
            
            // Create SignDoc
            cosmos.tx.v1beta1.Tx.SignDoc signDoc = cosmos.tx.v1beta1.Tx.SignDoc.newBuilder()
                .setBodyBytes(txBody.toByteString())
                .setAuthInfoBytes(authInfo.toByteString())
                .setChainId(chainId)
                .setAccountNumber(Long.parseLong(accountNumber))
                .build();
            
            // Sign transaction
            byte[] txBytes = TransactionSigner.signTransaction(signDoc, walletKey);
            
            Log.i(TAG, "Native token transfer transaction created, size: " + txBytes.length + " bytes");
            return txBytes;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to build native token transfer transaction", e);
            throw new Exception("Native token transfer transaction building failed: " + e.getMessage());
        }
    }
    
    private cosmos.tx.v1beta1.Tx.AuthInfo createAuthInfo(ECKey walletKey, String sequence) throws Exception {
        // Get public key
        byte[] publicKeyBytes = walletKey.getPubKey();
        
        // Create public key protobuf
        cosmos.crypto.secp256k1.PubKey pubKey = cosmos.crypto.secp256k1.PubKey.newBuilder()
            .setKey(ByteString.copyFrom(publicKeyBytes))
            .build();
        
        // Create signer info
        cosmos.tx.v1beta1.Tx.SignerInfo signerInfo = cosmos.tx.v1beta1.Tx.SignerInfo.newBuilder()
            .setPublicKey(com.google.protobuf.Any.newBuilder()
                .setTypeUrl("/cosmos.crypto.secp256k1.PubKey")
                .setValue(pubKey.toByteString())
                .build())
            .setModeInfo(cosmos.tx.v1beta1.Tx.ModeInfo.newBuilder()
                .setSingle(cosmos.tx.v1beta1.Tx.ModeInfo.Single.newBuilder()
                    .setMode(cosmos.tx.signing.v1beta1.SignMode.SIGN_MODE_DIRECT_VALUE)
                    .build())
                .build())
            .setSequence(Long.parseLong(sequence))
            .build();
        
        // Create fee (using standard gas and fee)
        cosmos.tx.v1beta1.Tx.Fee fee = cosmos.tx.v1beta1.Tx.Fee.newBuilder()
            .setGasLimit(200000) // Standard gas limit for native transfers
            .addAmount(CoinOuterClass.Coin.newBuilder()
                .setDenom("uscrt")
                .setAmount("5000") // 0.005 SCRT fee
                .build())
            .build();
        
        return cosmos.tx.v1beta1.Tx.AuthInfo.newBuilder()
            .addSignerInfos(signerInfo)
            .setFee(fee)
            .build();
    }

    private void showResponseAlert(String response, String senderAddress) {
        try {
            JSONObject responseObj = new JSONObject(response);
            String title = "Transaction Response";
            String message = response;
            
            if (responseObj.has("tx_response")) {
                JSONObject txResponse = responseObj.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                String txHash = txResponse.optString("txhash", "");
                String rawLog = txResponse.optString("raw_log", "");
                
                if (code == 0) {
                    title = "✅ Transfer Successful";
                    message = "Hash: " + txHash + "\n\nFull Response:\n" + response;
                } else {
                    title = "❌ Transfer Failed (Code: " + code + ")";
                    message = "Hash: " + txHash + "\nError: " + rawLog + "\n\nFull Response:\n" + response;
                }
            }
            
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", (dialog, which) -> {
                    finishWithSuccess(response, senderAddress);
                })
                .setCancelable(false)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show response alert", e);
            finishWithSuccess(response, senderAddress);
        }
    }

    private String getSelectedMnemonic() {
        try {
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int selectedIndex = securePrefs.getInt("selected_wallet_index", -1);
            
            if (arr.length() > 0) {
                if (selectedIndex >= 0 && selectedIndex < arr.length()) {
                    return arr.getJSONObject(selectedIndex).optString("mnemonic", "");
                } else {
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

    private static class TransactionParams {
        final String recipient;
        final String amount;
        final String denom;
        final String memo;

        TransactionParams(String recipient, String amount, String denom, String memo) {
            this.recipient = recipient;
            this.amount = amount;
            this.denom = denom;
            this.memo = memo != null ? memo : "";
        }
    }
}