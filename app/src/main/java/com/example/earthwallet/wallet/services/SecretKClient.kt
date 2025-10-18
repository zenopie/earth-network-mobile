package network.erth.wallet.wallet.services

import android.util.Log
import io.eqoty.cosmwasm.std.types.Coin
import io.eqoty.secretk.client.SigningCosmWasmClient
import io.eqoty.secretk.wallet.DirectSigningWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SecretK Client Wrapper
 *
 * Simple interface to interact with Secret Network using SecretK library over LCD
 * Can be called directly from any page/fragment
 */
object SecretKClient {

    private const val TAG = "SecretKClient"
    private const val LCD_ENDPOINT = "https://lcd.erth.network"

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
            val client = SigningCosmWasmClient.init(
                apiUrl = LCD_ENDPOINT,
                wallet = null
            )

            val response = client.queryContractSmart(
                contractAddress = contractAddress,
                queryMsg = queryMsg,
                contractCodeHash = codeHash
            )

            Log.d(TAG, "Query successful: $contractAddress")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Query failed for $contractAddress", e)
            throw Exception("Contract query failed: ${e.message}", e)
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
            val client = SigningCosmWasmClient.init(
                apiUrl = LCD_ENDPOINT,
                wallet = wallet
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
        } catch (e: Exception) {
            Log.e(TAG, "Execute failed for $contractAddress", e)
            throw Exception("Contract execution failed: ${e.message}", e)
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
            val client = SigningCosmWasmClient.init(
                apiUrl = LCD_ENDPOINT,
                wallet = wallet
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
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            throw Exception("Token send failed: ${e.message}", e)
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
            val client = SigningCosmWasmClient.init(
                apiUrl = LCD_ENDPOINT,
                wallet = null
            )

            val response = client.getBalance(address)
            Log.d(TAG, "Balance query successful for $address")
            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Balance query failed", e)
            throw Exception("Balance query failed: ${e.message}", e)
        }
    }
}
