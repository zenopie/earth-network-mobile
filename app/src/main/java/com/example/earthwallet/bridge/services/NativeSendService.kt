package com.example.earthwallet.bridge.services

import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.util.Log
import com.example.earthwallet.Constants
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.wallet.services.SecureWalletManager
import com.example.earthwallet.wallet.utils.WalletCrypto
import org.json.JSONObject

/**
 * Service for native SCRT sending transactions
 * Handles native SCRT transfers using Cosmos SDK MsgSend
 */
object NativeSendService {
    private const val TAG = "NativeSendService"

    @JvmStatic
    @Throws(Exception::class)
    fun execute(context: Context, intent: Intent): Array<String> {
        Log.d(TAG, "Starting native SCRT send transaction")

        // Parse intent parameters
        val recipientAddress = intent.getStringExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS)
            ?: throw Exception("Recipient address is required")
        val amount = intent.getStringExtra(TransactionActivity.EXTRA_AMOUNT)
            ?: throw Exception("Amount is required")
        val memo = intent.getStringExtra(TransactionActivity.EXTRA_MEMO) ?: ""

        // Validate parameters
        if (TextUtils.isEmpty(recipientAddress)) {
            throw Exception("Recipient address cannot be empty")
        }
        if (TextUtils.isEmpty(amount) || amount.toLongOrNull() == null || amount.toLong() <= 0) {
            throw Exception("Invalid amount")
        }

        Log.d(TAG, "Sending $amount uscrt to $recipientAddress")

        // Use SecureWalletManager to execute with mnemonic
        return SecureWalletManager.executeWithMnemonic(context) { mnemonic ->
            // Initialize wallet crypto
            WalletCrypto.initialize(context)
            val walletKey = WalletCrypto.deriveKeyFromMnemonic(mnemonic)
            val senderAddress = WalletCrypto.getAddress(walletKey)

            // Fetch chain and account information
            val lcdUrl = Constants.DEFAULT_LCD_URL
            val chainId = SecretNetworkService.fetchChainIdSync(lcdUrl)
            val accountData = SecretNetworkService.fetchAccountSync(lcdUrl, senderAddress)
                ?: throw Exception("Account not found: $senderAddress")

            Log.d(TAG, "Account data: ${accountData.toString()}")

            val accountNumber = accountData.optString("account_number")
            val sequence = accountData.optString("sequence")

            Log.d(TAG, "Creating native send transaction with account_number: $accountNumber, sequence: $sequence")

            // Build native send transaction using protobuf service
            val protobufService = SecretProtobufService()
            val txBytes = protobufService.buildNativeSendTransaction(
                sender = senderAddress,
                recipient = recipientAddress,
                amount = amount,
                memo = memo,
                accountNumber = accountNumber,
                sequence = sequence,
                chainId = chainId,
                walletKey = walletKey
            )

            // Broadcast transaction
            val response = SecretNetworkService.broadcastTransactionModernSync(lcdUrl, txBytes)
            Log.d(TAG, "Broadcast response: $response")

            // Enhance response with detailed results
            val enhancedResponse = enhanceTransactionResponse(response, lcdUrl)

            // Validate response
            validateTransactionResponse(enhancedResponse)

            arrayOf(enhancedResponse, senderAddress)
        }
    }

    private fun enhanceTransactionResponse(initialResponse: String, lcdUrl: String): String {
        return try {
            val response = JSONObject(initialResponse)
            if (response.has("tx_response")) {
                val txResponse = response.getJSONObject("tx_response")
                val code = txResponse.optInt("code", -1)
                val txHash = txResponse.optString("txhash", "")

                if (code == 0 && txHash.isNotEmpty()) {
                    try {
                        Thread.sleep(2000)
                        val detailedResponse = SecretNetworkService.queryTransactionByHashSync(lcdUrl, txHash)
                        detailedResponse ?: initialResponse
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        initialResponse
                    }
                } else {
                    initialResponse
                }
            } else {
                initialResponse
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enhance response: ${e.message}")
            initialResponse
        }
    }

    @Throws(Exception::class)
    private fun validateTransactionResponse(response: String) {
        val responseObj = JSONObject(response)

        if (responseObj.has("tx_response")) {
            val txResponse = responseObj.getJSONObject("tx_response")
            val code = txResponse.optInt("code", -1)
            val rawLog = txResponse.optString("raw_log", "")

            if (code != 0) {
                throw Exception("Transaction failed: Code $code. $rawLog")
            }
        } else {
            throw Exception("Invalid transaction response format")
        }
    }
}