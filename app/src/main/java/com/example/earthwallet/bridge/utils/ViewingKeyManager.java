package com.example.earthwallet.bridge.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

/**
 * ViewingKeyManager
 *
 * General-purpose utility class for managing SNIP-20 viewing keys across the application.
 * Provides a consistent interface for storing, retrieving, and checking viewing keys.
 *
 * Usage:
 *   ViewingKeyManager keyManager = ViewingKeyManager.getInstance(context);
 *   String viewingKey = keyManager.getViewingKey(walletAddress, contractAddress);
 *   keyManager.setViewingKey(walletAddress, contractAddress, viewingKey);
 */
public class ViewingKeyManager {

    private static final String TAG = "ViewingKeyManager";
    private static final String PREF_FILE = "viewing_keys_prefs";

    private static ViewingKeyManager instance;
    private SharedPreferences securePrefs;

    private ViewingKeyManager(Context context) {
        try {
            securePrefs = createSecurePrefs(context.getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize secure preferences", e);
            throw new RuntimeException("ViewingKeyManager initialization failed", e);
        }
    }

    /**
     * Get singleton instance of ViewingKeyManager
     */
    public static synchronized ViewingKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new ViewingKeyManager(context);
        }
        return instance;
    }

    /**
     * Get viewing key for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return The viewing key, or empty string if not found
     */
    public String getViewingKey(String walletAddress, String contractAddress) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return "";
        }

        String key = "viewing_key_" + walletAddress + "_" + contractAddress;
        return securePrefs.getString(key, "");
    }

    /**
     * Set viewing key for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @param viewingKey The viewing key to store
     */
    public void setViewingKey(String walletAddress, String contractAddress, String viewingKey) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            Log.e(TAG, "Cannot set viewing key: wallet address or contract address is empty");
            return;
        }

        String key = "viewing_key_" + walletAddress + "_" + contractAddress;
        securePrefs.edit().putString(key, viewingKey).apply();
        Log.d(TAG, "Viewing key set for contract: " + contractAddress);
    }

    /**
     * Set viewing key with token symbol for reference
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @param viewingKey The viewing key to store
     * @param tokenSymbol The token symbol for reference
     */
    public void setViewingKey(String walletAddress, String contractAddress, String viewingKey, String tokenSymbol) {
        setViewingKey(walletAddress, contractAddress, viewingKey);

        // Also store the token symbol for reference
        if (!TextUtils.isEmpty(tokenSymbol)) {
            String symbolKey = "viewing_key_symbol_" + walletAddress + "_" + contractAddress;
            securePrefs.edit().putString(symbolKey, tokenSymbol).apply();
        }
    }

    /**
     * Check if a viewing key exists for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return true if viewing key exists, false otherwise
     */
    public boolean hasViewingKey(String walletAddress, String contractAddress) {
        return !TextUtils.isEmpty(getViewingKey(walletAddress, contractAddress));
    }

    /**
     * Remove viewing key for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     */
    public void removeViewingKey(String walletAddress, String contractAddress) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return;
        }

        String key = "viewing_key_" + walletAddress + "_" + contractAddress;
        String symbolKey = "viewing_key_symbol_" + walletAddress + "_" + contractAddress;

        securePrefs.edit()
            .remove(key)
            .remove(symbolKey)
            .apply();

        Log.d(TAG, "Viewing key removed for contract: " + contractAddress);
    }

    /**
     * Get token symbol associated with a viewing key
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return The token symbol, or empty string if not found
     */
    public String getViewingKeyTokenSymbol(String walletAddress, String contractAddress) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return "";
        }

        String symbolKey = "viewing_key_symbol_" + walletAddress + "_" + contractAddress;
        return securePrefs.getString(symbolKey, "");
    }

    /**
     * Get all viewing keys for a specific wallet address
     * @param walletAddress The wallet address
     * @return A map of contract addresses to viewing keys
     */
    public java.util.Map<String, String> getAllViewingKeys(String walletAddress) {
        java.util.Map<String, String> viewingKeys = new java.util.HashMap<>();

        if (TextUtils.isEmpty(walletAddress)) {
            return viewingKeys;
        }

        try {
            java.util.Map<String, ?> allPrefs = securePrefs.getAll();
            String keyPrefix = "viewing_key_" + walletAddress + "_";

            for (String prefKey : allPrefs.keySet()) {
                if (prefKey.startsWith(keyPrefix) && !prefKey.contains("_symbol_")) {
                    String contractAddress = prefKey.substring(keyPrefix.length());
                    String viewingKey = (String) allPrefs.get(prefKey);
                    if (!TextUtils.isEmpty(viewingKey)) {
                        viewingKeys.put(contractAddress, viewingKey);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get all viewing keys", e);
        }

        return viewingKeys;
    }

    /**
     * Clear all viewing keys for a specific wallet address
     * @param walletAddress The wallet address
     */
    public void clearAllViewingKeys(String walletAddress) {
        if (TextUtils.isEmpty(walletAddress)) {
            return;
        }

        try {
            java.util.Map<String, ?> allPrefs = securePrefs.getAll();
            String keyPrefix = "viewing_key_" + walletAddress + "_";
            String symbolPrefix = "viewing_key_symbol_" + walletAddress + "_";

            SharedPreferences.Editor editor = securePrefs.edit();

            for (String prefKey : allPrefs.keySet()) {
                if (prefKey.startsWith(keyPrefix) || prefKey.startsWith(symbolPrefix)) {
                    editor.remove(prefKey);
                }
            }

            editor.apply();
            Log.d(TAG, "All viewing keys cleared for wallet: " + walletAddress);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear viewing keys", e);
        }
    }

    /**
     * Create encrypted shared preferences for secure storage
     */
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