package network.erth.wallet.ui.pages.gasstation

import android.content.Context
import android.os.Bundle
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
import kotlinx.coroutines.launch
import io.eqoty.cosmwasm.std.types.Coin
import network.erth.wallet.R
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONObject
import java.text.DecimalFormat
import kotlin.math.pow

/**
 * WrapUnwrapFragment
 *
 * Handles wrapping SCRT to sSCRT and unwrapping sSCRT to SCRT
 */
class WrapUnwrapFragment : Fragment() {

    companion object {
        private const val TAG = "WrapUnwrapFragment"

        @JvmStatic
        fun newInstance(): WrapUnwrapFragment = WrapUnwrapFragment()
    }

    // UI Components
    private var fromAmountInput: EditText? = null
    private var toAmountInput: EditText? = null
    private var fromBalanceText: TextView? = null
    private var toBalanceText: TextView? = null
    private var fromMaxButton: Button? = null
    private var fromTokenLogo: ImageView? = null
    private var toTokenLogo: ImageView? = null
    private var fromTokenName: TextView? = null
    private var toTokenName: TextView? = null
    private var toggleButton: ImageButton? = null
    private var wrapUnwrapButton: Button? = null
    private var faucetButton: Button? = null
    private var faucetInfoButton: TextView? = null
    private var faucetTooltipContainer: LinearLayout? = null
    private var faucetStatusText: TextView? = null

    // State
    private var currentWalletAddress = ""
    private var isWrapMode = true // true = wrap SCRT->sSCRT, false = unwrap sSCRT->SCRT
    private var scrtBalance = 0.0
    private var sscrtBalance = 0.0
    private var hasGasGrant = false

    // Interface for communication with parent
    interface WrapUnwrapListener {
        fun getCurrentWalletAddress(): String
        fun getHasGasGrant(): Boolean
        fun onWrapUnwrapComplete()
        fun onFaucetClicked()
        fun getFaucetStatus(): Triple<Boolean, Boolean, String?> // isRegistered, canClaim, statusText
    }

