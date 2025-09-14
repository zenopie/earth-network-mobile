package com.example.earthwallet.bridge.services;

import android.content.Context;
import android.util.Log;
import android.util.Base64;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.protobuf.ByteString;
import cosmos.tx.v1beta1.Tx;

import java.security.MessageDigest;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.wallet.services.TransactionSigner;

/**
 * SecretProtobufService
 * 
 * Handles Secret Network protobuf transaction building:
 * - Creates MsgExecuteContract messages
 * - Builds Cosmos SDK transaction structure
 * - Signs with protobuf SignDoc
 * - Encodes to TxRaw format for broadcasting
 */
public class SecretProtobufService {
    
    private static final String TAG = "SecretProtobufService";

    /**
     * Builds a complete protobuf transaction for Secret Network contract execution
     * Supports both single message (legacy) and multi-message transactions
     */
    public byte[] buildTransaction(String sender, String contractAddr, String codeHash,
                                 byte[] encryptedMsg, String funds, String memo,
                                 String accountNumber, String sequence, String chainId,
                                 ECKey walletKey) throws Exception {

        // Convert single message to messages array for unified processing
        JSONArray messagesArray = new JSONArray();
        JSONObject singleMessage = new JSONObject();
        try {
            singleMessage.put("sender", sender);
            singleMessage.put("contract", contractAddr);
            singleMessage.put("code_hash", codeHash);
            singleMessage.put("encrypted_msg", Base64.encodeToString(encryptedMsg, Base64.NO_WRAP));
            if (funds != null && !funds.isEmpty()) {
                singleMessage.put("sent_funds", parseCoins(funds));
            }
            messagesArray.put(singleMessage);
        } catch (Exception e) {
            throw new Exception("Failed to create single message array: " + e.getMessage());
        }

        return buildMultiMessageTransaction(messagesArray, memo, accountNumber, sequence, chainId, walletKey);
    }

    /**
     * Builds a protobuf transaction with multiple messages - unified method for all execute transactions
     */
    public byte[] buildMultiMessageTransaction(JSONArray messages, String memo,
                                             String accountNumber, String sequence,
                                             String chainId, ECKey walletKey) throws Exception {

        Log.i(TAG, "Building Secret Network protobuf transaction with " + messages.length() + " message(s)");

        try {
            return encodeMultiMessageTransactionToProtobuf(
                messages, memo, accountNumber, sequence, chainId, walletKey
            );

        } catch (Exception e) {
            Log.e(TAG, "Failed to build multi-message protobuf transaction", e);
            throw new Exception("Multi-message protobuf transaction building failed: " + e.getMessage());
        }
    }

    /**
     * Creates a protobuf transaction with multiple MsgExecuteContract messages
     */
    private byte[] encodeMultiMessageTransactionToProtobuf(JSONArray messages, String memo,
                                                         String accountNumber, String sequence,
                                                         String chainId, ECKey walletKey) throws Exception {

        Log.i(TAG, "Creating multi-message protobuf transaction");

        // Get sender from first message (all messages should have same sender)
        JSONObject firstMessage = messages.getJSONObject(0);
        String sender = firstMessage.getString("sender");
        byte[] senderBytes = decodeBech32Address(sender);

        // 1. Create all MsgExecuteContract messages
        cosmos.tx.v1beta1.Tx.TxBody.Builder txBodyBuilder =
            cosmos.tx.v1beta1.Tx.TxBody.newBuilder();

        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String contract = message.getString("contract");
            String encryptedMsgB64 = message.getString("encrypted_msg");

            // Convert addresses to binary format
            byte[] contractBytes = decodeBech32Address(contract);
            byte[] encryptedMsg = Base64.decode(encryptedMsgB64, Base64.NO_WRAP);

            // Create MsgExecuteContract
            secret.compute.v1beta1.MsgExecuteContract.Builder msgBuilder =
                secret.compute.v1beta1.MsgExecuteContract.newBuilder()
                    .setSender(ByteString.copyFrom(senderBytes))
                    .setContract(ByteString.copyFrom(contractBytes))
                    .setMsg(ByteString.copyFrom(encryptedMsg))
                    .setCallbackCodeHash("")
                    .setCallbackSig(ByteString.EMPTY);

            // Add funds if provided
            if (message.has("sent_funds")) {
                JSONArray coins = message.getJSONArray("sent_funds");
                for (int j = 0; j < coins.length(); j++) {
                    JSONObject coin = coins.getJSONObject(j);
                    cosmos.base.v1beta1.CoinOuterClass.Coin.Builder coinBuilder =
                        cosmos.base.v1beta1.CoinOuterClass.Coin.newBuilder()
                            .setDenom(coin.getString("denom"))
                            .setAmount(coin.getString("amount"));
                    msgBuilder.addSentFunds(coinBuilder.build());
                }
            }

            secret.compute.v1beta1.MsgExecuteContract msg = msgBuilder.build();

            // Wrap in Any and add to TxBody
            com.google.protobuf.Any messageAny = com.google.protobuf.Any.newBuilder()
                .setTypeUrl("/secret.compute.v1beta1.MsgExecuteContract")
                .setValue(msg.toByteString())
                .build();

            txBodyBuilder.addMessages(messageAny);
            Log.i(TAG, "Added message " + (i + 1) + " to transaction: " + contract);
        }

