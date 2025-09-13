package com.example.earthwallet.bridge.services;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Service for native SCRT sending transactions
 * Extracted from NativeSendActivity to work with TransactionActivity
 */
public class NativeSendService {
    private static final String TAG = "NativeSendService";
    
    public static String[] execute(Context context, Intent intent) throws Exception {
        // TODO: Extract logic from NativeSendActivity
        // For now, throw an exception to indicate not implemented
        throw new Exception("NativeSendService not yet implemented - needs extraction from NativeSendActivity");
    }
}