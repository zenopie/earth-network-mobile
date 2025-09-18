package com.example.earthwallet.ui.pages.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;

import com.example.earthwallet.bridge.activities.TransactionActivity;

import com.example.earthwallet.wallet.constants.Tokens;
import com.example.earthwallet.wallet.utils.WalletNetwork;
import com.example.earthwallet.wallet.services.SecureWalletManager;
import com.example.earthwallet.wallet.services.ContactsManager;
import com.example.earthwallet.bridge.services.SnipQueryService;
import com.example.earthwallet.bridge.utils.PermitManager;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;


import org.json.JSONArray;
import org.json.JSONObject;

import android.os.AsyncTask;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

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
    private static final int REQ_SEND_NATIVE = 3001;
    private static final int REQ_SEND_SNIP = 3002;
    
    // UI Components
    private Spinner tokenSpinner;
    private EditText recipientEditText;
    private ImageButton pickWalletButton;
    private ImageButton contactsButton;
    private ImageButton scanQrButton;
    private Button clearRecipientButton;
    private EditText amountEditText;
    private EditText memoEditText;
    private Button sendButton;
    private TextView balanceText;
    private ImageView tokenLogo;
    
    // Data
    private List<TokenOption> tokenOptions;
    private ActivityResultLauncher<ScanOptions> qrScannerLauncher;
    private String currentWalletAddress;
    private boolean balanceLoaded = false;
    private PermitManager permitManager;
    
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

        // Initialize QR scanner launcher
        qrScannerLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) {
                handleQRScanResult(result.getContents());
            }
        });

        // Listen for contact selection results
        getParentFragmentManager().setFragmentResultListener("contact_selected", this, (requestKey, result) -> {
            String contactName = result.getString("contact_name");
            String contactAddress = result.getString("contact_address");
            if (contactAddress != null) {
                recipientEditText.setText(contactAddress);
                Toast.makeText(getContext(), "Selected: " + contactName, Toast.LENGTH_SHORT).show();
            }
        });
        
        // Initialize UI components
        tokenSpinner = view.findViewById(R.id.tokenSpinner);
        recipientEditText = view.findViewById(R.id.recipientEditText);
        pickWalletButton = view.findViewById(R.id.pickWalletButton);
        contactsButton = view.findViewById(R.id.contactsButton);
        scanQrButton = view.findViewById(R.id.scanQrButton);
        clearRecipientButton = view.findViewById(R.id.clearRecipientButton);
        amountEditText = view.findViewById(R.id.amountEditText);
        memoEditText = view.findViewById(R.id.memoEditText);
        sendButton = view.findViewById(R.id.sendButton);
        balanceText = view.findViewById(R.id.balanceText);
        tokenLogo = view.findViewById(R.id.tokenLogo);
        
        try {
            permitManager = PermitManager.getInstance(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize permit manager", e);
            Toast.makeText(getContext(), "Failed to initialize wallet", Toast.LENGTH_SHORT).show();
            return view;
        }
        
        // Load current wallet address
        loadCurrentWalletAddress();

        setupTokenSpinner();
        setupClickListeners();

        // Force spinner background to be light
        tokenSpinner.setBackgroundColor(0xFFFFFFFF); // White background
        
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Load balance and logo for initially selected token (first item in spinner)
        if (tokenOptions != null && !tokenOptions.isEmpty()) {
            fetchTokenBalance(tokenOptions.get(0));
            loadTokenLogo(tokenOptions.get(0));
        }
    }
    
    private void setupTokenSpinner() {
        tokenOptions = new ArrayList<>();
        
        // Add native SCRT option
        tokenOptions.add(new TokenOption("SCRT", "SCRT", true, null));
        
        // Add SNIP-20 tokens
        for (String symbol : Tokens.ALL_TOKENS.keySet()) {
            Tokens.TokenInfo token = Tokens.ALL_TOKENS.get(symbol);
            tokenOptions.add(new TokenOption(symbol, symbol, false, token));
        }
        
        // Create simple list for spinner (just token symbols like swap fragment)
        List<String> tokenSymbols = new ArrayList<>();
        for (TokenOption option : tokenOptions) {
            tokenSymbols.add(option.displayName);
        }

        // Use the same adapter style as SwapTokensMainFragment
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
            R.layout.spinner_item, tokenSymbols);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        tokenSpinner.setAdapter(adapter);

        // Force spinner background to be transparent to blend with input box
        try {
            tokenSpinner.getBackground().setAlpha(0);
        } catch (Exception e) {
            Log.w(TAG, "Could not set spinner background alpha", e);
        }

        // Set up spinner selection listener to load balance and logo
        tokenSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < tokenOptions.size()) {
                    TokenOption selectedToken = tokenOptions.get(position);
                    fetchTokenBalance(selectedToken);
                    loadTokenLogo(selectedToken);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
    }
    
    
    private void launchQRScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan QR code to get recipient address");
        options.setBeepEnabled(true);
        options.setBarcodeImageEnabled(true);
        options.setOrientationLocked(true);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setCameraId(0); // Use rear camera
        
        qrScannerLauncher.launch(options);
    }
    
    private void handleQRScanResult(String scannedContent) {
        String content = scannedContent.trim();
        
        // Validate that the scanned content is a valid Secret Network address
        if (content.startsWith("secret1") && content.length() >= 45) {
            recipientEditText.setText(content);
            Toast.makeText(getContext(), "Address scanned successfully!", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "QR code scanned: " + content.substring(0, 14) + "...");
        } else {
            Toast.makeText(getContext(), "Invalid Secret Network address in QR code", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Invalid QR code content: " + content);
        }
    }
    
    private void showWalletSelectionDialog() {
        try {
            List<WalletOption> walletOptions = new ArrayList<>();

            // Load all wallets using SecureWalletManager (safe - no mnemonics exposed)
            JSONArray walletsArray = SecureWalletManager.getAllWallets(requireContext());
            String currentAddress = getCurrentWalletAddress();

            for (int i = 0; i < walletsArray.length(); i++) {
                JSONObject wallet = walletsArray.getJSONObject(i);
                String address = wallet.optString("address", "");
                String name = wallet.optString("name", "Wallet " + (i + 1));

                if (!TextUtils.isEmpty(address)) {
                    // Don't include the current wallet in the recipient list
                    if (!address.equals(currentAddress)) {
                        String displayName = name + " (" + address.substring(0, 14) + "...)";
                        walletOptions.add(new WalletOption(address, displayName, name));
                    }
                }
            }
            
            if (walletOptions.isEmpty()) {
                Toast.makeText(getContext(), "No other wallets available", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Create dialog with wallet options
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("Select Wallet");
            
            String[] walletNames = new String[walletOptions.size()];
            for (int i = 0; i < walletOptions.size(); i++) {
                walletNames[i] = walletOptions.get(i).displayName;
            }
            
            builder.setItems(walletNames, (dialog, which) -> {
                WalletOption selected = walletOptions.get(which);
                recipientEditText.setText(selected.address);
                Toast.makeText(getContext(), "Selected: " + selected.name, Toast.LENGTH_SHORT).show();
            });
            
            builder.setNegativeButton("Cancel", null);
            builder.show();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to show wallet selection dialog", e);
            Toast.makeText(getContext(), "Failed to load wallets", Toast.LENGTH_SHORT).show();
        }
    }

    private void showContactsDialog() {
        // Navigate to contacts fragment
        ContactsFragment contactsFragment = new ContactsFragment();

        // Replace current fragment with contacts fragment
        getParentFragmentManager().beginTransaction()
            .replace(getId(), contactsFragment)
            .addToBackStack(null)
            .commit();
    }
    
    private void setupClickListeners() {
        sendButton.setOnClickListener(v -> sendTokens());
        
        clearRecipientButton.setOnClickListener(v -> {
            recipientEditText.setText("");
        });
        
        pickWalletButton.setOnClickListener(v -> showWalletSelectionDialog());
        contactsButton.setOnClickListener(v -> showContactsDialog());
        scanQrButton.setOnClickListener(v -> launchQRScanner());
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
        
        int selectedPosition = tokenSpinner.getSelectedItemPosition();
        if (selectedPosition < 0 || selectedPosition >= tokenOptions.size()) {
            Toast.makeText(getContext(), "Please select a token", Toast.LENGTH_SHORT).show();
            return;
        }
        TokenOption selectedToken = tokenOptions.get(selectedPosition);
        
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
        Intent intent = new Intent(getActivity(), TransactionActivity.class);
        intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_NATIVE_SEND);
        intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, recipient);
        intent.putExtra(TransactionActivity.EXTRA_AMOUNT, microScrtString);
        intent.putExtra(TransactionActivity.EXTRA_MEMO, memo);
        
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

        // Use TransactionActivity for SNIP-20 token transfer
        Intent intent = new Intent(getActivity(), TransactionActivity.class);
        intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SNIP_EXECUTE);
        intent.putExtra(TransactionActivity.EXTRA_TOKEN_CONTRACT, token.contract);
        intent.putExtra(TransactionActivity.EXTRA_TOKEN_HASH, token.hash);
        intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, recipient);
        intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_HASH, "");
        intent.putExtra(TransactionActivity.EXTRA_AMOUNT, tokenAmountString);
        intent.putExtra(TransactionActivity.EXTRA_MESSAGE_JSON, transferMsg.toString());
        
        startActivityForResult(intent, REQ_SEND_SNIP);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SEND_NATIVE || requestCode == REQ_SEND_SNIP) {
            if (resultCode == Activity.RESULT_OK) {
                // Clear form
                clearForm();
                // Refresh balance after successful transaction
                int selectedPosition = tokenSpinner.getSelectedItemPosition();
                if (selectedPosition >= 0 && selectedPosition < tokenOptions.size()) {
                    fetchTokenBalance(tokenOptions.get(selectedPosition));
                }
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

    @Override
    public void onResume() {
        super.onResume();
        // Refresh wallet address first (like TokenBalancesFragment)
        loadCurrentWalletAddress();

        // Refresh balance and logo when returning to the screen
        int selectedPosition = tokenSpinner.getSelectedItemPosition();
        if (selectedPosition >= 0 && selectedPosition < tokenOptions.size()) {
            TokenOption selectedToken = tokenOptions.get(selectedPosition);
            fetchTokenBalance(selectedToken);
            loadTokenLogo(selectedToken);
        }
    }
    
    private void clearForm() {
        recipientEditText.setText("");
        amountEditText.setText("");
        memoEditText.setText("");
    }

    private void loadCurrentWalletAddress() {
        // Use SecureWalletManager to get wallet address directly
        try {
            currentWalletAddress = SecureWalletManager.getWalletAddress(requireContext());
            if (!TextUtils.isEmpty(currentWalletAddress)) {
                Log.d(TAG, "Loaded wallet address: " + currentWalletAddress.substring(0, Math.min(14, currentWalletAddress.length())) + "...");
            } else {
                Log.w(TAG, "No wallet address available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallet address", e);
            currentWalletAddress = "";
        }
    }

    private void fetchTokenBalance(TokenOption tokenOption) {

        if (TextUtils.isEmpty(currentWalletAddress)) {
            Log.w(TAG, "No wallet address available for balance fetch");
            balanceText.setText("Balance: Connect wallet");
            return;
        }

        if (tokenOption.isNative) {
            // Native SCRT balance using SecretWallet
            balanceText.setText("Balance: Loading...");
            new FetchScrtBalanceTask().execute(WalletNetwork.DEFAULT_LCD_URL, currentWalletAddress);
        } else {
            // SNIP-20 token balance using permit-based queries
            if (!hasPermitForToken(tokenOption.symbol)) {
                balanceText.setText("Balance: Create permit");
                return;
            }

            balanceText.setText("Balance: Loading...");
            fetchSnipTokenBalanceWithPermit(tokenOption.symbol);
        }
    }

    private boolean hasPermitForToken(String tokenSymbol) {
        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
        if (tokenInfo == null) {
            return false;
        }
        return permitManager.hasPermit(currentWalletAddress, tokenInfo.contract);
    }

    private void fetchSnipTokenBalanceWithPermit(String tokenSymbol) {
        new Thread(() -> {
            try {
                JSONObject result = SnipQueryService.queryBalanceWithPermit(
                    getActivity(),
                    tokenSymbol,
                    currentWalletAddress
                );

                // Handle result on UI thread
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        handleSnipBalanceResult(tokenSymbol, result.toString());
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Token balance query failed for " + tokenSymbol + ": " + e.getMessage(), e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        balanceText.setText("Balance: Error loading");
                    });
                }
            }
        }).start();
    }

    private void handleSnipBalanceResult(String tokenSymbol, String json) {
        try {
            if (TextUtils.isEmpty(json)) {
                balanceText.setText("Balance: Error loading");
                return;
            }

            JSONObject root = new JSONObject(json);
            boolean success = root.optBoolean("success", false);

            if (success) {
                JSONObject result = root.optJSONObject("result");
                if (result != null) {
                    JSONObject balance = result.optJSONObject("balance");
                    if (balance != null) {
                        String amount = balance.optString("amount", "0");
                        Tokens.TokenInfo tokenInfo = Tokens.getToken(tokenSymbol);
                        if (tokenInfo != null) {
                            double formattedBalance = 0;
                            if (!TextUtils.isEmpty(amount)) {
                                try {
                                    long rawAmount = Long.parseLong(amount);
                                    formattedBalance = rawAmount / Math.pow(10, tokenInfo.decimals);
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Failed to parse balance amount: " + amount, e);
                                }
                            }
                            balanceText.setText(String.format("Balance: %.6f %s", formattedBalance, tokenSymbol));
                        } else {
                            balanceText.setText("Balance: Error loading");
                        }
                    } else {
                        balanceText.setText("Balance: Error loading");
                    }
                } else {
                    balanceText.setText("Balance: Error loading");
                }
            } else {
                balanceText.setText("Balance: Error loading");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle SNIP balance result", e);
            balanceText.setText("Balance: Error loading");
        }
    }

    private void loadTokenLogo(TokenOption tokenOption) {
        if (tokenLogo == null) return;

        if (tokenOption.isNative) {
            // Native SCRT - use gas station icon
            tokenLogo.setImageResource(R.drawable.ic_local_gas_station);
        } else {
            // SNIP-20 token - load from assets
            try {
                Tokens.TokenInfo tokenInfo = tokenOption.tokenInfo;
                if (tokenInfo != null && !TextUtils.isEmpty(tokenInfo.logo)) {
                    // Load logo from assets
                    Bitmap bitmap = BitmapFactory.decodeStream(getContext().getAssets().open(tokenInfo.logo));
                    tokenLogo.setImageBitmap(bitmap);
                } else {
                    // No logo available, use default wallet icon
                    tokenLogo.setImageResource(R.drawable.ic_wallet);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load logo for " + tokenOption.symbol + ", using default icon", e);
                tokenLogo.setImageResource(R.drawable.ic_wallet);
            }
        }
    }

    /**
     * AsyncTask to fetch SCRT balance from LCD endpoint
     */
    private class FetchScrtBalanceTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            if (params.length < 2) return "Error: missing params";
            String lcdUrl = params[0];
            String address = params[1];

            try {
                // Use SecretWallet's bank query method
                long microScrt = WalletNetwork.fetchUscrtBalanceMicro(lcdUrl, address);
                return WalletNetwork.formatScrt(microScrt);
            } catch (Exception e) {
                Log.e(TAG, "SCRT balance query failed", e);
                return "Error: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (balanceText != null) {
                balanceText.setText("Balance: " + result);
                balanceLoaded = true;
            }
        }
    }
    
    
    @Override
    public String getCurrentWalletAddress() {
        try {
            return SecureWalletManager.getWalletAddress(requireContext());
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
    
    /**
     * Wallet option for recipient spinner
     */
    private static class WalletOption {
        final String address;
        final String displayName;
        final String name;
        
        WalletOption(String address, String displayName, String name) {
            this.address = address;
            this.displayName = displayName;
            this.name = name;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
}