        // 2. Complete TxBody with memo
        txBodyBuilder.setMemo(memo != null ? memo : "");
        cosmos.tx.v1beta1.Tx.TxBody txBody = txBodyBuilder.build();
        
        // 3. Create AuthInfo with fee and signature info
        // Completely omit payer and granter fields to match CosmJS protobuf encoding
        cosmos.tx.v1beta1.Tx.Fee fee = cosmos.tx.v1beta1.Tx.Fee.newBuilder()
            .setGasLimit(5000000)  // Increased from 200K to 5M to match SecretJS contract execution
            .addAmount(cosmos.base.v1beta1.CoinOuterClass.Coin.newBuilder()
                .setDenom("uscrt")
                .setAmount("100000")
                .build())
            // Don't set payer or granter at all - let protobuf omit these fields entirely
            .build();
        
        cosmos.tx.v1beta1.Tx.SignerInfo signerInfo = cosmos.tx.v1beta1.Tx.SignerInfo.newBuilder()
            .setPublicKey(com.google.protobuf.Any.newBuilder()
                .setTypeUrl("/cosmos.crypto.secp256k1.PubKey")
                .setValue(cosmos.crypto.secp256k1.PubKey.newBuilder()
                    .setKey(ByteString.copyFrom(walletKey.getPubKey()))
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
        
        // 4. Create SignDoc and sign it using TransactionSigner
        cosmos.tx.v1beta1.Tx.SignDoc signDoc = cosmos.tx.v1beta1.Tx.SignDoc.newBuilder()
            .setBodyBytes(txBody.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(chainId)
            .setAccountNumber(Long.parseLong(accountNumber))
            .build();
        
        
        // Use the general purpose signer
        byte[] txBytes = TransactionSigner.signTransaction(signDoc, walletKey);
        Log.i(TAG, "Clean protobuf transaction created, size: " + txBytes.length + " bytes");
        
        // Debug: Log raw transaction bytes in hex for comparison
        StringBuilder hex = new StringBuilder();
        for (byte b : txBytes) {
            hex.append(String.format("%02x", b));
        }
        Log.i(TAG, "Raw transaction hex: " + hex.toString());
        
        return txBytes;
    }


    /**
     * Decode bech32 address to 20-byte binary format (like SecretJS does)
     */
    private byte[] decodeBech32Address(String bech32Address) throws Exception {
        if (!bech32Address.startsWith("secret1")) {
            throw new IllegalArgumentException("Invalid secret address: " + bech32Address);
        }
        
        // Extract the data part after "secret1"
        String datapart = bech32Address.substring(7);
        
        // Decode bech32 data part to binary
        byte[] decoded = bech32Decode(datapart);
        
        // Cosmos addresses are 20 bytes
        if (decoded.length != 20) {
            throw new IllegalArgumentException("Invalid address length: " + decoded.length + " (expected 20)");
        }
        
        return decoded;
    }
    
    /**
     * Simple bech32 decoder implementation
     * Based on the bech32 specification for Cosmos addresses
     */
    private byte[] bech32Decode(String data) throws Exception {
        // Bech32 character set
        String charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        
        // Convert characters to 5-bit values
        int[] values = new int[data.length()];
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int index = charset.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("Invalid bech32 character: " + c);
            }
            values[i] = index;
        }
        
        // Remove checksum (last 6 characters)
        int dataLength = values.length - 6;
        int[] dataValues = new int[dataLength];
        System.arraycopy(values, 0, dataValues, 0, dataLength);
        
        // Convert from 5-bit to 8-bit groups
        return convertBits(dataValues, 5, 8, false);
    }
    
