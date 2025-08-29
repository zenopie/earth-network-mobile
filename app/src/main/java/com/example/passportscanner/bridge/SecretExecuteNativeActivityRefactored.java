package com.example.passportscanner.bridge;

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
import org.json.JSONObject;

/**
 * SecretExecuteNativeActivity (Refactored)
 *
 * Streamlined Activity that coordinates Secret Network contract execution:
 * - Validates inputs and derives wallet keys
 * - Delegates crypto operations to SecretCryptoService  
 * - Delegates network operations to SecretNetworkService
 * - Manages UI thread coordination and error handling
 *
 * Intent extras (inputs):
 * - EXTRA_CONTRACT_ADDRESS (String, required)
 * - EXTRA_CODE_HASH        (String, optional)
 * - EXTRA_EXECUTE_JSON     (String, required) e.g. {"claim_anml":{}}
 * - EXTRA_LCD_URL          (String, optional) defaults to SecretWallet.DEFAULT_LCD_URL
 * - EXTRA_FUNDS            (String, optional) e.g. "1000uscrt"
 * - EXTRA_MEMO             (String, optional)
 * - EXTRA_CONTRACT_ENCRYPTION_KEY_B64 (String, required if not available from LCD)
 *
 * Result extras (outputs):
 * - On success (RESULT_OK): EXTRA_RESULT_JSON (String) 
 * - On error (RESULT_CANCELED): EXTRA_ERROR (String)
 */
public class SecretExecuteNativeActivityRefactored extends AppCompatActivity {

    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.passportscanner.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.passportscanner.EXTRA_CODE_HASH";
    public static final String EXTRA_EXECUTE_JSON = "com.example.passportscanner.EXTRA_EXECUTE_JSON";
    public static final String EXTRA_FUNDS = "com.example.passportscanner.EXTRA_FUNDS";
    public static final String EXTRA_MEMO = "com.example.passportscanner.EXTRA_MEMO";
    public static final String EXTRA_LCD_URL = "com.example.passportscanner.EXTRA_LCD_URL";
    public static final String EXTRA_CONTRACT_ENCRYPTION_KEY_B64 = "com.example.passportscanner.EXTRA_CONTRACT_ENCRYPTION_KEY_B64";

    public static final String EXTRA_RESULT_JSON = "com.example.passportscanner.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.passportscanner.EXTRA_ERROR";
    public static final String EXTRA_SENDER_ADDRESS = "com.example.passportscanner.EXTRA_SENDER_ADDRESS";

    private static final String TAG = "SecretExecuteRefactored";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";

    private SharedPreferences securePrefs;
    private SecretNetworkService networkService;
    private SecretCryptoService cryptoService;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            // Initialize wallet and services
            SecretWallet.initialize(this);
            securePrefs = createSecurePrefs(this);
            networkService = new SecretNetworkService();
            cryptoService = new SecretCryptoService();
            
            // Validate inputs and execute transaction
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
        String lcdUrl = intent.getStringExtra(EXTRA_LCD_URL);
        String funds = intent.getStringExtra(EXTRA_FUNDS);
        String memo = intent.getStringExtra(EXTRA_MEMO);
        String contractPubKeyB64 = intent.getStringExtra(EXTRA_CONTRACT_ENCRYPTION_KEY_B64);

        // Validate required parameters
        if (TextUtils.isEmpty(contractAddr) || TextUtils.isEmpty(execJson)) {
            finishWithError("Missing required parameters: contract address and execute JSON");
            return;
        }

        // Set defaults
        if (TextUtils.isEmpty(lcdUrl)) lcdUrl = SecretWallet.DEFAULT_LCD_URL;
        if (funds == null) funds = "";
        if (memo == null) memo = "";

        // Get wallet mnemonic and derive key
        String mnemonic = getSelectedMnemonic();
        if (TextUtils.isEmpty(mnemonic)) {
            finishWithError("No wallet selected or mnemonic missing");
            return;
        }

        // Execute on background thread (including encryption key fetch if needed)
        final String finalLcdUrl = lcdUrl;
        final String finalContractAddr = contractAddr;
        final String finalCodeHash = codeHash;
        final String finalExecJson = execJson;
        final String finalFunds = funds;
        final String finalMemo = memo;
        final String finalContractPubKeyB64 = contractPubKeyB64;
        final String finalMnemonic = mnemonic;

