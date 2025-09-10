package com.example.earthwallet.ui.components;

import android.content.Context;
import android.util.Log;

/**
 * Reusable transaction handler that manages the complete flow:
 * 1. Show confirmation dialog
 * 2. Execute transaction on background thread
 * 3. Show status modal with loading/success/error states
 * 4. Handle completion
 */
public class TransactionHandler {
    private static final String TAG = "TransactionHandler";
    
    public interface TransactionExecutor {
        void executeTransaction() throws Exception;
    }
    
    public interface TransactionResultHandler {
        void onSuccess(String result, String senderAddress);
        void onError(String error);
    }
    
    private Context context;
    private TransactionConfirmationDialog confirmationDialog;
    private StatusModal statusModal;
    private TransactionResultHandler resultHandler;
    
    public TransactionHandler(Context context) {
        this.context = context;
        this.confirmationDialog = new TransactionConfirmationDialog(context);
        this.statusModal = new StatusModal(context);
        setupStatusModal();
    }
    
    private void setupStatusModal() {
        statusModal.setOnCloseListener(() -> {
            if (statusModal.getCurrentState() == StatusModal.State.SUCCESS) {
                // Success case handled by transaction executor
            } else if (statusModal.getCurrentState() == StatusModal.State.ERROR) {
                // Error case handled by transaction executor
            }
        });
    }
    
    /**
     * Execute a transaction with confirmation dialog and status modal
     */
    public void executeTransaction(TransactionConfirmationDialog.TransactionDetails details,
                                 TransactionExecutor executor,
                                 TransactionResultHandler resultHandler) {
        this.resultHandler = resultHandler;
        
        // Show confirmation dialog first
        confirmationDialog.show(details, new TransactionConfirmationDialog.OnConfirmationListener() {
            @Override
            public void onConfirmed() {
                // User confirmed - show status modal and execute transaction
                statusModal.show(StatusModal.State.LOADING);
                
                new Thread(() -> {
                    try {
                        executor.executeTransaction();
                    } catch (Exception e) {
                        Log.e(TAG, "Transaction failed", e);
                        if (context instanceof android.app.Activity) {
                            ((android.app.Activity) context).runOnUiThread(() -> {
                                statusModal.updateState(StatusModal.State.ERROR);
                                if (resultHandler != null) {
                                    resultHandler.onError("Transaction failed: " + e.getMessage());
                                }
                            });
                        }
                    }
                }).start();
            }
            
            @Override
            public void onCancelled() {
                // User cancelled
                if (resultHandler != null) {
                    resultHandler.onError("Transaction cancelled");
                }
            }
        });
    }
    
    /**
     * Call this from the transaction executor when transaction succeeds
     */
    public void onTransactionSuccess(String result, String senderAddress) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() -> {
                statusModal.updateState(StatusModal.State.SUCCESS);
                
                // Set up a delayed callback to handle the success result after status modal closes
                statusModal.setOnCloseListener(() -> {
                    if (resultHandler != null) {
                        resultHandler.onSuccess(result, senderAddress);
                    }
                });
            });
        }
    }
    
    /**
     * Call this from the transaction executor when transaction fails
     */
    public void onTransactionError(String error) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() -> {
                statusModal.updateState(StatusModal.State.ERROR);
                
                // Set up callback to handle error after status modal closes
                statusModal.setOnCloseListener(() -> {
                    if (resultHandler != null) {
                        resultHandler.onError(error);
                    }
                });
            });
        }
    }
    
    /**
     * Clean up resources
     */
    public void destroy() {
        if (confirmationDialog != null) {
            confirmationDialog.dismiss();
            confirmationDialog = null;
        }
        if (statusModal != null) {
            statusModal.destroy();
            statusModal = null;
        }
        resultHandler = null;
        context = null;
    }
}