    /**
     * Convert between different bit group sizes
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
     * Convert bytes to hex string for logging
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Parse funds string like "1000uscrt" into JSONArray of coins
     */
    private JSONArray parseCoins(String funds) throws Exception {
        // Simple parser for "amount+denom" format
        JSONArray coins = new JSONArray();
        
        if (funds == null || funds.trim().isEmpty()) {
            return coins;
        }
        
        // Extract amount and denom (e.g., "1000uscrt")
        String trimmed = funds.trim();
        StringBuilder amount = new StringBuilder();
        StringBuilder denom = new StringBuilder();
        
        boolean inDenom = false;
        for (char c : trimmed.toCharArray()) {
            if (Character.isDigit(c) && !inDenom) {
                amount.append(c);
            } else {
                inDenom = true;
                denom.append(c);
            }
        }
        
        if (amount.length() > 0 && denom.length() > 0) {
            org.json.JSONObject coin = new org.json.JSONObject();
            coin.put("amount", amount.toString());
            coin.put("denom", denom.toString());
            coins.put(coin);
        }
        
        return coins;
    }

    /**
     * CRITICAL VALIDATION: Verify wallet key derives to the sender address
     * This prevents signature verification failures due to key/address mismatch
     */
    private void validateWalletMatchesSender(String sender, ECKey walletKey) throws Exception {
        
        try {
            // Use the SAME address derivation method as the app uses
            String walletAddress = SecretWallet.getAddress(walletKey);
            
            // Log comparison for debugging
            Log.w(TAG, "=== WALLET/SENDER VALIDATION ===");
            Log.w(TAG, "Provided sender: " + sender);
            Log.w(TAG, "Wallet address:  " + walletAddress);
            Log.w(TAG, "MATCH: " + walletAddress.equals(sender));
            
            if (!walletAddress.equals(sender)) {
                String errorMsg = "SIGNATURE WILL FAIL: Wallet key doesn't match sender address!\n" +
                                "Provided sender: " + sender + "\n" +
                                "Wallet derives to: " + walletAddress + "\n" +
                                "Solution: Use the correct sender address that matches your wallet";
                
                Log.e(TAG, errorMsg);
                throw new Exception(errorMsg);
            }
            
            Log.i(TAG, "✅ Wallet validation passed - addresses match perfectly");
            
        } catch (Exception e) {
            if (e.getMessage().contains("SIGNATURE WILL FAIL")) {
                throw e; // Re-throw our validation error
            } else {
                throw new Exception("Wallet validation failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Callback interface for protobuf transaction execution
     */
    public interface ProtobufCallback {
        void onSuccess(JSONObject result);
        void onError(String error);
    }
    
    
    /**
     * Execute multiple transactions sequentially 
     * This is a temporary solution until true multi-message protobuf is implemented
     */
    private void executeMultipleTransactionsSequentially(JSONArray messages, int currentIndex, ProtobufCallback callback) {
        if (currentIndex >= messages.length()) {
            // All transactions completed successfully
            JSONObject result = new JSONObject();
            try {
                result.put("success", true);
                result.put("message", "All " + messages.length() + " transactions completed successfully");
            } catch (Exception e) {
                Log.w(TAG, "Failed to create result JSON", e);
            }
            callback.onSuccess(result);
            return;
        }
        
        try {
            JSONObject message = messages.getJSONObject(currentIndex);
            Log.i(TAG, "Executing transaction " + (currentIndex + 1) + "/" + messages.length());
            
            // Extract message details
            String sender = message.getString("sender");
            String contract = message.getString("contract");
            String codeHash = message.getString("code_hash");
            JSONObject msg = message.getJSONObject("msg");
            
            // Create a simple transaction executor (this would normally use SecretExecuteActivity)
            executeSingleMessage(sender, contract, codeHash, msg, new ProtobufCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    Log.i(TAG, "Transaction " + (currentIndex + 1) + " completed successfully");
                    // Continue with next transaction
                    executeMultipleTransactionsSequentially(messages, currentIndex + 1, callback);
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Transaction " + (currentIndex + 1) + " failed: " + error);
                    callback.onError("Transaction " + (currentIndex + 1) + " failed: " + error);
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to process message " + currentIndex, e);
            callback.onError("Failed to process message " + (currentIndex + 1) + ": " + e.getMessage());
        }
    }
    
    /**
     * Execute a single message (placeholder implementation)
     * TODO: Integrate with proper transaction execution
     */
    private void executeSingleMessage(String sender, String contract, String codeHash, JSONObject msg, ProtobufCallback callback) {
        // For now, just simulate success
        // In a real implementation, this would build and broadcast the transaction
        try {
            Thread.sleep(1000); // Simulate network delay
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("txhash", "simulated_tx_" + System.currentTimeMillis());
            callback.onSuccess(result);
        } catch (Exception e) {
            callback.onError("Execution failed: " + e.getMessage());
        }
    }
}