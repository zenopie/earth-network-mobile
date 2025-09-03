package com.example.earthwallet.ui.pages.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.PorterDuff;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.SecretWallet;

/**
 * WalletDisplayFragment
 * 
 * Handles wallet information display:
 * - Wallet address display and copying
 * - SCRT balance querying and display
 * - QR code generation and display
 * - Wallet navigation (switch wallet, create wallet)
 */
public class WalletDisplayFragment extends Fragment {
    
    private static final String TAG = "WalletDisplayFragment";
    
    // UI Components
    private TextView addressText;
    private TextView balanceText;
    private ImageView qrCodeView;
    private ImageButton sendButton;
    private ImageButton receiveButton;
    private LinearLayout addressContainer;
    
    // State
    private String currentAddress = "";
    private boolean balanceLoaded = false;
    
    // Interface for communication with parent
    public interface WalletDisplayListener {
        String getCurrentWalletAddress();
    }
    
    private WalletDisplayListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof WalletDisplayListener) {
            listener = (WalletDisplayListener) getParentFragment();
        } else if (context instanceof WalletDisplayListener) {
            listener = (WalletDisplayListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement WalletDisplayListener");
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_wallet_display, container, false);
        
        // Initialize UI components
        addressText = view.findViewById(R.id.addressText);
        balanceText = view.findViewById(R.id.balanceText);
        qrCodeView = view.findViewById(R.id.qrCodeView);
        sendButton = view.findViewById(R.id.sendButton);
        receiveButton = view.findViewById(R.id.receiveButton);
        addressContainer = view.findViewById(R.id.addressContainer);
        
        // Set white tint on button icons
        if (sendButton != null) {
            sendButton.setColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN);
        }
        if (receiveButton != null) {
            receiveButton.setColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN);
        }
        
        // Set up click listeners
        setupClickListeners();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Load wallet information from parent
        updateWalletInfo();
    }
    
    private void setupClickListeners() {
        // Set up address container click listener to copy address
        if (addressContainer != null) {
            addressContainer.setOnClickListener(v -> copyAddressToClipboard());
        }
    }
    
    /**
     * Public method to update wallet information
     */
    public void updateWalletInfo() {
        if (listener != null) {
            String newAddress = listener.getCurrentWalletAddress();
            
            // Only refresh balance if address changed or balance not yet loaded
            if (!newAddress.equals(currentAddress) || !balanceLoaded) {
                currentAddress = newAddress;
                updateUI();
                refreshBalance();
                generateQRCode();
            } else {
                // Address hasn't changed, just update UI without fetching balance
                currentAddress = newAddress;
                updateUI();
                generateQRCode();
            }
        }
    }
    
    /**
     * Public method to update just the address (for efficiency)
     */
    public void updateAddress(String address) {
        if (!address.equals(currentAddress)) {
            currentAddress = address;
            if (addressText != null) {
                addressText.setText(address);
            }
            refreshBalance();
            generateQRCode();
        }
    }
    
    /**
     * Public method to update just the balance
     */
    public void updateBalance(String balance) {
        if (balanceText != null) {
            balanceText.setText(balance);
        }
    }
    
    private void updateUI() {
        if (addressText != null && !TextUtils.isEmpty(currentAddress)) {
            addressText.setText(currentAddress);
        }
        
        // Set initial balance to "Loading..." only if not already loaded
        if (balanceText != null && !balanceLoaded) {
            balanceText.setText("Loading...");
        }
    }
    
    private void refreshBalance() {
        if (!TextUtils.isEmpty(currentAddress)) {
            // Launch background task to fetch SCRT balance
            balanceLoaded = false; // Mark as loading
            new FetchBalanceTask().execute(SecretWallet.DEFAULT_LCD_URL, currentAddress);
        }
    }
    
    private void copyAddressToClipboard() {
        if (!TextUtils.isEmpty(currentAddress)) {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Secret Address", currentAddress);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "Address copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void generateQRCode() {
        if (qrCodeView != null && !TextUtils.isEmpty(currentAddress)) {
            // For now, just hide the QR code view or show a placeholder
            // TODO: Implement QR code generation
            qrCodeView.setVisibility(View.GONE);
        }
    }
    
    /**
     * AsyncTask to fetch SCRT balance from LCD endpoint
     */
    private class FetchBalanceTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            if (params.length < 2) return "Error: missing params";
            String lcdUrl = params[0];
            String address = params[1];
            
            try {
                // Use SecretWallet's bank query method
                long microScrt = SecretWallet.fetchUscrtBalanceMicro(lcdUrl, address);
                return SecretWallet.formatScrt(microScrt);
            } catch (Exception e) {
                Log.e(TAG, "SCRT balance query failed", e);
                return "Error: " + e.getMessage();
            }
        }
        
        @Override
        protected void onPostExecute(String result) {
            if (balanceText != null) {
                balanceText.setText(result);
                balanceLoaded = true; // Mark as loaded
            }
        }
    }
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}