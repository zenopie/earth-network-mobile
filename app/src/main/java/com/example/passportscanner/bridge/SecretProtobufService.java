package com.example.passportscanner.bridge;

import android.util.Log;
import android.util.Base64;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.protobuf.ByteString;
import cosmos.tx.v1beta1.Tx;

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
     */
    public byte[] buildTransaction(String sender, String contractAddr, String codeHash, 
                                 byte[] encryptedMsg, String funds, String memo, 
                                 String accountNumber, String sequence, String chainId, 
                                 ECKey walletKey) throws Exception {
        
        Log.i(TAG, "Building Secret Network protobuf transaction");
        Log.d(TAG, "Sender: " + sender);
        Log.d(TAG, "Contract: " + contractAddr);
        Log.d(TAG, "Encrypted message size: " + encryptedMsg.length + " bytes");
        Log.d(TAG, "Chain: " + chainId + ", Account: " + accountNumber + ", Sequence: " + sequence);
        
        try {
            // Parse funds into coins array
            JSONArray coins = null;
            if (funds != null && !funds.isEmpty()) {
                coins = parseCoins(funds);
            }
            
            // Build the complete transaction using protobuf structure
            return encodeTransactionToProtobuf(
                sender, contractAddr, codeHash, encryptedMsg, coins, memo,
                accountNumber, sequence, chainId, walletKey
            );
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to build protobuf transaction", e);
            throw new Exception("Protobuf transaction building failed: " + e.getMessage());
        }
    }

    /**
     * Creates a clean, simple protobuf transaction for Secret Network
     */
    private byte[] encodeTransactionToProtobuf(String sender, String contractAddr, String codeHash,
                                             byte[] encryptedMsg, JSONArray coins, String memo,
                                             String accountNumber, String sequence, String chainId,
                                             ECKey walletKey) throws Exception {
        
        Log.i(TAG, "Creating clean protobuf transaction");
        
        // 1. Create MsgExecuteContract message
        secret.compute.v1beta1.MsgExecuteContract.Builder msgBuilder = 
            secret.compute.v1beta1.MsgExecuteContract.newBuilder()
                .setSender(sender)
                .setContract(contractAddr)
                .setCodeHash(codeHash != null ? codeHash : "")
                .setMsg(ByteString.copyFrom(encryptedMsg));
        
        // Add funds if provided
        if (coins != null && coins.length() > 0) {
            for (int i = 0; i < coins.length(); i++) {
                JSONObject coin = coins.getJSONObject(i);
                cosmos.base.v1beta1.CoinOuterClass.Coin.Builder coinBuilder = cosmos.base.v1beta1.CoinOuterClass.Coin.newBuilder()
                    .setDenom(coin.getString("denom"))
                    .setAmount(coin.getString("amount"));
                msgBuilder.addSentFunds(coinBuilder.build());
            }
        }
        
        secret.compute.v1beta1.MsgExecuteContract msg = msgBuilder.build();
        
        // 2. Create TxBody
        cosmos.tx.v1beta1.Tx.TxBody.Builder txBodyBuilder = 
            cosmos.tx.v1beta1.Tx.TxBody.newBuilder()
                .addMessages(com.google.protobuf.Any.newBuilder()
                    .setTypeUrl("/secret.compute.v1beta1.MsgExecuteContract")
                    .setValue(msg.toByteString())
                    .build())
                .setMemo(memo != null ? memo : "");
        
        cosmos.tx.v1beta1.Tx.TxBody txBody = txBodyBuilder.build();
        
        // 3. Create AuthInfo with fee and signature info
        cosmos.tx.v1beta1.Tx.Fee fee = cosmos.tx.v1beta1.Tx.Fee.newBuilder()
            .setGasLimit(200000)
            .addAmount(cosmos.base.v1beta1.CoinOuterClass.Coin.newBuilder()
                .setDenom("uscrt")
                .setAmount("100000")
                .build())
            // Don't set payer field at all - let it default to empty
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
        
        // 4. Create SignDoc and sign it
        cosmos.tx.v1beta1.Tx.SignDoc signDoc = cosmos.tx.v1beta1.Tx.SignDoc.newBuilder()
            .setBodyBytes(txBody.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(chainId)
            .setAccountNumber(Long.parseLong(accountNumber))
            .build();
        
        // Sign the transaction
        byte[] signDocBytes = signDoc.toByteArray();
        Sha256Hash hash = Sha256Hash.of(signDocBytes);
        ECKey.ECDSASignature signature = walletKey.sign(hash);
        byte[] signatureBytes = signature.encodeToDER();
        
        // 5. Create final TxRaw
        cosmos.tx.v1beta1.Tx.TxRaw txRaw = cosmos.tx.v1beta1.Tx.TxRaw.newBuilder()
            .setBodyBytes(txBody.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .addSignatures(ByteString.copyFrom(signatureBytes))
            .build();
        
        byte[] txBytes = txRaw.toByteArray();
        Log.i(TAG, "Clean protobuf transaction created, size: " + txBytes.length + " bytes");
        
        return txBytes;
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
        
        Log.d(TAG, "Parsed funds '" + funds + "' into " + coins.length() + " coins");
        return coins;
    }
}