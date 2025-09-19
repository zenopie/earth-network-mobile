package com.example.earthwallet.bridge.services

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * SecretNetworkService
 *
 * Handles all Secret Network LCD API interactions with Kotlin coroutines:
 * - Chain information queries
 * - Account data fetching
 * - Transaction broadcasting
 * - Transaction status queries
 */
object SecretNetworkService {

    private const val TAG = "SecretNetworkService"

    // Transaction query constants
    private const val TRANSACTION_QUERY_DELAY_MS = 5000L // 5 seconds initial delay
    private const val TRANSACTION_QUERY_TIMEOUT_MS = 60000L // 60 seconds total timeout
    private const val TRANSACTION_QUERY_MAX_RETRIES = 5 // Maximum retry attempts
    private const val TRANSACTION_QUERY_RETRY_DELAY_MS = 3000L // 3 seconds between retries

    /**
     * Fetches the chain ID from the LCD endpoint
     */
    suspend fun fetchChainId(lcdUrl: String): String = withContext(Dispatchers.IO) {
        val url = joinUrl(lcdUrl, "/cosmos/base/tendermint/v1beta1/node_info")
        val response = httpGet(url)

        val obj = JSONObject(response)
        val defaultNodeInfo = obj.getJSONObject("default_node_info")
        val chainId = defaultNodeInfo.getString("network")

        Log.i(TAG, "Retrieved chain ID: $chainId")
        chainId
    }