    private var listener: WrapUnwrapListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is WrapUnwrapListener -> parentFragment as WrapUnwrapListener
            context is WrapUnwrapListener -> context
            else -> null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_wrap_unwrap, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupClickListeners()
        loadCurrentWalletAddress()
        updateUI()
        fetchBalances()
    }

    private fun initializeViews(view: View) {
        fromAmountInput = view.findViewById(R.id.from_amount_input)
        toAmountInput = view.findViewById(R.id.to_amount_input)
        fromBalanceText = view.findViewById(R.id.from_balance_text)
        toBalanceText = view.findViewById(R.id.to_balance_text)
        fromMaxButton = view.findViewById(R.id.from_max_button)
        fromMaxButton?.text = "Max"
        fromTokenLogo = view.findViewById(R.id.from_token_logo)
        toTokenLogo = view.findViewById(R.id.to_token_logo)
        fromTokenName = view.findViewById(R.id.from_token_name)
        toTokenName = view.findViewById(R.id.to_token_name)
        toggleButton = view.findViewById(R.id.toggle_button)
        wrapUnwrapButton = view.findViewById(R.id.wrap_unwrap_button)
        faucetButton = view.findViewById(R.id.faucet_button)
        faucetInfoButton = view.findViewById(R.id.faucet_info_button)
        faucetTooltipContainer = view.findViewById(R.id.faucet_tooltip_container)
        faucetStatusText = view.findViewById(R.id.faucet_status_text)

        // Initialize faucet button as disabled
        faucetButton?.isEnabled = false
        faucetButton?.alpha = 0.5f
        faucetStatusText?.text = "Checking eligibility..."
    }

    private fun setupClickListeners() {
        // Add TextWatcher for amount mirroring (1:1 conversion)
        fromAmountInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val amount = s?.toString() ?: ""
                toAmountInput?.setText(amount)
                updateWrapUnwrapButton()
            }
        })

        fromMaxButton?.setOnClickListener { setMaxFromAmount() }
        toggleButton?.setOnClickListener { toggleWrapUnwrapMode() }
        wrapUnwrapButton?.setOnClickListener { executeWrapUnwrap() }
        faucetButton?.setOnClickListener { listener?.onFaucetClicked() }
        faucetInfoButton?.setOnClickListener { toggleFaucetTooltip() }
    }

    private fun toggleWrapUnwrapMode() {
        isWrapMode = !isWrapMode
        fromAmountInput?.setText("")
        toAmountInput?.setText("")
        updateUI()
        fetchBalances()
    }

    private fun updateUI() {
        if (isWrapMode) {
            // Wrap mode: SCRT -> sSCRT
            fromTokenName?.text = "SCRT"
            toTokenName?.text = "sSCRT"
            fromTokenLogo?.setImageResource(R.drawable.ic_wallet) // Gas pump icon for SCRT
            loadTokenLogo(toTokenLogo, "sSCRT")
            wrapUnwrapButton?.text = "Wrap"
        } else {
            // Unwrap mode: sSCRT -> SCRT
            fromTokenName?.text = "sSCRT"
            toTokenName?.text = "SCRT"
            loadTokenLogo(fromTokenLogo, "sSCRT")
            toTokenLogo?.setImageResource(R.drawable.ic_wallet) // Gas pump icon for SCRT
            wrapUnwrapButton?.text = "Unwrap"
        }

        updateBalanceDisplays()
        updateWrapUnwrapButton()
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

    private fun updateWrapUnwrapButton() {
        val fromAmountStr = fromAmountInput?.text.toString()
        val enabled = !TextUtils.isEmpty(fromAmountStr) && fromAmountStr != "0"
        wrapUnwrapButton?.isEnabled = enabled
    }

    private fun updateBalanceDisplays() {
        val df = DecimalFormat("#.##")

        if (isWrapMode) {
            // Wrap mode: showing SCRT balance as from, sSCRT balance as to
            fromBalanceText?.text = "Balance: ${if (scrtBalance >= 0) df.format(scrtBalance) else "..."}"
            toBalanceText?.text = "Balance: ${if (sscrtBalance >= 0) df.format(sscrtBalance) else "..."}"
            fromMaxButton?.visibility = if (scrtBalance > 0) View.VISIBLE else View.GONE
        } else {
            // Unwrap mode: showing sSCRT balance as from, SCRT balance as to
            fromBalanceText?.text = "Balance: ${if (sscrtBalance >= 0) df.format(sscrtBalance) else "..."}"
            toBalanceText?.text = "Balance: ${if (scrtBalance >= 0) df.format(scrtBalance) else "..."}"
            fromMaxButton?.visibility = if (sscrtBalance > 0) View.VISIBLE else View.GONE
        }
    }

    private fun setMaxFromAmount() {
        val maxBalance = if (isWrapMode) scrtBalance else sscrtBalance
        if (maxBalance > 0) {
            fromAmountInput?.setText(maxBalance.toString())
        }
    }

    private fun loadCurrentWalletAddress() {
        currentWalletAddress = listener?.getCurrentWalletAddress() ?: ""
        hasGasGrant = listener?.getHasGasGrant() ?: false
    }

    private fun fetchBalances() {
        if (TextUtils.isEmpty(currentWalletAddress)) {
            fromBalanceText?.text = "Balance: Connect wallet"
            toBalanceText?.text = "Balance: Connect wallet"
            return
        }

        fetchTokenBalance("sSCRT")
        fetchNativeScrtBalance()
    }

    private fun fetchTokenBalance(tokenSymbol: String) {
        val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
        if (tokenInfo == null) {
            return
        }

        // Execute query using lifecycleScope
        lifecycleScope.launch {
            try {
                val result = SecretKClient.querySnipBalanceWithPermit(
                    requireContext(),
                    tokenSymbol,
                    currentWalletAddress
                )

                handleTokenBalanceResult(tokenSymbol, result.toString())

            } catch (e: Exception) {
                Log.e(TAG, "Token balance query failed for $tokenSymbol", e)
                sscrtBalance = -1.0
                updateBalanceDisplays()
            }
        }
    }

    private fun fetchNativeScrtBalance() {
        // For now, set SCRT balance to placeholder - implement native balance query later
        scrtBalance = 0.0
        updateBalanceDisplays()
    }

    private fun handleTokenBalanceResult(tokenSymbol: String, json: String) {
        try {
            if (TextUtils.isEmpty(json)) {
                Log.e(TAG, "Balance query result JSON is empty")
                sscrtBalance = -1.0
                updateBalanceDisplays()
                return
            }

            val root = JSONObject(json)
            val balance = root.optJSONObject("balance")
            balance?.let { bal ->
                val amount = bal.optString("amount", "0")
                val tokenInfo = Tokens.getTokenInfo(tokenSymbol)
                tokenInfo?.let { info ->
                    var formattedBalance = 0.0
                    if (!TextUtils.isEmpty(amount)) {
                        try {
                            formattedBalance = amount.toDouble() / 10.0.pow(info.decimals)
                        } catch (e: NumberFormatException) {
                        }
                    }
                    sscrtBalance = formattedBalance
                    updateBalanceDisplays()
                }
            } ?: run {
                sscrtBalance = -1.0
                updateBalanceDisplays()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse balance query result", e)
            sscrtBalance = -1.0
            updateBalanceDisplays()
        }
    }

    private fun executeWrapUnwrap() {
        val fromAmountStr = fromAmountInput?.text.toString()
        if (TextUtils.isEmpty(fromAmountStr)) return

        try {
            val inputAmount = fromAmountStr.toDouble()
            val maxBalance = if (isWrapMode) scrtBalance else sscrtBalance

            if (inputAmount <= 0 || inputAmount > maxBalance) {
                Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show()
                return
            }

            wrapUnwrapButton?.isEnabled = false
            wrapUnwrapButton?.text = if (isWrapMode) "Wrapping..." else "Unwrapping..."

            lifecycleScope.launch {
                try {
                    val sscrtTokenInfo = Tokens.getTokenInfo("sSCRT")
                    if (sscrtTokenInfo == null) {
                        Toast.makeText(requireContext(), "sSCRT token info not found", Toast.LENGTH_SHORT).show()
                        resetWrapUnwrapButton()
                        return@launch
                    }

                    val result = if (isWrapMode) {
                        // Wrap SCRT to sSCRT
                        val inputAmountMicro = (inputAmount * 10.0.pow(6)).toLong()
                        val depositMsg = JSONObject()
                        depositMsg.put("deposit", JSONObject())

                        val sentFunds = listOf(Coin("uscrt", inputAmountMicro.toString()))

                        TransactionExecutor.executeContract(
                            fragment = this@WrapUnwrapFragment,
                            contractAddress = sscrtTokenInfo.contract,
                            message = depositMsg,
                            codeHash = sscrtTokenInfo.hash,
                            sentFunds = sentFunds,
                            contractLabel = "sSCRT Contract:"
                        )
                    } else {
                        // Unwrap sSCRT to SCRT
                        val inputAmountMicro = (inputAmount * 10.0.pow(sscrtTokenInfo.decimals)).toLong()
                        val redeemMsg = JSONObject()
                        val redeem = JSONObject()
                        redeem.put("amount", inputAmountMicro.toString())
                        redeemMsg.put("redeem", redeem)

                        TransactionExecutor.executeContract(
                            fragment = this@WrapUnwrapFragment,
                            contractAddress = sscrtTokenInfo.contract,
                            message = redeemMsg,
                            codeHash = sscrtTokenInfo.hash,
                            contractLabel = "sSCRT Contract:"
                        )
                    }

                    result.onSuccess {
                        resetWrapUnwrapButton()
                        fromAmountInput?.setText("")
                        toAmountInput?.setText("")
                        fetchBalances() // Refresh balances
                        listener?.onWrapUnwrapComplete()
                    }.onFailure { error ->
                        resetWrapUnwrapButton()
                        if (error.message != "Transaction cancelled by user" &&
                            error.message != "Authentication failed") {
                            Toast.makeText(context, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                } catch (e: Exception) {
                    resetWrapUnwrapButton()
                    Log.e(TAG, "Error executing wrap/unwrap", e)
                    Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: NumberFormatException) {
            Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show()
            resetWrapUnwrapButton()
        }
    }

    private fun resetWrapUnwrapButton() {
        wrapUnwrapButton?.isEnabled = true
        wrapUnwrapButton?.text = if (isWrapMode) "Wrap" else "Unwrap"
    }

    private fun toggleFaucetTooltip() {
        val isVisible = faucetTooltipContainer?.visibility == View.VISIBLE
        faucetTooltipContainer?.visibility = if (isVisible) View.GONE else View.VISIBLE
    }

    fun updateFaucetStatus() {
        val (isRegistered, canClaim, statusText) = listener?.getFaucetStatus() ?: Triple(false, false, "✗ Not registered")

        val isEligible = isRegistered && canClaim
        faucetButton?.isEnabled = isEligible
        faucetButton?.alpha = if (isEligible) 1.0f else 0.5f
        faucetStatusText?.text = statusText
    }

    fun refreshData() {
        loadCurrentWalletAddress()
        fetchBalances()
        updateFaucetStatus()
    }
}