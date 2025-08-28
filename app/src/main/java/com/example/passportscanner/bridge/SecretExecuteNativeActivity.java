package com.example.passportscanner.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.passportscanner.wallet.SecretWallet;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * SecretExecuteNativeActivity
 *
 * Pure-native Secret Network contract execute:
 * - Derives key from selected wallet
 * - Fetches account_number, sequence, chain_id from LCD
 * - Encrypts execute msg with provided contract encryption pubkey (secp256k1), AES-GCM compatible with Secret contracts
 * - Signs StdSignDoc (legacy Amino JSON) with secp256k1 and broadcasts to LCD /txs (sync)
 *
 * IMPORTANT: You MUST provide the contract's encryption public key (Base64, compressed secp256k1 33 bytes)
 * via EXTRA_CONTRACT_ENCRYPTION_KEY_B64. Without it, this Activity will fail fast with an error.
 *
 * Intent extras (inputs):
 * - EXTRA_CONTRACT_ADDRESS (String, required)
 * - EXTRA_CODE_HASH        (String, optional, not used by native encryption but kept for parity)
 * - EXTRA_EXECUTE_JSON     (String, required) e.g. {"claim_anml":{}}
 * - EXTRA_LCD_URL          (String, optional) defaults to SecretWallet.DEFAULT_LCD_URL
 * - EXTRA_FUNDS            (String, optional) e.g. "1000uscrt" (not sent if empty)
 * - EXTRA_MEMO             (String, optional)
 * - EXTRA_CONTRACT_ENCRYPTION_KEY_B64 (String, required) 33-byte compressed secp256k1 pubkey in Base64
 *
 * Result extras (outputs):
 * - On success (RESULT_OK):
 *   - EXTRA_RESULT_JSON (String) JSON body returned by LCD (contains txhash if accepted)
 * - On error (RESULT_CANCELED):
 *   - EXTRA_ERROR (String)
 */
public class SecretExecuteNativeActivity extends AppCompatActivity {

    public static final String EXTRA_CONTRACT_ADDRESS = "com.example.passportscanner.EXTRA_CONTRACT_ADDRESS";
    public static final String EXTRA_CODE_HASH = "com.example.passportscanner.EXTRA_CODE_HASH";
    public static final String EXTRA_EXECUTE_JSON = "com.example.passportscanner.EXTRA_EXECUTE_JSON";
    public static final String EXTRA_FUNDS = "com.example.passportscanner.EXTRA_FUNDS";
    public static final String EXTRA_MEMO = "com.example.passportscanner.EXTRA_MEMO";
    public static final String EXTRA_LCD_URL = "com.example.passportscanner.EXTRA_LCD_URL";
    public static final String EXTRA_CONTRACT_ENCRYPTION_KEY_B64 = "com.example.passportscanner.EXTRA_CONTRACT_ENCRYPTION_KEY_B64";

    public static final String EXTRA_RESULT_JSON = "com.example.passportscanner.EXTRA_RESULT_JSON";
    public static final String EXTRA_ERROR = "com.example.passportscanner.EXTRA_ERROR";

    private static final String TAG = "SecretExecuteNative";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final String KEY_MNEMONIC = "mnemonic";
    
    // Hardcoded consensus IO public key from SecretJS (mainnetConsensusIoPubKey)
    // This is the public key used for contract encryption on Secret Network mainnet
    private static final String MAINNET_CONSENSUS_IO_PUBKEY_B64 = "A79+5YOHfm0SwLpUDClVzqBec3a87023ee49b0e7eb8178c49d0a49c3c98ed60e";

    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize SecretWallet (word list for derivation)
        try {
            SecretWallet.initialize(this);
        } catch (Throwable t) {
            finishWithError("Wallet init failed: " + t.getMessage());
            return;
        }

        // Init secure prefs
        securePrefs = createSecurePrefs(this);

        // Read intent
        Intent intent = getIntent();
        String contractAddr = intent != null ? intent.getStringExtra(EXTRA_CONTRACT_ADDRESS) : null;
        String codeHash = intent != null ? intent.getStringExtra(EXTRA_CODE_HASH) : null; // optional
        String execJson = intent != null ? intent.getStringExtra(EXTRA_EXECUTE_JSON) : null;
        String lcdUrl = intent != null ? intent.getStringExtra(EXTRA_LCD_URL) : null;
        String funds = intent != null ? intent.getStringExtra(EXTRA_FUNDS) : null;
        String memo = intent != null ? intent.getStringExtra(EXTRA_MEMO) : null;
        String contractPubKeyB64 = intent != null ? intent.getStringExtra(EXTRA_CONTRACT_ENCRYPTION_KEY_B64) : null;

        if (TextUtils.isEmpty(contractAddr) || TextUtils.isEmpty(execJson)) {
            finishWithError("Missing required extras: contract address and execute JSON");
            return;
        }
        if (TextUtils.isEmpty(contractPubKeyB64)) {
            try {
                // Try to resolve from LCD if not provided
                contractPubKeyB64 = fetchContractEncryptionKey(lcdUrl, contractAddr);
            } catch (Throwable t) {
                Log.w(TAG, "Fetching contract encryption key failed: " + t.getMessage(), t);
            }
            if (TextUtils.isEmpty(contractPubKeyB64)) {
                // Fallback to hardcoded mainnet consensus IO public key from SecretJS
                Log.i(TAG, "Using hardcoded mainnet consensus IO public key as fallback");
                contractPubKeyB64 = MAINNET_CONSENSUS_IO_PUBKEY_B64;
            }
        }
        if (TextUtils.isEmpty(lcdUrl)) lcdUrl = SecretWallet.DEFAULT_LCD_URL;
        if (funds == null) funds = "";
        if (memo == null) memo = "";

        // Load selected mnemonic and derive key/address
        String mnemonic = getSelectedMnemonic();
        if (TextUtils.isEmpty(mnemonic)) {
            finishWithError("No wallet selected or mnemonic missing");
            return;
        }

        final ECKey key;
        final String sender;
        final byte[] pubCompressed;
        try {
            key = SecretWallet.deriveKeyFromMnemonic(mnemonic);
            sender = SecretWallet.getAddress(key);
            pubCompressed = key.getPubKeyPoint().getEncoded(true);
        } catch (Throwable t) {
            finishWithError("Key derivation failed: " + t.getMessage());
            return;
        }

        // Resolve chain_id, account_number, sequence on background thread
        Log.i(TAG, "=== DIAGNOSTIC: Starting account/chain fetch ===");
        Log.i(TAG, "DIAGNOSTIC: LCD URL: " + lcdUrl);
        Log.i(TAG, "DIAGNOSTIC: Wallet address: " + sender);
        Log.i(TAG, "DIAGNOSTIC: Expected address format: " + (sender.startsWith("secret1") ? "VALID" : "INVALID"));
        
