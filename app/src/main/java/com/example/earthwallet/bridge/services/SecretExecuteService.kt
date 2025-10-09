package network.erth.wallet.bridge.services

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import network.erth.wallet.wallet.utils.SecurePreferencesUtil
import network.erth.wallet.wallet.utils.WalletCrypto
import network.erth.wallet.Constants
import org.bitcoinj.core.ECKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service for executing Secret Network contract transactions
 * Extracted from SecretExecuteActivity to work with TransactionActivity
 */
object SecretExecuteService {
    private const val TAG = "SecretExecuteService"
    private const val PREF_FILE = "secret_wallet_prefs"

    @JvmStatic
    @Throws(Exception::class)
    fun execute(context: Context, intent: Intent): Array<String> {
        // Parse intent parameters
        val params = parseIntentParameters(intent)
        if (!validateParameters(params)) {
            throw Exception("Invalid transaction parameters")
        }

        // Initialize services
        WalletCrypto.initialize(context)
        val securePrefs = createSecurePrefs(context)
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

        // Validate required parameters
        val codeHash = params.codeHash ?: throw Exception("Code hash is required")
        val execJson = params.execJson ?: throw Exception("Execution JSON is required")
        val contractAddr = params.contractAddr ?: throw Exception("Contract address is required")

        // Encrypt contract message
        val encryptedMessage = SecretCryptoService.encryptContractMessageSync(
            codeHash, execJson, mnemonic
        )

        // Build protobuf transaction
        val feeGranter = intent.getStringExtra("fee_granter")
        Log.d(TAG, "SecretExecuteService: feeGranter from intent = $feeGranter")
        val txBytes = protobufService.buildTransaction(
            senderAddress, contractAddr, codeHash, encryptedMessage,
            params.getFundsOrEmpty(), params.getMemoOrEmpty(), accountNumber,
            sequence, chainId, walletKey, feeGranter
        )

        // Broadcast transaction
        val response = SecretNetworkService.broadcastTransactionModernSync(lcdUrl, txBytes)

        // Enhance response with detailed results
        val enhancedResponse = enhanceTransactionResponse(response, lcdUrl)

        // Validate response
        validateTransactionResponse(enhancedResponse)

        return arrayOf(enhancedResponse, senderAddress)
    }

    private fun parseIntentParameters(intent: Intent): TransactionParams {
        return TransactionParams(
            contractAddr = intent.getStringExtra("contract_address"),
            codeHash = intent.getStringExtra("code_hash"),
            execJson = intent.getStringExtra("execute_json"),
            funds = intent.getStringExtra("funds"),
            memo = intent.getStringExtra("memo"),
            contractPubKeyB64 = intent.getStringExtra("contract_encryption_key_b64")
        )
    }

    private fun validateParameters(params: TransactionParams): Boolean {
        return !TextUtils.isEmpty(params.contractAddr) && !TextUtils.isEmpty(params.execJson)
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
                throw Exception("Transaction failed: Code $code. $rawLog")
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

    private data class TransactionParams(
        val contractAddr: String?,
        val codeHash: String?,
        val execJson: String?,
        val funds: String?,
        val memo: String?,
        val contractPubKeyB64: String?
    ) {
        // Provide default values for nullable fields when accessed
        fun getFundsOrEmpty(): String = funds ?: ""
        fun getMemoOrEmpty(): String = memo ?: ""
    }
}