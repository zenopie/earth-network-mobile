package network.erth.wallet.ui.pages.managelp

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.bridge.services.SecretQueryService
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecureWalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InfoFragment : Fragment() {

    companion object {
        private const val TAG = "InfoFragment"

        @JvmStatic
        fun newInstance(tokenKey: String): InfoFragment {
            val fragment = InfoFragment()
            val args = Bundle()
            args.putString("token_key", tokenKey)
            fragment.arguments = args
            return fragment
        }
    }

    private var tokenKey: String? = null

    private lateinit var totalSharesText: TextView
    private lateinit var userSharesText: TextView
    private lateinit var poolOwnershipText: TextView
    private lateinit var unbondingPercentText: TextView
    private lateinit var erthValueText: TextView
    private lateinit var tokenValueText: TextView
    private lateinit var tokenValueLabel: TextView

    // Services
    private var queryService: SecretQueryService? = null
    private var executorService: ExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            tokenKey = it.getString("token_key")
        }

        queryService = SecretQueryService(requireContext())
        executorService = Executors.newCachedThreadPool()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_liquidity_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        refreshData()
    }

    private fun initializeViews(view: View) {
        totalSharesText = view.findViewById(R.id.total_shares_text)
        userSharesText = view.findViewById(R.id.user_shares_text)
        poolOwnershipText = view.findViewById(R.id.pool_ownership_text)
        unbondingPercentText = view.findViewById(R.id.unbonding_percent_text)
        erthValueText = view.findViewById(R.id.erth_value_text)
        tokenValueText = view.findViewById(R.id.token_value_text)
        tokenValueLabel = view.findViewById(R.id.token_value_label)

        // Update token label
        tokenKey?.let { token ->
            tokenValueLabel.text = "$token:"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) {
            refreshData()
        }
    }

    fun refreshData() {
        loadPoolData()
    }

    private fun loadPoolData() {
        if (tokenKey == null) return

        executorService?.execute {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress == null) {
                    return@execute
                }

                val tokenContract = getTokenContract(tokenKey!!)
                if (tokenContract == null) {
                    return@execute
                }

                // Query pool data like other fragments do
                val queryMsg = JSONObject()
                val queryUserInfo = JSONObject()
                val poolsArray = JSONArray()
                poolsArray.put(tokenContract)
                queryUserInfo.put("pools", poolsArray)
                queryUserInfo.put("user", userAddress)
                queryMsg.put("query_user_info", queryUserInfo)

                val result = queryService!!.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryMsg
                )

                activity?.runOnUiThread {
                    processPoolData(result)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading pool data", e)
            }
        }
    }

    private fun processPoolData(result: JSONObject) {
        try {
            if (result.has("data")) {
                val dataArray = result.getJSONArray("data")
                if (dataArray.length() > 0) {
                    val poolData = dataArray.getJSONObject(0)

                    var poolState: JSONObject? = null
                    var userInfo: JSONObject? = null

                    if (poolData.has("pool_info")) {
                        poolState = poolData.getJSONObject("pool_info")
                    }
                    if (poolData.has("user_info")) {
                        userInfo = poolData.getJSONObject("user_info")
                    }

                    updateUI(poolState, userInfo)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing pool data", e)
        }
    }

    private fun updateUI(poolState: JSONObject?, userInfo: JSONObject?) {
        if (poolState == null || userInfo == null) {
            return
        }

        try {
            val state = poolState.optJSONObject("state")
            if (state != null) {
                // Calculate data like LiquidityManagementComponent does
                val totalSharesMicro = state.optLong("total_shares", 0)
                val userStakedMicro = userInfo.optLong("amount_staked", 0)
                val unbondingSharesMicro = state.optLong("unbonding_shares", 0)

                val totalShares = totalSharesMicro / 1000000.0
                val userStaked = userStakedMicro / 1000000.0
                val unbondingShares = unbondingSharesMicro / 1000000.0

                val ownershipPercent = if (totalShares > 0) (userStaked / totalShares) * 100 else 0.0
                val unbondingPercent = if (totalShares > 0) (unbondingShares / totalShares) * 100 else 0.0

                // Update UI
                totalSharesText.text = String.format("Total Pool Shares: %,.0f", totalShares)
                userSharesText.text = String.format("Your Shares: %,.0f", userStaked)
                poolOwnershipText.text = String.format("Pool Ownership: %.4f%%", ownershipPercent)
                unbondingPercentText.text = String.format("%.4f%%", unbondingPercent)

                // Calculate underlying values
                val erthReserveMicro = state.optLong("erth_reserve", 0)
                val tokenBReserveMicro = state.optLong("token_b_reserve", 0)

                val erthReserveMacro = erthReserveMicro / 1000000.0
                val tokenBReserveMacro = tokenBReserveMicro / 1000000.0

                val userErthValue = (erthReserveMacro * ownershipPercent) / 100.0
                val userTokenBValue = (tokenBReserveMacro * ownershipPercent) / 100.0

                erthValueText.text = String.format("%.6f", userErthValue)
                tokenValueText.text = String.format("%.6f", userTokenBValue)

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI", e)
        }
    }

    private fun getTokenContract(tokenSymbol: String): String? {
        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        return tokenInfo?.contract
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executorService != null && !executorService!!.isShutdown) {
            executorService!!.shutdown()
        }
    }
}