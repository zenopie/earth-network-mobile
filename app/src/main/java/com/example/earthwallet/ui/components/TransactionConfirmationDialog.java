package com.example.earthwallet.ui.components;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.earthwallet.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Reusable transaction confirmation dialog component
 * Used to show transaction details and get user confirmation before execution
 */
public class TransactionConfirmationDialog {
    
    public interface OnConfirmationListener {
        void onConfirmed();
        void onCancelled();
    }
    
    public static class TransactionDetails {
        public String contractAddress;
        public String contractLabel = "Contract:";
        public String message;
        public String funds;
        public String memo;
        public String customWarning;
        
        public TransactionDetails(String contractAddress, String message) {
            this.contractAddress = contractAddress;
            this.message = message;
        }
        
        public TransactionDetails setContractLabel(String label) {
            this.contractLabel = label;
            return this;
        }
        
        public TransactionDetails setFunds(String funds) {
            this.funds = funds;
            return this;
        }
        
        public TransactionDetails setMemo(String memo) {
            this.memo = memo;
            return this;
        }
        
        public TransactionDetails setCustomWarning(String warning) {
            this.customWarning = warning;
            return this;
        }
    }
    
    private BottomSheetDialog bottomSheetDialog;
    private OnConfirmationListener listener;
    
    public TransactionConfirmationDialog(Context context) {
        bottomSheetDialog = new BottomSheetDialog(context);
    }
    
    public void show(TransactionDetails details, OnConfirmationListener listener) {
        this.listener = listener;
        
        View bottomSheetView = LayoutInflater.from(bottomSheetDialog.getContext())
            .inflate(R.layout.transaction_confirmation_popup, null);
        bottomSheetDialog.setContentView(bottomSheetView);
        
        final boolean[] isConfirmed = {false};
        
        // Find views
        TextView contractLabel = bottomSheetView.findViewById(R.id.contract_label);
        TextView contractAddressText = bottomSheetView.findViewById(R.id.contract_address_text);
        TextView executeMessageText = bottomSheetView.findViewById(R.id.execute_message_text);
        TextView fundsText = bottomSheetView.findViewById(R.id.funds_text);
        TextView memoText = bottomSheetView.findViewById(R.id.memo_text);
        View fundsSection = bottomSheetView.findViewById(R.id.funds_section);
        View memoSection = bottomSheetView.findViewById(R.id.memo_section);
        Button cancelButton = bottomSheetView.findViewById(R.id.cancel_button);
        Button confirmButton = bottomSheetView.findViewById(R.id.confirm_button);
        
        // Set transaction details
        contractLabel.setText(details.contractLabel);
        contractAddressText.setText(truncateAddress(details.contractAddress));
        executeMessageText.setText(details.message);
        
        // Show funds section if funds are provided
        if (!TextUtils.isEmpty(details.funds)) {
            fundsSection.setVisibility(View.VISIBLE);
            fundsText.setText(details.funds);
        } else {
            fundsSection.setVisibility(View.GONE);
        }
        
        // Show memo section if memo is provided
        if (!TextUtils.isEmpty(details.memo)) {
            memoSection.setVisibility(View.VISIBLE);
            memoText.setText(details.memo);
        } else {
            memoSection.setVisibility(View.GONE);
        }
        
        // Set click listeners
        cancelButton.setOnClickListener(v -> {
            isConfirmed[0] = false;
            bottomSheetDialog.dismiss();
        });
        
        confirmButton.setOnClickListener(v -> {
            isConfirmed[0] = true;
            bottomSheetDialog.dismiss();
        });
        
        // Handle swipe down / dismiss
        bottomSheetDialog.setOnDismissListener(dialog -> {
            if (isConfirmed[0] && listener != null) {
                listener.onConfirmed();
            } else if (listener != null) {
                listener.onCancelled();
            }
        });
        
        bottomSheetDialog.show();
        
        // Configure bottom sheet to expand to full content height
        View bottomSheet = bottomSheetDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
    }
    
    private String truncateAddress(String address) {
        if (TextUtils.isEmpty(address)) return "";
        if (address.length() <= 20) return address;
        return address.substring(0, 10) + "..." + address.substring(address.length() - 6);
    }
    
    public void dismiss() {
        if (bottomSheetDialog != null && bottomSheetDialog.isShowing()) {
            bottomSheetDialog.dismiss();
        }
    }
}