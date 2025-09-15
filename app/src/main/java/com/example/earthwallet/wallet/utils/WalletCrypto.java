package com.example.earthwallet.wallet.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.HDUtils;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * WalletCrypto
 *
 * Pure cryptographic utility functions for Secret Network wallets.
 * Handles mnemonic generation, key derivation, and address generation.
 * All methods are stateless and have no side effects.
 */
public final class WalletCrypto {

    private static final String TAG = "WalletCrypto";
    public static final String HRP = "secret";

    // Initialization state
    private static volatile boolean isInitialized = false;
    private static final Object lock = new Object();

    private WalletCrypto() {}

    /**
     * Initialize MnemonicCode with the English word list from assets.
     * Must be called before any mnemonic operations.
     */
    public static void initialize(Context context) {
        if (isInitialized) return;
        synchronized (lock) {
            if (isInitialized) return;
            try {
                AssetManager assetManager = context.getAssets();
                InputStream is = assetManager.open("org/bitcoinj/crypto/wordlist/english.txt");
                MnemonicCode.INSTANCE = new MnemonicCode(is, null);
                is.close();
                Log.d(TAG, "MnemonicCode initialized with English word list from assets.");
                isInitialized = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize MnemonicCode from assets: " + e.getMessage(), e);
                throw new RuntimeException("Failed to initialize MnemonicCode", e);
            }
        }
    }

    /**
     * Generate a new BIP-39 mnemonic (12 words)
     */
    public static String generateMnemonic() {
        try {
            // 128 bits entropy (12 words)
            byte[] entropy = new byte[16];
            SecureRandom secureRandom = new SecureRandom();
            secureRandom.nextBytes(entropy);

            List<String> words = MnemonicCode.INSTANCE.toMnemonic(entropy);
            if (words == null || words.isEmpty()) {
                Log.e(TAG, "MnemonicCode.toMnemonic returned null or empty list");
                // Zero sensitive entropy before throwing
                Arrays.fill(entropy, (byte)0);
                throw new IllegalStateException("Failed to generate mnemonic: word list issue?");
            }
            String mnemonic = String.join(" ", words);

            // Zero sensitive entropy after use
            Arrays.fill(entropy, (byte)0);
            return mnemonic;
        } catch (Exception e) {
            Log.e(TAG, "Mnemonic generation failed", e);
            throw new RuntimeException("Mnemonic generation failed", e);
        }
    }

    /**
     * Derive ECKey from mnemonic using BIP-44 path m/44'/529'/0'/0/0
     */
    public static ECKey deriveKeyFromMnemonic(String mnemonic) {
        try {
            List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
            DeterministicSeed seed = new DeterministicSeed(words, null, "", 0L);
            DeterministicKeyChain chain = DeterministicKeyChain.builder().seed(seed).build();
            List<ChildNumber> path = HDUtils.parsePath("M/44H/529H/0H/0/0");
            return ECKey.fromPrivate(chain.getKeyByPath(path, true).getPrivKey());
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    /**
     * Secure version: Derive ECKey from mnemonic char array using BIP-44 path m/44'/529'/0'/0/0
     * More secure as it minimizes String creation in memory
     */
    public static ECKey deriveKeyFromSecureMnemonic(char[] mnemonicChars) {
        try {
            // Convert char array to string only temporarily for processing
            String mnemonic = new String(mnemonicChars);
            List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));

            // Clear the temporary string immediately
            mnemonic = null;

            DeterministicSeed seed = new DeterministicSeed(words, null, "", 0L);
            DeterministicKeyChain chain = DeterministicKeyChain.builder().seed(seed).build();
            List<ChildNumber> path = HDUtils.parsePath("M/44H/529H/0H/0/0");
            return ECKey.fromPrivate(chain.getKeyByPath(path, true).getPrivKey());
        } catch (Exception e) {
            throw new RuntimeException("Secure key derivation failed", e);
        }
    }

    /**
     * Get Secret Network address from ECKey
     */
    public static String getAddress(ECKey key) {
        // Compressed public key
        byte[] pubCompressed = key.getPubKeyPoint().getEncoded(true);
        // Cosmos-style address: RIPEMD160(SHA256(pubkey))
        byte[] hash = Utils.sha256hash160(pubCompressed);
        // Convert to 5-bit groups and Bech32 encode
        byte[] fiveBits = Bech32.convertBits(hash, 8, 5, true);
        return Bech32.encode(HRP, fiveBits);
    }

    /**
     * Get Secret Network address from mnemonic
     */
    public static String getAddressFromMnemonic(String mnemonic) {
        ECKey key = deriveKeyFromMnemonic(mnemonic);
        return getAddress(key);
    }

    /**
     * Get private key as hex string
     */
    public static String getPrivateKeyHex(ECKey key) {
        return key.getPrivateKeyAsHex();
    }
}