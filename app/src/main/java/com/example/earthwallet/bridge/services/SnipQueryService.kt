package network.erth.wallet.bridge.services

import android.content.Context
import android.text.TextUtils
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.bridge.utils.PermitManager
import org.json.JSONObject

/**
 * SnipQueryService
 *
 * Service wrapper for SNIP-20/SNIP-721 token queries that handles:
 * - SNIP-20 balance queries with viewing keys
 * - SNIP-20 token info queries (name, symbol, decimals)
 * - SNIP-721 balance and token queries
 * - Generic SNIP contract queries
 *
 * This service abstracts the common SNIP query patterns and provides a direct
 * synchronous interface for SNIP contract interactions without Activity overhead.
 */
object SnipQueryService {

    // Query types
    const val QUERY_TYPE_BALANCE = "balance"
    const val QUERY_TYPE_TOKEN_INFO = "token_info"
    const val QUERY_TYPE_GENERIC = "generic"

    private const val TAG = "SnipQueryService"

    /**
     * Query a SNIP contract directly
     *
     * @param context Android context for secure wallet access
     * @param tokenSymbol Token symbol for automatic contract lookup (optional)
     * @param contractAddress Contract address (required if no symbol)
     * @param codeHash Contract code hash (optional, will use token's hash if available)
     * @param queryType Query type: "balance", "token_info", "generic"
     * @param walletAddress Wallet address for balance queries (optional)
     * @param viewingKey Viewing key for SNIP-20 balance queries (optional)
     * @param customQuery Custom JSON query for generic queries (optional)
     * @return Query result JSON object with format: {"success":true,"result":...}
     */
    @JvmStatic
    @Throws(Exception::class)
    fun queryContract(
        context: Context,
        tokenSymbol: String?,
        contractAddress: String?,
        codeHash: String?,
        queryType: String,
        walletAddress: String?,
        viewingKey: String?,
        customQuery: String?
    ): JSONObject {

        // Validate required parameters
        if (TextUtils.isEmpty(queryType)) {
            throw IllegalArgumentException("Query type is required")
        }

        var finalContractAddress = contractAddress
        var finalCodeHash = codeHash

        // Get contract info from token symbol if provided
        if (!TextUtils.isEmpty(tokenSymbol)) {
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol!!)
            tokenInfo?.let {
                finalContractAddress = it.contract
                if (TextUtils.isEmpty(finalCodeHash)) {
                    finalCodeHash = it.hash
                }
            }
        }

        if (TextUtils.isEmpty(finalContractAddress)) {
            throw IllegalArgumentException("Contract address is required (either directly or via token symbol)")
        }

        // Build query JSON based on type
        val queryObj = buildQuery(queryType, walletAddress, viewingKey, customQuery)
            ?: throw IllegalArgumentException("Failed to build query JSON")


        // Execute native query with secure mnemonic handling
        // Use HostActivity context if available for centralized securePrefs access
        val queryContext = if (context is network.erth.wallet.ui.host.HostActivity) {
            context
        } else {
            context
        }

        val queryService = SecretQueryService(queryContext)
        val result = queryService.queryContract(
            finalContractAddress!!,
            finalCodeHash,
            queryObj
        )

        // Format result to match expected format
        val response = JSONObject()
        response.put("success", true)
        response.put("result", result)

        return response
    }

    /**
     * Convenience method for balance queries with viewing key (deprecated)
     * @deprecated Use queryBalanceWithPermit() instead
     */
    @Deprecated("Use queryBalanceWithPermit() instead")
    @JvmStatic
    @Throws(Exception::class)
    fun queryBalance(context: Context, tokenSymbol: String, walletAddress: String, viewingKey: String): JSONObject {
        return queryContract(context, tokenSymbol, null, null, QUERY_TYPE_BALANCE, walletAddress, viewingKey, null)
    }

    /**
     * Query balance using SNIP-24 permit - CORRECTED structure from SecretJS analysis
     */
    @JvmStatic
    @Throws(Exception::class)
    fun queryBalanceWithPermit(context: Context, tokenSymbol: String, walletAddress: String): JSONObject {

        // Get permit manager and check for valid permit
        val permitManager = PermitManager.getInstance(context)

        // Get contract info from token symbol
        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
            ?: throw Exception("Unknown token: $tokenSymbol")

        val contractAddress = tokenInfo.contract
        val codeHash = tokenInfo.hash

        // Get valid permit for balance queries
        val permit = permitManager.getValidPermitForQuery(walletAddress, contractAddress, "balance")
            ?: throw Exception("No valid permit found for $tokenSymbol")

        // Create the CORRECT permit query structure per SNIP-24 spec
        val innerQuery = JSONObject()
        val balanceQuery = JSONObject()
        // NOTE: Address is NOT included in permit queries - it's derived from permit signature
        innerQuery.put("balance", balanceQuery)

        val withPermitQuery = permitManager.createWithPermitQuery(innerQuery, permit, "secret-4")

        // Execute the query
        val queryService = SecretQueryService(context)
        val result = queryService.queryContract(contractAddress, codeHash, withPermitQuery)

        // Format result
        val response = JSONObject()
        response.put("success", true)
        response.put("result", result)

        return response
    }

    /**
     * Convenience method for token info queries
     */
    @JvmStatic
    @Throws(Exception::class)
    fun queryTokenInfo(context: Context, tokenSymbol: String): JSONObject {
        return queryContract(context, tokenSymbol, null, null, QUERY_TYPE_TOKEN_INFO, null, null, null)
    }

    @Throws(Exception::class)
    private fun buildQuery(queryType: String, walletAddress: String?, viewingKey: String?, customQuery: String?): JSONObject? {
        val query = JSONObject()

        when (queryType) {
            QUERY_TYPE_BALANCE -> {
                if (TextUtils.isEmpty(walletAddress) || TextUtils.isEmpty(viewingKey)) {
                    throw IllegalArgumentException("Balance queries require wallet address and viewing key")
                }
                val balanceQuery = JSONObject()
                balanceQuery.put("address", walletAddress)
                balanceQuery.put("key", viewingKey)
                balanceQuery.put("time", System.currentTimeMillis())
                query.put("balance", balanceQuery)
            }

            QUERY_TYPE_TOKEN_INFO -> {
                query.put("token_info", JSONObject())
            }

            QUERY_TYPE_GENERIC -> {
                if (TextUtils.isEmpty(customQuery)) {
                    throw IllegalArgumentException("Generic queries require custom query JSON")
                }
                return JSONObject(customQuery!!)
            }

            else -> {
                throw IllegalArgumentException("Unknown query type: $queryType")
            }
        }

        return query
    }
}