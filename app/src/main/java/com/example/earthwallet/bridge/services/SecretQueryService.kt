package network.erth.wallet.bridge.services

import android.content.Context
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import network.erth.wallet.wallet.services.SecureWalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * SecretQueryService
 *
 * Native implementation of Secret Network contract queries without webview dependency.
 * Provides the same functionality as SecretQueryActivity but using pure Java/Android HTTP and crypto.
 *
 * This service replicates the SecretJS query flow:
 * 1. Fetch contract code hash (if not provided)
 * 2. Encrypt query using SecretJS-compatible encryption
 * 3. Send encrypted query to Secret Network LCD endpoint
 * 4. Decrypt and return response
 */
class SecretQueryService(private val context: Context) {

    companion object {
        private const val TAG = "SecretQueryService"
        private const val DEFAULT_LCD_URL = "https://lcd.erth.network"
    }

    /**
     * Query a Secret Network contract natively (no webview)
     * Uses just-in-time mnemonic fetching with automatic cleanup for enhanced security.
     *
     * @param contractAddress Contract address
     * @param codeHash Contract code hash (optional, will be fetched if null)
     * @param queryJson Query JSON object
     * @return Query result JSON object
     */
    @Throws(Exception::class)
    fun queryContract(contractAddress: String, codeHash: String?, queryJson: JSONObject): JSONObject {
        val lcdUrl = DEFAULT_LCD_URL

        // Step 1: Get code hash if not provided (matches SecretJS ComputeQuerier.queryContract line 176)
        val finalCodeHash = if (TextUtils.isEmpty(codeHash)) {
            fetchContractCodeHash(lcdUrl, contractAddress)
        } else {
            codeHash!!
        }

        // Clean code hash (remove 0x prefix, lowercase)
        val cleanCodeHash = finalCodeHash.replace("0x", "").lowercase()

        // Use SecureWalletManager for just-in-time mnemonic access with automatic cleanup
        return SecureWalletManager.executeWithMnemonic(context) { mnemonic ->

            // Step 2: Encrypt query message (matches SecretJS ComputeQuerier.queryContract line 182)
            val encryptedQuery = SecretCryptoService.encryptContractMessageSync(cleanCodeHash, queryJson.toString(), mnemonic)

            // Extract nonce for decryption (first 32 bytes of encrypted message)
            val nonce = encryptedQuery.sliceArray(0..31)

            // Step 3: Send encrypted query to Secret Network (matches SecretJS Query.QuerySecretContract)
            val encryptedResult = sendEncryptedQuery(lcdUrl, contractAddress, encryptedQuery)

            // Step 4: Decrypt response (matches SecretJS ComputeQuerier.queryContract line 197)
            val result = decryptQueryResult(encryptedResult, nonce, mnemonic)

            result
        }
    }

    /**
     * Fetch contract code hash from the network
     */
    @Throws(Exception::class)
    private fun fetchContractCodeHash(lcdUrl: String, contractAddress: String): String {
        val url = joinUrl(lcdUrl, "/compute/v1beta1/code_hash/by_contract_address/$contractAddress")

        val response = httpGet(url)
        val obj = JSONObject(response)

        if (!obj.has("code_hash")) {
            throw Exception("No code_hash in response: $response")
        }

        val codeHash = obj.getString("code_hash")
        return codeHash
    }

    /**
     * Send encrypted query to Secret Network compute endpoint
     * Uses GET method with query parameter like SecretJS does
     */
    @Throws(Exception::class)
    private fun sendEncryptedQuery(lcdUrl: String, contractAddress: String, encryptedQuery: ByteArray): String {
        // SecretJS uses GET method with query parameter (matches grpc_gateway/secret/compute/v1beta1/query.pb.ts:105)
        val encryptedQueryBase64 = Base64.encodeToString(encryptedQuery, Base64.NO_WRAP)
        val url = joinUrl(lcdUrl, "/compute/v1beta1/query/$contractAddress?query=${URLEncoder.encode(encryptedQueryBase64, "UTF-8")}")

        val response = httpGet(url)

        // Parse response to extract encrypted data field
        val responseObj = JSONObject(response)
        if (!responseObj.has("data")) {
            throw Exception("No data field in query response: $response")
        }

        return responseObj.getString("data")
    }

