package network.erth.wallet.wallet.services

import android.content.Context
import android.util.Base64
import android.util.Log
import io.eqoty.cosmwasm.std.types.Coin
import io.eqoty.secretk.client.SigningCosmWasmClient
import io.eqoty.secretk.wallet.DirectSigningWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SecretK Client Wrapper
 *
 * Simple interface to interact with Secret Network using SecretK library over LCD
 * Supports both direct queries and SNIP-20 permit-based queries
 * Can be called directly from any page/fragment
 */
object SecretKClient {

    private const val TAG = "SecretKClient"
    private const val LCD_ENDPOINT = "https://lcd.erth.network"
    private const val CHAIN_ID = "secret-4"

    /**
     * Query a contract (public query, no wallet needed)
     *
     * @param contractAddress Contract address to query
     * @param queryMsg Query message as JSON string
     * @param codeHash Optional code hash for faster queries
     * @return Query response as JSON string
     */
    suspend fun queryContract(
        contractAddress: String,
        queryMsg: String,
        codeHash: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val client = SigningCosmWasmClient(
                apiUrl = LCD_ENDPOINT,
                wallet = null,
                chainId = CHAIN_ID
            )

            val response = client.queryContractSmart(
                contractAddress = contractAddress,
                queryMsg = queryMsg,
                contractCodeHash = codeHash
            )

            Log.d(TAG, "Query successful: $contractAddress")
            response
        } catch (e: Throwable) {
            Log.e(TAG, "Query failed for $contractAddress", e)

            // Check if it's a JSON deserialization error with contract error in message
            val errorMsg = e.message ?: ""
            if (errorMsg.contains("Unexpected JSON token") && errorMsg.contains("JSON input:")) {
                // Extract the contract error from the exception message
                // Format: "... JSON input: {"code":12, "message":"...", "details":[]} ..."
                val jsonInputPrefix = "JSON input: "
                val jsonStart = errorMsg.indexOf(jsonInputPrefix)
                if (jsonStart >= 0) {
                    val jsonStartPos = jsonStart + jsonInputPrefix.length
                    val remaining = errorMsg.substring(jsonStartPos)

                    // Find the end of the JSON object (handle nested objects/arrays)
                    var braceCount = 0
                    var jsonEnd = -1
                    for (i in remaining.indices) {
                        when (remaining[i]) {
                            '{' -> braceCount++
                            '}' -> {
                                braceCount--
                                if (braceCount == 0) {
                                    jsonEnd = i
                                    break
                                }
                            }
                        }
                    }

                    if (jsonEnd > 0) {
                        val errorJson = remaining.substring(0, jsonEnd + 1)
                        try {
                            val contractError = JSONObject(errorJson)
                            val code = contractError.optInt("code", -1)
                            val message = contractError.optString("message", "Unknown error")
                            throw Exception("Contract error (code $code): $message")
                        } catch (jsonEx: Exception) {
                            // Couldn't parse, wrap and throw
                            throw Exception("Contract query failed: ${e.message}", e)
                        }
                    }
                }
            }

            // Wrap Error or other Throwable in Exception for consistent error handling
            if (e is Exception) {
                throw e
            } else {
                throw Exception("Contract query failed: ${e.message}", e)
            }
        }
    }

    /**
     * Execute a contract transaction (requires wallet)
     *
     * @param mnemonic Wallet mnemonic
     * @param contractAddress Contract address to execute
     * @param handleMsg Execute message as JSON string
     * @param sentFunds Optional funds to send with transaction
     * @param codeHash Optional code hash
     * @param gasLimit Gas limit for transaction
     * @return Transaction response data
     */
    suspend fun executeContract(
        mnemonic: String,
        contractAddress: String,
        handleMsg: String,
        sentFunds: List<Coin> = emptyList(),
        codeHash: String? = null,
        gasLimit: Int = 200_000
    ): String = withContext(Dispatchers.IO) {
        try {
            val wallet = DirectSigningWallet(mnemonic)
            val client = SigningCosmWasmClient(
                apiUrl = LCD_ENDPOINT,
                wallet = wallet,
                chainId = CHAIN_ID
            )

            val senderAddress = wallet.accounts.first().address

            val msgs = listOf(
                io.eqoty.secretk.types.MsgExecuteContract(
                    sender = senderAddress,
                    contractAddress = contractAddress,
                    msg = handleMsg,
                    sentFunds = sentFunds,
                    codeHash = codeHash
                )
            )

            val response = client.execute(
                msgs = msgs,
                txOptions = io.eqoty.secretk.types.TxOptions(
                    gasLimit = gasLimit
                )
            )

            Log.d(TAG, "Execute successful: $contractAddress")
            response.data.firstOrNull() ?: "{}"
        } catch (e: Throwable) {
            Log.e(TAG, "Execute failed for $contractAddress", e)
            if (e is Exception) {
                throw e
            } else {
                throw Exception("Contract execution failed: ${e.message}", e)
            }
        }
    }

    /**
     * Execute multiple contract messages in a single transaction
     *
     * @param mnemonic Wallet mnemonic
     * @param messages List of contract execution messages
     * @param memo Optional transaction memo
     * @param gasLimit Gas limit for transaction (default: 300_000 for multiple messages)
     * @return Transaction response JSON
     */
    data class ContractMessage(
        val contractAddress: String,
        val handleMsg: String,
        val sentFunds: List<Coin> = emptyList(),
        val codeHash: String? = null
    )

    suspend fun executeMultipleContracts(
        mnemonic: String,
        messages: List<ContractMessage>,
        memo: String = "",
        gasLimit: Int = 300_000
    ): String = withContext(Dispatchers.IO) {
        try {
            if (messages.isEmpty()) {
                throw Exception("No messages provided for multi-contract execution")
            }

            val wallet = DirectSigningWallet(mnemonic)
            val client = SigningCosmWasmClient(
                apiUrl = LCD_ENDPOINT,
                wallet = wallet,
                chainId = CHAIN_ID
            )

            val senderAddress = wallet.accounts.first().address

            // Build multiple MsgExecuteContract messages
            val msgs = messages.map { message ->
                io.eqoty.secretk.types.MsgExecuteContract(
                    sender = senderAddress,
                    contractAddress = message.contractAddress,
                    msg = message.handleMsg,
                    sentFunds = message.sentFunds,
                    codeHash = message.codeHash
                )
            }

            val response = client.execute(
                msgs = msgs,
                txOptions = io.eqoty.secretk.types.TxOptions(
                    gasLimit = gasLimit,
                    memo = memo
                )
            )

            Log.d(TAG, "Multi-contract execute successful: ${messages.size} messages")

            // Return all response data as JSON array
            val dataArray = org.json.JSONArray()
            response.data.forEach { dataArray.put(it) }
            dataArray.toString()
        } catch (e: Throwable) {
            Log.e(TAG, "Multi-contract execute failed", e)
            if (e is Exception) {
                throw e
            } else {
                throw Exception("Multi-contract execution failed: ${e.message}", e)
            }
        }
    }

    /**
     * Send native tokens (requires wallet)
     *
     * @param mnemonic Wallet mnemonic
     * @param toAddress Recipient address
     * @param amount Amount to send
     * @param denom Token denomination (default: uscrt)
     * @param gasLimit Gas limit for transaction
     * @return Transaction hash
     */
    suspend fun sendTokens(
        mnemonic: String,
        toAddress: String,
        amount: Long,
        denom: String = "uscrt",
        gasLimit: Int = 50_000
    ): String = withContext(Dispatchers.IO) {
        try {
            val wallet = DirectSigningWallet(mnemonic)
            val client = SigningCosmWasmClient(
                apiUrl = LCD_ENDPOINT,
                wallet = wallet,
                chainId = CHAIN_ID
            )

            val senderAddress = wallet.accounts.first().address

            val msgs = listOf(
                io.eqoty.secretk.types.MsgSend(
                    fromAddress = senderAddress,
                    toAddress = toAddress,
                    amount = listOf(Coin(amount.toString(), denom))
                )
            )

            val response = client.execute(
                msgs = msgs,
                txOptions = io.eqoty.secretk.types.TxOptions(
                    gasLimit = gasLimit
                )
            )

            Log.d(TAG, "Send successful: $amount$denom to $toAddress")
            // TODO: Return actual transaction hash when we figure out the correct field
            "success"
        } catch (e: Throwable) {
            Log.e(TAG, "Send failed", e)
            if (e is Exception) {
                throw e
            } else {
                throw Exception("Token send failed: ${e.message}", e)
            }
        }
    }

    /**
     * Get account balance
     *
     * @param address Address to check balance
     * @return Balance response as string
     */
    suspend fun getBalance(
        address: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val client = SigningCosmWasmClient(
                apiUrl = LCD_ENDPOINT,
                wallet = null,
                chainId = CHAIN_ID
            )

            val response = client.getBalance(address)
            Log.d(TAG, "Balance query successful for $address")
            response.toString()
        } catch (e: Throwable) {
            Log.e(TAG, "Balance query failed", e)
            if (e is Exception) {
                throw e
            } else {
                throw Exception("Balance query failed: ${e.message}", e)
            }
        }
    }

    /**
     * Query a contract with JSONObject parameters
     *
     * @param contractAddress Contract address to query
     * @param queryJson Query message as JSONObject
     * @param codeHash Optional code hash for faster queries
     * @return Query response as JSONObject
     */
    suspend fun queryContractJson(
        contractAddress: String,
        queryJson: JSONObject,
        codeHash: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            val response = queryContract(contractAddress, queryJson.toString(), codeHash)

            // Always wrap response in "data" field for consistent processing
            val wrapper = JSONObject()
            val trimmedResponse = response.trim()
            if (trimmedResponse.startsWith("[")) {
                // Response is a JSONArray
                wrapper.put("data", JSONArray(response))
            } else {
                // Response is a JSONObject
                wrapper.put("data", JSONObject(response))
            }
            wrapper
        } catch (e: Exception) {
            Log.e(TAG, "Contract query failed", e)
            // Just rethrow to avoid double wrapping
            throw e
        }
    }

    /**
     * Query SNIP-20 token balance using SNIP-24 permit
     *
     * @param context Android context for permit access
     * @param tokenSymbol Token symbol (e.g., "ERTH", "ANML", "sSCRT")
     * @param walletAddress Wallet address to query balance for
     * @return Balance response as JSONObject with format: {"balance": {"amount": "123456"}}
     */
    suspend fun querySnipBalanceWithPermit(
        context: Context,
        tokenSymbol: String,
        walletAddress: String
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            // Get token info
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                ?: throw Exception("Unknown token: $tokenSymbol")

            // Get permit from manager
            val permitManager = PermitManager.getInstance(context)
            val permitJson = permitManager.getPermit(walletAddress, tokenInfo.contract)
                ?: throw Exception("No permit found for $tokenSymbol")

            // Create query using template string exactly like SecretK example
            val queryWithPermit = """
            {
              "with_permit": {
                "permit": $permitJson,
                "query": { "balance": {} }
              }
            }
            """.trimIndent()

            // Execute query
            val response = queryContract(tokenInfo.contract, queryWithPermit, tokenInfo.hash)
            Log.d(TAG, "SNIP-20 balance query successful for $tokenSymbol")
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "SNIP-20 balance query failed for $tokenSymbol", e)
            throw Exception("Balance query failed: ${e.message}", e)
        }
    }

    /**
     * Query SNIP-20 token info (name, symbol, decimals)
     *
     * @param tokenSymbol Token symbol (e.g., "ERTH", "ANML", "sSCRT")
     * @return Token info response as JSONObject
     */
    suspend fun querySnipTokenInfo(
        tokenSymbol: String
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            // Get token info
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                ?: throw Exception("Unknown token: $tokenSymbol")

            // Create token_info query
            val query = JSONObject().apply {
                put("token_info", JSONObject())
            }

            // Execute query
            val response = queryContract(tokenInfo.contract, query.toString(), tokenInfo.hash)
            Log.d(TAG, "SNIP-20 token_info query successful for $tokenSymbol")
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "SNIP-20 token_info query failed for $tokenSymbol", e)
            throw Exception("Token info query failed: ${e.message}", e)
        }
    }

    /**
     * Query contract by token symbol with custom query
     * Helper method that looks up contract address and code hash from token registry
     *
     * @param tokenSymbol Token symbol to look up contract info
     * @param queryJson Query message as JSONObject
     * @return Query response as JSONObject
     */
    suspend fun queryContractBySymbol(
        tokenSymbol: String,
        queryJson: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            // Get token info
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                ?: throw Exception("Unknown token: $tokenSymbol")

            // Execute query
            val response = queryContract(tokenInfo.contract, queryJson.toString(), tokenInfo.hash)
            Log.d(TAG, "Contract query successful for $tokenSymbol")
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "Contract query failed for $tokenSymbol", e)
            throw Exception("Contract query failed: ${e.message}", e)
        }
    }
}
