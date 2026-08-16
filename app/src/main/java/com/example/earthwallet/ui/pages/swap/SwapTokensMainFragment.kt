package network.erth.wallet.ui.pages.swap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.Dex
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.ErthPriceService
import network.erth.wallet.wallet.services.SecureWalletManager
import java.text.DecimalFormat
import kotlin.math.pow

/**
 * SwapTokensMainFragment
 *
 * Token swapping on the earth x/dex (spoke-and-wheel AMM hubbed on ERTH):
 * - From/To native token selection with live bank balances.
 * - Client-side price quote from pool reserves (constant-product with fee).
 * - Slippage tolerance -> min_amount_out; the chain performs any ERTH routing.
 *
 * All tokens are native bank denoms, so there are no viewing keys, permits or
 * contract queries — a quote is pure arithmetic over pool reserves and a swap is
 * a single MsgSwap broadcast.
 */
class SwapTokensMainFragment : Fragment() {

    companion object {
        private const val TAG = "SwapTokensFragment"
        private const val ERTH = "ERTH"
        // ERTH held back by "Max" to pay the uerth tx fee (fee is 2000 uerth; keep a buffer).
        private const val FEE_RESERVE_ERTH = 0.01
    }

    // UI Components
    private var fromTokenSpinner: Spinner? = null
    private var toTokenSpinner: Spinner? = null
    private var fromAmountInput: EditText? = null
    private var toAmountInput: EditText? = null
    private var slippageInput: EditText? = null
    private var fromBalanceText: TextView? = null
    private var toBalanceText: TextView? = null
    private var fromUsdValue: TextView? = null
    private var toUsdValue: TextView? = null
    private var rateText: TextView? = null
    private var minReceivedText: TextView? = null
    private var fromMaxButton: Button? = null
    private var toMaxButton: Button? = null
    private var toggleButton: ImageButton? = null
    private var swapButton: Button? = null
    private var detailsToggle: Button? = null
    private var detailsContainer: LinearLayout? = null
    private var fromTokenLogo: ImageView? = null
    private var toTokenLogo: ImageView? = null

    // State
    private var currentWalletAddress = ""
    private var tokenSymbols: List<String> = listOf()
    private var fromToken = "ANML"
    private var toToken = ERTH
    private var fromBalance = 0.0
    private var toBalance = 0.0
    private var slippage = 1.0
    private var detailsVisible = false
    private val inputHandler = Handler(Looper.getMainLooper())
    private var simulationRunnable: Runnable? = null

    // Pool reserves cache keyed by spoke-token denom (uanml, uusdc, ...). Loaded once per view.
    private var pools: Map<String, Dex.Pool> = emptyMap()
    private var feePercent = 0.0
    private var erthPrice: Double? = null

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenSymbols = ArrayList(Tokens.getAllTokens().keys)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_swap_tokens_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupSpinners()
        setupClickListeners()
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        loadCurrentWalletAddress()

