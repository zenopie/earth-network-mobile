package com.example.earthwallet.ui.pages.wallet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.earthwallet.R;
import com.example.earthwallet.bridge.activities.NativeSendActivity;
import com.example.earthwallet.bridge.activities.SecretExecuteActivity;
import com.example.earthwallet.bridge.activities.SnipExecuteActivity;
import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.wallet.services.SecretWallet;
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.bitcoinj.core.ECKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SendTokensFragment
 * 
 * Handles sending both native SCRT tokens and SNIP-20 tokens:
 * - Native SCRT transfers using cosmos.bank.v1beta1.MsgSend
 * - SNIP-20 token transfers using contract execution
 * - Token selection and amount validation
 * - Recipient address validation
 */
public class SendTokensFragment extends Fragment implements 
    WalletDisplayFragment.WalletDisplayListener {
    
    private static final String TAG = "SendTokensFragment";
    private static final String PREF_FILE = "secret_wallet_prefs";
    private static final int REQ_SEND_NATIVE = 3001;
    private static final int REQ_SEND_SNIP = 3002;
    
    // UI Components
    private Spinner tokenSpinner;
    private EditText recipientEditText;
    private EditText amountEditText;
    private EditText memoEditText;
    private Button sendButton;
    private TextView balanceText;
    
    // Data
    private List<TokenOption> tokenOptions;
    private SharedPreferences securePrefs;
    
    // Interface for communication with parent
    public interface SendTokensListener {
        String getCurrentWalletAddress();
        void onSendComplete();
    }
    
    private SendTokensListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof SendTokensListener) {
            listener = (SendTokensListener) getParentFragment();
        } else if (context instanceof SendTokensListener) {
            listener = (SendTokensListener) context;
        } else {
            Log.w(TAG, "Parent does not implement SendTokensListener");
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_send_tokens, container, false);
        
        // Initialize UI components
        tokenSpinner = view.findViewById(R.id.tokenSpinner);
        recipientEditText = view.findViewById(R.id.recipientEditText);
        amountEditText = view.findViewById(R.id.amountEditText);
        memoEditText = view.findViewById(R.id.memoEditText);
        sendButton = view.findViewById(R.id.sendButton);
        balanceText = view.findViewById(R.id.balanceText);
        
        try {
            securePrefs = createSecurePrefs(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize secure preferences", e);
            Toast.makeText(getContext(), "Failed to initialize wallet", Toast.LENGTH_SHORT).show();
            return view;
        }
        
        setupTokenSpinner();
        setupClickListeners();
        
        return view;
    }
    
    private void setupTokenSpinner() {
        tokenOptions = new ArrayList<>();
        
        // Add native SCRT option
        tokenOptions.add(new TokenOption("SCRT", "Native SCRT", true, null));
        
        // Add SNIP-20 tokens
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            Tokens.TokenInfo token = Tokens.ALL_TOKENS.get(symbol);
            tokenOptions.add(new TokenOption(symbol, symbol + " (" + token.contract.substring(0, 14) + "...)", false, token));
        }
        
        ArrayAdapter<TokenOption> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, tokenOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tokenSpinner.setAdapter(adapter);
    }
    
    private void setupClickListeners() {
        sendButton.setOnClickListener(v -> sendTokens());
    }
    
    private void sendTokens() {
        String recipient = recipientEditText.getText().toString().trim();
        String amount = amountEditText.getText().toString().trim();
        String memo = memoEditText.getText().toString().trim();
        
        if (TextUtils.isEmpty(recipient)) {
            Toast.makeText(getContext(), "Please enter recipient address", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (TextUtils.isEmpty(amount)) {
            Toast.makeText(getContext(), "Please enter amount", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!recipient.startsWith("secret1")) {
            Toast.makeText(getContext(), "Invalid Secret Network address", Toast.LENGTH_SHORT).show();
            return;
        }
        
        TokenOption selectedToken = (TokenOption) tokenSpinner.getSelectedItem();
        if (selectedToken == null) {
            Toast.makeText(getContext(), "Please select a token", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            if (selectedToken.isNative) {
                sendNativeToken(recipient, amount, memo);
            } else {
                sendSnipToken(selectedToken, recipient, amount, memo);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send tokens", e);
            Toast.makeText(getContext(), "Failed to send: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void sendNativeToken(String recipient, String amount, String memo) throws Exception {
        // Convert amount to microSCRT (6 decimals)
        double amountDouble = Double.parseDouble(amount);
        long microScrt = Math.round(amountDouble * 1_000_000);
        String microScrtString = String.valueOf(microScrt);
        
        // Use the native token sending activity (we'll need to create this)
        Intent intent = new Intent(getActivity(), NativeSendActivity.class);
        intent.putExtra(NativeSendActivity.EXTRA_RECIPIENT, recipient);
        intent.putExtra(NativeSendActivity.EXTRA_AMOUNT, microScrtString);
        intent.putExtra(NativeSendActivity.EXTRA_DENOM, "uscrt");
        intent.putExtra(NativeSendActivity.EXTRA_MEMO, memo);
        
        startActivityForResult(intent, REQ_SEND_NATIVE);
    }
    
    private void sendSnipToken(TokenOption tokenOption, String recipient, String amount, String memo) throws Exception {
        Tokens.TokenInfo token = tokenOption.tokenInfo;
        
        // Convert amount to token's smallest unit
        double amountDouble = Double.parseDouble(amount);
        long tokenAmount = Math.round(amountDouble * Math.pow(10, token.decimals));
        String tokenAmountString = String.valueOf(tokenAmount);
        
        // Create message for SNIP-20 transfer
        JSONObject transferMsg = new JSONObject();
        JSONObject transfer = new JSONObject();
        transfer.put("recipient", recipient);
        transfer.put("amount", tokenAmountString);
        if (!TextUtils.isEmpty(memo)) {
            transfer.put("memo", memo);
        }
        transferMsg.put("transfer", transfer);
        
        // Use SnipExecuteActivity for SNIP-20 token transfer
        Intent intent = new Intent(getActivity(), SnipExecuteActivity.class);
        intent.putExtra(SnipExecuteActivity.EXTRA_TOKEN_CONTRACT, token.contract);
        intent.putExtra(SnipExecuteActivity.EXTRA_TOKEN_HASH, token.hash);
        intent.putExtra(SnipExecuteActivity.EXTRA_RECIPIENT, recipient);
        intent.putExtra(SnipExecuteActivity.EXTRA_RECIPIENT_HASH, "");
        intent.putExtra(SnipExecuteActivity.EXTRA_AMOUNT, tokenAmountString);
        intent.putExtra(SnipExecuteActivity.EXTRA_MESSAGE_JSON, transferMsg.toString());
        
        startActivityForResult(intent, REQ_SEND_SNIP);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_SEND_NATIVE || requestCode == REQ_SEND_SNIP) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(getContext(), "Transaction sent successfully!", Toast.LENGTH_SHORT).show();
                // Clear form
                clearForm();
                // Notify parent
                if (listener != null) {
                    listener.onSendComplete();
                }
            } else {
                String error = data != null ? data.getStringExtra("error") : "Unknown error";
                Toast.makeText(getContext(), "Transaction failed: " + error, Toast.LENGTH_LONG).show();
            }
        }
    }
    
    private void clearForm() {
        recipientEditText.setText("");
        amountEditText.setText("");
        memoEditText.setText("");
    }
    
    private static SharedPreferences createSecurePrefs(Context context) throws Exception {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences.create(
            PREF_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }
    
    @Override
    public String getCurrentWalletAddress() {
        try {
            return SecureWalletManager.getWalletAddress(requireContext(), securePrefs);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get current wallet address", e);
            return "";
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
    
    /**
     * Token option for spinner
     */
    private static class TokenOption {
        final String symbol;
        final String displayName;
        final boolean isNative;
        final Tokens.TokenInfo tokenInfo;
        
        TokenOption(String symbol, String displayName, boolean isNative, Tokens.TokenInfo tokenInfo) {
            this.symbol = symbol;
            this.displayName = displayName;
            this.isNative = isNative;
            this.tokenInfo = tokenInfo;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
}