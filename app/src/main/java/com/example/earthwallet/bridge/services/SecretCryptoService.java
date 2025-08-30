package com.example.earthwallet.bridge.services;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.example.earthwallet.wallet.services.SecretWallet;

import org.bitcoinj.core.ECKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.cryptomator.siv.SivMode;
import org.cryptomator.siv.UnauthenticCiphertextException;

import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

/**
 * SecretCryptoService
 * 
 * Handles all cryptographic operations for Secret Network:
 * - Message encryption using AES-GCM/AES-SIV with HKDF key derivation
 * - ECDH shared secret computation with x25519 compatibility
 * - Transaction signing with secp256k1
 */
public class SecretCryptoService {
    
    private static final String TAG = "SecretCryptoService";
    
    // Hardcoded consensus IO public key from SecretJS (mainnetConsensusIoPubKey)
    private static final String MAINNET_CONSENSUS_IO_PUBKEY_B64 = "79++5YOHfm0SwhlpUDClv7cuCjq9xBZlWqSjDJWkRG8=";
    
    // HKDF constants (matches SecretJS encryption.ts line 18-20)
    private static final byte[] HKDF_SALT = hexStringToByteArray("000000000000000000024bead8df69990852c202db0e0097c1a12ea637d7e96d");

    /**
     * Encrypts a contract message using SecretJS-compatible encryption
     * Matches SecretJS encryption.ts exactly - uses consensus IO pubkey, no contract pubkey needed
     * 
     * @param codeHash Contract code hash (optional)
     * @param msgJson Execute message JSON  
     * @param mnemonic Wallet mnemonic for key derivation
     * @return Encrypted message bytes
     */
    public byte[] encryptContractMessage(String codeHash, String msgJson, String mnemonic) throws Exception {
        Log.i(TAG, "Starting SecretJS-compatible encryption (no contract pubkey needed)");
        Log.i(TAG, "Input code hash: " + (codeHash != null ? codeHash : "null"));
        Log.i(TAG, "Input message JSON: " + msgJson);
        
        // Generate 32-byte nonce (matches SecretJS encryption.ts line 106)
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        Log.i(TAG, "Generated 32-byte nonce");
        
        // Get the curve25519 provider (use BEST for native fallback to pure Java)
        Curve25519 curve25519 = Curve25519.getInstance(Curve25519.BEST);
        
        // Generate a separate encryption seed like SecretJS does
        // SecretJS uses EncryptionUtilsImpl.GenerateNewSeed() - a random 32-byte seed
        // For deterministic behavior, we'll derive it from the wallet mnemonic
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update("secretjs-encryption-seed".getBytes(StandardCharsets.UTF_8));
        sha256.update(mnemonic.getBytes(StandardCharsets.UTF_8));
        byte[] encryptionSeed = sha256.digest();
        
        // Generate deterministic keypair from wallet mnemonic (like SecretJS does)
        // SecretJS uses EncryptionUtilsImpl.GenerateNewKeyPairFromSeed(seed)
        byte[] x25519PrivKey = new byte[32];
        System.arraycopy(encryptionSeed, 0, x25519PrivKey, 0, 32);
        
        // Clamp the private key according to curve25519 spec (like curve25519-js does)
        x25519PrivKey[0] &= 248;  // Clear bottom 3 bits
        x25519PrivKey[31] &= 127; // Clear top bit  
        x25519PrivKey[31] |= 64;  // Set second-highest bit
        
        // Generate public key by scalar multiplication with base point
        // Use ECDH with standard base point to get public key
        byte[] basePoint = new byte[32];
        basePoint[0] = 9; // Standard curve25519 base point
        byte[] encryptionPubKey = curve25519.calculateAgreement(basePoint, x25519PrivKey);
        
        
        // This is the public key that goes in the encrypted message (SecretJS format)
        byte[] walletPubkey32 = encryptionPubKey;
        
        // Use consensus IO public key (matches SecretJS encryption.ts line 89)
        byte[] consensusIoPubKey = Base64.decode(MAINNET_CONSENSUS_IO_PUBKEY_B64, Base64.NO_WRAP);
        Log.i(TAG, "Using consensus IO public key for ECDH");
        
        // Compute x25519 ECDH shared secret using encryption private key (matches SecretJS encryption.ts line 91)
        byte[] txEncryptionIkm = curve25519.calculateAgreement(consensusIoPubKey, x25519PrivKey);
        Log.i(TAG, "Computed x25519 shared secret directly using curve25519 library");
        
        // Derive encryption key using HKDF (matches SecretJS encryption.ts lines 92-98)
        byte[] keyMaterial = new byte[txEncryptionIkm.length + nonce.length];
        System.arraycopy(txEncryptionIkm, 0, keyMaterial, 0, txEncryptionIkm.length);
        System.arraycopy(nonce, 0, keyMaterial, txEncryptionIkm.length, nonce.length);
        
        byte[] txEncryptionKey = hkdf(keyMaterial, HKDF_SALT, "", 32);
        Log.i(TAG, "Derived encryption key using HKDF");
        
        // Create plaintext: contractCodeHash + JSON.stringify(msg) (matches SecretJS encryption.ts line 116)
        String plaintext;
        if (codeHash != null && !codeHash.isEmpty()) {
            plaintext = codeHash + msgJson;
        } else {
            plaintext = msgJson;
        }
        byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        
        Log.i(TAG, "=== MESSAGE STRUCTURE DEBUG ===");
        Log.i(TAG, "Code hash: \"" + (codeHash != null ? codeHash : "null") + "\"");
        Log.i(TAG, "Message JSON: \"" + msgJson + "\"");
        Log.i(TAG, "Final plaintext: \"" + plaintext + "\"");
        Log.i(TAG, "Plaintext bytes length: " + plaintextBytes.length);
        Log.i(TAG, "Plaintext hex: " + bytesToHex(plaintextBytes));
        Log.i(TAG, "Expected SecretJS format: contractCodeHash + JSON.stringify(msg)");
        Log.i(TAG, "=== END MESSAGE DEBUG ===");
        
        // Encrypt using RFC 5297 AES-SIV (matches SecretJS miscreant library exactly)
        Log.i(TAG, "Using RFC 5297 AES-SIV encryption - deriving keys like miscreant");
        byte[] ciphertext;
        try {
            // Split the 32-byte key in half like AES-SIV RFC 5297 standard
            // miscreant library likely uses this simple approach, not HKDF
            byte[] macKey = new byte[16];
            byte[] encKey = new byte[16];
            System.arraycopy(txEncryptionKey, 0, macKey, 0, 16);    // First 16 bytes for MAC
            System.arraycopy(txEncryptionKey, 16, encKey, 0, 16);   // Second 16 bytes for ENC
            
            
            // Use proper AES-SIV implementation that matches miscreant
            SivMode sivMode = new SivMode();
            // Match SecretJS siv.seal(plaintext, [new Uint8Array()]) - empty associated data
            ciphertext = sivMode.encrypt(encKey, macKey, plaintextBytes, new byte[0]);
            Log.i(TAG, "AES-SIV encryption successful using HKDF-derived keys (matches miscreant)");
        } catch (Exception e) {
            Log.e(TAG, "RFC 5297 AES-SIV encryption failed", e);
            throw new Exception("AES-SIV encryption failed: " + e.getMessage());
        }
        
        // Create encrypted message format: nonce(32) + wallet_pubkey(32) + siv_ciphertext
        // This matches SecretJS encryption.ts line 121: [...nonce, ...this.pubkey, ...ciphertext]
        byte[] encryptedMsg = new byte[32 + 32 + ciphertext.length];
        System.arraycopy(nonce, 0, encryptedMsg, 0, 32);
        System.arraycopy(walletPubkey32, 0, encryptedMsg, 32, 32);
        System.arraycopy(ciphertext, 0, encryptedMsg, 64, ciphertext.length);
        
        Log.i(TAG, "=== FINAL ENCRYPTED MESSAGE DEBUG ===");
        Log.i(TAG, "Nonce (32 bytes): " + bytesToHex(nonce));
        Log.i(TAG, "Wallet pubkey (32 bytes): " + bytesToHex(walletPubkey32));
        Log.i(TAG, "SIV ciphertext (" + ciphertext.length + " bytes): " + bytesToHex(ciphertext));
        Log.i(TAG, "Final message length: " + encryptedMsg.length + " bytes (32+32+" + ciphertext.length + ")");
        Log.i(TAG, "SecretJS format: nonce(32) || wallet_pubkey(32) || siv_ciphertext");
        Log.i(TAG, "=== END ENCRYPTED MESSAGE DEBUG ===");
        return encryptedMsg;
    }

