package network.erth.wallet.bridge.services

import android.content.Context
import android.content.SharedPreferences
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
        private const val PREFS_NAME = "contract_registry_prefs"
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val KEY_REGISTRY_DATA = "registry_data"

        @Volatile
        private var instance: RegistryService? = null

        fun getInstance(context: Context): RegistryService {
            return instance ?: synchronized(this) {
                instance ?: RegistryService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Query the contract registry and populate all contract and token addresses
     * This should be called on app startup
     */
    suspend fun initializeRegistry(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Querying contract registry...")

            // Query the registry contract using SecretK
            val queryMsg = """{"get_all_contracts": {}}"""

            val responseString = SecretKClient.queryContract(
                contractAddress = Constants.REGISTRY_CONTRACT,
                queryMsg = queryMsg,
                codeHash = Constants.REGISTRY_HASH
            )

            val response = JSONObject(responseString)

            if (response.has("contracts")) {
                val contracts = response.getJSONArray("contracts")

                // Parse and categorize the registry data
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

                    // Categorize as token or contract based on name
                    if (name.contains("_token")) {
                        val tokenName = name.replace("_token", "").uppercase()
                        tokensData[tokenName] = contractInfo
                    } else {
                        contractsData[name] = contractInfo
                    }
                }

                // Populate the contracts
                populateContracts(contractsData)

                // Populate the tokens
                populateTokens(tokensData)

                // Save to SharedPreferences for persistence
                saveRegistryData(contractsData, tokensData)

                Log.d(TAG, "Registry data loaded successfully")
                Log.d(TAG, "Contracts: ${contractsData.keys}")
                Log.d(TAG, "Tokens: ${tokensData.keys}")

                return@withContext true
            } else {
                Log.e(TAG, "No contracts field in registry response")

                // Try to load from cache
                return@withContext loadFromCache()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying registry", e)

            // Try to load from cache on error
            return@withContext loadFromCache()
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
     * Save registry data to SharedPreferences for caching
     */
    private fun saveRegistryData(contractsData: Map<String, ContractInfo>, tokensData: Map<String, ContractInfo>) {
        val data = JSONObject().apply {
            put("contracts", JSONObject().apply {
                contractsData.forEach { (name, info) ->
                    put(name, JSONObject().apply {
                        put("contract", info.contract)
                        put("hash", info.hash)
                    })
                }
            })
            put("tokens", JSONObject().apply {
                tokensData.forEach { (name, info) ->
                    put(name, JSONObject().apply {
                        put("contract", info.contract)
                        put("hash", info.hash)
                    })
                }
            })
        }

        prefs.edit()
            .putString(KEY_REGISTRY_DATA, data.toString())
            .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            .apply()
    }

    /**
     * Load registry data from cache
     */
    private fun loadFromCache(): Boolean {
        return try {
            val cachedData = prefs.getString(KEY_REGISTRY_DATA, null)
            if (cachedData != null) {
                val data = JSONObject(cachedData)

                // Parse contracts
                val contractsData = mutableMapOf<String, ContractInfo>()
                if (data.has("contracts")) {
                    val contracts = data.getJSONObject("contracts")
                    contracts.keys().forEach { name ->
                        val info = contracts.getJSONObject(name)
                        contractsData[name] = ContractInfo(
                            contract = info.getString("contract"),
                            hash = info.getString("hash")
                        )
                    }
                }

                // Parse tokens
                val tokensData = mutableMapOf<String, ContractInfo>()
                if (data.has("tokens")) {
                    val tokens = data.getJSONObject("tokens")
                    tokens.keys().forEach { name ->
                        val info = tokens.getJSONObject(name)
                        tokensData[name] = ContractInfo(
                            contract = info.getString("contract"),
                            hash = info.getString("hash")
                        )
                    }
                }

                // Populate from cache
                populateContracts(contractsData)
                populateTokens(tokensData)

                val lastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0)
                Log.d(TAG, "Loaded registry data from cache (last updated: $lastUpdated)")

                true
            } else {
                Log.w(TAG, "No cached registry data available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from cache", e)
            false
        }
    }

    /**
     * Contract information data class
     */
    data class ContractInfo(
        val contract: String,
        val hash: String
    )
}
