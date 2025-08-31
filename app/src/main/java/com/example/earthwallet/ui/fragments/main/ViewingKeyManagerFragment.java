package com.example.earthwallet.ui.fragments.main;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;
import com.example.earthwallet.bridge.activities.SecretExecuteActivity;
import com.example.earthwallet.wallet.constants.Tokens;

import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * ViewingKeyManagerFragment
 * 
 * Handles all viewing key operations:
 * - Generating viewing keys (random or manual)
 * - Setting viewing keys on blockchain
 * - Managing viewing key dialogs
 * - Storing viewing keys securely
 */
public class ViewingKeyManagerFragment extends Fragment {
    
    private static final String TAG = "ViewingKeyManagerFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final int REQ_SET_VIEWING_KEY = 2002;
    
    // State management
    private SharedPreferences securePrefs;
    private Tokens.TokenInfo pendingViewingKeyToken = null;
    private String pendingViewingKey = null;
    private String walletAddress = "";
    
    // Interface for communication with parent
    public interface ViewingKeyManagerListener {
        void onViewingKeySet(Tokens.TokenInfo token, String viewingKey);
        String getCurrentWalletAddress();
        SharedPreferences getSecurePrefs();
    }
    
    private ViewingKeyManagerListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof ViewingKeyManagerListener) {
            listener = (ViewingKeyManagerListener) getParentFragment();
        } else if (context instanceof ViewingKeyManagerListener) {
            listener = (ViewingKeyManagerListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement ViewingKeyManagerListener");
        }
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            securePrefs = EncryptedSharedPreferences.create(
                PREF_FILE,
                masterKeyAlias,
                requireContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create secure preferences", e);
        }
        
        if (listener != null) {
            walletAddress = listener.getCurrentWalletAddress();
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // This fragment doesn't have its own UI - it's a helper fragment
        return null;
    }
    
    /**
     * Public method to request viewing key generation for a token
     */
    public void requestViewingKey(Tokens.TokenInfo token) {
        Log.i(TAG, "Requesting viewing key for " + token.symbol);
        
        // Check if viewing key already exists
        String existingKey = getViewingKey(token.contract);
        if (!TextUtils.isEmpty(existingKey)) {
            showViewingKeyManagementDialog(token);
        } else {
            showAutoViewingKeyDialog(token);
        }
    }
    
    private void showAutoViewingKeyDialog(Tokens.TokenInfo token) {
        new AlertDialog.Builder(getContext())
            .setTitle("Set Viewing Key for " + token.symbol)
            .setMessage("A viewing key is required to see your " + token.symbol + " balance. Would you like to:")
            .setPositiveButton("Generate Random Key", (dialog, which) -> {
                generateViewingKey(token);
            })
            .setNeutralButton("Enter Custom Key", (dialog, which) -> {
                showSetViewingKeyDialog(token);
            })
            .setNegativeButton("Learn More", (dialog, which) -> {
                showViewingKeyHelpDialog(token);
            })
            .setCancelable(true)
            .show();
    }
    
    private void showSetViewingKeyDialog(Tokens.TokenInfo token) {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_set_viewing_key, null);
        EditText keyInput = dialogView.findViewById(R.id.viewing_key_input);
        
        new AlertDialog.Builder(getContext())
            .setTitle("Set Viewing Key for " + token.symbol)
            .setView(dialogView)
            .setPositiveButton("Set Key", (dialog, which) -> {
                String customKey = keyInput.getText().toString().trim();
                if (!TextUtils.isEmpty(customKey)) {
                    executeSetViewingKeyTransaction(token, customKey);
                } else {
                    Toast.makeText(getContext(), "Please enter a viewing key", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void showViewingKeyHelpDialog(Tokens.TokenInfo token) {
        String helpText = "Viewing keys allow you to see your token balance while keeping transactions private.\n\n" +
                "• Generated keys are random and secure\n" +
                "• Custom keys let you use existing keys from other wallets\n" +
                "• Keys are stored securely on your device\n" +
                "• You can change or regenerate keys anytime";
        
        new AlertDialog.Builder(getContext())
            .setTitle("About Viewing Keys")
            .setMessage(helpText)
            .setPositiveButton("Generate Key", (dialog, which) -> {
                generateViewingKey(token);
            })
            .setNeutralButton("Enter Custom", (dialog, which) -> {
                showSetViewingKeyDialog(token);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void showViewingKeyManagementDialog(Tokens.TokenInfo token) {
        String currentKey = getViewingKey(token.contract);
        String message = "Current viewing key: " + 
            (currentKey.length() > 20 ? currentKey.substring(0, 20) + "..." : currentKey) +
            "\n\nWhat would you like to do?";
        
        new AlertDialog.Builder(getContext())
            .setTitle("Manage Viewing Key - " + token.symbol)
            .setMessage(message)
            .setPositiveButton("Regenerate", (dialog, which) -> {
                new AlertDialog.Builder(getContext())
                    .setTitle("Regenerate Viewing Key?")
                    .setMessage("This will generate a new viewing key and update it on the blockchain. Continue?")
                    .setPositiveButton("Yes", (d, w) -> generateViewingKey(token))
                    .setNegativeButton("No", null)
                    .show();
            })
            .setNeutralButton("Set Custom", (dialog, which) -> {
                showSetViewingKeyDialog(token);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void generateViewingKey(Tokens.TokenInfo token) {
        try {
            Log.d(TAG, "Starting viewing key generation for " + token.symbol);
            Log.d(TAG, "Calling generateSecret20ViewingKey for contract: " + token.contract);
            
            // Generate random viewing key (matches Secret Network standard)
            SecureRandom random = new SecureRandom();
            byte[] keyBytes = new byte[32];
            random.nextBytes(keyBytes);
            String viewingKey = "api_key_" + Base64.encodeToString(keyBytes, Base64.NO_WRAP);
            
            Log.i(TAG, "Generated random viewing key for contract: " + token.contract);
            Log.d(TAG, "Generated viewing key length: " + viewingKey.length());
            Log.d(TAG, "Setting viewing key on blockchain for contract: " + token.contract);
            
            executeSetViewingKeyTransaction(token, viewingKey);
            
            Log.i(TAG, "Initiated viewing key transaction for " + token.symbol);
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate viewing key for " + token.symbol, e);
            Toast.makeText(getContext(), "Failed to generate viewing key: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void executeSetViewingKeyTransaction(Tokens.TokenInfo token, String viewingKey) {
        try {
            // Store pending state for result handling
            pendingViewingKeyToken = token;
            pendingViewingKey = viewingKey;
            
            // Create set_viewing_key message for SNIP-20 contract
            JSONObject setViewingKeyMsg = new JSONObject();
            JSONObject msgContent = new JSONObject();
            msgContent.put("key", viewingKey);
            setViewingKeyMsg.put("set_viewing_key", msgContent);
            
            // Launch SecretExecuteActivity to set viewing key on blockchain
            Intent intent = new Intent(getContext(), SecretExecuteActivity.class);
            intent.putExtra(SecretExecuteActivity.EXTRA_CONTRACT_ADDRESS, token.contract);
            intent.putExtra(SecretExecuteActivity.EXTRA_CODE_HASH, token.hash);
            intent.putExtra(SecretExecuteActivity.EXTRA_EXECUTE_JSON, setViewingKeyMsg.toString());
            intent.putExtra(SecretExecuteActivity.EXTRA_MEMO, "Set viewing key for " + token.symbol);
            startActivityForResult(intent, REQ_SET_VIEWING_KEY);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute set viewing key transaction", e);
            Toast.makeText(getContext(), "Failed to create transaction: " + e.getMessage(), Toast.LENGTH_LONG).show();
            
            // Clear pending state on error
            pendingViewingKeyToken = null;
            pendingViewingKey = null;
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_SET_VIEWING_KEY) {
            if (resultCode == Activity.RESULT_OK) {
                // Transaction succeeded - use the stored pending values
                if (pendingViewingKeyToken != null && !TextUtils.isEmpty(pendingViewingKey)) {
                    try {
                        Log.d(TAG, "Processing viewing key success for " + pendingViewingKeyToken.symbol);
                        
                        // Save the viewing key locally
                        setViewingKey(pendingViewingKeyToken.contract, pendingViewingKey);
                        Toast.makeText(getContext(), "Viewing key set successfully for " + pendingViewingKeyToken.symbol + "!", Toast.LENGTH_SHORT).show();
                        
                        // Notify parent that viewing key was set
                        if (listener != null) {
                            listener.onViewingKeySet(pendingViewingKeyToken, pendingViewingKey);
                        }
                        
                        Log.i(TAG, "Successfully set viewing key for " + pendingViewingKeyToken.symbol);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to handle set viewing key result", e);
                        Toast.makeText(getContext(), "Failed to process viewing key result", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.w(TAG, "Transaction succeeded but no pending viewing key info");
                    Toast.makeText(getContext(), "Viewing key transaction completed", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Transaction failed
                String error = (data != null) ? data.getStringExtra(SecretExecuteActivity.EXTRA_ERROR) : "Transaction failed";
                Toast.makeText(getContext(), "Failed to set viewing key: " + error, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Set viewing key transaction failed: " + error);
            }
            
            // Clear pending state
            pendingViewingKeyToken = null;
            pendingViewingKey = null;
        }
    }
    
    /**
     * Public method to update wallet address
     */
    public void updateWalletAddress(String newAddress) {
        walletAddress = newAddress;
    }
    
    // Helper methods for viewing key management
    private String getViewingKey(String contractAddress) {
        if (TextUtils.isEmpty(walletAddress)) {
            walletAddress = listener != null ? listener.getCurrentWalletAddress() : "";
        }
        if (TextUtils.isEmpty(walletAddress)) {
            return "";
        }
        return securePrefs.getString("viewing_key_" + walletAddress + "_" + contractAddress, "");
    }
    
    private void setViewingKey(String contractAddress, String viewingKey) {
        if (TextUtils.isEmpty(walletAddress)) {
            walletAddress = listener != null ? listener.getCurrentWalletAddress() : "";
        }
        if (TextUtils.isEmpty(walletAddress)) {
            Log.e(TAG, "Cannot set viewing key: no wallet address available");
            return;
        }
        
        // Store both the viewing key and the token symbol for later matching
        String tokenSymbol = null;
        if (pendingViewingKeyToken != null && contractAddress.equals(pendingViewingKeyToken.contract)) {
            tokenSymbol = pendingViewingKeyToken.symbol;
        }
        
        securePrefs.edit().putString("viewing_key_" + walletAddress + "_" + contractAddress, viewingKey).apply();
        
        // Also store the token symbol for this viewing key
        if (!TextUtils.isEmpty(tokenSymbol)) {
            securePrefs.edit().putString("viewing_key_symbol_" + walletAddress + "_" + contractAddress, tokenSymbol).apply();
        }
    }
    
    /**
     * Public method to check if a token has a viewing key
     */
    public boolean hasViewingKey(String contractAddress) {
        return !TextUtils.isEmpty(getViewingKey(contractAddress));
    }
    
    /**
     * Public method to get viewing key for a contract
     */
    public String getViewingKeyForContract(String contractAddress) {
        return getViewingKey(contractAddress);
    }
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}