    /**
     * Fetches account information from the LCD endpoint
     */
    suspend fun fetchAccount(lcdUrl: String, address: String): JSONObject? = withContext(Dispatchers.IO) {
        val url = joinUrl(lcdUrl, "/cosmos/auth/v1beta1/accounts/$address")

        try {
            val response = httpGet(url)
            val obj = JSONObject(response)

            if (obj.has("account")) {
                val account = obj.getJSONObject("account")
                Log.i(TAG, "Successfully retrieved account data")
                account
            } else {
                Log.w(TAG, "No account field in response")
                null
            }
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) {
                Log.w(TAG, "Account not found (404) - may not exist or be funded")
                null
            } else {
                throw e
            }
        }
    }

    /**
     * Parses account number and sequence from account response
     */
    fun parseAccountFields(account: JSONObject): Pair<String, String> {
        var accountNumber = "0"
        var sequence = "0"

        // Try different account formats
        if (account.has("account_number")) {
            accountNumber = account.getString("account_number")
        }
        if (account.has("sequence")) {
            sequence = account.getString("sequence")
        }

        // Handle base_account nested structure
        if (account.has("base_account")) {
            val baseAccount = account.getJSONObject("base_account")
            if (baseAccount.has("account_number")) {
                accountNumber = baseAccount.getString("account_number")
            }
            if (baseAccount.has("sequence")) {
                sequence = baseAccount.getString("sequence")
            }
        }

        return Pair(accountNumber, sequence)
    }

    /**
     * Fetches encryption key for Secret Network contracts (matches SecretJS behavior)
     * SecretJS uses the consensus IO public key for all contracts on mainnet
     */
    suspend fun fetchContractEncryptionKey(lcdUrl: String, contractAddr: String): String = withContext(Dispatchers.IO) {
        // SecretJS hardcodes the mainnet consensus IO key instead of fetching contract-specific keys
        // This is the exact same key used in SecretJS encryption.ts
        val consensusIoKey = "79++5YOHfm0SwhlpUDClv7cuCjq9xBZlWqSjDJWkRG8="

        Log.i(TAG, "Using hardcoded mainnet consensus IO key (matches SecretJS exactly)")
        consensusIoKey
    }

    /**
     * Broadcasts a transaction using the modern endpoint
     */
    suspend fun broadcastTransactionModern(lcdUrl: String, txBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val modernUrl = joinUrl(lcdUrl, "/cosmos/tx/v1beta1/txs")
        Log.i(TAG, "Broadcasting to modern endpoint: $modernUrl")

        val txBytesBase64 = Base64.encodeToString(txBytes, Base64.NO_WRAP)

        val txBody = JSONObject().apply {
            put("tx_bytes", txBytesBase64)
            put("mode", "BROADCAST_MODE_SYNC")
        }

        httpPostJson(modernUrl, txBody.toString())
    }

    /**
     * Broadcasts a transaction using the legacy endpoint
     */
    suspend fun broadcastTransactionLegacy(lcdUrl: String, signedTx: JSONObject): String = withContext(Dispatchers.IO) {
        val legacyUrl = joinUrl(lcdUrl, "/txs")
        Log.i(TAG, "Broadcasting to legacy endpoint: $legacyUrl")

        val txBody = JSONObject().apply {
            put("tx", signedTx)
            put("mode", "sync")
        }

        httpPostJson(legacyUrl, txBody.toString())
    }

    /**
     * Queries transaction details by hash with retry logic and timeout
     */
    suspend fun queryTransactionByHash(lcdUrl: String, txHash: String): String? {
        Log.i(TAG, "Querying transaction: $txHash")

        return try {
            withTimeout(TRANSACTION_QUERY_TIMEOUT_MS) {
                repeat(TRANSACTION_QUERY_MAX_RETRIES) { retryCount ->
                    try {
                        val queryUrl = joinUrl(lcdUrl, "/cosmos/tx/v1beta1/txs/$txHash")
                        Log.d(TAG, "Query attempt ${retryCount + 1}/$TRANSACTION_QUERY_MAX_RETRIES for txhash: $txHash")

                        val response = httpGet(queryUrl)

                        // Validate response has transaction data
                        val responseObj = JSONObject(response)
                        if (responseObj.has("tx_response")) {
                            val txResponse = responseObj.getJSONObject("tx_response")

                            // Check if we have execution data
                            val rawLog = txResponse.optString("raw_log", "")
                            val logs = txResponse.optJSONArray("logs")
                            val data = txResponse.optString("data", "")
                            val events = txResponse.optJSONArray("events")

                            if (rawLog.isNotEmpty() || (logs?.length() ?: 0) > 0 ||
                                data.isNotEmpty() || (events?.length() ?: 0) > 0) {
                                Log.i(TAG, "Transaction query successful with execution data")
                                return@withTimeout response
                            } else {
                                Log.d(TAG, "Transaction found but no execution data yet")
                            }
                        } else {
                            Log.w(TAG, "Response missing tx_response field")
                        }

                    } catch (e: Exception) {
                        Log.w(TAG, "Query attempt ${retryCount + 1} failed: ${e.message}")

                        if (e.message?.contains("404") == true) {
                            Log.d(TAG, "Transaction not found yet (404) - will retry")
                        }
                    }

                    if (retryCount < TRANSACTION_QUERY_MAX_RETRIES - 1) {
                        Log.d(TAG, "Waiting ${TRANSACTION_QUERY_RETRY_DELAY_MS}ms before retry...")
                        delay(TRANSACTION_QUERY_RETRY_DELAY_MS)
                    }
                }

                Log.w(TAG, "Transaction query failed after $TRANSACTION_QUERY_MAX_RETRIES attempts")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transaction query timed out: ${e.message}")
            null
        }
    }

    // HTTP utility methods

    private suspend fun httpGet(urlStr: String): String = withContext(Dispatchers.IO) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "SecretExecute/1.0")

            val responseCode = conn.responseCode

            val inputStream = if (responseCode >= 200 && responseCode < 300) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val result = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } != -1) {
                result.write(buffer, 0, length)
            }

            val response = result.toString("UTF-8")

            if (responseCode >= 400) {
                throw Exception("HTTP $responseCode: $response")
            }

            response
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun httpPostJson(urlStr: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "SecretExecute/1.0")
            conn.doOutput = true

            // Send request body
            conn.outputStream.use { os ->
                val input = jsonBody.toByteArray(StandardCharsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = conn.responseCode

            val inputStream = if (responseCode >= 200 && responseCode < 300) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val result = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } != -1) {
                result.write(buffer, 0, length)
            }

            val response = result.toString("UTF-8")

            if (responseCode >= 400) {
                throw Exception("HTTP $responseCode: $response")
            }

            response
        } finally {
            conn.disconnect()
        }
    }

    private fun joinUrl(base: String, path: String): String {
        return when {
            base.endsWith("/") && path.startsWith("/") -> base + path.substring(1)
            !base.endsWith("/") && !path.startsWith("/") -> "$base/$path"
            else -> base + path
        }
    }

    // Java compatibility methods - blocking versions for existing Java code
    @JvmStatic
    fun fetchChainIdSync(lcdUrl: String): String {
        return kotlinx.coroutines.runBlocking {
            fetchChainId(lcdUrl)
        }
    }

    @JvmStatic
    fun fetchAccountSync(lcdUrl: String, address: String): JSONObject? {
        return kotlinx.coroutines.runBlocking {
            fetchAccount(lcdUrl, address)
        }
    }

    @JvmStatic
    fun broadcastTransactionModernSync(lcdUrl: String, txBytes: ByteArray): String {
        return kotlinx.coroutines.runBlocking {
            broadcastTransactionModern(lcdUrl, txBytes)
        }
    }

    @JvmStatic
    fun queryTransactionByHashSync(lcdUrl: String, txHash: String): String? {
        return kotlinx.coroutines.runBlocking {
            queryTransactionByHash(lcdUrl, txHash)
        }
    }

    // Java compatibility version of parseAccountFields
    @JvmStatic
    fun parseAccountFieldsAsArray(account: JSONObject): Array<String> {
        val (accountNumber, sequence) = parseAccountFields(account)
        return arrayOf(accountNumber, sequence)
    }
}