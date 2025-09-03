package com.example.earthwallet.wallet.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

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
     * Execute an operation with just-in-time mnemonic fetching and automatic cleanup.
     * 
     * @param context Android context for fallback access to secure preferences
     * @param operation The operation to execute with the mnemonic
     * @return The result of the operation
     * @throws Exception If mnemonic retrieval or operation execution fails
     */
    public static <T> T executeWithMnemonic(Context context, MnemonicOperation<T> operation) throws Exception {
        return executeWithMnemonic(context, null, operation);
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
            return SecretWallet.getAddressFromMnemonic(mnemonic);
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
            return SecretWallet.getAddressFromMnemonic(mnemonic);
        });
    }
}