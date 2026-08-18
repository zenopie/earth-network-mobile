package network.erth.wallet.ui.pages.managelp

import network.erth.wallet.ui.components.TxResult
import network.erth.wallet.ui.components.TxFlow
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import network.erth.wallet.wallet.services.SecureWalletManager
import java.text.DecimalFormat

/**
 * Add liquidity to an earth x/dex pool (ERTH + spoke token). Amounts are entered
 * at the current pool ratio and provided in a single MsgAddLiquidity — native bank
 * denoms need no allowance/approve step.
 */
class AddLiquidityFragment : Fragment() {

    companion object {
        private const val TAG = "AddLiquidityFragment"
        // ERTH held back to pay the uerth tx fee (fee is 2000 uerth; keep a buffer).
        private const val FEE_RESERVE_ERTH = 0.01

        @JvmStatic
        fun newInstance(tokenKey: String): AddLiquidityFragment {
            val fragment = AddLiquidityFragment()
            fragment.arguments = Bundle().apply { putString("token_key", tokenKey) }
            return fragment
        }
    }

    private lateinit var tokenAmountInput: EditText
    private lateinit var erthAmountInput: EditText
    private lateinit var tokenBalanceText: TextView
    private lateinit var erthBalanceText: TextView
    private lateinit var tokenLabel: TextView
    private lateinit var tokenInputLogo: ImageView
    private lateinit var erthInputLogo: ImageView
    private lateinit var tokenMaxButton: Button
    private lateinit var erthMaxButton: Button
    private lateinit var addLiquidityButton: Button

    private var tokenKey: String? = null
    private var tokenBalance = 0.0
    private var erthBalance = 0.0
    private var currentWalletAddress = ""

