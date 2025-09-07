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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.wallet.services.SecretWallet;

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
                performTransaction(params, mnemonic);
            } catch (Exception e) {
                Log.e(TAG, "Transaction failed", e);
                runOnUiThread(() -> finishWithError("Transaction failed: " + e.getMessage()));
            }
        }).start();
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
            showEnhancedResponseAlert(enhancedResponse, senderAddress);
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
    private void showEnhancedResponseAlert(String enhancedResponse, String senderAddress) {
        try {
            // Parse the response to make it more readable
            JSONObject response = new JSONObject(enhancedResponse);
            String title = "Transaction Response";
            String message = enhancedResponse;
            int code = -1; // Default to failed
            
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                code = txResponse.optInt("code", -1);
                String txHash = txResponse.optString("txhash", "");
                String rawLog = txResponse.optString("raw_log", "");
                
                if (code == 0) {
                    title = "✅ Transaction Successful";
                    message = "Hash: " + txHash + "\n\nFull Response:\n" + enhancedResponse;
                } else {
                    title = "❌ Transaction Failed (Code: " + code + ")";
                    message = "Hash: " + txHash + "\nError: " + rawLog + "\n\nFull Response:\n" + enhancedResponse;
                }
            }
            
            if (code == 0) {
                // For successful transactions, show a toast and finish immediately
                android.widget.Toast.makeText(this, "Transaction successful!", android.widget.Toast.LENGTH_SHORT).show();
                finishWithSuccess(enhancedResponse, senderAddress);
            } else {
                // For failed transactions, still show the alert dialog with details
                new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", (dialog, which) -> {
                        finishWithSuccess(enhancedResponse, senderAddress);
                    })
                    .setNegativeButton("Copy", (dialog, which) -> {
                        android.content.ClipboardManager clipboard = 
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        android.content.ClipData clip = 
                            android.content.ClipData.newPlainText("Transaction Response", enhancedResponse);
                        clipboard.setPrimaryClip(clip);
                        finishWithSuccess(enhancedResponse, senderAddress);
                    })
                    .setCancelable(false)
                    .show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to show enhanced response alert", e);
            // Fallback: show raw response
            new AlertDialog.Builder(this)
                .setTitle("Transaction Response")
                .setMessage(enhancedResponse)
                .setPositiveButton("OK", (dialog, which) -> {
                    finishWithSuccess(enhancedResponse, senderAddress);
                })
                .setCancelable(false)
                .show();
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