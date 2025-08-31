package com.example.earthwallet.ui.fragments.main;

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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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
    private Button refreshBalanceBtn;
    private ImageView qrCodeView;
    
    // State
    private String currentAddress = "";
    
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
        refreshBalanceBtn = view.findViewById(R.id.refreshBalanceBtn);
        qrCodeView = view.findViewById(R.id.qrCodeView);
        
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
        refreshBalanceBtn.setOnClickListener(v -> refreshBalance());
    }
    
    /**
     * Public method to update wallet information
     */
    public void updateWalletInfo() {
        if (listener != null) {
            currentAddress = listener.getCurrentWalletAddress();
            
            updateUI();
            refreshBalance();
            generateQRCode();
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
        
        // Set initial balance to "Loading..." while we fetch it
        if (balanceText != null) {
            balanceText.setText("Loading...");
        }
    }
    
    private void refreshBalance() {
        if (!TextUtils.isEmpty(currentAddress)) {
            // Launch background task to fetch SCRT balance
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
            }
        }
    }
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}