package com.example.earthwallet.ui.pages.gasstation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.earthwallet.R
import com.example.earthwallet.Constants
import com.example.earthwallet.bridge.activities.TransactionActivity
import com.example.earthwallet.bridge.services.SecretQueryService
import com.example.earthwallet.wallet.constants.Tokens
import org.json.JSONObject
import java.io.InputStream
import java.text.DecimalFormat
import kotlin.math.pow

/**
 * GasStationFragment
 *
 * Implements gas station functionality for swapping any token to SCRT for gas:
 * - From token selection with balance display
 * - Gas swap simulation and execution using swap_for_gas message
 * - Integration with registration and faucet systems
 * - Viewing key management integration
 */
class GasStationFragment : Fragment() {

    companion object {
        private const val TAG = "GasStationFragment"
        // Removed - using PermitManager instead of viewing keys
        private const val REQUEST_REGISTRATION_CHECK = 4002
        private const val REQUEST_FAUCET_CLAIM = 4003
    }

    // UI Components
    private var fromTokenSpinner: Spinner? = null
    private var fromAmountInput: EditText? = null
    private var expectedScrtInput: EditText? = null
    private var fromBalanceText: TextView? = null
    private var scrtBalanceText: TextView? = null
    private var faucetStatusText: TextView? = null
    private var fromMaxButton: Button? = null
    // Removed - using PermitManager instead of viewing keys
    private var fromTokenLogo: ImageView? = null
    private var swapForGasButton: Button? = null
    private var faucetButton: Button? = null

    // State
    // Removed - using PermitManager instead of viewing keys
    private var currentWalletAddress = ""
    private var tokenSymbols: List<String> = listOf()
    private var fromToken = "sSCRT"
    private var fromBalance = 0.0
    private var scrtBalance = 0.0
    private var isSimulating = false
    private var isRegistered = false
    private var canClaimFaucet = false
    private val inputHandler = Handler(Looper.getMainLooper())
    private var simulationRunnable: Runnable? = null

