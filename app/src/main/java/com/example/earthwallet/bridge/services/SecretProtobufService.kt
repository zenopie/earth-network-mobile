package com.example.earthwallet.bridge.services

import android.util.Base64
import android.util.Log
import com.example.earthwallet.wallet.services.TransactionSigner
import com.example.earthwallet.wallet.utils.WalletCrypto
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.crypto.secp256k1.PubKey
import cosmos.tx.signing.v1beta1.SignMode
import cosmos.tx.v1beta1.Tx
import org.bitcoinj.core.ECKey
import org.json.JSONArray
import org.json.JSONObject
import secret.compute.v1beta1.MsgExecuteContract

/**
 * SecretProtobufService
 *
 * Handles Secret Network protobuf transaction building:
 * - Creates MsgExecuteContract messages
 * - Builds Cosmos SDK transaction structure
 * - Signs with protobuf SignDoc
 * - Encodes to TxRaw format for broadcasting
 */
class SecretProtobufService {

    companion object {
        private const val TAG = "SecretProtobufService"
    }

    /**
     * Builds a complete protobuf transaction for Secret Network contract execution
     * Supports both single message (legacy) and multi-message transactions
     */
    @Throws(Exception::class)
    fun buildTransaction(
        sender: String,
        contractAddr: String,
        codeHash: String,
        encryptedMsg: ByteArray,
        funds: String?,
        memo: String,
        accountNumber: String,
        sequence: String,
        chainId: String,
        walletKey: ECKey
    ): ByteArray {

        // Convert single message to messages array for unified processing
        val messagesArray = JSONArray()
        val singleMessage = JSONObject()
        try {
            singleMessage.put("sender", sender)
            singleMessage.put("contract", contractAddr)
            singleMessage.put("code_hash", codeHash)
            singleMessage.put("encrypted_msg", Base64.encodeToString(encryptedMsg, Base64.NO_WRAP))
            if (!funds.isNullOrEmpty()) {
                singleMessage.put("sent_funds", parseCoins(funds))
            }
            messagesArray.put(singleMessage)
        } catch (e: Exception) {
            throw Exception("Failed to create single message array: ${e.message}")
        }

        return buildMultiMessageTransaction(messagesArray, memo, accountNumber, sequence, chainId, walletKey)
    }