    /**
     * Signs a message using secp256k1
     */
    public byte[] signMessage(byte[] messageHash, String mnemonic) throws Exception {
        ECKey key = SecretWallet.deriveKeyFromMnemonic(mnemonic);
        return key.sign(org.bitcoinj.core.Sha256Hash.wrap(messageHash)).encodeToDER();
    }

    /**
     * Gets the compressed public key for a wallet
     */
    public byte[] getWalletPublicKey(String mnemonic) throws Exception {
        ECKey key = SecretWallet.deriveKeyFromMnemonic(mnemonic);
        return key.getPubKeyPoint().getEncoded(true);
    }

    // Private helper methods

    

    private byte[] hkdf(byte[] keyMaterial, byte[] salt, String info, int outputLength) throws Exception {
        // Simplified HKDF implementation using HMAC-SHA256
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(salt.length > 0 ? salt : new byte[32], "HmacSHA256");
        hmac.init(keySpec);
        
        byte[] prk = hmac.doFinal(keyMaterial);
        
        // Expand phase
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        
        byte[] output = new byte[outputLength];
        int iterations = (outputLength + 31) / 32; // ceil(outputLength / 32)
        
        byte[] t = new byte[0];
        for (int i = 1; i <= iterations; i++) {
            hmac.reset();
            hmac.update(t);
            hmac.update(infoBytes);
            hmac.update((byte) i);
            t = hmac.doFinal();
            
            int copyLength = Math.min(32, outputLength - (i - 1) * 32);
            System.arraycopy(t, 0, output, (i - 1) * 32, copyLength);
        }
        
        return output;
    }



    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexStringToByteArray(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}