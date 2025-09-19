package com.example.earthwallet.bridge.services

import android.content.Context
import android.content.Intent

/**
 * Service for native SCRT sending transactions
 * Extracted from NativeSendActivity to work with TransactionActivity
 */
object NativeSendService {
    private const val TAG = "NativeSendService"

    @JvmStatic
    @Throws(Exception::class)
    fun execute(context: Context, intent: Intent): Array<String> {
        // TODO: Extract logic from NativeSendActivity
        // For now, throw an exception to indicate not implemented
        throw Exception("NativeSendService not yet implemented - needs extraction from NativeSendActivity")
    }
}