        updateTokenLogos()
        loadPoolsAndPrices()
        fetchBalances()
    }

    private fun initializeViews(view: View) {
        fromTokenSpinner = view.findViewById(R.id.from_token_spinner)
        toTokenSpinner = view.findViewById(R.id.to_token_spinner)
        fromAmountInput = view.findViewById(R.id.from_amount_input)
        toAmountInput = view.findViewById(R.id.to_amount_input)
        slippageInput = view.findViewById(R.id.slippage_input)

        fromBalanceText = view.findViewById(R.id.from_balance_text)
        toBalanceText = view.findViewById(R.id.to_balance_text)
        fromUsdValue = view.findViewById(R.id.from_usd_value)
        toUsdValue = view.findViewById(R.id.to_usd_value)
        rateText = view.findViewById(R.id.rate_text)
        minReceivedText = view.findViewById(R.id.min_received_text)

        fromMaxButton = view.findViewById(R.id.from_max_button)
        toMaxButton = view.findViewById(R.id.to_max_button)

        toggleButton = view.findViewById(R.id.toggle_button)
        swapButton = view.findViewById(R.id.swap_button)
        detailsToggle = view.findViewById(R.id.details_toggle)
        detailsContainer = view.findViewById(R.id.details_container)

        fromTokenLogo = view.findViewById(R.id.from_token_logo)
        toTokenLogo = view.findViewById(R.id.to_token_logo)
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, tokenSymbols)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        fromTokenSpinner?.adapter = adapter
        toTokenSpinner?.adapter = adapter

        fromTokenSpinner?.setSelection(tokenSymbols.indexOf(fromToken))
        toTokenSpinner?.setSelection(tokenSymbols.indexOf(toToken))

        fromTokenSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = tokenSymbols[position]
                if (selected != fromToken) {
                    if (selected == toToken) {
                        // Swap tokens if selecting the same as 'to'
                        toToken = fromToken
                        toTokenSpinner?.setSelection(tokenSymbols.indexOf(toToken))
                    }
                    fromToken = selected
                    onTokenSelectionChanged()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        toTokenSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = tokenSymbols[position]
                if (selected != toToken) {
                    if (selected == fromToken) {
                        // Swap tokens if selecting the same as 'from'
                        fromToken = toToken
                        fromTokenSpinner?.setSelection(tokenSymbols.indexOf(fromToken))
                    }
                    toToken = selected
                    onTokenSelectionChanged()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        // Debounced re-quote as the user types the input amount.
        fromAmountInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                simulationRunnable?.let { inputHandler.removeCallbacks(it) }
                simulationRunnable = Runnable { onFromAmountChanged() }
                inputHandler.postDelayed(simulationRunnable!!, 300)
            }
        })

        fromMaxButton?.setOnClickListener { setMaxFromAmount() }
        toMaxButton?.setOnClickListener { /* no-op: earth balances need no permits */ }

        toggleButton?.setOnClickListener { toggleTokenPair() }
        swapButton?.setOnClickListener { executeSwap() }

        detailsToggle?.setOnClickListener { toggleDetails() }

        slippageInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSlippage()
            }
        })
    }

    private fun onTokenSelectionChanged() {
        updateTokenLogos()
        clearAmounts()
        fetchBalances()
    }

    // --- pricing ---------------------------------------------------------

    private fun loadPoolsAndPrices() {
        lifecycleScope.launch {
            try {
                val (loaded, fee) = withContext(Dispatchers.IO) {
                    val list = Dex.pools().associateBy { it.tokenDenom }
                    val f = Dex.swapFeePercent().toDoubleOrNull() ?: 0.0
                    list to f
                }
                pools = loaded
                feePercent = fee
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load pools", e)
            }
            try {
                erthPrice = ErthPriceService.fetchErthPrice()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch ERTH price", e)
            }
            // Re-quote in case the user already typed an amount while loading.
            onFromAmountChanged()
        }
    }

    private fun onFromAmountChanged() {
        val amountStr = fromAmountInput?.text.toString()
        if (TextUtils.isEmpty(amountStr)) {
            toAmountInput?.setText("")
            updateSwapButton()
            return
        }
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            toAmountInput?.setText("")
            updateSwapButton()
            return
        }

        val output = quote(fromToken, toToken, amount)
        if (output != null) {
            toAmountInput?.setText(DecimalFormat("#.######").format(output))
        } else {
            toAmountInput?.setText("")
        }
        updateDetailsDisplay()
        updateSwapButton()
    }

    /**
     * Expected output (human units) for swapping [inputAmount] of [from] into [to],
     * mirroring the chain's constant-product-with-fee math over pool reserves.
     * ERTH is the hub; token->token routes through ERTH in two hops.
     */
    private fun quote(from: String, to: String, inputAmount: Double): Double? {
        val fromInfo = Tokens.getTokenInfo(from) ?: return null
        val toInfo = Tokens.getTokenInfo(to) ?: return null
        val amountIn = inputAmount * 10.0.pow(fromInfo.decimals)

        val outBase: Double = when {
            from == ERTH -> {
                val p = pools[toInfo.denom] ?: return null
                erthToToken(p, amountIn)
            }
            to == ERTH -> {
                val p = pools[fromInfo.denom] ?: return null
                tokenToErth(p, amountIn)
            }
            else -> {
                val pFrom = pools[fromInfo.denom] ?: return null
                val pTo = pools[toInfo.denom] ?: return null
                val midErth = tokenToErth(pFrom, amountIn)
                erthToToken(pTo, midErth)
            }
        }
        return outBase / 10.0.pow(toInfo.decimals)
    }

    /** Spoke token -> ERTH (fee taken on the ERTH output). Reserves in base units. */
    private fun tokenToErth(pool: Dex.Pool, amountIn: Double): Double {
        val erthR = pool.erthReserve.toDouble()
        val tokenR = pool.tokenReserve.toDouble()
        val grossErth = erthR * amountIn / (tokenR + amountIn)
        return grossErth * (1.0 - feePercent / 100.0)
    }

    /** ERTH -> spoke token (fee taken on the ERTH input). Reserves in base units. */
    private fun erthToToken(pool: Dex.Pool, amountErthIn: Double): Double {
        val erthR = pool.erthReserve.toDouble()
        val tokenR = pool.tokenReserve.toDouble()
        val effIn = amountErthIn * (1.0 - feePercent / 100.0)
        return tokenR * effIn / (erthR + effIn)
    }

    // --- amount/detail UI ------------------------------------------------

    private fun setMaxFromAmount() {
        if (fromBalance <= 0) return
        // The tx fee is paid in ERTH (uerth). When swapping ERTH, "Max" must leave
        // enough behind to cover it, or the tx fails with insufficient funds.
        val max = if (fromToken == ERTH) (fromBalance - FEE_RESERVE_ERTH).coerceAtLeast(0.0) else fromBalance
        fromAmountInput?.setText(DecimalFormat("#.######").format(max))
    }

    private fun toggleTokenPair() {
        val tempToken = fromToken
        fromToken = toToken
        toToken = tempToken

        fromTokenSpinner?.setSelection(tokenSymbols.indexOf(fromToken))
        toTokenSpinner?.setSelection(tokenSymbols.indexOf(toToken))

        updateTokenLogos()
        clearAmounts()
        fetchBalances()
    }

    private fun toggleDetails() {
        detailsVisible = !detailsVisible
        detailsContainer?.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        detailsToggle?.text = if (detailsVisible) "Hide Details ▲" else "Show Details ▼"
    }

    private fun clearAmounts() {
        fromAmountInput?.setText("")
        toAmountInput?.setText("")
        fromUsdValue?.text = "$0.00"
        toUsdValue?.text = "$0.00"
        updateSwapButton()
    }

    private fun updateTokenLogos() {
        loadTokenLogo(fromTokenLogo, fromToken)
        loadTokenLogo(toTokenLogo, toToken)
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
            imageView?.setImageResource(R.drawable.ic_wallet)
        }
    }

    private fun updateSwapButton() {
        val fromAmountStr = fromAmountInput?.text.toString()
        val toAmountStr = toAmountInput?.text.toString()

        swapButton?.isEnabled = !TextUtils.isEmpty(fromAmountStr) &&
                !TextUtils.isEmpty(toAmountStr) &&
                fromAmountStr != "0" &&
                toAmountStr != "0"
    }

    private fun updateSlippage() {
        slippage = slippageInput?.text.toString().toDoubleOrNull() ?: 1.0
        updateDetailsDisplay()
    }

    private fun updateDetailsDisplay() {
        val fromAmountStr = fromAmountInput?.text.toString()
        val toAmountStr = toAmountInput?.text.toString()

        if (!TextUtils.isEmpty(fromAmountStr) && !TextUtils.isEmpty(toAmountStr)) {
            val fromAmount = fromAmountStr.toDoubleOrNull()
            val toAmount = toAmountStr.toDoubleOrNull()
            if (fromAmount != null && fromAmount > 0 && toAmount != null) {
                val rate = toAmount / fromAmount
                val df = DecimalFormat("#.######")
                rateText?.text = "1 $fromToken = ${df.format(rate)} $toToken"

                val minReceived = toAmount * (1 - slippage / 100)
                val decimals = Tokens.getTokenInfo(toToken)?.decimals ?: 6
                val minDf = DecimalFormat("#.${"0".repeat(decimals)}")
                minReceivedText?.text = "${minDf.format(minReceived)} $toToken"
            }
        }
        updateUsdValues()
    }

    private fun updateUsdValues() {
        fromUsdValue?.text = usdText(fromToken, fromAmountInput?.text.toString())
        toUsdValue?.text = usdText(toToken, toAmountInput?.text.toString())
    }

    private fun usdText(token: String, amountStr: String?): String {
        val amount = amountStr?.toDoubleOrNull() ?: return "$0.00"
        val usd = usdValueForToken(token, amount) ?: return "$0.00"
        return ErthPriceService.formatUSD(usd)
    }

    /** USD value using the ERTH spot price and the token's pool spot rate against ERTH. */
    private fun usdValueForToken(token: String, amount: Double): Double? {
        val price = erthPrice ?: return null
        if (token == ERTH) return amount * price
        val info = Tokens.getTokenInfo(token) ?: return null
        val pool = pools[info.denom] ?: return null
        val tokenR = pool.tokenReserve.toDouble()
        if (tokenR <= 0) return null
        // reserves share 6 decimals, so erth/token ratio is the spot rate in ERTH per token
        val spotRateErth = pool.erthReserve.toDouble() / tokenR
        return amount * spotRateErth * price
    }

    // --- balances --------------------------------------------------------

    private fun loadCurrentWalletAddress() {
        currentWalletAddress = try {
            SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            ""
        }
    }

    private fun fetchBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            fromBalanceText?.text = "Balance: Connect wallet"
            toBalanceText?.text = "Balance: Connect wallet"
            return
        }
        fetchBalance(fromToken, true)
        fetchBalance(toToken, false)
    }

    private fun fetchBalance(tokenSymbol: String, isFromToken: Boolean) {
        val info = Tokens.getTokenInfo(tokenSymbol) ?: return
        lifecycleScope.launch {
            val human = try {
                val raw = withContext(Dispatchers.IO) { Bank.balance(currentWalletAddress, info.denom) }
                raw.toDouble() / 10.0.pow(info.decimals)
            } catch (e: Exception) {
                Log.e(TAG, "Balance query failed for $tokenSymbol", e)
                0.0
            }
            if (isFromToken) {
                fromBalance = human
                updateFromBalanceDisplay()
            } else {
                toBalance = human
                updateToBalanceDisplay()
            }
        }
    }

    private fun updateFromBalanceDisplay() {
        val df = DecimalFormat("#.##")
        fromBalanceText?.text = "Balance: ${df.format(fromBalance)}"
        fromMaxButton?.visibility = if (fromBalance > 0) View.VISIBLE else View.GONE
        fromMaxButton?.text = "Max"
    }

    private fun updateToBalanceDisplay() {
        val df = DecimalFormat("#.##")
        toBalanceText?.text = "Balance: ${df.format(toBalance)}"
        toMaxButton?.visibility = View.GONE
    }

    // --- swap execution --------------------------------------------------

    private fun executeSwap() {
        val fromAmountStr = fromAmountInput?.text.toString()
        if (TextUtils.isEmpty(fromAmountStr)) return

        val inputAmount = fromAmountStr.toDoubleOrNull()
        if (inputAmount == null || inputAmount <= 0 || inputAmount > fromBalance) {
            Toast.makeText(context, "Invalid amount", Toast.LENGTH_SHORT).show()
            return
        }
        // Swapping ERTH must leave enough ERTH to pay the uerth tx fee.
        if (fromToken == ERTH && inputAmount > fromBalance - FEE_RESERVE_ERTH) {
            Toast.makeText(context, "Leave a little ERTH for the network fee", Toast.LENGTH_SHORT).show()
            return
        }

        val fromInfo = Tokens.getTokenInfo(fromToken)
        val toInfo = Tokens.getTokenInfo(toToken)
        if (fromInfo == null || toInfo == null) {
            Toast.makeText(context, "Token not supported", Toast.LENGTH_SHORT).show()
            return
        }

        val amountInBase = Tokens.parseTokenAmount(fromAmountStr, fromToken)
        if (amountInBase == null || amountInBase <= 0) {
            Toast.makeText(context, "Invalid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val expectedOut = quote(fromToken, toToken, inputAmount)
        if (expectedOut == null) {
            Toast.makeText(context, "No pool for this pair", Toast.LENGTH_SHORT).show()
            return
        }
        val minOutBase = (expectedOut * (1.0 - slippage / 100.0) * 10.0.pow(toInfo.decimals)).toLong()

        swapButton?.isEnabled = false
        lifecycleScope.launch {
            try {
                val txHash = withContext(Dispatchers.IO) {
                    SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                        val key = EarthWallet.deriveKey(mnemonic)
                        val creator = EarthWallet.address(key)
                        EarthTx.broadcast(
                            key,
                            listOf(
                                Dex.msgSwap(
                                    creator = creator,
                                    tokenInDenom = fromInfo.denom,
                                    tokenInAmount = amountInBase.toString(),
                                    denomOut = toInfo.denom,
                                    minOut = minOutBase.coerceAtLeast(0).toString(),
                                )
                            )
                        )
                    }
                }
                Log.i(TAG, "Swap broadcast: $txHash")
                Toast.makeText(context, "Swap submitted", Toast.LENGTH_SHORT).show()
                clearAmounts()
                fetchBalances()
                loadPoolsAndPrices()
            } catch (e: Exception) {
                Log.e(TAG, "Swap failed", e)
                Toast.makeText(context, "Swap failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                updateSwapButton()
            }
        }
    }

    // --- transaction-success broadcast -----------------------------------

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                clearAmounts()
                Handler(Looper.getMainLooper()).postDelayed({
                    fetchBalances()
                    loadPoolsAndPrices()
                }, 200)
            }
        }
    }

    private fun registerBroadcastReceiver() {
        if (activity != null && transactionSuccessReceiver != null) {
            val filter = IntentFilter("network.erth.wallet.TRANSACTION_SUCCESS")
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().applicationContext.registerReceiver(transactionSuccessReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register broadcast receiver", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }
}
