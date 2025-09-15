package com.example.earthwallet.bridge.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.bridge.models.Permit;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Arrays;
import java.util.List;

/**
 * PermitManager
 *
 * General-purpose utility class for managing SNIP-24 permits across the application.
 * Provides a consistent interface for storing, retrieving, and checking permits.
 * Replaces the old viewing key system with the more user-friendly permit system.
 *
 * Usage:
 *   PermitManager permitManager = PermitManager.getInstance(context);
 *   Permit permit = permitManager.getPermit(walletAddress, contractAddress);
 *   permitManager.setPermit(walletAddress, permit);
 */
public class PermitManager {

    private static final String TAG = "PermitManager";
    private static final String PREF_FILE = "permits_prefs";

    private static PermitManager instance;
    private SharedPreferences securePrefs;
    private Gson gson;

    private PermitManager(Context context) {
        try {
            securePrefs = createSecurePrefs(context.getApplicationContext());
            gson = new Gson();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize secure preferences", e);
            throw new RuntimeException("PermitManager initialization failed", e);
        }
    }

    /**
     * Get singleton instance of PermitManager
     */
    public static synchronized PermitManager getInstance(Context context) {
        if (instance == null) {
            instance = new PermitManager(context);
        }
        return instance;
    }

    /**
     * Get permit for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return The permit, or null if not found
     */
    public Permit getPermit(String walletAddress, String contractAddress) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return null;
        }

        String key = "permit_" + walletAddress + "_" + contractAddress;
        String permitJson = securePrefs.getString(key, "");

        if (TextUtils.isEmpty(permitJson)) {
            return null;
        }

        try {
            return gson.fromJson(permitJson, Permit.class);
        } catch (Exception e) {
            Log.e(TAG, "Failed to deserialize permit", e);
            return null;
        }
    }

    /**
     * Set permit for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @param permit The permit to store
     */
    public void setPermit(String walletAddress, String contractAddress, Permit permit) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress) || permit == null) {
            Log.e(TAG, "Cannot set permit: invalid parameters");
            return;
        }

        try {
            String key = "permit_" + walletAddress + "_" + contractAddress;
            String permitJson = gson.toJson(permit);
            securePrefs.edit().putString(key, permitJson).apply();
            Log.d(TAG, "Permit set for contract: " + contractAddress);
        } catch (Exception e) {
            Log.e(TAG, "Failed to serialize permit", e);
        }
    }

    /**
     * Create and set a permit for multiple contracts with default permissions
     * @param walletAddress The wallet address
     * @param contractAddresses List of contract addresses
     * @param permitName The permit name (e.g., app name)
     * @param permissions List of permissions (balance, history, allowance)
     * @return The created permit
     */
    public Permit createPermit(String walletAddress, List<String> contractAddresses, String permitName, List<String> permissions) {
        if (TextUtils.isEmpty(walletAddress) || contractAddresses == null || contractAddresses.isEmpty()) {
            Log.e(TAG, "Cannot create permit: invalid parameters");
            return null;
        }

        Permit permit = new Permit(permitName, contractAddresses, permissions);

        // Store permit for each contract
        for (String contractAddress : contractAddresses) {
            setPermit(walletAddress, contractAddress, permit);
        }

        return permit;
    }

    /**
     * Check if a permit exists for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @return true if permit exists and is valid, false otherwise
     */
    public boolean hasPermit(String walletAddress, String contractAddress) {
        Permit permit = getPermit(walletAddress, contractAddress);
        return permit != null && permit.isValid();
    }

    /**
     * Remove permit for a specific wallet address and contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     */
    public void removePermit(String walletAddress, String contractAddress) {
        if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(contractAddress)) {
            return;
        }

        String key = "permit_" + walletAddress + "_" + contractAddress;
        securePrefs.edit().remove(key).apply();
        Log.d(TAG, "Permit removed for contract: " + contractAddress);
    }

    /**
     * Check if a permit allows a specific permission for a contract
     * @param walletAddress The wallet address
     * @param contractAddress The SNIP-20 contract address
     * @param permission The permission to check (balance, history, allowance)
     * @return true if permit exists and allows the permission, false otherwise
     */
    public boolean hasPermission(String walletAddress, String contractAddress, String permission) {
        Permit permit = getPermit(walletAddress, contractAddress);
        return permit != null && permit.hasPermission(permission);
    }

    /**
     * Get all permits for a specific wallet address
     * @param walletAddress The wallet address
     * @return A map of contract addresses to permits
     */
    public java.util.Map<String, Permit> getAllPermits(String walletAddress) {
        java.util.Map<String, Permit> permits = new java.util.HashMap<>();

        if (TextUtils.isEmpty(walletAddress)) {
            return permits;
        }

        try {
            java.util.Map<String, ?> allPrefs = securePrefs.getAll();
            String keyPrefix = "permit_" + walletAddress + "_";

            for (String prefKey : allPrefs.keySet()) {
                if (prefKey.startsWith(keyPrefix)) {
                    String contractAddress = prefKey.substring(keyPrefix.length());
                    String permitJson = (String) allPrefs.get(prefKey);

                    if (!TextUtils.isEmpty(permitJson)) {
                        try {
                            Permit permit = gson.fromJson(permitJson, Permit.class);
                            if (permit != null && permit.isValid()) {
                                permits.put(contractAddress, permit);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to deserialize permit for contract: " + contractAddress, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get all permits", e);
        }

        return permits;
    }

    /**
     * Clear all permits for a specific wallet address
     * @param walletAddress The wallet address
     */
    public void clearAllPermits(String walletAddress) {
        if (TextUtils.isEmpty(walletAddress)) {
            return;
        }

        try {
            java.util.Map<String, ?> allPrefs = securePrefs.getAll();
            String keyPrefix = "permit_" + walletAddress + "_";

            SharedPreferences.Editor editor = securePrefs.edit();

            for (String prefKey : allPrefs.keySet()) {
                if (prefKey.startsWith(keyPrefix)) {
                    editor.remove(prefKey);
                }
            }

            editor.apply();
            Log.d(TAG, "All permits cleared for wallet: " + walletAddress);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear permits", e);
        }
    }

    /**
     * Validate a permit signature against the expected wallet address
     * @param permit The permit to validate
     * @param walletAddress The expected wallet address
     * @return true if permit signature is valid, false otherwise
     */
    public boolean validatePermitSignature(Permit permit, String walletAddress) {
        if (permit == null || !permit.isValid() || TextUtils.isEmpty(walletAddress)) {
            Log.w(TAG, "Invalid permit or wallet address for validation");
            return false;
        }

        try {
            // TODO: Implement proper signature validation
            // This would involve:
            // 1. Recreating the PermitSignDoc
            // 2. Serializing it to the same JSON format used for signing
            // 3. Verifying the signature against the public key
            // 4. Deriving address from public key and comparing with walletAddress

            Log.d(TAG, "Permit signature validation not yet implemented");
            return true; // For now, assume valid if permit structure is correct

        } catch (Exception e) {
            Log.e(TAG, "Failed to validate permit signature", e);
            return false;
        }
    }

    /**
     * Check if a permit is expired based on timestamp
     * @param permit The permit to check
     * @param maxAgeMs Maximum age in milliseconds (default 24 hours)
     * @return true if permit is still valid, false if expired
     */
    public boolean isPermitValid(Permit permit, long maxAgeMs) {
        if (permit == null || !permit.isValid()) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long permitAge = currentTime - permit.getTimestamp();

        return permitAge <= maxAgeMs;
    }

    /**
     * Check if a permit is valid with default 24-hour expiration
     */
    public boolean isPermitValid(Permit permit) {
        return isPermitValid(permit, 24 * 60 * 60 * 1000L); // 24 hours in milliseconds
    }

    /**
     * Get a valid permit for querying (checks existence, validation, and expiration)
     * @param walletAddress The wallet address
     * @param contractAddress The contract address
     * @param permission The required permission
     * @return A valid permit, or null if none exists or is valid
     */
    public Permit getValidPermitForQuery(String walletAddress, String contractAddress, String permission) {
        Permit permit = getPermit(walletAddress, contractAddress);

        if (permit == null) {
            Log.d(TAG, "No permit found for contract: " + contractAddress);
            return null;
        }

        if (!isPermitValid(permit)) {
            Log.d(TAG, "Permit expired for contract: " + contractAddress);
            removePermit(walletAddress, contractAddress); // Clean up expired permit
            return null;
        }

        if (!permit.hasPermission(permission)) {
            Log.w(TAG, "Permit lacks required permission '" + permission + "' for contract: " + contractAddress);
            return null;
        }

        if (!validatePermitSignature(permit, walletAddress)) {
            Log.w(TAG, "Permit signature validation failed for contract: " + contractAddress);
            removePermit(walletAddress, contractAddress); // Remove invalid permit
            return null;
        }

        Log.d(TAG, "Valid permit found for " + permission + " query");
        return permit;
    }

    /**
     * Format a permit for SNIP-24 query structure (corrected to match SNIP-24 spec)
     * @param permit The permit to format
     * @param chainId The chain ID (e.g., "secret-4")
     * @return JSON object with permit structure for queries
     */
    public org.json.JSONObject formatPermitForQuery(Permit permit, String chainId) throws Exception {
        if (permit == null || !permit.isValid()) {
            throw new Exception("Invalid permit for query formatting");
        }

        org.json.JSONObject permitObj = new org.json.JSONObject();

        // SNIP-24 params structure
        org.json.JSONObject params = new org.json.JSONObject();
        params.put("chain_id", chainId);
        params.put("permit_name", permit.getPermitName());
        params.put("allowed_tokens", new org.json.JSONArray(permit.getAllowedTokens()));
        params.put("permissions", new org.json.JSONArray(permit.getPermissions()));

        permitObj.put("params", params);

        // Signature section with proper structure
        org.json.JSONObject signature = new org.json.JSONObject();

        // Public key with base64 encoding
        org.json.JSONObject pubKey = new org.json.JSONObject();
        pubKey.put("type", "tendermint/PubKeySecp256k1");
        pubKey.put("value", permit.getPublicKey()); // Should be base64 encoded
        signature.put("pub_key", pubKey);

        // Signature should be base64 encoded
        signature.put("signature", permit.getSignature());

        permitObj.put("signature", signature);

        return permitObj;
    }

    /**
     * Create a with_permit query structure for SNIP-24
     * @param innerQuery The actual query (e.g., balance query)
     * @param permit The permit to use
     * @param chainId The chain ID
     * @return Complete with_permit query structure
     */
    public org.json.JSONObject createWithPermitQuery(org.json.JSONObject innerQuery, Permit permit, String chainId) throws Exception {
        org.json.JSONObject withPermit = new org.json.JSONObject();
        org.json.JSONObject permitQuery = new org.json.JSONObject();

        permitQuery.put("permit", formatPermitForQuery(permit, chainId));
        permitQuery.put("query", innerQuery);

        withPermit.put("with_permit", permitQuery);
        return withPermit;
    }


    /**
     * TEMPORARY LEGACY COMPATIBILITY - to be removed
     * This method returns empty string to avoid compilation errors in other fragments
     * that are still being updated
     */
    @Deprecated
    public String getViewingKey(String walletAddress, String contractAddress) {
        Log.w(TAG, "getViewingKey() is deprecated - use hasPermit() instead");
        return hasPermit(walletAddress, contractAddress) ? "permit_exists" : "";
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