package com.example.passportscanner.wallet;

import android.util.Log;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import com.google.protobuf.ByteString;
import cosmos.tx.v1beta1.Tx;

/**
 * General purpose transaction signer for Secret Network
 * Handles all signature operations with proper validation and error handling
 */
public class TransactionSigner {
    
    private static final String TAG = "TransactionSigner";

    /**
     * Signs a transaction with the provided wallet key
     * @param signDoc The SignDoc protobuf to sign
     * @param walletKey The wallet key to sign with
     * @return Signed transaction bytes ready for broadcast
     */
    public static byte[] signTransaction(cosmos.tx.v1beta1.Tx.SignDoc signDoc, ECKey walletKey) throws Exception {
        Log.d(TAG, "Signing transaction with wallet key");
        
        try {
            // 1. Serialize SignDoc for signing
            byte[] signDocBytes = signDoc.toByteArray();
            Log.d(TAG, "SignDoc serialized: " + signDocBytes.length + " bytes");
            
            // 2. Create signature
            TransactionSignature signature = createSignature(signDocBytes, walletKey);
            Log.d(TAG, "Signature created: " + signature.getLength() + " bytes");
            
            // 3. Build final transaction
            cosmos.tx.v1beta1.Tx.TxRaw txRaw = buildSignedTransaction(signDoc, signature);
            
            byte[] txBytes = txRaw.toByteArray();
            Log.i(TAG, "Signed transaction ready: " + txBytes.length + " bytes");
            
            return txBytes;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to sign transaction", e);
            throw new Exception("Transaction signing failed: " + e.getMessage());
        }
    }
    
    /**
     * Creates a signature for the given data
     */
    public static TransactionSignature createSignature(byte[] data, ECKey walletKey) throws Exception {
        Log.d(TAG, "Creating signature for " + data.length + " bytes of data");
        
        // Hash the data
        Sha256Hash hash = Sha256Hash.of(data);
        
        // Sign the hash
        ECKey.ECDSASignature ecdsaSignature = walletKey.sign(hash);
        
        // Get public key for verification
        byte[] publicKey = walletKey.getPubKey();
        
        // Create our signature wrapper
        return new TransactionSignature(ecdsaSignature, publicKey);
    }
    
    /**
     * Builds the final signed transaction
     */
    private static cosmos.tx.v1beta1.Tx.TxRaw buildSignedTransaction(
            cosmos.tx.v1beta1.Tx.SignDoc signDoc, 
            TransactionSignature signature) throws Exception {
        
        return cosmos.tx.v1beta1.Tx.TxRaw.newBuilder()
            .setBodyBytes(signDoc.getBodyBytes())
            .setAuthInfoBytes(signDoc.getAuthInfoBytes())
            .addSignatures(ByteString.copyFrom(signature.getBytes()))
            .build();
    }
    
    /**
     * Validates that a wallet key matches a sender address
     */
    public static void validateWalletMatchesSender(String senderAddress, ECKey walletKey) throws Exception {
        String walletAddress = SecretWallet.getAddress(walletKey);
        
        if (!walletAddress.equals(senderAddress)) {
            throw new Exception("Wallet/sender mismatch: " +
                "Expected: " + senderAddress + ", " +
                "Wallet derives to: " + walletAddress);
        }
        
        Log.d(TAG, "✅ Wallet validation passed");
    }
    
    /**
     * Wrapper class for transaction signatures
     */
    public static class TransactionSignature {
        private final ECKey.ECDSASignature ecdsaSignature;
        private final byte[] publicKey;
        private final byte[] signatureBytes;
        
        public TransactionSignature(ECKey.ECDSASignature ecdsaSignature, byte[] publicKey) throws Exception {
            this.ecdsaSignature = ecdsaSignature;
            this.publicKey = publicKey;
            
            // Use raw 64-byte format for Cosmos compatibility
            this.signatureBytes = createRawSignature(ecdsaSignature);
            
            Log.d(TAG, "Signature created: " + signatureBytes.length + " bytes (Raw format)");
        }
        
        /**
         * Converts ECDSA signature to raw 64-byte format (32-byte r + 32-byte s)
         */
        private byte[] createRawSignature(ECKey.ECDSASignature signature) throws Exception {
            byte[] rBytes = signature.r.toByteArray();
            byte[] sBytes = signature.s.toByteArray();
            
            // Create 64-byte array
            byte[] result = new byte[64];
            
            // Copy r to first 32 bytes (right-aligned)
            int rStart = Math.max(0, rBytes.length - 32);
            int rDest = Math.max(0, 32 - rBytes.length);
            int rLen = Math.min(32, rBytes.length);
            System.arraycopy(rBytes, rStart, result, rDest, rLen);
            
            // Copy s to last 32 bytes (right-aligned)
            int sStart = Math.max(0, sBytes.length - 32);
            int sDest = Math.max(0, 64 - sBytes.length);
            int sLen = Math.min(32, sBytes.length);
            System.arraycopy(sBytes, sStart, result, sDest, sLen);
            
            return result;
        }
        
        public byte[] getBytes() {
            return signatureBytes;
        }
        
        public int getLength() {
            return signatureBytes.length;
        }
        
        public byte[] getPublicKey() {
            return publicKey;
        }
        
        public String getHex() {
            StringBuilder hex = new StringBuilder();
            for (byte b : signatureBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
        
        // Future enhancement: Add method to convert to raw format
        public byte[] getRawBytes() throws Exception {
            // TODO: Convert DER to raw 64-byte format for Cosmos compatibility
            throw new UnsupportedOperationException("Raw format not yet implemented");
        }
    }
}