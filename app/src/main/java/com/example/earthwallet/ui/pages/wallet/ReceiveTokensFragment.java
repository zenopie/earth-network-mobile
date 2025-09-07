package com.example.earthwallet.ui.pages.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import com.example.earthwallet.wallet.services.SecureWalletManager;

import org.bitcoinj.core.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

/**
 * ReceiveTokensFragment
 * 
 * Handles receiving tokens by displaying wallet address and QR code:
 * - Shows the current wallet address
 * - Provides copy-to-clipboard functionality
 * - Generates QR code for easy sharing
 * - Works for both native SCRT and SNIP-20 tokens (same address)
 */
public class ReceiveTokensFragment extends Fragment {
    
    private static final String TAG = "ReceiveTokensFragment";
    
    private static final String PREF_FILE = "secret_wallet_prefs";
    
    // UI Components
    private TextView addressText;
    private TextView instructionsText;
    private Button copyButton;
    private ImageView qrCodeView;
    
    
    // Interface for communication with parent
    public interface ReceiveTokensListener {
        String getCurrentWalletAddress();
    }
    
    private ReceiveTokensListener listener;
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof ReceiveTokensListener) {
            listener = (ReceiveTokensListener) getParentFragment();
        } else if (context instanceof ReceiveTokensListener) {
            listener = (ReceiveTokensListener) context;
        } else {
            Log.w(TAG, "Parent does not implement ReceiveTokensListener");
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_receive_tokens, container, false);
        
        // Initialize UI components
        addressText = view.findViewById(R.id.addressText);
        instructionsText = view.findViewById(R.id.instructionsText);
        copyButton = view.findViewById(R.id.copyButton);
        qrCodeView = view.findViewById(R.id.qrCodeView);
        
        
        setupClickListeners();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateWalletAddress();
    }
    
    private void setupClickListeners() {
        copyButton.setOnClickListener(v -> copyAddressToClipboard());
        
        // Also allow clicking on the address text to copy
        addressText.setOnClickListener(v -> copyAddressToClipboard());
    }
    
    private void updateWalletAddress() {
        try {
            String address = SecureWalletManager.getWalletAddress(requireContext());
            if (!TextUtils.isEmpty(address)) {
                addressText.setText(address);
                generateQRCode(address);
            } else {
                addressText.setText("No wallet address available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get wallet address", e);
            addressText.setText("Error loading address");
        }
    }
    
    private void copyAddressToClipboard() {
        String address = addressText.getText().toString();
        if (!TextUtils.isEmpty(address) && !address.equals("No wallet address available") && !address.equals("Wallet not available")) {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Secret Address", address);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "Address copied to clipboard", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "No address to copy", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void generateQRCode(String address) {
        if (qrCodeView != null && !TextUtils.isEmpty(address)) {
            // For now, just hide the QR code view
            // TODO: Implement QR code generation using a QR library
            qrCodeView.setVisibility(View.GONE);
            
            Log.d(TAG, "QR code generation not yet implemented for address: " + address);
        }
    }
    
    /**
     * Public method to refresh the displayed address
     */
    public void refreshAddress() {
        updateWalletAddress();
    }
    
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}