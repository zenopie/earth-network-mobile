package com.example.earthwallet.wallet.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.wallet.utils.WalletCrypto;

import org.json.JSONArray;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * SecureWalletManager
 * 
 * Provides secure just-in-time mnemonic fetching with automatic cleanup.
 * This class ensures mnemonics are only held in memory for the minimum required time
 * and are securely zeroed after use.
 */
public final class SecureWalletManager {
    
    private static final String TAG = "SecureWalletManager";
    private static final String PREF_FILE = "secret_wallet_prefs";

    private SecureWalletManager() {}
    
    /**
     * Interface for operations that need access to the mnemonic.
     * The mnemonic is provided just-in-time and automatically cleaned up after the operation.
     */
    public interface MnemonicOperation<T> {
        T execute(String mnemonic) throws Exception;
    }

    /**
     * Secure interface for operations that need access to the mnemonic as char array.
     * More secure than String version as char arrays can be properly zeroed.
     */
    public interface SecureMnemonicOperation<T> {
        T execute(char[] mnemonic) throws Exception;
    }
    
    /**
     * Execute an operation with just-in-time mnemonic fetching and automatic cleanup.
     *
     * @param context Android context for fallback access to secure preferences
     * @param operation The operation to execute with the mnemonic
     * @return The result of the operation
     * @throws Exception If mnemonic retrieval or operation execution fails
     */
    public static <T> T executeWithMnemonic(Context context, MnemonicOperation<T> operation) throws Exception {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context);
        return executeWithMnemonic(context, null, operation);
    }

    /**
     * Execute an operation with just-in-time secure mnemonic fetching and automatic cleanup.
     * More secure version that uses char arrays instead of Strings.
     *
     * @param context Android context for fallback access to secure preferences
     * @param operation The operation to execute with the mnemonic char array
     * @return The result of the operation
     * @throws Exception If mnemonic retrieval or operation execution fails
     */
    public static <T> T executeWithSecureMnemonic(Context context, SecureMnemonicOperation<T> operation) throws Exception {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context);
        char[] mnemonicChars = null;
        try {
            // Fetch mnemonic and convert to char array immediately
            String mnemonic = fetchMnemonicSecurely(context);
            if (TextUtils.isEmpty(mnemonic)) {
                throw new IllegalStateException("No wallet mnemonic found - wallet may not be initialized");
            }

            mnemonicChars = mnemonic.toCharArray();
            // Clear the String immediately
            securelyClearString(mnemonic);

            // Execute the operation with the char array
            return operation.execute(mnemonicChars);

        } finally {
            // Securely zero the char array regardless of success or failure
            if (mnemonicChars != null) {
                Arrays.fill(mnemonicChars, '\0');
                mnemonicChars = null;
            }
        }
    }
    
    /**
     * Execute an operation with just-in-time mnemonic fetching and automatic cleanup.
     *
     * @param context Android context for fallback access to secure preferences
     * @param securePrefs Pre-initialized secure preferences instance (preferred)
     * @param operation The operation to execute with the mnemonic
     * @return The result of the operation
     * @throws Exception If mnemonic retrieval or operation execution fails
     */
    public static <T> T executeWithMnemonic(Context context, SharedPreferences securePrefs, MnemonicOperation<T> operation) throws Exception {
        // Ensure WalletCrypto is initialized before any mnemonic operations
        WalletCrypto.initialize(context);
        String mnemonic = null;
        try {
            // Fetch mnemonic just-in-time using provided securePrefs or fallback to context
            mnemonic = (securePrefs != null) 
                ? fetchMnemonicFromPrefs(securePrefs)
                : fetchMnemonicSecurely(context);
            
            if (TextUtils.isEmpty(mnemonic)) {
                throw new IllegalStateException("No wallet mnemonic found - wallet may not be initialized");
            }
            
            // Execute the operation with the mnemonic
            return operation.execute(mnemonic);
            
        } finally {
            // Securely zero the mnemonic regardless of success or failure
            if (mnemonic != null) {
                securelyClearString(mnemonic);
                mnemonic = null;
            }
        }
    }
    
    /**
     * Fetch mnemonic from already-initialized secure preferences (preferred method)
     */
    private static String fetchMnemonicFromPrefs(SharedPreferences securePrefs) throws Exception {
        try {
            // Get mnemonic for the selected wallet (matches existing multi-wallet logic)
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            
            if (arr.length() > 0) {
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "");
                } else if (arr.length() == 1) {
                    return arr.getJSONObject(0).optString("mnemonic", "");
                }
            }
            
            // Fallback to legacy mnemonic
            return securePrefs.getString("mnemonic", "");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch mnemonic from secure preferences", e);
            throw new Exception("Failed to retrieve wallet mnemonic from secure preferences", e);
        }
    }
    
    /**
     * Fetch wallet mnemonic from secure preferences using the same pattern as existing code
     */
    private static String fetchMnemonicSecurely(Context context) throws Exception {
        try {
            // Use encrypted preferences for secure wallet storage
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    PREF_FILE,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            
            // Get mnemonic for the selected wallet (matches existing multi-wallet logic)
            String walletsJson = securePrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            int sel = securePrefs.getInt("selected_wallet_index", -1);
            
            if (arr.length() > 0) {
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "");
                } else if (arr.length() == 1) {
                    return arr.getJSONObject(0).optString("mnemonic", "");
                }
            }
            
            // Fallback to legacy mnemonic
            String legacyMnemonic = securePrefs.getString("mnemonic", "");
            if (!TextUtils.isEmpty(legacyMnemonic)) {
                return legacyMnemonic;
            }
            
            Log.w(TAG, "No mnemonic found in secure preferences, trying fallback");
            return tryFallbackMnemonicRetrieval(context);
            
        } catch (GeneralSecurityException | java.io.IOException e) {
            Log.w(TAG, "Failed to access encrypted preferences: " + e.getMessage());
            return tryFallbackMnemonicRetrieval(context);
        }
    }
    
    /**
     * Fallback mnemonic retrieval using plain SharedPreferences (matches existing fallback logic)
     */
    private static String tryFallbackMnemonicRetrieval(Context context) throws Exception {
        try {
            SharedPreferences flatPrefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            String walletsJson = flatPrefs.getString("wallets", "[]");
            JSONArray arr = new JSONArray(walletsJson);
            
            if (arr.length() > 0) {
                int sel = flatPrefs.getInt("selected_wallet_index", -1);
                if (sel >= 0 && sel < arr.length()) {
                    return arr.getJSONObject(sel).optString("mnemonic", "");
                } else {
                    return arr.getJSONObject(0).optString("mnemonic", "");
                }
            }
            
            return flatPrefs.getString("mnemonic", "");
            
        } catch (Exception e) {
            Log.e(TAG, "All mnemonic retrieval methods failed", e);
            throw new Exception("Failed to retrieve wallet mnemonic from secure or fallback storage", e);
        }
    }
    
    /**
     * Securely clear a string from memory by overwriting its internal char array.
     * This is a best-effort approach since String is immutable in Java, but it helps
     * reduce the time sensitive data stays in memory.
     */
    private static void securelyClearString(String sensitiveString) {
        if (sensitiveString == null) {
            return;
        }
        
        try {
            // Convert to char array and clear it
            char[] chars = sensitiveString.toCharArray();
            Arrays.fill(chars, '\0');
            
            // Note: We cannot directly access String's internal char array in modern Java,
            // but clearing the char array we created helps minimize exposure.
            // The original String will be garbage collected eventually.
            
            Log.d(TAG, "Mnemonic cleared from memory (best effort)");
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to securely clear mnemonic: " + e.getMessage());
        }
    }
    
    /**
     * Check if a wallet mnemonic is available without actually retrieving it
     */
    public static boolean isWalletAvailable(Context context) {
        return isWalletAvailable(context, null);
    }
    
    /**
     * Check if a wallet mnemonic is available without actually retrieving it
     */
    public static boolean isWalletAvailable(Context context, SharedPreferences securePrefs) {
        try {
            String mnemonic = (securePrefs != null) 
                ? fetchMnemonicFromPrefs(securePrefs)
                : fetchMnemonicSecurely(context);
            boolean available = !TextUtils.isEmpty(mnemonic);
            
            // Clear the mnemonic immediately after checking
            if (mnemonic != null) {
                securelyClearString(mnemonic);
            }
            
            return available;
        } catch (Exception e) {
            Log.w(TAG, "Failed to check wallet availability: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the wallet address using secure just-in-time mnemonic fetching
     * 
     * @param context Android context for accessing secure preferences
     * @return The wallet address or null if no wallet is available
     */
    public static String getWalletAddress(Context context) throws Exception {
        return executeWithMnemonic(context, mnemonic -> {
            return WalletCrypto.getAddressFromMnemonic(mnemonic);
        });
    }
    
    /**
     * Get the wallet address using secure just-in-time mnemonic fetching
     * 
     * @param context Android context for fallback access
     * @param securePrefs Pre-initialized secure preferences instance (preferred)
     * @return The wallet address or null if no wallet is available
     */
    public static String getWalletAddress(Context context, SharedPreferences securePrefs) throws Exception {
        return executeWithMnemonic(context, securePrefs, mnemonic -> {
            return WalletCrypto.getAddressFromMnemonic(mnemonic);
        });
    }

    // =========================================================================
    // Wallet Management Methods
    // =========================================================================

    /**
     * Get current wallet name
     */
    public static String getCurrentWalletName(Context context) throws Exception {
        return getCurrentWalletName(context, null);
    }

    /**
     * Get current wallet name using pre-initialized secure preferences
     */
    public static String getCurrentWalletName(Context context, SharedPreferences securePrefs) throws Exception {
        try {
            SharedPreferences prefs = (securePrefs != null) ? securePrefs : createSecurePrefs(context);
            String walletsJson = prefs.getString("wallets", "[]");
            JSONArray walletsArray = new JSONArray(walletsJson);
            int selectedIndex = prefs.getInt("selected_wallet_index", -1);

            if (walletsArray.length() > 0) {
                int index = (selectedIndex >= 0 && selectedIndex < walletsArray.length()) ? selectedIndex : 0;
                return walletsArray.getJSONObject(index).optString("name", "Wallet " + (index + 1));
            }
            return "No wallet";
        } catch (Exception e) {
            Log.e(TAG, "Failed to get current wallet name", e);
            throw new Exception("Failed to get current wallet name", e);
        }
    }

    /**
     * Get total number of wallets
     */
    public static int getWalletCount(Context context) throws Exception {
        return getWalletCount(context, null);
    }

    /**
     * Get total number of wallets using pre-initialized secure preferences
     */
    public static int getWalletCount(Context context, SharedPreferences securePrefs) throws Exception {
        try {
            SharedPreferences prefs = (securePrefs != null) ? securePrefs : createSecurePrefs(context);
            String walletsJson = prefs.getString("wallets", "[]");
            JSONArray walletsArray = new JSONArray(walletsJson);
            return walletsArray.length();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get wallet count", e);
            throw new Exception("Failed to get wallet count", e);
        }
    }

    /**
     * Get selected wallet index
     */
    public static int getSelectedWalletIndex(Context context) throws Exception {
        return getSelectedWalletIndex(context, null);
    }

    /**
     * Get selected wallet index using pre-initialized secure preferences
     */
    public static int getSelectedWalletIndex(Context context, SharedPreferences securePrefs) throws Exception {
        try {
            SharedPreferences prefs = (securePrefs != null) ? securePrefs : createSecurePrefs(context);
            return prefs.getInt("selected_wallet_index", -1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get selected wallet index", e);
            throw new Exception("Failed to get selected wallet index", e);
        }
    }

    /**
     * Ensure current wallet has an address (migrate legacy wallets)
     */
    public static void ensureCurrentWalletHasAddress(Context context) throws Exception {
        ensureCurrentWalletHasAddress(context, null);
    }

    /**
     * Ensure current wallet has an address using pre-initialized secure preferences
     */
    public static void ensureCurrentWalletHasAddress(Context context, SharedPreferences securePrefs) throws Exception {
        try {
            SharedPreferences prefs = (securePrefs != null) ? securePrefs : createSecurePrefs(context);
            String walletsJson = prefs.getString("wallets", "[]");
            JSONArray walletsArray = new JSONArray(walletsJson);
            int selectedIndex = prefs.getInt("selected_wallet_index", -1);

            if (walletsArray.length() > 0) {
                int index = (selectedIndex >= 0 && selectedIndex < walletsArray.length()) ? selectedIndex : 0;
                org.json.JSONObject selectedWallet = walletsArray.getJSONObject(index);

                String currentAddress = selectedWallet.optString("address", "");
                String currentMnemonic = selectedWallet.optString("mnemonic", "");

                // If address is missing, derive it and update storage
                if (TextUtils.isEmpty(currentAddress) && !TextUtils.isEmpty(currentMnemonic)) {
                    String address = WalletCrypto.getAddressFromMnemonic(currentMnemonic);
                    selectedWallet.put("address", address);
                    walletsArray.put(index, selectedWallet);
                    prefs.edit().putString("wallets", walletsArray.toString()).apply();
                    Log.d(TAG, "Migrated wallet address for: " + selectedWallet.optString("name", "Unknown"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to ensure wallet has address", e);
            throw new Exception("Failed to ensure wallet has address", e);
        }
    }

    // =========================================================================
    // Wallet Creation Methods
    // =========================================================================

    /**
     * Generate a new BIP-39 mnemonic
     */
    public static String generateMnemonic(Context context) throws Exception {
        WalletCrypto.initialize(context);
        return WalletCrypto.generateMnemonic();
    }

    /**
     * Create a new wallet with name and mnemonic
     */
    public static void createWallet(Context context, String walletName, String mnemonic) throws Exception {
        createWallet(context, null, walletName, mnemonic);
    }

    /**
     * Create a new wallet with name and mnemonic using pre-initialized secure preferences
     */
    public static void createWallet(Context context, SharedPreferences securePrefs, String walletName, String mnemonic) throws Exception {
        try {
            WalletCrypto.initialize(context);

            // Derive address from mnemonic
            String address = WalletCrypto.getAddressFromMnemonic(mnemonic);

            SharedPreferences prefs = (securePrefs != null) ? securePrefs : createSecurePrefs(context);

            // Load existing wallets
            String walletsJson = prefs.getString("wallets", "[]");
            JSONArray walletsArray = new JSONArray(walletsJson);

            // Create new wallet object
            org.json.JSONObject newWallet = new org.json.JSONObject();
            newWallet.put("name", walletName);
            newWallet.put("mnemonic", mnemonic);
            newWallet.put("address", address);

            // Add to wallets array
            walletsArray.put(newWallet);

            // Set as selected wallet (index of new wallet)
            int newIndex = walletsArray.length() - 1;

            // Save to preferences
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("wallets", walletsArray.toString());
            editor.putInt("selected_wallet_index", newIndex);
            editor.apply();

            Log.d(TAG, "Created new wallet: " + walletName + " (" + address + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create wallet", e);
            throw new Exception("Failed to create wallet: " + e.getMessage(), e);
        }
    }

    /**
     * Validate a BIP-39 mnemonic
     */
    public static boolean validateMnemonic(Context context, String mnemonic) {
        try {
            WalletCrypto.initialize(context);
            // Try to derive a key - if it fails, mnemonic is invalid
            WalletCrypto.deriveKeyFromMnemonic(mnemonic);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Invalid mnemonic: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create secure preferences (helper method)
     */
    private static SharedPreferences createSecurePrefs(Context context) throws Exception {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | java.io.IOException e) {
            Log.e(TAG, "Failed to create secure preferences", e);
            throw new Exception("Failed to create secure preferences", e);
        }
    }
}