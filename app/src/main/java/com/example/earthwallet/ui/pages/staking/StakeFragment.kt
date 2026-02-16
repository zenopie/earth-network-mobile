package network.erth.wallet.ui.pages.staking

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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.bridge.utils.PermitManager
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONObject

/**
 * Fragment for staking ERTH tokens
 */
class StakeFragment : Fragment() {

    companion object {
        private const val TAG = "StakeFragment"

        @JvmStatic
        fun newInstance(): StakeFragment = StakeFragment()
    }

    // UI Components
    private lateinit var stakeBalanceLabel: TextView
    private lateinit var stakeMaxButton: Button
    private lateinit var stakeAmountInput: EditText
    private lateinit var stakeButton: Button

    // Data
    private var erthBalance = 0.0
    private var permitManager: PermitManager? = null

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_staking_stake, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        permitManager = PermitManager.getInstance(requireContext())

        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        setupClickListeners()

        refreshData()
    }

    private fun initializeViews(view: View) {
        stakeBalanceLabel = view.findViewById(R.id.stake_balance_label)
        stakeMaxButton = view.findViewById(R.id.stake_max_button)
        stakeAmountInput = view.findViewById(R.id.stake_amount_input)
        stakeButton = view.findViewById(R.id.stake_button)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshData()
                Handler(Looper.getMainLooper()).postDelayed({ refreshData() }, 100)
                Handler(Looper.getMainLooper()).postDelayed({ refreshData() }, 500)
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

    private fun setupClickListeners() {
        stakeMaxButton.setOnClickListener {
            if (erthBalance > 0) {
                stakeAmountInput.setText(erthBalance.toString())
            }
        }

        stakeAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateStakeButton()
            }
        })

        stakeButton.setOnClickListener { handleStake() }
    }

    fun refreshData() {
        queryErthBalance()
    }

    private fun queryErthBalance() {
        lifecycleScope.launch {
            try {
                val walletAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (walletAddress == null) {
                    erthBalance = -1.0
                    updateUI()
                    return@launch
                }

                if (!permitManager!!.hasPermit(walletAddress, Tokens.ERTH.contract)) {
                    erthBalance = -1.0
                    updateUI()
                    return@launch
                }

                val result = SecretKClient.querySnipBalanceWithPermit(requireContext(), "ERTH", walletAddress)

                if (result.has("balance")) {
                    val balanceObj = result.getJSONObject("balance")
                    if (balanceObj.has("amount")) {
                        val amountStr = balanceObj.getString("amount")
                        erthBalance = amountStr.toDouble() / 1_000_000.0
                    }
                } else {
                    erthBalance = 0.0
                }

                updateUI()

            } catch (e: Exception) {
                Log.e(TAG, "Error querying ERTH balance", e)
                erthBalance = -1.0
                updateUI()
            }
        }
    }

    private fun updateUI() {
        if (activity == null) return

        activity?.runOnUiThread {
            if (erthBalance >= 0) {
                stakeBalanceLabel.text = String.format("Balance: %,.0f", erthBalance)
                stakeMaxButton.visibility = View.VISIBLE
            } else {
                stakeBalanceLabel.text = "Balance: Create permit"
                stakeMaxButton.visibility = View.GONE
            }

            validateStakeButton()
        }
    }

    private fun validateStakeButton() {
        val amountText = stakeAmountInput.text.toString().trim()
        var isValid = false

        if (!TextUtils.isEmpty(amountText)) {
            try {
                val amount = amountText.toDouble()
                isValid = amount > 0 && amount <= erthBalance
            } catch (e: NumberFormatException) { }
        }

        stakeButton.isEnabled = isValid
    }

    private fun handleStake() {
        val amountText = stakeAmountInput.text.toString().trim()
        if (TextUtils.isEmpty(amountText)) {
            context?.let {
                Toast.makeText(it, "Please enter an amount to stake", Toast.LENGTH_SHORT).show()
            }
            return
        }

        lifecycleScope.launch {
            try {
                val amount = amountText.toDouble()
                if (amount <= 0) {
                    context?.let {
                        Toast.makeText(it, "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (amount > erthBalance) {
                    context?.let {
                        Toast.makeText(it, "Insufficient balance", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val amountMicro = Math.round(amount * 1_000_000)

                val stakeMsg = JSONObject()
                stakeMsg.put("stake_erth", JSONObject())

                val erthToken = Tokens.getTokenInfo("ERTH")
                val result = TransactionExecutor.sendSnip20Token(
                    fragment = this@StakeFragment,
                    tokenContract = erthToken?.contract ?: "",
                    tokenHash = erthToken?.hash ?: "",
                    recipient = Constants.STAKING_CONTRACT,
                    recipientHash = Constants.STAKING_HASH,
                    amount = amountMicro.toString(),
                    message = stakeMsg
                )

                result.onSuccess {
                    stakeAmountInput.setText("")
                    refreshData()
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        context?.let {
                            Toast.makeText(it, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error staking ERTH", e)
                context?.let {
                    Toast.makeText(it, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (transactionSuccessReceiver != null && context != null) {
            try {
                requireActivity().applicationContext.unregisterReceiver(transactionSuccessReceiver)
            } catch (e: Exception) { }
        }
    }
}