    /**
     * Builds a protobuf transaction with multiple messages - unified method for all execute transactions
     */
    @Throws(Exception::class)
    fun buildMultiMessageTransaction(
        messages: JSONArray,
        memo: String,
        accountNumber: String,
        sequence: String,
        chainId: String,
        walletKey: ECKey
    ): ByteArray {

        Log.i(TAG, "Building Secret Network protobuf transaction with ${messages.length()} message(s)")

        return try {
            encodeMultiMessageTransactionToProtobuf(
                messages, memo, accountNumber, sequence, chainId, walletKey
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build multi-message protobuf transaction", e)
            throw Exception("Multi-message protobuf transaction building failed: ${e.message}")
        }
    }

    /**
     * Creates a protobuf transaction with multiple MsgExecuteContract messages
     */
    @Throws(Exception::class)
    private fun encodeMultiMessageTransactionToProtobuf(
        messages: JSONArray,
        memo: String,
        accountNumber: String,
        sequence: String,
        chainId: String,
        walletKey: ECKey
    ): ByteArray {

        Log.i(TAG, "Creating multi-message protobuf transaction")

        // Get sender from first message (all messages should have same sender)
        val firstMessage = messages.getJSONObject(0)
        val sender = firstMessage.getString("sender")
        val senderBytes = decodeBech32Address(sender)

        // 1. Create all MsgExecuteContract messages
        val txBodyBuilder = Tx.TxBody.newBuilder()

        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val contract = message.getString("contract")
            val encryptedMsgB64 = message.getString("encrypted_msg")

            // Convert addresses to binary format
            val contractBytes = decodeBech32Address(contract)
            val encryptedMsg = Base64.decode(encryptedMsgB64, Base64.NO_WRAP)

            // Create MsgExecuteContract
            val msgBuilder = MsgExecuteContract.newBuilder()
                .setSender(ByteString.copyFrom(senderBytes))
                .setContract(ByteString.copyFrom(contractBytes))
                .setMsg(ByteString.copyFrom(encryptedMsg))
                .setCallbackCodeHash("")
                .setCallbackSig(ByteString.EMPTY)

            // Add funds if provided
            if (message.has("sent_funds")) {
                val coins = message.getJSONArray("sent_funds")
                for (j in 0 until coins.length()) {
                    val coin = coins.getJSONObject(j)
                    val coinBuilder = CoinOuterClass.Coin.newBuilder()
                        .setDenom(coin.getString("denom"))
                        .setAmount(coin.getString("amount"))
                    msgBuilder.addSentFunds(coinBuilder.build())
                }
            }

            val msg = msgBuilder.build()

            // Wrap in Any and add to TxBody
            val messageAny = Any.newBuilder()
                .setTypeUrl("/secret.compute.v1beta1.MsgExecuteContract")
                .setValue(msg.toByteString())
                .build()

            txBodyBuilder.addMessages(messageAny)
            Log.i(TAG, "Added message ${i + 1} to transaction: $contract")
        }

        // 2. Complete TxBody with memo
        txBodyBuilder.memo = memo
        val txBody = txBodyBuilder.build()

        // 3. Create AuthInfo with fee and signature info
        // Completely omit payer and granter fields to match CosmJS protobuf encoding
        val fee = Tx.Fee.newBuilder()
            .setGasLimit(5000000)  // Increased from 200K to 5M to match SecretJS contract execution
            .addAmount(
                CoinOuterClass.Coin.newBuilder()
                    .setDenom("uscrt")
                    .setAmount("100000")
                    .build()
            )
            // Don't set payer or granter at all - let protobuf omit these fields entirely
            .build()

        val signerInfo = Tx.SignerInfo.newBuilder()
            .setPublicKey(
                Any.newBuilder()
                    .setTypeUrl("/cosmos.crypto.secp256k1.PubKey")
                    .setValue(
                        PubKey.newBuilder()
                            .setKey(ByteString.copyFrom(walletKey.pubKey))
                            .build()
                            .toByteString()
                    )
                    .build()
            )
            .setModeInfo(
                Tx.ModeInfo.newBuilder()
                    .setSingle(
                        Tx.ModeInfo.Single.newBuilder()
                            .setMode(SignMode.SIGN_MODE_DIRECT.number)
                            .build()
                    )
                    .build()
            )
            .setSequence(sequence.toLong())
            .build()

        val authInfo = Tx.AuthInfo.newBuilder()
            .addSignerInfos(signerInfo)
            .setFee(fee)
            .build()

        // 4. Create SignDoc and sign it using TransactionSigner
        val signDoc = Tx.SignDoc.newBuilder()
            .setBodyBytes(txBody.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(chainId)
            .setAccountNumber(accountNumber.toLong())
            .build()

        // Use the general purpose signer
        val txBytes = TransactionSigner.signTransaction(signDoc, walletKey)
        Log.i(TAG, "Clean protobuf transaction created, size: ${txBytes.size} bytes")

        // Debug: Log raw transaction bytes in hex for comparison
        val hex = StringBuilder()
        for (b in txBytes) {
            hex.append(String.format("%02x", b))
        }
        Log.i(TAG, "Raw transaction hex: $hex")

        return txBytes
    }

    /**
     * Decode bech32 address to 20-byte binary format (like SecretJS does)
     */
    @Throws(Exception::class)
    private fun decodeBech32Address(bech32Address: String): ByteArray {
        if (!bech32Address.startsWith("secret1")) {
            throw IllegalArgumentException("Invalid secret address: $bech32Address")
        }

        // Extract the data part after "secret1"
        val datapart = bech32Address.substring(7)

        // Decode bech32 data part to binary
        val decoded = bech32Decode(datapart)

        // Cosmos addresses are 20 bytes
        if (decoded.size != 20) {
            throw IllegalArgumentException("Invalid address length: ${decoded.size} (expected 20)")
        }

        return decoded
    }

    /**
     * Simple bech32 decoder implementation
     * Based on the bech32 specification for Cosmos addresses
     */
    @Throws(Exception::class)
    private fun bech32Decode(data: String): ByteArray {
        // Bech32 character set
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        // Convert characters to 5-bit values
        val values = IntArray(data.length)
        for (i in data.indices) {
            val c = data[i]
            val index = charset.indexOf(c)
            if (index < 0) {
                throw IllegalArgumentException("Invalid bech32 character: $c")
            }
            values[i] = index
        }

        // Remove checksum (last 6 characters)
        val dataLength = values.size - 6
        val dataValues = IntArray(dataLength)
        System.arraycopy(values, 0, dataValues, 0, dataLength)

        // Convert from 5-bit to 8-bit groups
        return convertBits(dataValues, 5, 8, false)
            ?: throw Exception("Failed to convert bech32 data")
    }

    /**
     * Convert between different bit group sizes
     */
    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1

        for (value in data) {
            if (value < 0 || (value shr fromBits) != 0) {
                return null
            }
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad) {
            if (bits > 0) {
                ret.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }

        return ret.toByteArray()
    }

    /**
     * Convert bytes to hex string for logging
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val result = StringBuilder()
        for (b in bytes) {
            result.append(String.format("%02x", b))
        }
        return result.toString()
    }

    /**
     * Parse funds string like "1000uscrt" into JSONArray of coins
     */
    @Throws(Exception::class)
    private fun parseCoins(funds: String?): JSONArray {
        // Simple parser for "amount+denom" format
        val coins = JSONArray()

        if (funds.isNullOrBlank()) {
            return coins
        }

        // Extract amount and denom (e.g., "1000uscrt")
        val trimmed = funds.trim()
        val amount = StringBuilder()
        val denom = StringBuilder()

        var inDenom = false
        for (c in trimmed.toCharArray()) {
            if (Character.isDigit(c) && !inDenom) {
                amount.append(c)
            } else {
                inDenom = true
                denom.append(c)
            }
        }

        if (amount.isNotEmpty() && denom.isNotEmpty()) {
            val coin = JSONObject()
            coin.put("amount", amount.toString())
            coin.put("denom", denom.toString())
            coins.put(coin)
        }

        return coins
    }

    /**
     * CRITICAL VALIDATION: Verify wallet key derives to the sender address
     * This prevents signature verification failures due to key/address mismatch
     */
    @Throws(Exception::class)
    private fun validateWalletMatchesSender(sender: String, walletKey: ECKey) {
        try {
            // Use the SAME address derivation method as the app uses
            val walletAddress = WalletCrypto.getAddress(walletKey)

            // Log comparison for debugging
            Log.w(TAG, "=== WALLET/SENDER VALIDATION ===")
            Log.w(TAG, "Provided sender: $sender")
            Log.w(TAG, "Wallet address:  $walletAddress")
            Log.w(TAG, "MATCH: ${walletAddress == sender}")

            if (walletAddress != sender) {
                val errorMsg = """
                    SIGNATURE WILL FAIL: Wallet key doesn't match sender address!
                    Provided sender: $sender
                    Wallet derives to: $walletAddress
                    Solution: Use the correct sender address that matches your wallet
                """.trimIndent()

                Log.e(TAG, errorMsg)
                throw Exception(errorMsg)
            }

            Log.i(TAG, "✅ Wallet validation passed - addresses match perfectly")

        } catch (e: Exception) {
            if (e.message?.contains("SIGNATURE WILL FAIL") == true) {
                throw e // Re-throw our validation error
            } else {
                throw Exception("Wallet validation failed: ${e.message}")
            }
        }
    }

    /**
     * Callback interface for protobuf transaction execution
     */
    interface ProtobufCallback {
        fun onSuccess(result: JSONObject)
        fun onError(error: String)
    }

    /**
     * Execute multiple transactions sequentially
     * This is a temporary solution until true multi-message protobuf is implemented
     */
    private fun executeMultipleTransactionsSequentially(
        messages: JSONArray,
        currentIndex: Int,
        callback: ProtobufCallback
    ) {
        if (currentIndex >= messages.length()) {
            // All transactions completed successfully
            val result = JSONObject()
            try {
                result.put("success", true)
                result.put("message", "All ${messages.length()} transactions completed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create result JSON", e)
            }
            callback.onSuccess(result)
            return
        }

        try {
            val message = messages.getJSONObject(currentIndex)
            Log.i(TAG, "Executing transaction ${currentIndex + 1}/${messages.length()}")

            // Extract message details
            val sender = message.getString("sender")
            val contract = message.getString("contract")
            val codeHash = message.getString("code_hash")
            val msg = message.getJSONObject("msg")

            // Create a simple transaction executor (this would normally use SecretExecuteActivity)
            executeSingleMessage(sender, contract, codeHash, msg, object : ProtobufCallback {
                override fun onSuccess(result: JSONObject) {
                    Log.i(TAG, "Transaction ${currentIndex + 1} completed successfully")
                    // Continue with next transaction
                    executeMultipleTransactionsSequentially(messages, currentIndex + 1, callback)
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Transaction ${currentIndex + 1} failed: $error")
                    callback.onError("Transaction ${currentIndex + 1} failed: $error")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process message $currentIndex", e)
            callback.onError("Failed to process message ${currentIndex + 1}: ${e.message}")
        }
    }

    /**
     * Execute a single message (placeholder implementation)
     * TODO: Integrate with proper transaction execution
     */
    private fun executeSingleMessage(
        sender: String,
        contract: String,
        codeHash: String,
        msg: JSONObject,
        callback: ProtobufCallback
    ) {
        // For now, just simulate success
        // In a real implementation, this would build and broadcast the transaction
        try {
            Thread.sleep(1000) // Simulate network delay
            val result = JSONObject()
            result.put("success", true)
            result.put("txhash", "simulated_tx_${System.currentTimeMillis()}")
            callback.onSuccess(result)
        } catch (e: Exception) {
            callback.onError("Execution failed: ${e.message}")
        }
    }
}