    // Activity Result Launcher for transaction activity
    private lateinit var swapForGasLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize activity result launcher
        swapForGasLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val json = result.data?.getStringExtra(TransactionActivity.EXTRA_RESULT_JSON)
                handleSwapForGasResult(json)
            }
        }

        // Using PermitManager instead of viewing keys

        // Initialize token list (exclude SCRT since we're converting TO SCRT)
        tokenSymbols = ArrayList(Tokens.getAllTokens().keys).apply {
            remove("SCRT")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_gas_station, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupSpinner()
        setupClickListeners()
        loadCurrentWalletAddress()
        updateTokenLogo()
        fetchBalances()
        checkRegistrationStatus()
    }

    private fun initializeViews(view: View) {
        fromTokenSpinner = view.findViewById(R.id.from_token_spinner)
        fromAmountInput = view.findViewById(R.id.from_amount_input)
        expectedScrtInput = view.findViewById(R.id.expected_scrt_input)

        fromBalanceText = view.findViewById(R.id.from_balance_text)
        scrtBalanceText = view.findViewById(R.id.scrt_balance_text)
        faucetStatusText = view.findViewById(R.id.faucet_status_text)

        fromMaxButton = view.findViewById(R.id.from_max_button)
        // Removed - using PermitManager instead of viewing keys
        fromTokenLogo = view.findViewById(R.id.from_token_logo)

        swapForGasButton = view.findViewById(R.id.swap_for_gas_button)
        faucetButton = view.findViewById(R.id.faucet_button)
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, tokenSymbols)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        fromTokenSpinner?.adapter = adapter
        fromTokenSpinner?.setSelection(tokenSymbols.indexOf(fromToken))

        fromTokenSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = tokenSymbols[position]
                if (selected != fromToken) {
                    fromToken = selected
                    onTokenSelectionChanged()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        // Add delayed simulation TextWatcher
        fromAmountInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Cancel previous simulation if still pending
                simulationRunnable?.let { inputHandler.removeCallbacks(it) }

                // Schedule new simulation with delay
                simulationRunnable = Runnable { onFromAmountChangedDelayed() }
                inputHandler.postDelayed(simulationRunnable!!, 500)
            }
        })

        fromMaxButton?.setOnClickListener { setMaxFromAmount() }
        // Removed - using PermitManager instead of viewing keys

        swapForGasButton?.setOnClickListener { executeSwapForGas() }
        faucetButton?.setOnClickListener { claimFaucet() }
    }

    private fun onTokenSelectionChanged() {
        updateTokenLogo()
        clearAmounts()
        fetchBalances()
    }

    private fun onFromAmountChangedDelayed() {
        val amountStr = fromAmountInput?.text.toString()
        if (TextUtils.isEmpty(amountStr)) {
            expectedScrtInput?.setText("")
            updateSwapButton()
            return
        }

        try {
            val amount = amountStr.toDouble()
            if (amount > 0) {
                simulateSwapForGas(amount)
            } else {
                expectedScrtInput?.setText("")
            }
        } catch (e: NumberFormatException) {
            expectedScrtInput?.setText("")
        }
        updateSwapButton()
    }

    private fun simulateSwapForGas(inputAmount: Double) {
        if (isSimulating) return
        isSimulating = true

        val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
        Log.d(TAG, "Starting gas swap simulation: $inputAmount $fromTokenSymbol -> SCRT")

        val fromTokenInfo = Tokens.getTokenInfo(fromTokenSymbol)
        if (fromTokenInfo == null) {
            Log.e(TAG, "From token not supported: $fromTokenSymbol")
            Toast.makeText(context, "Token not supported", Toast.LENGTH_SHORT).show()
            isSimulating = false
            updateSwapButton()
            return
        }

        // Build swap simulation query - swap to sSCRT first (1:1 unwrap to SCRT)
        val sscrtTokenInfo = Tokens.getTokenInfo("sSCRT")
        if (sscrtTokenInfo == null) {
            Toast.makeText(context, "sSCRT not available", Toast.LENGTH_SHORT).show()
            isSimulating = false
            updateSwapButton()
            return
        }

        val queryJson = String.format(
            "{\"simulate_swap\": {\"input_token\": \"%s\", \"amount\": \"%s\", \"output_token\": \"%s\"}}",
            fromTokenInfo.contract,
            (inputAmount * 10.0.pow(fromTokenInfo.decimals)).toLong().toString(),
            sscrtTokenInfo.contract
        )

        Log.d(TAG, "Gas swap simulation query: $queryJson")

        // Use SecretQueryService in background thread
        Thread {
            try {
                if (!com.example.earthwallet.wallet.services.SecureWalletManager.isWalletAvailable(requireContext())) {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "No wallet found", Toast.LENGTH_SHORT).show()
                        isSimulating = false
                        updateSwapButton()
                    }
                    return@Thread
                }

                val queryObj = JSONObject(queryJson)
                val queryService = SecretQueryService(requireContext())
                val result = queryService.queryContract(
                    Constants.EXCHANGE_CONTRACT,
                    Constants.EXCHANGE_HASH,
                    queryObj
                )

                // Format result to match expected format
                val response = JSONObject()
                response.put("success", true)
                response.put("result", result)

                // Handle result on UI thread
                activity?.runOnUiThread {
                    handleSimulationResult(response.toString())
                }

            } catch (e: Exception) {
                Log.e(TAG, "Gas swap simulation failed", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Simulation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    isSimulating = false
                    updateSwapButton()
                }
            }
        }.start()
    }

    private fun handleSimulationResult(json: String) {
        Log.d(TAG, "handleSimulationResult called with JSON: $json")
        try {
            val root = JSONObject(json)
            val success = root.optBoolean("success", false)

            if (success) {
                val result = root.optJSONObject("result")
                result?.let {
                    val outputAmount = it.optString("output_amount", "0")
                    // The final SCRT amount will be the same as sSCRT amount (1:1 unwrap)
                    val sscrtTokenInfo = Tokens.getTokenInfo("sSCRT")
                    sscrtTokenInfo?.let { tokenInfo ->
                        val scrtOutput = outputAmount.toDouble() / 10.0.pow(tokenInfo.decimals)
                        val df = DecimalFormat("#.######")
                        expectedScrtInput?.setText(df.format(scrtOutput))

                        Log.d(TAG, "Gas swap simulation successful - input: ${fromAmountInput?.text} " +
                                "${tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]}, " +
                                "output: ${df.format(scrtOutput)} SCRT")
                    }
                }
            } else {
                Toast.makeText(context, "Simulation failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse simulation result", e)
            Toast.makeText(context, "Error simulating swap", Toast.LENGTH_SHORT).show()
        }

        isSimulating = false
        updateSwapButton()
    }

    private fun setMaxFromAmount() {
        if (fromBalance > 0) {
            fromAmountInput?.setText(fromBalance.toString())
        }
    }

    private fun clearAmounts() {
        fromAmountInput?.setText("")
        expectedScrtInput?.setText("")
        updateSwapButton()
    }

    private fun updateTokenLogo() {
        val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
        loadTokenLogo(fromTokenLogo, fromTokenSymbol)
    }

    private fun loadTokenLogo(imageView: ImageView?, tokenSymbol: String) {
        try {
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
            if (tokenInfo?.logo != null) {
                val inputStream = context?.assets?.open(tokenInfo.logo)
                val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                imageView?.setImageDrawable(drawable)
                inputStream?.close()
            } else {
                imageView?.setImageResource(R.drawable.ic_wallet)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Logo not found for $tokenSymbol, using default icon")
            imageView?.setImageResource(R.drawable.ic_wallet)
        }
    }

    private fun updateSwapButton() {
        val fromAmountStr = fromAmountInput?.text.toString()
        val expectedScrtStr = expectedScrtInput?.text.toString()

        val enabled = !TextUtils.isEmpty(fromAmountStr) &&
                     !TextUtils.isEmpty(expectedScrtStr) &&
                     fromAmountStr != "0" &&
                     expectedScrtStr != "0"

        swapForGasButton?.isEnabled = enabled
    }

    private fun loadCurrentWalletAddress() {
        try {
            currentWalletAddress = com.example.earthwallet.wallet.services.SecureWalletManager.getWalletAddress(requireContext()) ?: ""
            Log.d(TAG, "Loaded wallet address: $currentWalletAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            currentWalletAddress = ""
        }
    }

    private fun fetchBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            fromBalanceText?.text = "Balance: Connect wallet"
            scrtBalanceText?.text = "Balance: Connect wallet"
            return
        }

        val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
        fetchTokenBalance(fromTokenSymbol, true)
        fetchNativeScrtBalance()
    }

    private fun fetchTokenBalance(tokenSymbol: String, isFromToken: Boolean) {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            Log.w(TAG, "No wallet address available")
            return
        }

        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        if (tokenInfo == null) {
            Log.w(TAG, "Token not found: $tokenSymbol")
            return
        }

        // Execute query in background thread using PermitManager
        Thread {
            try {
                val result = com.example.earthwallet.bridge.services.SnipQueryService.queryBalanceWithPermit(
                    requireContext(),
                    tokenSymbol,
                    currentWalletAddress
                )

                activity?.runOnUiThread {
                    Log.d(TAG, "Token balance result: $result")
                    handleTokenBalanceResult(tokenSymbol, isFromToken, result.toString())
                }

            } catch (e: Exception) {
                Log.e(TAG, "Token balance query failed for $tokenSymbol", e)
                activity?.runOnUiThread {
                    fromBalance = -1.0
                    updateFromBalanceDisplay()
                }
            }
        }.start()
    }

    private fun fetchNativeScrtBalance() {
        // For now, set SCRT balance to placeholder - implement native balance query later
        scrtBalance = 0.0
        updateScrtBalanceDisplay()
    }

    private fun handleTokenBalanceResult(tokenSymbol: String, @Suppress("UNUSED_PARAMETER") isFromToken: Boolean, json: String) {
        try {
            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "Balance query result JSON is empty")
                fromBalance = -1.0
                updateFromBalanceDisplay()
                return
            }

            val root = JSONObject(json)
            val success = root.optBoolean("success", false)

            if (success) {
                val result = root.optJSONObject("result")
                result?.let {
                    val balance = it.optJSONObject("balance")
                    balance?.let { bal ->
                        val amount = bal.optString("amount", "0")
                        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                        tokenInfo?.let { info ->
                            var formattedBalance = 0.0
                            if (!TextUtils.isEmpty(amount)) {
                                try {
                                    formattedBalance = amount.toDouble() / 10.0.pow(info.decimals)
                                } catch (e: NumberFormatException) {
                                    Log.w(TAG, "Invalid amount format: $amount")
                                }
                            }
                            fromBalance = formattedBalance
                            updateFromBalanceDisplay()
                        }
                    } ?: run {
                        fromBalance = -1.0
                        updateFromBalanceDisplay()
                    }
                }
            } else {
                val error = root.optString("error", "Unknown error")
                Log.e(TAG, "Balance query failed: $error")
                fromBalance = -1.0
                updateFromBalanceDisplay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse balance query result", e)
            fromBalance = -1.0
            updateFromBalanceDisplay()
        }
    }

    private fun updateFromBalanceDisplay() {
        val df = DecimalFormat("#.##")

        when {
            fromBalance >= 0 -> {
                fromBalanceText?.text = "Balance: ${df.format(fromBalance)}"
                fromMaxButton?.visibility = if (fromBalance > 0) View.VISIBLE else View.GONE
            }
            fromBalance == -1.0 -> {
                fromBalanceText?.text = "Balance: Error"
                fromMaxButton?.visibility = View.GONE
            }
            else -> {
                fromBalanceText?.text = "Balance: ..."
                fromMaxButton?.visibility = View.GONE
            }
        }
    }

    private fun updateScrtBalanceDisplay() {
        val df = DecimalFormat("#.##")
        scrtBalanceText?.text = "Balance: ${df.format(scrtBalance)}"
    }

    private fun checkRegistrationStatus() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            updateFaucetStatus(false, false)
            return
        }

        // Check registration status using registration contract query
        Thread {
            try {
                val queryJson = String.format(
                    "{\"query_registration_status\": {\"address\": \"%s\"}}",
                    currentWalletAddress
                )

                val queryObj = JSONObject(queryJson)
                val queryService = SecretQueryService(requireContext())
                val result = queryService.queryContract(
                    Constants.REGISTRATION_CONTRACT,
                    Constants.REGISTRATION_HASH,
                    queryObj
                )

                activity?.runOnUiThread {
                    handleRegistrationStatusResult(result.toString())
                }

            } catch (e: Exception) {
                Log.e(TAG, "Registration status check failed", e)
                activity?.runOnUiThread {
                    updateFaucetStatus(false, false)
                }
            }
        }.start()
    }

    private fun handleRegistrationStatusResult(json: String) {
        try {
            val root = JSONObject(json)
            val registrationStatus = root.optBoolean("registration_status", false)

            if (registrationStatus) {
                // Check if they can claim faucet (once per week)
                val lastClaim = root.optLong("last_claim", 0)
                val now = System.currentTimeMillis() * 1000000 // Convert to nanoseconds
                val oneWeekInNanos = 7L * 24 * 60 * 60 * 1000 * 1000000 // One week in nanoseconds
                val canClaim = (now - lastClaim) > oneWeekInNanos

                updateFaucetStatus(true, canClaim)
            } else {
                updateFaucetStatus(false, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse registration status result", e)
            updateFaucetStatus(false, false)
        }
    }

    private fun updateFaucetStatus(registered: Boolean, canClaim: Boolean) {
        isRegistered = registered
        canClaimFaucet = canClaim

        when {
            registered && canClaim -> {
                faucetStatusText?.text = "✓ Registered ✓ Available to use"
                faucetButton?.isEnabled = true
            }
            registered && !canClaim -> {
                faucetStatusText?.text = "✓ Registered ✗ Already used this week"
                faucetButton?.isEnabled = false
            }
            else -> {
                faucetStatusText?.text = "✗ Not registered"
                faucetButton?.isEnabled = false
            }
        }
    }

    // Removed - using PermitManager for token access

    // Removed - using PermitManager for token access

    private fun executeSwapForGas() {
        val fromAmountStr = fromAmountInput?.text.toString()
        if (TextUtils.isEmpty(fromAmountStr)) return

        try {
            val inputAmount = fromAmountStr.toDouble()
            if (inputAmount <= 0 || inputAmount > fromBalance) {
                Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show()
                return
            }

            swapForGasButton?.isEnabled = false
            swapForGasButton?.text = "Swapping..."

            val fromTokenSymbol = tokenSymbols[fromTokenSpinner?.selectedItemPosition ?: 0]
            val fromTokenInfo = Tokens.getTokenInfo(fromTokenSymbol)
            if (fromTokenInfo == null) {
                Toast.makeText(requireContext(), "Token not supported", Toast.LENGTH_SHORT).show()
                resetSwapButton()
                return
            }

            // Build the swap_for_gas message
            val swapForGasMessage = String.format(
                "{\"swap_for_gas\": {\"from\": \"%s\", \"amount\": \"%s\"}}",
                currentWalletAddress,
                (inputAmount * 10.0.pow(fromTokenInfo.decimals)).toLong().toString()
            )

            val inputAmountMicro = (inputAmount * 10.0.pow(fromTokenInfo.decimals)).toLong()

            Log.d(TAG, "Starting swap for gas execution")
            Log.d(TAG, "From token: $fromTokenSymbol")
            Log.d(TAG, "Amount: $inputAmountMicro")
            Log.d(TAG, "Message: $swapForGasMessage")

            val intent = Intent(requireContext(), TransactionActivity::class.java)
            intent.putExtra(TransactionActivity.EXTRA_TRANSACTION_TYPE, TransactionActivity.TYPE_SNIP_EXECUTE)
            intent.putExtra(TransactionActivity.EXTRA_TOKEN_CONTRACT, fromTokenInfo.contract)
            intent.putExtra(TransactionActivity.EXTRA_TOKEN_HASH, fromTokenInfo.hash)
            intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_ADDRESS, Constants.EXCHANGE_CONTRACT)
            intent.putExtra(TransactionActivity.EXTRA_RECIPIENT_HASH, Constants.EXCHANGE_HASH)
            intent.putExtra(TransactionActivity.EXTRA_AMOUNT, inputAmountMicro.toString())
            intent.putExtra(TransactionActivity.EXTRA_MESSAGE_JSON, swapForGasMessage)

            swapForGasLauncher.launch(intent)

        } catch (e: NumberFormatException) {
            Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show()
            resetSwapButton()
        }
    }

    private fun claimFaucet() {
        if (!isRegistered || !canClaimFaucet) {
            Toast.makeText(requireContext(), "Faucet not available", Toast.LENGTH_SHORT).show()
            return
        }

        faucetButton?.isEnabled = false
        faucetButton?.text = "Claiming..."

        // Implement faucet claiming logic - this would typically call a backend API
        Toast.makeText(requireContext(), "Faucet functionality not implemented yet", Toast.LENGTH_SHORT).show()

        // Reset button
        faucetButton?.isEnabled = true
        faucetButton?.text = "Faucet"
    }

    private fun resetSwapButton() {
        swapForGasButton?.isEnabled = true
        swapForGasButton?.text = "Swap for Gas"
    }


    private fun handleSwapForGasResult(json: String?) {
        Log.d(TAG, "handleSwapForGasResult called with JSON: $json")

        resetSwapButton()

        try {
            val root = JSONObject(json ?: "")

            val success = if (root.has("tx_response")) {
                val txResponse = root.getJSONObject("tx_response")
                val code = txResponse.optInt("code", -1)
                val isSuccess = (code == 0)

                if (isSuccess) {
                    val txHash = txResponse.optString("txhash", "")
                    Log.d(TAG, "Gas swap transaction hash: $txHash")
                }
                isSuccess
            } else {
                root.optBoolean("success", false)
            }

            if (success) {
                clearAmounts()
                fetchBalances() // Refresh balances
            } else {
                Toast.makeText(context, "Gas swap failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse gas swap result", e)
            Toast.makeText(context, "Gas swap failed", Toast.LENGTH_SHORT).show()
        }
    }

    // Removed - using PermitManager instead of viewing keys
}