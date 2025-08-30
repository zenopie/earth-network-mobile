package com.example.passportscanner.bridge;

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

import com.example.passportscanner.wallet.SecretWallet;

import org.bitcoinj.core.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SecretExecuteNativeActivity (Clean Version)
 *
 * Minimal, clean implementation using our SecretCryptoService and SecretNetworkService.
 * Builds protobuf transactions for Secret Network contract execution.
 */
public class SecretExecuteNativeActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.passportscanner.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.passportscanner.EXTRA_CODE_HASH";
    public static final String EXTRA_EXECUTE_JSON = "com.example.passportscanner.EXTRA_EXECUTE_JSON";
    public static final String EXTRA_FUNDS = "com.example.passportscanner.EXTRA_FUNDS";
    public static final String EXTRA_MEMO = "com.example.passportscanner.EXTRA_MEMO";
    public static final String EXTRA_LCD_URL = "com.example.passportscanner.EXTRA_LCD_URL";
    public static final String EXTRA_CONTRACT_ENCRYPTION_KEY_B64 = "com.example.passportscanner.EXTRA_CONTRACT_ENCRYPTION_KEY_B64";

    // Result extras
    public static final String EXTRA_RESULT_JSON = "com.example.passportscanner.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.passportscanner.EXTRA_ERROR";
    public static final String EXTRA_SENDER_ADDRESS = "com.example.passportscanner.EXTRA_SENDER_ADDRESS";

    private static final String TAG = "SecretExecuteClean";
    private static final String PREF_FILE = "secret_wallet_prefs";

    private SharedPreferences securePrefs;
    private SecretNetworkService networkService;
    private SecretCryptoService cryptoService;
    private SecretProtobufService protobufService;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            // Initialize services
            SecretWallet.initialize(this);
            securePrefs = createSecurePrefs(this);
            networkService = new SecretNetworkService();
            cryptoService = new SecretCryptoService();
            protobufService = new SecretProtobufService();
            
            // Execute transaction on background thread
            executeTransaction();
            
        } catch (Exception e) {
            Log.e(TAG, "Initialization failed", e);
            finishWithError("Initialization failed: " + e.getMessage());
        }
    }

    private void executeTransaction() {
        // Parse intent parameters
        Intent intent = getIntent();
        String contractAddr = intent.getStringExtra(EXTRA_CONTRACT_ADDRESS);
        String codeHash = intent.getStringExtra(EXTRA_CODE_HASH);
        String execJson = intent.getStringExtra(EXTRA_EXECUTE_JSON);
        String funds = intent.getStringExtra(EXTRA_FUNDS);
        String memo = intent.getStringExtra(EXTRA_MEMO);
        String contractPubKeyB64 = intent.getStringExtra(EXTRA_CONTRACT_ENCRYPTION_KEY_B64);

        // Validate required parameters
        if (TextUtils.isEmpty(contractAddr) || TextUtils.isEmpty(execJson)) {
            finishWithError("Missing required parameters");
            return;
        }

        // Set defaults
        if (funds == null) funds = "";
        if (memo == null) memo = "";

        // Get wallet mnemonic
        String mnemonic = getSelectedMnemonic();
        if (TextUtils.isEmpty(mnemonic)) {
            finishWithError("No wallet mnemonic found");
            return;
        }

        // Execute on background thread
        final String finalContractAddr = contractAddr;
        final String finalCodeHash = codeHash;
        final String finalExecJson = execJson;
        final String finalFunds = funds;
        final String finalMemo = memo;
        final String finalContractPubKeyB64 = contractPubKeyB64;
        final String finalMnemonic = mnemonic;

        new Thread(() -> {
            try {
                performTransaction(SecretWallet.DEFAULT_LCD_URL, finalContractAddr, finalCodeHash, 
                                 finalExecJson, finalFunds, finalMemo, 
                                 finalContractPubKeyB64, finalMnemonic);
            } catch (Exception e) {
                Log.e(TAG, "Transaction failed", e);
                runOnUiThread(() -> finishWithError("Transaction failed: " + e.getMessage()));
            }
        }).start();
    }

    private void performTransaction(String lcdUrl, String contractAddr, String codeHash,
                                  String execJson, String funds, String memo,
                                  String contractPubKeyB64, String mnemonic) throws Exception {
        
        Log.i(TAG, "Starting clean Secret Network transaction");
        
        // 1. Get wallet information
        ECKey walletKey = SecretWallet.deriveKeyFromMnemonic(mnemonic);
        String senderAddress = SecretWallet.getAddress(walletKey);
        
        Log.i(TAG, "Sender address: " + senderAddress);

        // 2. Fetch chain and account information using clean network service
        String chainId = networkService.fetchChainId(lcdUrl);
        Log.i(TAG, "Chain ID: " + chainId);
        
        JSONObject accountData = networkService.fetchAccount(lcdUrl, senderAddress);
        if (accountData == null) {
            throw new Exception("Account not found: " + senderAddress);
        }
        
        String[] accountFields = networkService.parseAccountFields(accountData);
        String accountNumber = accountFields[0];
        String sequence = accountFields[1];
        
        Log.i(TAG, "Account: " + accountNumber + ", Sequence: " + sequence);

        // 3. Encrypt contract message using clean crypto service
        byte[] encryptedMessage = cryptoService.encryptContractMessage(
            contractPubKeyB64, codeHash, execJson, mnemonic);
        
        Log.i(TAG, "Message encrypted, size: " + encryptedMessage.length + " bytes");

        // 4. Build protobuf transaction using clean service
        byte[] txBytes = protobufService.buildTransaction(senderAddress, contractAddr, codeHash, 
                                                        encryptedMessage, funds, memo, accountNumber, 
                                                        sequence, chainId, walletKey);

        // 5. Broadcast transaction using clean network service
        String response = networkService.broadcastTransactionModern(lcdUrl, txBytes);
        
        Log.i(TAG, "Transaction broadcast successful");

        // 6. Enhance response (query for execution results)
        String enhancedResponse = enhanceTransactionResponse(response, lcdUrl);
        Log.i(TAG, "Enhanced transaction response: " + enhancedResponse);

        // 7. Show enhanced response in alert and return result on UI thread
        runOnUiThread(() -> {
            showEnhancedResponseAlert(enhancedResponse, senderAddress);
        });
    }

    // Protobuf transaction building is now handled by SecretProtobufService

    private String enhanceTransactionResponse(String initialResponse, String lcdUrl) {
        try {
            JSONObject response = new JSONObject(initialResponse);
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                String txHash = txResponse.optString("txhash", "");

                if (code == 0 && !txHash.isEmpty()) {
                    Log.i(TAG, "Transaction successful, querying for detailed results...");
                    
                    Thread.sleep(3000);
                    
                    String detailedResponse = networkService.queryTransactionByHash(lcdUrl, txHash);
                    if (detailedResponse != null) {
                        return detailedResponse;
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
            
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
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
            
            Log.w(TAG, "No wallets found in multi-wallet system");
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
}