    private var pool: Dex.Pool? = null
    private var erthReserve = 0.0
    private var tokenReserve = 0.0
    private var isUpdatingRatio = false

    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenKey = arguments?.getString("token_key")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_liquidity_add, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupListeners()
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        loadCurrentWalletAddress()
        updateTokenInfo()
        loadTokenBalances()
        loadPoolReserves()
    }

    private fun initializeViews(view: View) {
        tokenAmountInput = view.findViewById(R.id.token_amount_input)
        erthAmountInput = view.findViewById(R.id.erth_amount_input)
        tokenBalanceText = view.findViewById(R.id.token_balance_text)
        erthBalanceText = view.findViewById(R.id.erth_balance_text)
        tokenLabel = view.findViewById(R.id.token_label)
        tokenInputLogo = view.findViewById(R.id.token_input_logo)
        erthInputLogo = view.findViewById(R.id.erth_input_logo)
        tokenMaxButton = view.findViewById(R.id.token_max_button)
        erthMaxButton = view.findViewById(R.id.erth_max_button)
        addLiquidityButton = view.findViewById(R.id.add_liquidity_button)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                tokenAmountInput.setText("")
                erthAmountInput.setText("")
                Handler(Looper.getMainLooper()).postDelayed({
                    loadTokenBalances()
                    loadPoolReserves()
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

    private fun setupListeners() {
        tokenMaxButton.setOnClickListener {
            if (tokenBalance > 0) {
                tokenAmountInput.setText(tokenBalance.toString())
                calculateErthFromToken(tokenBalance.toString())
            }
        }
        erthMaxButton.setOnClickListener {
            if (erthBalance > 0) {
                // The tx fee is paid in ERTH (uerth); hold a little back to cover it.
                val max = (erthBalance - FEE_RESERVE_ERTH).coerceAtLeast(0.0)
                val maxStr = DecimalFormat("#.######").format(max)
                erthAmountInput.setText(maxStr)
                calculateTokenFromErth(maxStr)
            }
        }
        tokenAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (!isUpdatingRatio) calculateErthFromToken(s.toString())
            }
        })
        erthAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (!isUpdatingRatio) calculateTokenFromErth(s.toString())
            }
        })
        addLiquidityButton.setOnClickListener { handleAddLiquidity() }
    }

    private fun updateTokenInfo() {
        tokenKey?.let { token ->
            tokenLabel.text = token
            tokenAmountInput.hint = "Amount of $token"
            loadTokenLogo(tokenInputLogo, token)
            loadTokenLogo(erthInputLogo, "ERTH")
        }
    }

    private fun loadTokenLogo(imageView: ImageView, tokenSymbol: String) {
        try {
            val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
            if (tokenInfo != null) {
                val inputStream = context!!.assets.open(tokenInfo.logo)
                imageView.setImageDrawable(Drawable.createFromStream(inputStream, null))
                inputStream.close()
            } else {
                imageView.setImageResource(R.drawable.ic_wallet)
            }
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_wallet)
        }
    }

    private fun loadCurrentWalletAddress() {
        currentWalletAddress = try {
            SecureWalletManager.getWalletAddress(requireContext()) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet address", e)
            ""
        }
    }

    private fun loadTokenBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            tokenBalanceText.text = "Balance: Connect wallet"
            erthBalanceText.text = "Balance: Connect wallet"
            return
        }
        tokenKey?.let { fetchTokenBalance(it, true) }
        fetchTokenBalance("ERTH", false)
    }

    private fun fetchTokenBalance(tokenSymbol: String, isToken: Boolean) {
        val info = Tokens.getTokenInfo(tokenSymbol) ?: return
        lifecycleScope.launch {
            val human = try {
                val raw = withContext(Dispatchers.IO) { Bank.balance(currentWalletAddress, info.denom) }
                raw.toDouble() / Math.pow(10.0, info.decimals.toDouble())
            } catch (e: Exception) {
                Log.e(TAG, "Balance query failed for $tokenSymbol", e)
                0.0
            }
            if (isToken) {
                tokenBalance = human
                updateTokenBalanceDisplay()
            } else {
                erthBalance = human
                updateErthBalanceDisplay()
            }
        }
    }

    private fun updateTokenBalanceDisplay() {
        val df = DecimalFormat("#.##")
        tokenBalanceText.text = "Balance: ${df.format(tokenBalance)}"
        tokenMaxButton.visibility = if (tokenBalance > 0) View.VISIBLE else View.GONE
    }

    private fun updateErthBalanceDisplay() {
        val df = DecimalFormat("#.##")
        erthBalanceText.text = "Balance: ${df.format(erthBalance)}"
        erthMaxButton.visibility = if (erthBalance > 0) View.VISIBLE else View.GONE
    }

    private fun loadPoolReserves() {
        val token = tokenKey ?: return
        val info = Tokens.getTokenInfo(token) ?: return
        lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { Dex.poolForToken(info.denom) }
                pool = p
                if (p != null) {
                    erthReserve = p.erthReserve.toDouble() / 1_000_000.0
                    tokenReserve = p.tokenReserve.toDouble() / 1_000_000.0
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading pool reserves", e)
            }
        }
    }

    private fun calculateErthFromToken(tokenAmountStr: String) {
        if (TextUtils.isEmpty(tokenAmountStr) || erthReserve <= 0 || tokenReserve <= 0) return
        val tokenAmount = tokenAmountStr.toDoubleOrNull() ?: return
        isUpdatingRatio = true
        erthAmountInput.setText(if (tokenAmount > 0) String.format("%.6f", tokenAmount * erthReserve / tokenReserve) else "")
        isUpdatingRatio = false
    }

    private fun calculateTokenFromErth(erthAmountStr: String) {
        if (TextUtils.isEmpty(erthAmountStr) || erthReserve <= 0 || tokenReserve <= 0) return
        val erthAmount = erthAmountStr.toDoubleOrNull() ?: return
        isUpdatingRatio = true
        tokenAmountInput.setText(if (erthAmount > 0) String.format("%.6f", erthAmount * tokenReserve / erthReserve) else "")
        isUpdatingRatio = false
    }

    private fun handleAddLiquidity() {
        val tokenAmountStr = tokenAmountInput.text.toString().trim()
        val erthAmountStr = erthAmountInput.text.toString().trim()
        if (TextUtils.isEmpty(tokenAmountStr) || TextUtils.isEmpty(erthAmountStr)) {
            TxResult.message(requireContext(), "Couldn't continue", "Please enter both token amounts")
            return
        }
        val tokenAmount = tokenAmountStr.toDoubleOrNull()
        val erthAmount = erthAmountStr.toDoubleOrNull()
        if (tokenAmount == null || erthAmount == null || tokenAmount <= 0 || erthAmount <= 0) {
            TxResult.message(requireContext(), "Couldn't continue", "Amounts must be greater than zero")
            return
        }
        if (tokenAmount > tokenBalance) {
            TxResult.message(requireContext(), "Couldn't continue", "Insufficient $tokenKey balance")
            return
        }
        if (erthAmount > erthBalance - FEE_RESERVE_ERTH) {
            TxResult.message(requireContext(), "Couldn't continue", "Leave a little ERTH for the network fee")
            return
        }
        if (erthAmount > erthBalance) {
            TxResult.message(requireContext(), "Couldn't continue", "Insufficient ERTH balance")
            return
        }

        val info = Tokens.getTokenInfo(tokenKey!!)
        val poolSnapshot = pool
        if (info == null || poolSnapshot == null) {
            TxResult.message(requireContext(), "Couldn't continue", "Pool not found")
            return
        }
        val tokenMicro = Tokens.parseTokenAmount(tokenAmountStr, tokenKey!!) ?: return
        val erthMicro = Tokens.parseTokenAmount(erthAmountStr, "ERTH") ?: return

        addLiquidityButton.isEnabled = false
        TxFlow.run(
            fragment = this,
            action = "Add liquidity",
            msgTypeUrl = "/earth.dex.v1.MsgAddLiquidity",
            onSuccess = {
                tokenAmountInput.setText("")
                erthAmountInput.setText("")
                loadTokenBalances()
                loadPoolReserves()
            },
            onFinally = { addLiquidityButton.isEnabled = true },
        ) {
            SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                val key = EarthWallet.deriveKey(mnemonic)
                val creator = EarthWallet.address(key)
                EarthTx.broadcast(
                    key,
                    listOf(
                        Dex.msgAddLiquidity(
                            creator = creator,
                            poolId = poolSnapshot.id,
                            denomA = Tokens.ERTH.denom,
                            amtA = erthMicro.toString(),
                            denomB = info.denom,
                            amtB = tokenMicro.toString(),
                        )
                    )
                )
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
