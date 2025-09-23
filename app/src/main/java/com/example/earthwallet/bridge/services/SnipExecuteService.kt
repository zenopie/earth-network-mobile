package com.example.earthwallet.bridge.services

import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Service for executing SNIP-20/SNIP-721 token transactions
 * Extracted from SnipExecuteActivity to work with TransactionActivity
 *
 * Handles SNIP token execution using the "send" message format:
 * {
 *   "send": {
 *     "recipient": "contract_address",
 *     "code_hash": "recipient_hash",
 *     "amount": "amount_string",
 *     "msg": "base64_encoded_message"
 *   }
 * }
 */
object SnipExecuteService {
    private const val TAG = "SnipExecuteService"

    @JvmStatic
    @Throws(Exception::class)
    fun execute(context: Context, intent: Intent): Array<String> {
        // Parse intent parameters
        val tokenContract = intent.getStringExtra("token_contract")
        val tokenHash = intent.getStringExtra("token_hash")
        val recipient = intent.getStringExtra("recipient")
        val recipientHash = intent.getStringExtra("recipient_hash")
        val amount = intent.getStringExtra("amount")
        val messageJson = intent.getStringExtra("message_json")

        // Validate required parameters
        if (TextUtils.isEmpty(tokenContract)) {
            throw Exception("Token contract is required")
        }
        if (TextUtils.isEmpty(tokenHash)) {
            throw Exception("Token hash is required")
        }
        if (TextUtils.isEmpty(recipient)) {
            throw Exception("Recipient is required")
        }
        if (TextUtils.isEmpty(amount)) {
            throw Exception("Amount is required")
        }
        if (TextUtils.isEmpty(messageJson)) {
            throw Exception("Message JSON is required")
        }

        // Build SNIP send message
        val snipMessage = buildSnipSendMessage(recipient!!, recipientHash, amount!!, messageJson!!)

        // Create intent for SecretExecuteService
        val executeIntent = Intent().apply {
            putExtra("contract_address", tokenContract)
            putExtra("code_hash", tokenHash)
            putExtra("execute_json", snipMessage)
        }

        // Delegate to SecretExecuteService
        return SecretExecuteService.execute(context, executeIntent)
    }

    @Throws(Exception::class)
    private fun buildSnipSendMessage(
        recipient: String,
        recipientHash: String?,
        amount: String,
        messageJson: String
    ): String {
        try {

            // Encode message JSON to base64
            val encodedMessage = Base64.encodeToString(messageJson.toByteArray(), Base64.NO_WRAP)

            // Build SNIP send message
            val sendMsg = JSONObject()
            val sendData = JSONObject().apply {
                put("recipient", recipient)
                if (!TextUtils.isEmpty(recipientHash)) {
                    put("code_hash", recipientHash)
                }
                put("amount", amount)
                put("msg", encodedMessage)
            }
            sendMsg.put("send", sendData)

            val result = sendMsg.toString()
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build SNIP message", e)
            throw Exception("Failed to build SNIP message: ${e.message}")
        }
    }
}