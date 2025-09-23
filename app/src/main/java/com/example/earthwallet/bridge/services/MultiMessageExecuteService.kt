package network.erth.wallet.bridge.services

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import network.erth.wallet.wallet.utils.SecurePreferencesUtil
import network.erth.wallet.wallet.utils.WalletCrypto
import network.erth.wallet.Constants
import org.bitcoinj.core.ECKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service for multi-message transactions
 * Builds a single transaction with multiple messages instead of sequential transactions
 */
object MultiMessageExecuteService {
    private const val TAG = "MultiMessageExecuteService"
    private const val PREF_FILE = "secret_wallet_prefs"

    @JvmStatic
    @Throws(Exception::class)
    fun execute(context: Context, intent: Intent): Array<String> {

        // Parse intent parameters
        val messagesJson = intent.getStringExtra("messages")
        val memo = intent.getStringExtra("memo")

        if (TextUtils.isEmpty(messagesJson)) {
            throw Exception("No messages provided for multi-message transaction")
        }

        // Parse messages array
        val messages = JSONArray(messagesJson!!)
        if (messages.length() == 0) {
            throw Exception("Empty messages array provided")
        }


        // Initialize services
        WalletCrypto.initialize(context)
        val securePrefs = createSecurePrefs(context)
        // Use static methods from SecretNetworkService Kotlin object
        // Use static methods from SecretCryptoService Kotlin object
        val protobufService = SecretProtobufService()

        // Get wallet information
        val mnemonic = getSelectedMnemonic(securePrefs)
            ?: throw Exception("No wallet mnemonic found")

        val walletKey = WalletCrypto.deriveKeyFromMnemonic(mnemonic)
        val senderAddress = WalletCrypto.getAddress(walletKey)

        // Fetch chain and account information
        val lcdUrl = Constants.DEFAULT_LCD_URL
        val chainId = SecretNetworkService.fetchChainIdSync(lcdUrl)
        val accountData = SecretNetworkService.fetchAccountSync(lcdUrl, senderAddress)
            ?: throw Exception("Account not found: $senderAddress")

        val accountFields = SecretNetworkService.parseAccountFieldsAsArray(accountData)
        val accountNumber = accountFields[0]
        val sequence = accountFields[1]

        // Prepare encrypted messages for all contract executions
        val encryptedMessages = JSONArray()

        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val contract = message.getString("contract")
            val codeHash = message.getString("code_hash")
            val msg = message.getJSONObject("msg")

            // Encrypt each contract message
            val encryptedMsg = SecretCryptoService.encryptContractMessageSync(codeHash, msg.toString(), mnemonic)

            // Create encrypted message object for protobuf service
            val encryptedMessage = JSONObject().apply {
                put("sender", senderAddress)
                put("contract", contract)
                put("code_hash", codeHash)
                put("encrypted_msg", Base64.encodeToString(encryptedMsg, Base64.NO_WRAP))
                put("sent_funds", message.optJSONArray("sent_funds"))
            }

            encryptedMessages.put(encryptedMessage)
        }

        // Build multi-message protobuf transaction using unified SecretProtobufService
        val txBytes = protobufService.buildMultiMessageTransaction(
            encryptedMessages, memo ?: "",
            accountNumber, sequence, chainId, walletKey
        )

        // Broadcast transaction
        val response = SecretNetworkService.broadcastTransactionModernSync(lcdUrl, txBytes)

        // Enhance response with detailed results
        val enhancedResponse = enhanceTransactionResponse(response, lcdUrl)

        // Validate response
        validateTransactionResponse(enhancedResponse)

        return arrayOf(enhancedResponse, senderAddress)
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
                throw Exception("Multi-message transaction failed: Code $code. $rawLog")
            }
        } else {
            throw Exception("Invalid transaction response format")
        }
    }

    private fun getSelectedMnemonic(securePrefs: SharedPreferences): String? {
        return try {
            // Use multi-wallet system (wallets array + selected_wallet_index)
            val walletsJson = securePrefs.getString("wallets", "[]") ?: "[]"
            val arr = JSONArray(walletsJson)
            val selectedIndex = securePrefs.getInt("selected_wallet_index", -1)

            when {
                arr.length() > 0 -> {
                    val index = if (selectedIndex in 0 until arr.length()) {
                        selectedIndex
                    } else {
                        // Default to first wallet if invalid selection
                        0
                    }
                    arr.getJSONObject(index).optString("mnemonic", "").takeIf { it.isNotEmpty() }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mnemonic from multi-wallet system", e)
            null
        }
    }

    @Throws(RuntimeException::class)
    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            SecurePreferencesUtil.createEncryptedPreferences(context, PREF_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create secure preferences", e)
            throw RuntimeException("Secure preferences initialization failed", e)
        }
    }
}