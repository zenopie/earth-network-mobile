package network.erth.wallet.bridge.services

import android.content.Context
import android.util.Log
import network.erth.wallet.Constants
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecretKClient
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service to query the contract registry and populate contract and token addresses
 * This ensures the app always uses the latest contract addresses from the registry
 */
class RegistryService(private val context: Context) {

    companion object {
        private const val TAG = "RegistryService"

        @Volatile
        private var instance: RegistryService? = null

        fun getInstance(context: Context): RegistryService {
            return instance ?: synchronized(this) {
                instance ?: RegistryService(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Query the contract registry and populate all contract and token addresses
     * This should be called on app startup
     */
    suspend fun initializeRegistry(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Querying contract registry...")

            val queryMsg = """{"get_all_contracts": {}}"""

            val responseString = SecretKClient.queryContract(
                contractAddress = Constants.REGISTRY_CONTRACT,
                queryMsg = queryMsg,
                codeHash = Constants.REGISTRY_HASH
            )

            val response = JSONObject(responseString)

            if (response.has("contracts")) {
                val contracts = response.getJSONArray("contracts")

                val contractsData = mutableMapOf<String, ContractInfo>()
                val tokensData = mutableMapOf<String, ContractInfo>()

                for (i in 0 until contracts.length()) {
                    val item = contracts.getJSONObject(i)
                    val name = item.getString("name")
                    val info = item.getJSONObject("info")

                    val contractInfo = ContractInfo(
                        contract = info.getString("address"),
                        hash = info.getString("code_hash")
                    )

                    if (name.contains("_token")) {
                        val tokenName = name.replace("_token", "").uppercase()
                        tokensData[tokenName] = contractInfo
                    } else {
                        contractsData[name] = contractInfo
                    }
                }

                populateContracts(contractsData)
                populateTokens(tokensData)

                Log.d(TAG, "Registry data loaded successfully")
                Log.d(TAG, "Contracts: ${contractsData.keys}")
                Log.d(TAG, "Tokens: ${tokensData.keys}")

                return@withContext true
            } else {
                Log.e(TAG, "No contracts field in registry response")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying registry", e)
            return@withContext false
        }
    }

    /**
     * Populate contract addresses from registry data
     */
    private fun populateContracts(contractsData: Map<String, ContractInfo>) {
        contractsData["registration"]?.let {
            Constants.REGISTRATION_CONTRACT = it.contract
            Constants.REGISTRATION_HASH = it.hash
        }

        contractsData["exchange"]?.let {
            Constants.EXCHANGE_CONTRACT = it.contract
            Constants.EXCHANGE_HASH = it.hash
        }

        contractsData["staking"]?.let {
            Constants.STAKING_CONTRACT = it.contract
            Constants.STAKING_HASH = it.hash
        }

        contractsData["airdrop"]?.let {
            Constants.AIRDROP_CONTRACT = it.contract
            Constants.AIRDROP_HASH = it.hash
        }

        Log.d(TAG, "Contracts populated")
    }

    /**
     * Populate token addresses from registry data
     */
    private fun populateTokens(tokensData: Map<String, ContractInfo>) {
        tokensData["ERTH"]?.let {
            Tokens.ERTH.contract = it.contract
            Tokens.ERTH.hash = it.hash
        }

        tokensData["ANML"]?.let {
            Tokens.ANML.contract = it.contract
            Tokens.ANML.hash = it.hash
        }

        tokensData["SSCRT"]?.let {
            Tokens.SSCRT.contract = it.contract
            Tokens.SSCRT.hash = it.hash
        }

        Log.d(TAG, "Tokens populated: ERTH=${Tokens.ERTH.contract}, ANML=${Tokens.ANML.contract}, SSCRT=${Tokens.SSCRT.contract}")
    }

    /**
     * Contract information data class
     */
    data class ContractInfo(
        val contract: String,
        val hash: String
    )
}