    /**
     * Decrypt query result using the same nonce from encryption
     * Matches SecretJS flow exactly: JSON.parse(fromUtf8(fromBase64(fromUtf8(decryptedBase64Result))))
     */
    @Throws(Exception::class)
    private fun decryptQueryResult(encryptedResultBase64: String, nonce: ByteArray, mnemonic: String): JSONObject {

        // Step 1: Decode base64 encrypted result from network (fromBase64)
        val encryptedResult = Base64.decode(encryptedResultBase64, Base64.NO_WRAP)

        return try {
            // Step 2: Decrypt using SecretCryptoService (matches SecretJS decrypt() method)
            val decryptedBytes = SecretCryptoService.decryptQueryResponseSync(encryptedResult, nonce, mnemonic)

            // Step 3: Handle SecretJS response decoding flow
            // The decryptedBytes should contain the actual JSON response, but let's check both cases:

            // Case 1: Direct JSON (most common)
            val directJson = String(decryptedBytes, StandardCharsets.UTF_8)

            try {
                // Handle empty results (Secret Network can return empty bytes)
                if (directJson.trim().isEmpty()) {
                    return JSONObject("{}")
                }

                // Check if it's a JSON array and wrap it in an object with "data" key
                if (directJson.trim().startsWith("[")) {
                    val wrapper = JSONObject()
                    wrapper.put("data", JSONArray(directJson))
                    return wrapper
                }

                JSONObject(directJson)
            } catch (e1: Exception) {

                // Case 2: Base64-encoded JSON (matches complex SecretJS flow)
                try {
                    val finalJsonBytes = Base64.decode(directJson, Base64.NO_WRAP)
                    val finalJson = String(finalJsonBytes, StandardCharsets.UTF_8)

                    if (finalJson.trim().isEmpty()) {
                        return JSONObject("{}")
                    }

                    // Check if it's a JSON array and wrap it in an object with "data" key
                    if (finalJson.trim().startsWith("[")) {
                        val wrapper = JSONObject()
                        wrapper.put("data", JSONArray(finalJson))
                        return wrapper
                    }

                    JSONObject(finalJson)
                } catch (e2: Exception) {
                    Log.e(TAG, "Both direct and base64-decoded JSON parsing failed")
                    throw Exception("Failed to parse decrypted response as JSON: direct=${e1.message}, base64=${e2.message}")
                }
            }

        } catch (e: Exception) {

            // Check if this is an encrypted error message (matches SecretJS error handling)
            val errorMessage = e.message
            if (errorMessage != null && errorMessage.contains("encrypted:")) {
                // TODO: Implement encrypted error decryption like SecretJS does
                // For now, return the raw error
                val errorResult = JSONObject()
                errorResult.put("error", "Encrypted error (decryption not implemented)")
                errorResult.put("raw_error", errorMessage)
                return errorResult
            }

            // Fallback: try to parse as plaintext JSON (some endpoints might return unencrypted data)
            try {
                val resultString = String(encryptedResult, StandardCharsets.UTF_8)
                JSONObject(resultString)
            } catch (e2: Exception) {
                Log.e(TAG, "All decryption attempts failed", e2)

                // Return detailed error information
                val errorResult = JSONObject()
                errorResult.put("error", "Failed to decrypt or parse response")
                errorResult.put("decryption_error", e.message)
                errorResult.put("plaintext_error", e2.message)
                errorResult.put("raw_data", encryptedResultBase64)
                errorResult
            }
        }
    }

    // HTTP utility methods

    @Throws(Exception::class)
    private fun httpGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "SecretQuery/1.0")

        val responseCode = conn.responseCode

        val inputStream = if (responseCode in 200..299) {
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

        return response
    }

    @Throws(Exception::class)
    private fun httpPostJson(urlStr: String, jsonBody: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "SecretQuery/1.0")
        conn.doOutput = true

        // Send request body
        conn.outputStream.use { os ->
            val input = jsonBody.toByteArray(StandardCharsets.UTF_8)
            os.write(input, 0, input.size)
        }

        val responseCode = conn.responseCode

        val inputStream = if (responseCode in 200..299) {
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

        return response
    }

    private fun joinUrl(base: String, path: String): String {
        return when {
            base.endsWith("/") && path.startsWith("/") -> base + path.substring(1)
            !base.endsWith("/") && !path.startsWith("/") -> "$base/$path"
            else -> base + path
        }
    }
}