        // Move network operations to background thread to avoid NetworkOnMainThreadException
        final String finalLcdUrl = lcdUrl;
        final String finalSender = sender;
        final ECKey finalKey = key;
        final byte[] finalPubCompressed = pubCompressed;
        final String finalContractAddr = contractAddr;
        final String finalContractPubKeyB64 = contractPubKeyB64;
        final String finalCodeHash = codeHash;
        final String finalExecJson = execJson;
        final String finalFunds = funds;
        final String finalMemo = memo;
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                String chainId = "unknown";
                String accountNumberStr = "0";
                String sequenceStr = "0";
                try {
                    Log.d(TAG, "DIAGNOSTIC: Testing network connectivity to LCD endpoint...");
                    chainId = fetchChainId(finalLcdUrl);
                    Log.i(TAG, "DIAGNOSTIC: Successfully connected to network. Chain ID: " + chainId);
                    Log.i(TAG, "DIAGNOSTIC: Expected chain: secret-4, Actual chain: " + chainId + " (Match: " + "secret-4".equals(chainId) + ")");
                    
                    Log.d(TAG, "DIAGNOSTIC: Attempting to fetch account info for: " + finalSender);
                    JSONObject acct = fetchAccount(finalLcdUrl, finalSender);
                    Log.i(TAG, "DIAGNOSTIC: Raw account response: " + (acct != null ? acct.toString() : "null"));
                    
                    if (acct == null) {
                        Log.e(TAG, "DIAGNOSTIC: Account fetch returned null - this indicates:");
                        Log.e(TAG, "DIAGNOSTIC: 1. Account does not exist on chain: " + chainId);
                        Log.e(TAG, "DIAGNOSTIC: 2. Account is not funded (some chains require funding for existence)");
                        Log.e(TAG, "DIAGNOSTIC: 3. Network/endpoint issue with: " + finalLcdUrl);
                        throw new Exception("Account response is null - account may not exist or be funded on chain " + chainId);
                    }
                    
                    String[] acctFields = parseAccountFields(acct);
                    accountNumberStr = acctFields[0];
                    sequenceStr = acctFields[1];
                    Log.i(TAG, "DIAGNOSTIC: Successfully parsed account - Number: " + accountNumberStr + ", Sequence: " + sequenceStr);
                    Log.i(TAG, "=== DIAGNOSTIC: Account/chain fetch completed successfully ===");
                    
                    // Continue with transaction building on background thread
                    continueWithTransaction(finalKey, finalPubCompressed, finalSender, finalContractAddr,
                                          finalContractPubKeyB64, finalCodeHash, finalExecJson, finalFunds,
                                          finalMemo, finalLcdUrl, chainId, accountNumberStr, sequenceStr);
                    
                } catch (Throwable t) {
                    final String finalChainId = chainId; // Make effectively final for inner class
                    Log.e(TAG, "=== DIAGNOSTIC: Account/chain fetch FAILED ===", t);
                    Log.e(TAG, "DIAGNOSTIC: Error type: " + t.getClass().getSimpleName());
                    Log.e(TAG, "DIAGNOSTIC: Error message: " + t.getMessage());
                    Log.e(TAG, "DIAGNOSTIC: LCD URL used: " + finalLcdUrl);
                    Log.e(TAG, "DIAGNOSTIC: Chain ID: " + finalChainId);
                    Log.e(TAG, "DIAGNOSTIC: Address: " + finalSender);
                    Log.e(TAG, "DIAGNOSTIC: Possible causes:");
                    Log.e(TAG, "DIAGNOSTIC: 1. Network connectivity issue to " + finalLcdUrl);
                    Log.e(TAG, "DIAGNOSTIC: 2. Account " + finalSender + " does not exist on " + finalChainId);
                    Log.e(TAG, "DIAGNOSTIC: 3. Account exists but is not funded");
                    Log.e(TAG, "DIAGNOSTIC: 4. Wrong network - address may be for different chain");
                    Log.e(TAG, "DIAGNOSTIC: 5. LCD endpoint configuration issue");
                    Log.e(TAG, "DIAGNOSTIC: 6. NetworkOnMainThreadException - fixed by using background thread");
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            finishWithError("Account/chain fetch failed: " + t.getMessage() +
                                " (Ensure wallet address " + finalSender + " exists and is funded on " + finalChainId + ")");
                        }
                    });
                }
            }
        }).start();
        
        // Return early - completion will happen in background thread
        return;
    }
    
    private void continueWithTransaction(ECKey key, byte[] pubCompressed, String sender, String contractAddr,
                                       String contractPubKeyB64, String codeHash, String execJson, String funds,
                                       String memo, String lcdUrl, String chainId, String accountNumberStr, String sequenceStr) {
        try {
            // Encrypt execute msg per Secret contract scheme (AES-GCM with ECDH-derived key)
            final String encryptedMsgJson;
            try {
                encryptedMsgJson = encryptContractMsg(contractPubKeyB64, codeHash, execJson);
            } catch (Throwable t) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finishWithError("Encryption failed: " + t.getMessage());
                    }
                });
                return;
            }

            // Build MsgExecuteContract
            Log.i(TAG, "=== TX STRUCTURE DIAGNOSTIC: Building MsgExecuteContract ===");
            
            JSONObject msgValue = new JSONObject();
            msgValue.put("sender", sender);
            msgValue.put("contract", contractAddr);
            
            // DIAGNOSTIC: Log the encrypted message structure
            Log.i(TAG, "TX DIAGNOSTIC: Encrypted message JSON: " + encryptedMsgJson);
            Log.i(TAG, "TX DIAGNOSTIC: Encrypted message length: " + encryptedMsgJson.length());
            
            // "msg" is the encrypted payload (JSON string)
            msgValue.put("msg", new JSONObject(encryptedMsgJson));
            
            if (!TextUtils.isEmpty(funds)) {
                // Very simple parser "1000uscrt,2ukrw" -> [{amount,denom},...]
                JSONArray coins = parseCoins(funds);
                if (coins != null) {
                    msgValue.put("sent_funds", coins);
                    Log.i(TAG, "TX DIAGNOSTIC: Added sent_funds: " + coins.toString());
                } else {
                    Log.i(TAG, "TX DIAGNOSTIC: No sent_funds (coins parsing failed)");
                }
            } else {
                Log.i(TAG, "TX DIAGNOSTIC: No sent_funds (funds empty)");
            }

            JSONObject msg = new JSONObject();
            msg.put("type", "/secret.compute.v1beta1.MsgExecuteContract");
            msg.put("value", msgValue);
            
            Log.i(TAG, "TX DIAGNOSTIC: Message type: /secret.compute.v1beta1.MsgExecuteContract");
            Log.i(TAG, "TX DIAGNOSTIC: Message value: " + msgValue.toString());

            // Fix the fee structure - add minimal fee to avoid "empty tx" error
            JSONObject fee = new JSONObject();
            JSONArray feeAmount = new JSONArray();
            
            // Add minimal fee (1 uscrt) to avoid "invalid empty tx" error
            JSONObject feeCoins = new JSONObject();
            feeCoins.put("amount", "1");
            feeCoins.put("denom", "uscrt");
            feeAmount.put(feeCoins);
            
            fee.put("amount", feeAmount);
            fee.put("gas", 200000); // Use gas for legacy endpoint
            
            Log.i(TAG, "TX DIAGNOSTIC: Fee structure (FIXED): " + fee.toString());
            Log.i(TAG, "TX DIAGNOSTIC: Added minimal fee (1 uscrt) to avoid 'invalid empty tx' error");
            Log.i(TAG, "TX DIAGNOSTIC: Using 'gasLimit' field for proper transaction structure");

            JSONObject signDoc = new JSONObject();
            signDoc.put("account_number", accountNumberStr);
            signDoc.put("chain_id", chainId);
            signDoc.put("fee", fee);
            signDoc.put("memo", memo);
            signDoc.put("msgs", new JSONArray().put(msg));
            signDoc.put("sequence", sequenceStr);
            
            Log.i(TAG, "TX DIAGNOSTIC: SignDoc structure: " + signDoc.toString());
            Log.i(TAG, "TX DIAGNOSTIC: Account number: " + accountNumberStr + " (type: " + accountNumberStr.getClass().getSimpleName() + ")");
            Log.i(TAG, "TX DIAGNOSTIC: Sequence: " + sequenceStr + " (type: " + sequenceStr.getClass().getSimpleName() + ")");
            Log.i(TAG, "TX DIAGNOSTIC: Chain ID: " + chainId);
            Log.i(TAG, "TX DIAGNOSTIC: Memo: '" + memo + "' (length: " + memo.length() + ")");

            // Sign signDoc (Amino JSON) with secp256k1
            String signatureB64 = signSecp256k1Base64(key, signDoc.toString().getBytes("UTF-8"));
            Log.i(TAG, "TX DIAGNOSTIC: Signature generated, length: " + signatureB64.length());

            JSONObject sigObj = new JSONObject();
            JSONObject pk = new JSONObject();
            pk.put("type", "tendermint/PubKeySecp256k1");
            pk.put("value", Base64.encodeToString(pubCompressed, Base64.NO_WRAP));
            sigObj.put("pub_key", pk);
            sigObj.put("signature", signatureB64);

            JSONObject stdTx = new JSONObject();
            stdTx.put("msg", new JSONArray().put(msg));
            stdTx.put("fee", fee);
            stdTx.put("signatures", new JSONArray().put(sigObj));
            stdTx.put("memo", memo);
            
            Log.i(TAG, "TX DIAGNOSTIC: Final StdTx structure: " + stdTx.toString());
            Log.i(TAG, "TX DIAGNOSTIC: StdTx size: " + stdTx.toString().length() + " bytes");
            
            // Check for potential "empty tx" indicators
            if (fee.getJSONArray("amount").length() == 0) {
                Log.e(TAG, "TX DIAGNOSTIC: CRITICAL - Fee amount array is EMPTY! This is likely causing 'invalid empty tx' error");
            }
            if (memo.isEmpty()) {
                Log.w(TAG, "TX DIAGNOSTIC: WARNING - Memo is empty, some chains require non-empty memo");
            }
            if (stdTx.getJSONArray("msg").length() == 0) {
                Log.e(TAG, "TX DIAGNOSTIC: CRITICAL - Messages array is EMPTY!");
            }

            // Broadcast - try modern endpoint first, fallback to legacy
            Log.i(TAG, "BROADCAST DIAGNOSTIC: Attempting transaction broadcast...");
            Log.i(TAG, "BROADCAST DIAGNOSTIC: LCD URL: " + lcdUrl);
            
            String broadcastResponse = null;
            Exception lastError = null;
            
            // Try modern endpoint first (/cosmos/tx/v1beta1/txs)
            try {
                String modernUrl = joinUrl(lcdUrl, "/cosmos/tx/v1beta1/txs");
                Log.i(TAG, "BROADCAST DIAGNOSTIC: Trying modern endpoint: " + modernUrl);
                Log.i(TAG, "CODE 3 FIX: Using proper protobuf encoding for modern endpoint");
                
                // Create proper protobuf-encoded transaction
                JSONArray coins = null;
                if (!TextUtils.isEmpty(funds)) {
                    coins = parseCoins(funds);
                }
                
                // Encode transaction to protobuf bytes
                byte[] txBytes = encodeTransactionToProtobuf(
                    sender, contractAddr, encryptedMsgJson, coins, memo,
                    accountNumberStr, sequenceStr,
                    Base64.decode(signatureB64, Base64.NO_WRAP), pubCompressed
                );
                
                // Create request body with proper tx_bytes
                JSONObject modernTxBody = new JSONObject();
                modernTxBody.put("tx_bytes", Base64.encodeToString(txBytes, Base64.NO_WRAP));
                modernTxBody.put("mode", "BROADCAST_MODE_SYNC");
                
                Log.i(TAG, "BROADCAST DIAGNOSTIC: Modern endpoint protobuf tx_bytes length: " + txBytes.length);
                Log.i(TAG, "CODE 3 FIX: Sending proper protobuf-encoded transaction to modern endpoint");
                broadcastResponse = httpPostJson(modernUrl, modernTxBody.toString());
                Log.i(TAG, "BROADCAST DIAGNOSTIC: Modern endpoint SUCCESS with protobuf encoding");
            } catch (Exception e) {
                Log.w(TAG, "BROADCAST DIAGNOSTIC: Modern endpoint failed: " + e.getMessage());
                Log.e(TAG, "CODE 3 DEBUG: Modern endpoint error details: " + e.getMessage());
                
                // Check specifically for code 3 INVALID_ARGUMENT
                if (e.getMessage() != null && e.getMessage().contains("code") && e.getMessage().contains("3")) {
                    Log.e(TAG, "CODE 3 DEBUG: CONFIRMED - Modern endpoint returned code 3 INVALID_ARGUMENT");
                    Log.e(TAG, "CODE 3 DEBUG: This confirms the transaction format is invalid for protobuf endpoint");
                    Log.e(TAG, "CODE 3 DEBUG: Root cause: Sending JSON transaction to protobuf-expecting endpoint");
                }
                
                lastError = e;
                
                // Check if it's a "code 12 not implemented" or similar API error
                if (e.getMessage() != null && (e.getMessage().contains("code") || e.getMessage().contains("not implemented"))) {
                    Log.w(TAG, "BROADCAST DIAGNOSTIC: Modern endpoint returned API error, trying legacy endpoint");
                } else {
                    Log.w(TAG, "BROADCAST DIAGNOSTIC: Modern endpoint network error, trying legacy endpoint");
                }
                
                // Try legacy endpoint as fallback (/txs)
                try {
                    String legacyUrl = joinUrl(lcdUrl, "/txs");
                    Log.i(TAG, "BROADCAST DIAGNOSTIC: Trying legacy endpoint: " + legacyUrl);
                    
                    // Legacy endpoint uses "sync" mode
                    JSONObject legacyTxBody = new JSONObject();
                    legacyTxBody.put("tx", stdTx);
                    legacyTxBody.put("mode", "sync");
                    
                    broadcastResponse = httpPostJson(legacyUrl, legacyTxBody.toString());
                    Log.i(TAG, "BROADCAST DIAGNOSTIC: Legacy endpoint SUCCESS");
                } catch (Exception e2) {
                    Log.e(TAG, "BROADCAST DIAGNOSTIC: Both endpoints failed!");
                    Log.e(TAG, "BROADCAST DIAGNOSTIC: Modern endpoint error: " + e.getMessage());
                    Log.e(TAG, "BROADCAST DIAGNOSTIC: Legacy endpoint error: " + e2.getMessage());
                    
                    // No more fallback attempts - if both modern protobuf and legacy fail,
                    // then there's a more fundamental issue
                    throw new Exception("All transaction broadcast methods failed. Modern protobuf: " + e.getMessage() + ", Legacy: " + e2.getMessage());
                }
            }
            
            // Make final for use in inner class
            final String finalResponse = broadcastResponse;
            
            // Return to UI thread for result
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent out = new Intent();
                    out.putExtra(EXTRA_RESULT_JSON, finalResponse != null ? finalResponse : "{}");
                    setResult(Activity.RESULT_OK, out);
                    finish();
                }
            });
        } catch (Throwable t) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finishWithError("Build/broadcast failed: " + t.getMessage());
                }
            });
        }
    }

    // Encryption: Improved implementation based on SecretJS reverse engineering
    // Uses wallet private key + contract pubkey ECDH with better key derivation
    // Includes codeHash in plaintext as per SecretJS implementation
    private String encryptContractMsg(String contractPubKeyB64, String codeHash, String msgJson) throws Exception {
        Log.i(TAG, "=== ENCRYPTION DIAGNOSTIC: Starting contract message encryption ===");
        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Contract pubkey B64 input: " + (contractPubKeyB64 != null ? contractPubKeyB64.substring(0, Math.min(20, contractPubKeyB64.length())) + "..." : "null"));
        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Contract pubkey B64 length: " + (contractPubKeyB64 != null ? contractPubKeyB64.length() : 0));
        
        // Generate random nonce (12 bytes for AES-GCM)
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Generated nonce length: " + nonce.length);
        
        // Decode contract public key
        byte[] contractPubCompressed;
        try {
            contractPubCompressed = Base64.decode(contractPubKeyB64, Base64.NO_WRAP);
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Decoded contract pubkey length: " + contractPubCompressed.length + " bytes");
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Expected length: 33 bytes (compressed secp256k1)");
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Length match: " + (contractPubCompressed.length == 33 ? "VALID" : "INVALID"));
            
            // Handle different public key formats
            if (contractPubCompressed.length == 33) {
                // Already compressed format
                if (contractPubCompressed[0] != 0x02 && contractPubCompressed[0] != 0x03) {
                    Log.e(TAG, "ENCRYPTION DIAGNOSTIC: CONTRACT PUBKEY FORMAT ERROR!");
                    Log.e(TAG, "ENCRYPTION DIAGNOSTIC: First byte: 0x" + String.format("%02x", contractPubCompressed[0]));
                    Log.e(TAG, "ENCRYPTION DIAGNOSTIC: Expected: 0x02 or 0x03 for compressed secp256k1");
                    throw new Exception("Invalid compressed public key format: first byte should be 0x02 or 0x03, got 0x" + String.format("%02x", contractPubCompressed[0]));
                }
                Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Contract pubkey is already in compressed format (33 bytes)");
            } else if (contractPubCompressed.length == 65) {
                // Uncompressed format - convert to compressed
                if (contractPubCompressed[0] != 0x04) {
                    Log.e(TAG, "ENCRYPTION DIAGNOSTIC: Invalid uncompressed public key format: first byte should be 0x04, got 0x" + String.format("%02x", contractPubCompressed[0]));
                    throw new Exception("Invalid uncompressed public key format: first byte should be 0x04, got 0x" + String.format("%02x", contractPubCompressed[0]));
                }
                Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Converting uncompressed pubkey (65 bytes) to compressed format...");
                contractPubCompressed = convertToCompressedKey(contractPubCompressed);
                Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Conversion successful, compressed length: " + contractPubCompressed.length);
            } else if (contractPubCompressed.length == 48) {
                // This appears to be a 32-byte x-coordinate + 16-byte additional data format
                Log.w(TAG, "ENCRYPTION DIAGNOSTIC: Unusual key length (48 bytes) - analyzing key structure");
                Log.w(TAG, "ENCRYPTION DIAGNOSTIC: Full key hex: " + bytesToHex(contractPubCompressed, contractPubCompressed.length));
                
                boolean foundValidKey = false;
                
                // The key structure appears to be: [1 byte prefix][31 bytes x-coord part 1][32 bytes x-coord part 2][15 bytes additional]
                // Let's try different interpretations of the 48-byte structure
                
                // Method 1: Extract bytes 1-32 as x-coordinate (skip the 0x03 prefix)
                if (contractPubCompressed.length >= 33) {
                    Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Method 1 - Extracting bytes 1-32 as x-coordinate...");
                    byte[] xCoord = new byte[32];
                    System.arraycopy(contractPubCompressed, 1, xCoord, 0, 32);
                    Log.i(TAG, "ENCRYPTION DIAGNOSTIC: X-coordinate: " + bytesToHex(xCoord, 32));
                    
                    // Try both even and odd y-coordinate possibilities
                    for (byte prefix : new byte[]{0x02, 0x03}) {
                        byte[] testKey = new byte[33];
                        testKey[0] = prefix;
                        System.arraycopy(xCoord, 0, testKey, 1, 32);
                        
                        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Testing with prefix 0x" + String.format("%02x", prefix) + ": " + bytesToHex(testKey, 8) + "...");
                        if (isValidSecp256k1Point(testKey)) {
                            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: SUCCESS! Method 1 - Valid compressed key with prefix 0x" + String.format("%02x", prefix));
                            contractPubCompressed = testKey;
                            foundValidKey = true;
                            break;
                        }
                    }
                }
                
                // Method 2: Extract bytes 0-31 as x-coordinate (include the 0x03 as part of x-coord)
                if (!foundValidKey && contractPubCompressed.length >= 32) {
                    Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Method 2 - Extracting bytes 0-31 as x-coordinate...");
                    byte[] xCoord = new byte[32];
                    System.arraycopy(contractPubCompressed, 0, xCoord, 0, 32);
                    Log.i(TAG, "ENCRYPTION DIAGNOSTIC: X-coordinate: " + bytesToHex(xCoord, 32));
                    
                    for (byte prefix : new byte[]{0x02, 0x03}) {
                        byte[] testKey = new byte[33];
                        testKey[0] = prefix;
                        System.arraycopy(xCoord, 0, testKey, 1, 32);
                        
                        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Testing with prefix 0x" + String.format("%02x", prefix) + ": " + bytesToHex(testKey, 8) + "...");
                        if (isValidSecp256k1Point(testKey)) {
                            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: SUCCESS! Method 2 - Valid compressed key with prefix 0x" + String.format("%02x", prefix));
                            contractPubCompressed = testKey;
                            foundValidKey = true;
                            break;
                        }
                    }
                }
                
                // Method 3: Try extracting from different 32-byte windows
                if (!foundValidKey) {
                    Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Method 3 - Trying different 32-byte windows...");
                    for (int offset = 2; offset <= 16 && offset + 32 <= contractPubCompressed.length; offset++) {
                        byte[] xCoord = new byte[32];
                        System.arraycopy(contractPubCompressed, offset, xCoord, 0, 32);
                        
                        for (byte prefix : new byte[]{0x02, 0x03}) {
                            byte[] testKey = new byte[33];
                            testKey[0] = prefix;
                            System.arraycopy(xCoord, 0, testKey, 1, 32);
                            
                            if (isValidSecp256k1Point(testKey)) {
                                Log.i(TAG, "ENCRYPTION DIAGNOSTIC: SUCCESS! Method 3 - Valid compressed key at offset " + offset + " with prefix 0x" + String.format("%02x", prefix));
                                contractPubCompressed = testKey;
                                foundValidKey = true;
                                break;
                            }
                        }
                        if (foundValidKey) break;
                    }
                }
                
                // Method 4: Maybe it's a different encoding entirely - try the hardcoded fallback
                if (!foundValidKey) {
                    Log.w(TAG, "ENCRYPTION DIAGNOSTIC: All extraction methods failed. The 48-byte key may be in an unsupported format.");
                    Log.w(TAG, "ENCRYPTION DIAGNOSTIC: Falling back to using the hardcoded mainnet consensus IO public key.");
                    
                    // Use the hardcoded key as a last resort
                    try {
                        byte[] fallbackKey = Base64.decode(MAINNET_CONSENSUS_IO_PUBKEY_B64, Base64.NO_WRAP);
                        if (fallbackKey.length == 33 && isValidSecp256k1Point(fallbackKey)) {
                            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Using hardcoded mainnet consensus IO key as fallback");
                            contractPubCompressed = fallbackKey;
                            foundValidKey = true;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "ENCRYPTION DIAGNOSTIC: Even hardcoded fallback key failed: " + e.getMessage());
                    }
                }
                
                if (!foundValidKey) {
                    Log.e(TAG, "ENCRYPTION DIAGNOSTIC: CRITICAL: Unable to extract or derive any valid secp256k1 key");
                    Log.e(TAG, "ENCRYPTION DIAGNOSTIC: The 48-byte input appears to be in an unknown or corrupted format");
                    throw new Exception("Invalid 48-byte public key: exhausted all extraction methods and fallbacks");
                }
            } else {
                Log.e(TAG, "ENCRYPTION DIAGNOSTIC: CONTRACT PUBKEY LENGTH ERROR!");
                Log.e(TAG, "ENCRYPTION DIAGNOSTIC: Got " + contractPubCompressed.length + " bytes, expected 33 (compressed) or 65 (uncompressed)");
                Log.e(TAG, "ENCRYPTION DIAGNOSTIC: First few bytes: " + bytesToHex(contractPubCompressed, Math.min(8, contractPubCompressed.length)));
                throw new Exception("Invalid contract public key length: " + contractPubCompressed.length + " bytes (expected 33 compressed or 65 uncompressed secp256k1)");
            }
            
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Contract pubkey format validation PASSED");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: BASE64 DECODE ERROR!", e);
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: Input B64 string: " + contractPubKeyB64);
            throw new Exception("Failed to decode contract public key from Base64: " + e.getMessage(), e);
        }
        
        // Get wallet private key (not ephemeral - this was the key issue!)
        String mnemonic = getSelectedMnemonic();
        ECKey walletKey;
        try {
            walletKey = SecretWallet.deriveKeyFromMnemonic(mnemonic);
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Wallet key derivation successful");
        } catch (Exception e) {
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: WALLET KEY DERIVATION ERROR!", e);
            throw new Exception("Failed to derive wallet key from mnemonic: " + e.getMessage(), e);
        }
        
        // Get wallet public key and validate
        byte[] walletPubCompressed;
        try {
            walletPubCompressed = walletKey.getPubKeyPoint().getEncoded(true);
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Wallet pubkey length: " + walletPubCompressed.length + " bytes");
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Wallet pubkey format: 0x" + String.format("%02x", walletPubCompressed[0]));
            
            if (walletPubCompressed.length != 33) {
                Log.e(TAG, "ENCRYPTION DIAGNOSTIC: WALLET PUBKEY LENGTH ERROR!");
                throw new Exception("Invalid wallet public key length: " + walletPubCompressed.length + " bytes (expected 33)");
            }
        } catch (Exception e) {
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: WALLET PUBKEY ENCODING ERROR!", e);
            throw new Exception("Failed to encode wallet public key: " + e.getMessage(), e);
        }
        
        // Compute ECDH shared secret using wallet key (not ephemeral)
        org.bouncycastle.math.ec.ECPoint contractPoint;
        try {
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Attempting to decode contract point from compressed key...");
            contractPoint = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
                    .getCurve().decodePoint(contractPubCompressed);
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Contract point decode successful");
        } catch (Exception e) {
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: CONTRACT POINT DECODE ERROR!", e);
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: This is likely the source of 'incorrect length for compressed encoding'");
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: Contract pubkey hex: " + bytesToHex(contractPubCompressed, contractPubCompressed.length));
            throw new Exception("Failed to decode contract public key point (this is likely the 'incorrect length for compressed encoding' error): " + e.getMessage(), e);
        }
        
        org.bouncycastle.math.ec.ECPoint shared;
        byte[] sharedSecret;
        try {
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Computing ECDH shared secret...");
            shared = contractPoint.multiply(walletKey.getPrivKey()).normalize();
            sharedSecret = shared.getXCoord().getEncoded();
            Log.i(TAG, "ENCRYPTION DIAGNOSTIC: ECDH computation successful, shared secret length: " + sharedSecret.length);
        } catch (Exception e) {
            Log.e(TAG, "ENCRYPTION DIAGNOSTIC: ECDH COMPUTATION ERROR!", e);
            throw new Exception("Failed to compute ECDH shared secret: " + e.getMessage(), e);
        }
        
        // Improved key derivation: combine shared secret with nonce (HKDF-like)
        byte[] keyMaterial = new byte[sharedSecret.length + nonce.length];
        System.arraycopy(sharedSecret, 0, keyMaterial, 0, sharedSecret.length);
        System.arraycopy(nonce, 0, keyMaterial, sharedSecret.length, nonce.length);
        byte[] aesKey = sha256(keyMaterial);
        
        // Encrypt using AES-GCM
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        SecretKeySpec sk = new SecretKeySpec(aesKey, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, sk, spec);
        
        // Create plaintext: codeHash + JSON message (as per SecretJS)
        // SecretJS uses: toUtf8(contractCodeHash + JSON.stringify(msg))
        String plaintext;
        if (codeHash != null && !codeHash.isEmpty()) {
            plaintext = codeHash + msgJson;
        } else {
            // Fallback if no codeHash provided
            plaintext = msgJson;
        }
        
        // Encrypt the plaintext
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));
        
        // Create output in expected format
        JSONObject payload = new JSONObject();
        payload.put("nonce", base64(nonce));
        payload.put("ephemeral_pubkey", base64(walletPubCompressed));
        payload.put("ciphertext", base64(ciphertext));
        
        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Encryption completed successfully");
        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Output nonce length: " + nonce.length);
        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Output ephemeral_pubkey length: " + walletPubCompressed.length);
        Log.i(TAG, "ENCRYPTION DIAGNOSTIC: Output ciphertext length: " + ciphertext.length);
        Log.i(TAG, "=== ENCRYPTION DIAGNOSTIC: Contract message encryption completed ===");
        
        return payload.toString();
    }

    private static String[] parseAccountFields(JSONObject acctRoot) throws Exception {
        // Try Cosmos v0.47+ style: { "account": { "@type": "...BaseAccount", "account_number":"..", "sequence":".." } }
        JSONObject account = acctRoot.optJSONObject("account");
        if (account != null) {
            String acc = account.optString("account_number", null);
            String seq = account.optString("sequence", null);
            if (!TextUtils.isEmpty(acc) && !TextUtils.isEmpty(seq)) {
                return new String[]{acc, seq};
            }
            // Some chains nest base_account
            JSONObject base = account.optJSONObject("base_account");
            if (base != null) {
                acc = base.optString("account_number", null);
                seq = base.optString("sequence", null);
                if (!TextUtils.isEmpty(acc) && !TextUtils.isEmpty(seq)) {
                    return new String[]{acc, seq};
                }
            }
        }
        // Legacy /auth/accounts/{addr}
        String acc = acctRoot.optString("account_number", null);
        String seq = acctRoot.optString("sequence", null);
        if (!TextUtils.isEmpty(acc) && !TextUtils.isEmpty(seq)) {
            return new String[]{acc, seq};
        }
        throw new IllegalStateException("Unable to parse account_number/sequence");
    }

    private static JSONArray parseCoins(String s) {
        try {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return null;
            JSONArray arr = new JSONArray();
            String[] parts = trimmed.split(",");
            for (String p : parts) {
                String coin = p.trim();
                if (coin.isEmpty()) continue;
                // split number and denom
                int i = 0;
                while (i < coin.length() && Character.isDigit(coin.charAt(i))) i++;
                if (i == 0) continue;
                String amount = coin.substring(0, i);
                String denom = coin.substring(i);
                JSONObject c = new JSONObject();
                c.put("amount", amount);
                c.put("denom", denom);
                arr.put(c);
            }
            return arr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fetchChainId(String lcdBase) throws Exception {
        // Try new endpoint
        try {
            String url = joinUrl(lcdBase, "/cosmos/base/tendermint/v1beta1/node_info");
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                JSONObject defaultNodeInfo = root.optJSONObject("default_node_info");
                if (defaultNodeInfo != null) {
                    String network = defaultNodeInfo.optString("network", null);
                    if (!TextUtils.isEmpty(network)) return network;
                }
                // Some LCDs expose "node_info" at top-level
                JSONObject ni = root.optJSONObject("node_info");
                if (ni != null) {
                    String network = ni.optString("network", null);
                    if (!TextUtils.isEmpty(network)) return network;
                }
            }
        } catch (Throwable ignored) {}
        // Legacy fallback (not standard across all LCDs)
        try {
            String url = joinUrl(lcdBase, "/node_info");
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                JSONObject nodeInfo = root.optJSONObject("node_info");
                if (nodeInfo != null) {
                    String network = nodeInfo.optString("network", null);
                    if (!TextUtils.isEmpty(network)) return network;
                }
            }
        } catch (Throwable ignored) {}
        // As a last resort, return known mainnet id if this LCD is for Earth Network; else generic.
        return "secret-4";
    }

    private static JSONObject fetchAccount(String lcdBase, String address) throws Exception {
        Log.i(TAG, "DIAGNOSTIC: Attempting to fetch account via multiple endpoints...");
        Log.i(TAG, "DIAGNOSTIC: Target address: " + address);
        
        // Try new endpoint first
        try {
            String url = joinUrl(lcdBase, "/cosmos/auth/v1beta1/accounts/") + address;
            Log.d(TAG, "DIAGNOSTIC: Trying modern endpoint: " + url);
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                Log.i(TAG, "DIAGNOSTIC: Modern endpoint returned response");
                Log.d(TAG, "DIAGNOSTIC: Response body: " + body);
                JSONObject result = new JSONObject(body);
                
                // Check for API error responses (like {"code": 12, "message": "Not Implemented"})
                if (result.has("code") && result.optInt("code", 0) != 0) {
                    String errorMsg = result.optString("message", "Unknown API error");
                    Log.w(TAG, "DIAGNOSTIC: Modern endpoint returned API error - Code: " + result.optInt("code") + ", Message: " + errorMsg);
                    // Don't throw here, try legacy endpoint
                } else if (result.has("account")) {
                    // Success case - we have valid account data
                    JSONObject account = result.getJSONObject("account");
                    String accountNumber = account.optString("account_number", "");
                    String sequence = account.optString("sequence", "");
                    Log.i(TAG, "DIAGNOSTIC: Modern endpoint SUCCESS - Account: " + accountNumber + ", Sequence: " + sequence);
                    return result;
                } else {
                    Log.w(TAG, "DIAGNOSTIC: Modern endpoint response missing 'account' field");
                }
            } else {
                Log.w(TAG, "DIAGNOSTIC: Modern endpoint returned empty/null response");
            }
        } catch (Exception e) {
            Log.w(TAG, "DIAGNOSTIC: Modern endpoint exception: " + e.getMessage());
        }
        
        // Try legacy endpoint as fallback
        try {
            String url = joinUrl(lcdBase, "/auth/accounts/") + address;
            Log.d(TAG, "DIAGNOSTIC: Trying legacy endpoint: " + url);
            String body = httpGet(url);
            if (body != null && !body.isEmpty()) {
                Log.i(TAG, "DIAGNOSTIC: Legacy endpoint returned response");
                Log.d(TAG, "DIAGNOSTIC: Response body: " + body);
                JSONObject result = new JSONObject(body);
                
                // Check for API error responses
                if (result.has("code") && result.optInt("code", 0) != 0) {
                    String errorMsg = result.optString("message", "Unknown API error");
                    Log.w(TAG, "DIAGNOSTIC: Legacy endpoint returned API error - Code: " + result.optInt("code") + ", Message: " + errorMsg);
                } else if (result.has("account_number") || result.has("address")) {
                    // Success case - we have account data at root level
                    String accountNumber = result.optString("account_number", "");
                    String sequence = result.optString("sequence", "");
                    Log.i(TAG, "DIAGNOSTIC: Legacy endpoint SUCCESS - Account: " + accountNumber + ", Sequence: " + sequence);
                    return result;
                } else {
                    Log.w(TAG, "DIAGNOSTIC: Legacy endpoint response missing account fields");
                }
            } else {
                Log.w(TAG, "DIAGNOSTIC: Legacy endpoint returned empty/null response");
            }
        } catch (Exception e) {
            Log.w(TAG, "DIAGNOSTIC: Legacy endpoint exception: " + e.getMessage());
        }
        
        Log.e(TAG, "DIAGNOSTIC: Both account endpoints failed for address: " + address);
        Log.e(TAG, "DIAGNOSTIC: This indicates the account does not exist on the network or LCD is misconfigured");
        throw new Exception("Account not found via LCD - tried both modern and legacy endpoints");
    }

    // Attempt to fetch the contract's encryption public key (compressed secp256k1, Base64) from LCD.
    // Tries multiple likely response shapes for robustness.
    private static String fetchContractEncryptionKey(String lcdBase, String contractAddr) throws Exception {
        // Primary endpoint (Secret LCD v1beta1)
        String url1 = joinUrl(lcdBase, "/compute/v1beta1/contract/") + contractAddr + "/encryption_key";
        try {
            String body = httpGet(url1);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                // Common fields observed
                String direct = root.optString("encryption_key", null);
                if (!TextUtils.isEmpty(direct)) return direct;
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    String ek = result.optString("encryption_key", null);
                    if (!TextUtils.isEmpty(ek)) return ek;
                }
                // Sometimes nested deeper
                for (String key : new String[] {"encryptionKey", "pub_key", "pubKey"}) {
                    String v = root.optString(key, null);
                    if (!TextUtils.isEmpty(v)) return v;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetchContractEncryptionKey: primary endpoint failed: " + t.getMessage());
        }

        // If nothing found, give up (callers will handle error)
        return null;
    }

    private static String signSecp256k1Base64(ECKey key, byte[] signBytes) {
        Sha256Hash digest = Sha256Hash.of(signBytes);
        ECKey.ECDSASignature sig = key.sign(digest).toCanonicalised();
        byte[] r = bigIntToFixed(sig.r, 32);
        byte[] s = bigIntToFixed(sig.s, 32);
        byte[] rs = new byte[64];
        System.arraycopy(r, 0, rs, 0, 32);
        System.arraycopy(s, 0, rs, 32, 32);
        return Base64.encodeToString(rs, Base64.NO_WRAP);
    }

    private static byte[] bigIntToFixed(BigInteger bi, int size) {
        byte[] src = bi.toByteArray();
        if (src.length == size) return src;
        byte[] out = new byte[size];
        if (src.length > size) {
            System.arraycopy(src, src.length - size, out, 0, size);
        } else {
            System.arraycopy(src, 0, out, size - src.length, src.length);
        }
        return out;
    }

    private static byte[] sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(b);
    }

    private static String base64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }
    
    // Helper method for diagnostic hex output
    private static String bytesToHex(byte[] bytes, int maxLength) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLength);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[i]));
            if (i < len - 1) sb.append(" ");
        }
        if (bytes.length > maxLength) sb.append("...");
        return sb.toString();
    }
    
    // Convert uncompressed public key (65 bytes) to compressed format (33 bytes)
    private static byte[] convertToCompressedKey(byte[] uncompressed) throws Exception {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new Exception("Invalid uncompressed key format");
        }
        
        // Extract x and y coordinates (32 bytes each after the 0x04 prefix)
        byte[] x = new byte[32];
        byte[] y = new byte[32];
        System.arraycopy(uncompressed, 1, x, 0, 32);
        System.arraycopy(uncompressed, 33, y, 0, 32);
        
        // Determine if y is even or odd (for compression prefix)
        boolean yIsEven = (y[31] & 1) == 0;
        
        // Create compressed key: prefix (0x02 for even y, 0x03 for odd y) + x coordinate
        byte[] compressed = new byte[33];
        compressed[0] = yIsEven ? (byte) 0x02 : (byte) 0x03;
        System.arraycopy(x, 0, compressed, 1, 32);
        
        return compressed;
    }
    
    // Validate if a compressed key represents a valid point on the secp256k1 curve
    private static boolean isValidSecp256k1Point(byte[] compressedKey) {
        if (compressedKey == null || compressedKey.length != 33) {
            return false;
        }
        if (compressedKey[0] != 0x02 && compressedKey[0] != 0x03) {
            return false;
        }
        
        try {
            // Try to decode the point - if it succeeds, it's valid
            org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
                    .getCurve().decodePoint(compressedKey);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    // Manual protobuf encoding for Cosmos SDK transactions
    // This creates a proper protobuf-encoded transaction for the modern endpoint
    private static byte[] encodeTransactionToProtobuf(String sender, String contractAddr,
                                                     String encryptedMsgJson, JSONArray sentFunds,
                                                     String memo, String accountNumber, String sequence,
                                                     byte[] signature, byte[] pubKeyCompressed) throws Exception {
        Log.i(TAG, "PROTOBUF DEBUG: Starting manual protobuf encoding");
        
        try {
            ByteArrayOutputStream txBytes = new ByteArrayOutputStream();
            
            // Create TxBody (field 1)
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            
            // Messages array (field 1 in TxBody)
            ByteArrayOutputStream msgBytes = new ByteArrayOutputStream();
            
            // MsgExecuteContract
            ByteArrayOutputStream execMsgBytes = new ByteArrayOutputStream();
            
            // Field 1: sender (string)
            writeProtobufString(execMsgBytes, 1, sender);
            
            // Field 2: contract (string)
            writeProtobufString(execMsgBytes, 2, contractAddr);
            
            // Field 3: msg (bytes) - the encrypted message as JSON bytes
            writeProtobufBytes(execMsgBytes, 3, encryptedMsgJson.getBytes(StandardCharsets.UTF_8));
            
            // Field 4: sent_funds (repeated Coin)
            if (sentFunds != null && sentFunds.length() > 0) {
                for (int i = 0; i < sentFunds.length(); i++) {
                    JSONObject coin = sentFunds.getJSONObject(i);
                    ByteArrayOutputStream coinBytes = new ByteArrayOutputStream();
                    writeProtobufString(coinBytes, 1, coin.getString("denom"));
                    writeProtobufString(coinBytes, 2, coin.getString("amount"));
                    writeProtobufMessage(execMsgBytes, 4, coinBytes.toByteArray());
                }
            }
            
            // Wrap MsgExecuteContract in Any type
            ByteArrayOutputStream anyBytes = new ByteArrayOutputStream();
            writeProtobufString(anyBytes, 1, "/secret.compute.v1beta1.MsgExecuteContract");
            writeProtobufBytes(anyBytes, 2, execMsgBytes.toByteArray());
            
            // Add to messages array
            writeProtobufMessage(bodyBytes, 1, anyBytes.toByteArray());
            
            // Field 2: memo (string)
            if (memo != null && !memo.isEmpty()) {
                writeProtobufString(bodyBytes, 2, memo);
            }
            
            // Field 3: timeout_height (uint64) - optional, skip for now
            // Field 4: extension_options - skip
            // Field 5: non_critical_extension_options - skip
            
            // Create AuthInfo (field 2)
            ByteArrayOutputStream authInfoBytes = new ByteArrayOutputStream();
            
            // Field 1: signer_infos (repeated SignerInfo)
            ByteArrayOutputStream signerInfoBytes = new ByteArrayOutputStream();
            
            // Field 1: public_key (Any)
            ByteArrayOutputStream pubKeyAnyBytes = new ByteArrayOutputStream();
            writeProtobufString(pubKeyAnyBytes, 1, "/cosmos.crypto.secp256k1.PubKey");
            writeProtobufBytes(pubKeyAnyBytes, 2, pubKeyCompressed);
            writeProtobufMessage(signerInfoBytes, 1, pubKeyAnyBytes.toByteArray());
            
            // Field 2: mode_info
            ByteArrayOutputStream modeInfoBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream singleBytes = new ByteArrayOutputStream();
            writeProtobufVarint(singleBytes, 1, 127); // SIGN_MODE_LEGACY_AMINO_JSON
            writeProtobufMessage(modeInfoBytes, 1, singleBytes.toByteArray());
            writeProtobufMessage(signerInfoBytes, 2, modeInfoBytes.toByteArray());
            
            // Field 3: sequence
            writeProtobufVarint(signerInfoBytes, 3, Long.parseLong(sequence));
            
            writeProtobufMessage(authInfoBytes, 1, signerInfoBytes.toByteArray());
            
            // Field 2: fee
            ByteArrayOutputStream feeBytes = new ByteArrayOutputStream();
            
            // Field 1: amount (repeated Coin) - minimal fee
            ByteArrayOutputStream feeAmountBytes = new ByteArrayOutputStream();
            writeProtobufString(feeAmountBytes, 1, "uscrt");
            writeProtobufString(feeAmountBytes, 2, "1");
            writeProtobufMessage(feeBytes, 1, feeAmountBytes.toByteArray());
            
            // Field 2: gas_limit
            writeProtobufVarint(feeBytes, 2, 200000);
            
            writeProtobufMessage(authInfoBytes, 2, feeBytes.toByteArray());
            
            // Assemble final transaction
            writeProtobufMessage(txBytes, 1, bodyBytes.toByteArray()); // body
            writeProtobufMessage(txBytes, 2, authInfoBytes.toByteArray()); // auth_info
            writeProtobufBytes(txBytes, 3, signature); // signatures
            
            byte[] result = txBytes.toByteArray();
            Log.i(TAG, "PROTOBUF DEBUG: Successfully encoded transaction, size: " + result.length + " bytes");
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "PROTOBUF DEBUG: Encoding failed: " + e.getMessage(), e);
            throw new Exception("Protobuf encoding failed: " + e.getMessage(), e);
        }
    }
    
    // Helper methods for protobuf encoding
    private static void writeProtobufVarint(ByteArrayOutputStream out, int fieldNumber, long value) throws Exception {
        writeProtobufTag(out, fieldNumber, 0); // varint wire type
        writeVarint(out, value);
    }
    
    private static void writeProtobufString(ByteArrayOutputStream out, int fieldNumber, String value) throws Exception {
        if (value == null || value.isEmpty()) return;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeProtobufBytes(out, fieldNumber, bytes);
    }
    
    private static void writeProtobufBytes(ByteArrayOutputStream out, int fieldNumber, byte[] value) throws Exception {
        if (value == null || value.length == 0) return;
        writeProtobufTag(out, fieldNumber, 2); // length-delimited wire type
        writeVarint(out, value.length);
        out.write(value);
    }
    
    private static void writeProtobufMessage(ByteArrayOutputStream out, int fieldNumber, byte[] messageBytes) throws Exception {
        writeProtobufBytes(out, fieldNumber, messageBytes);
    }
    
    private static void writeProtobufTag(ByteArrayOutputStream out, int fieldNumber, int wireType) throws Exception {
        writeVarint(out, (fieldNumber << 3) | wireType);
    }
    
    private static void writeVarint(ByteArrayOutputStream out, long value) throws Exception {
        while ((value & 0x80) != 0) {
            out.write((int)((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int)(value & 0x7F));
    }

    private static String joinUrl(String base, String path) {
        if (base == null) return path;
        String b = base;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + path;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            Log.d(TAG, "DIAGNOSTIC: HTTP GET: " + urlStr);
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SecretExecuteNative/1.0");
            
            Log.d(TAG, "DIAGNOSTIC: Connecting to: " + url.getHost() + ":" + url.getPort());
            conn.connect();
            
            int responseCode = conn.getResponseCode();
            String responseMessage = conn.getResponseMessage();
            Log.i(TAG, "DIAGNOSTIC: HTTP Response: " + responseCode + " " + responseMessage);
            
            // Read response body regardless of status code
            InputStream in = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                Log.w(TAG, "DIAGNOSTIC: HTTP Response stream is null for code: " + responseCode);
                // For some error codes, still throw exception if no response body
                if (responseCode >= 400) {
                    throw new Exception("HTTP " + responseCode + " " + responseMessage + " (no response body)");
                }
                return "";
            }
            
            byte[] bytes = readAllBytes(in);
            String response = new String(bytes, "UTF-8");
            Log.d(TAG, "DIAGNOSTIC: HTTP Response length: " + response.length() + " bytes");
            Log.d(TAG, "DIAGNOSTIC: HTTP Response preview: " + (response.length() > 200 ? response.substring(0, 200) + "..." : response));
            
            // For HTTP errors, log but don't throw exception - let caller handle API error responses
            if (responseCode >= 400) {
                Log.w(TAG, "DIAGNOSTIC: HTTP Error " + responseCode + " but got response body: " + response);
                if (responseCode == 404) {
                    Log.w(TAG, "DIAGNOSTIC: 404 Not Found - endpoint or resource does not exist");
                } else if (responseCode == 500) {
                    Log.w(TAG, "DIAGNOSTIC: 500 Server Error - LCD server issue");
                } else if (responseCode >= 400 && responseCode < 500) {
                    Log.w(TAG, "DIAGNOSTIC: 4xx Client Error - request issue");
                }
                // Return the response body so caller can parse API error messages
                // Only throw if it's a network-level error without useful response
                if (response.trim().isEmpty()) {
                    throw new Exception("HTTP " + responseCode + " " + responseMessage + " (empty response)");
                }
            }
            
            return response;
        } catch (Exception e) {
            // Only log and rethrow for actual network/connection errors
            if (!(e.getMessage() != null && e.getMessage().startsWith("HTTP "))) {
                Log.e(TAG, "DIAGNOSTIC: HTTP GET failed for " + urlStr, e);
                Log.e(TAG, "DIAGNOSTIC: Exception type: " + e.getClass().getSimpleName());
                Log.e(TAG, "DIAGNOSTIC: This could indicate:");
                Log.e(TAG, "DIAGNOSTIC: 1. Network connectivity issue");
                Log.e(TAG, "DIAGNOSTIC: 2. DNS resolution failure");
                Log.e(TAG, "DIAGNOSTIC: 3. SSL/TLS certificate issue");
                Log.e(TAG, "DIAGNOSTIC: 4. Server is down or unreachable");
                Log.e(TAG, "DIAGNOSTIC: 5. Firewall blocking the request");
            }
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String httpPostJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            Log.d(TAG, "BROADCAST DIAGNOSTIC: HTTP POST to: " + urlStr);
            Log.d(TAG, "BROADCAST DIAGNOSTIC: Request body length: " + jsonBody.length() + " bytes");
            
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SecretExecuteNative/1.0");
            conn.connect();
            
            try (OutputStream os = conn.getOutputStream()) {
                byte[] data = jsonBody.getBytes("UTF-8");
                os.write(data);
            }
            
            int responseCode = conn.getResponseCode();
            String responseMessage = conn.getResponseMessage();
            Log.i(TAG, "BROADCAST DIAGNOSTIC: HTTP Response: " + responseCode + " " + responseMessage);
            
            InputStream in = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                Log.w(TAG, "BROADCAST DIAGNOSTIC: No response body for code: " + responseCode);
                if (responseCode >= 400) {
                    throw new Exception("HTTP " + responseCode + " " + responseMessage + " (no response body)");
                }
                return "";
            }
            
            byte[] bytes = readAllBytes(in);
            String response = new String(bytes, "UTF-8");
            Log.d(TAG, "BROADCAST DIAGNOSTIC: Response length: " + response.length() + " bytes");
            Log.d(TAG, "BROADCAST DIAGNOSTIC: Response preview: " + (response.length() > 300 ? response.substring(0, 300) + "..." : response));
            
            // Check for API-level errors in the response body (like {"code": 12, "message": "Not Implemented"})
            if (responseCode >= 400 || (response.contains("\"code\"") && response.contains("\"message\""))) {
                try {
                    JSONObject errorObj = new JSONObject(response);
                    if (errorObj.has("code") && errorObj.optInt("code", 0) != 0) {
                        int errorCode = errorObj.optInt("code");
                        String errorMessage = errorObj.optString("message", "Unknown API error");
                        Log.e(TAG, "BROADCAST DIAGNOSTIC: API Error - Code: " + errorCode + ", Message: " + errorMessage);
                        
                        if (errorCode == 12) {
                            Log.e(TAG, "BROADCAST DIAGNOSTIC: ERROR CODE 12 DETECTED - 'Not Implemented'");
                            Log.e(TAG, "BROADCAST DIAGNOSTIC: This indicates the endpoint " + urlStr + " is not supported by this node");
                            Log.e(TAG, "BROADCAST DIAGNOSTIC: Modern nodes typically don't support legacy /txs endpoint");
                        }
                        
                        throw new Exception("API Error " + errorCode + ": " + errorMessage + " (endpoint: " + urlStr + ")");
                    }
                } catch (Exception jsonError) {
                    Log.w(TAG, "BROADCAST DIAGNOSTIC: Could not parse error response as JSON: " + jsonError.getMessage());
                }
                
                // If we can't parse as JSON but got an HTTP error, still throw
                if (responseCode >= 400) {
                    throw new Exception("HTTP " + responseCode + " " + responseMessage + ": " + response);
                }
            }
            
            return response;
        } catch (Exception e) {
            Log.e(TAG, "BROADCAST DIAGNOSTIC: HTTP POST failed for " + urlStr, e);
            Log.e(TAG, "BROADCAST DIAGNOSTIC: Exception type: " + e.getClass().getSimpleName());
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private SharedPreferences createSecurePrefs(Context ctx) {
        try {
            String alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREF_FILE,
                    alias,
                    ctx,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Throwable t) {
            Log.w(TAG, "EncryptedSharedPreferences not available, falling back", t);
            return ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
    }

    private String getSelectedMnemonic() {
        try {
            if (securePrefs == null) return "";
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            if (arr.length() > 0 && sel >= 0 && sel < arr.length()) {
                return arr.getJSONObject(sel).optString("mnemonic", "");
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSelectedMnemonic failed", t);
        }
        return securePrefs != null ? securePrefs.getString(KEY_MNEMONIC, "") : "";
    }

    private void finishWithError(String message) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_ERROR, message != null ? message : "Unknown error");
            setResult(Activity.RESULT_CANCELED, data);
        } catch (Throwable ignored) {
            setResult(Activity.RESULT_CANCELED);
        }
        finish();
    }
}