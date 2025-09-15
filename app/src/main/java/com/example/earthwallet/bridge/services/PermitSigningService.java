package com.example.earthwallet.bridge.services;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.earthwallet.bridge.models.Permit;
import com.example.earthwallet.bridge.models.PermitSignDoc;
import com.example.earthwallet.bridge.utils.PermitManager;
import com.example.earthwallet.wallet.services.TransactionSigner;
import com.example.earthwallet.wallet.services.SecureWalletManager;
import com.google.gson.Gson;

import org.bitcoinj.core.ECKey;
import java.util.Arrays;
import java.util.List;
import java.util.Base64;

/**
 * Service for signing SNIP-24 permits
 * Integrates with TransactionActivity for confirmation and signing flow
 */
public class PermitSigningService {

    private static final String TAG = "PermitSigningService";
    private static final String CHAIN_ID = "secret-4"; // Secret Network mainnet

    /**
     * Execute permit signing through the standard transaction flow
     * Expected intent extras:
     * - permit_name: The permit name (e.g., app name)
     * - allowed_tokens: Comma-separated list of contract addresses
     * - permissions: Comma-separated list of permissions (balance,history,allowance)
     */
    public static String[] execute(Context context, Intent intent) throws Exception {
        Log.d(TAG, "Starting permit signing");

        // Extract parameters
        String permitName = intent.getStringExtra("permit_name");
        String allowedTokensStr = intent.getStringExtra("allowed_tokens");
        String permissionsStr = intent.getStringExtra("permissions");

        if (permitName == null || allowedTokensStr == null || permissionsStr == null) {
            throw new Exception("Missing required parameters for permit signing");
        }

        List<String> allowedTokens = Arrays.asList(allowedTokensStr.split(","));
        List<String> permissions = Arrays.asList(permissionsStr.split(","));

        Log.d(TAG, "Creating permit for " + allowedTokens.size() + " tokens");

        // Use SecureWalletManager for just-in-time mnemonic access with automatic cleanup
        return SecureWalletManager.executeWithMnemonic(context, mnemonic -> {
            Log.d(TAG, "Signing permit with wallet");

            // Get wallet key and address from mnemonic
            ECKey walletKey = com.example.earthwallet.wallet.utils.WalletCrypto.deriveKeyFromMnemonic(mnemonic);
            String walletAddress = com.example.earthwallet.wallet.utils.WalletCrypto.getAddress(walletKey);

            if (walletKey == null || walletAddress == null) {
                throw new Exception("No wallet available for signing");
            }

            try {
                // Create permit sign document
                PermitSignDoc signDoc = new PermitSignDoc(CHAIN_ID, permitName, allowedTokens, permissions);

                // Serialize for signing (following Cosmos SDK amino JSON format)
                Gson gson = new Gson();
                String signDocJson = gson.toJson(signDoc);
                byte[] signDocBytes = signDocJson.getBytes("UTF-8");

                Log.d(TAG, "Permit sign document created");

                // Sign the document
                TransactionSigner.TransactionSignature signature =
                    TransactionSigner.createSignature(signDocBytes, walletKey);

                // Create permit with base64-encoded signature and public key
                Permit permit = new Permit(permitName, allowedTokens, permissions);
                permit.setSignature(Base64.getEncoder().encodeToString(signature.getBytes()));
                permit.setPublicKey(Base64.getEncoder().encodeToString(signature.getPublicKey()));

                Log.d(TAG, "Permit signed successfully");

                // Store permit for each token contract
                PermitManager permitManager = PermitManager.getInstance(context);
                for (String contractAddress : allowedTokens) {
                    permitManager.setPermit(walletAddress, contractAddress, permit);
                }
                Log.d(TAG, "Permit stored for " + allowedTokens.size() + " contracts");

                // Return success result in expected format [result, senderAddress]
                String resultJson = gson.toJson(permit);
                return new String[]{resultJson, walletAddress};

            } catch (Exception e) {
                Log.e(TAG, "Failed to sign permit", e);
                throw new Exception("Permit signing failed: " + e.getMessage());
            }
        });
    }

    /**
     * Create an intent for permit signing via TransactionActivity
     */
    public static Intent createPermitIntent(Context context, String permitName,
                                          List<String> allowedTokens, List<String> permissions) {
        Intent intent = new Intent(context, com.example.earthwallet.bridge.activities.TransactionActivity.class);
        intent.putExtra(com.example.earthwallet.bridge.activities.TransactionActivity.EXTRA_TRANSACTION_TYPE,
                       "permit_signing");
        intent.putExtra("permit_name", permitName);
        intent.putExtra("allowed_tokens", String.join(",", allowedTokens));
        intent.putExtra("permissions", String.join(",", permissions));
        return intent;
    }

    /**
     * Helper method to convert bytes to hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}