        new Thread(() -> {
            try {
                // Get contract encryption key if not provided (on background thread)
                String encryptionKey = finalContractPubKeyB64;
                if (TextUtils.isEmpty(encryptionKey)) {
                    Log.d(TAG, "Fetching contract encryption key on background thread");
                    encryptionKey = networkService.fetchContractEncryptionKey(finalLcdUrl, finalContractAddr);
                }
                
                performTransaction(finalLcdUrl, finalContractAddr, finalCodeHash, 
                                 finalExecJson, finalFunds, finalMemo, 
                                 encryptionKey, finalMnemonic);
            } catch (Exception e) {
                Log.e(TAG, "Transaction failed", e);
                runOnUiThread(() -> finishWithError("Transaction failed: " + e.getMessage()));
            }
        }).start();
    }

    private void performTransaction(String lcdUrl, String contractAddr, String codeHash,
                                  String execJson, String funds, String memo,
                                  String contractPubKeyB64, String mnemonic) throws Exception {
        
        Log.i(TAG, "Starting Secret Network transaction");
        
        // 1. Get wallet information
        ECKey walletKey = SecretWallet.deriveKeyFromMnemonic(mnemonic);
        String senderAddress = SecretWallet.getAddress(walletKey);
        byte[] senderPubKey = cryptoService.getWalletPublicKey(mnemonic);
        
        Log.i(TAG, "Wallet address: " + senderAddress);

        // 2. Fetch chain and account information
        Log.i(TAG, "Fetching chain ID from: " + lcdUrl);
        String chainId;
        try {
            chainId = networkService.fetchChainId(lcdUrl);
            Log.i(TAG, "Successfully retrieved chain ID: " + chainId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch chain ID", e);
            throw new Exception("Chain fetch failed: " + e.getMessage());
        }
        
        Log.i(TAG, "Fetching account data for: " + senderAddress);
        JSONObject accountData;
        try {
            accountData = networkService.fetchAccount(lcdUrl, senderAddress);
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch account data", e);
            throw new Exception("Account fetch failed: " + e.getMessage());
        }
        
        if (accountData == null) {
            Log.w(TAG, "Account data is null - account may not exist or be funded");
            throw new Exception("Account not found or not funded: " + senderAddress + 
                              ". Make sure the wallet has been used on-chain.");
        }
        
        String[] accountFields;
        try {
            accountFields = networkService.parseAccountFields(accountData);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse account fields", e);
            throw new Exception("Account parsing failed: " + e.getMessage());
        }
        
        String accountNumber = accountFields[0];
        String sequence = accountFields[1];
        
        Log.i(TAG, "Chain: " + chainId + ", Account: " + accountNumber + ", Sequence: " + sequence);

        // 3. Encrypt contract message
        byte[] encryptedMessage = cryptoService.encryptContractMessage(
            contractPubKeyB64, codeHash, execJson, mnemonic);
        
        Log.i(TAG, "Message encrypted, size: " + encryptedMessage.length + " bytes");

        // 4. Build and encode transaction (this would need the protobuf encoding logic)
        // For now, using a placeholder that would need to be implemented
        byte[] txBytes = buildProtobufTransaction(senderAddress, contractAddr, encryptedMessage,
                                                funds, memo, accountNumber, sequence, 
                                                chainId, walletKey, senderPubKey);

        // 5. Broadcast transaction
        String response = networkService.broadcastTransactionModern(lcdUrl, txBytes);
        
        Log.i(TAG, "Transaction broadcast successful");

        // 6. Enhance response if needed (query for execution results)
        String enhancedResponse = enhanceTransactionResponse(response, lcdUrl);

        // 7. Return result on UI thread
        runOnUiThread(() -> finishWithSuccess(enhancedResponse, senderAddress));
    }

    private byte[] buildProtobufTransaction(String sender, String contractAddr, byte[] encryptedMsg,
                                          String funds, String memo, String accountNumber, 
                                          String sequence, String chainId, ECKey walletKey,
                                          byte[] senderPubKey) throws Exception {
        // TODO: Implement proper protobuf transaction building
        // This would include creating the proper Cosmos SDK transaction structure
        // with MsgExecuteContract, signing with protobuf SignDoc, etc.
        
        // For now, this is a placeholder that would need the complex protobuf logic
        // from the original implementation
        throw new UnsupportedOperationException("Protobuf transaction building not yet implemented in refactored version");
    }

    private String enhanceTransactionResponse(String initialResponse, String lcdUrl) {
        try {
            JSONObject response = new JSONObject(initialResponse);
            if (response.has("tx_response")) {
                JSONObject txResponse = response.getJSONObject("tx_response");
                int code = txResponse.optInt("code", -1);
                String txHash = txResponse.optString("txhash", "");

                if (code == 0 && !txHash.isEmpty()) {
                    Log.i(TAG, "Transaction successful, querying for detailed results...");
                    
                    // Wait briefly for transaction processing
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

    private String getSelectedMnemonic() {
        try {
            return securePrefs.getString(KEY_MNEMONIC, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get mnemonic", e);
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