package network.erth.wallet.ui.pages.staking

import network.erth.wallet.ui.components.TxResult
import network.erth.wallet.ui.components.TxFlow
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.Bank
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager

/**
 * Delegate ERTH (uerth) to a validator via native x/staking.
 *
 * The picker lists validators smallest-stake first and shows voting power, so a
 * delegation is a deliberate choice rather than a default that concentrates
 * stake on whoever is already largest. See [ValidatorPicker].
 */
class StakeFragment : Fragment() {

    companion object {
        private const val TAG = "StakeFragment"
        // ERTH held back by "Max" to pay the uerth tx fee (fee is 2000 uerth; keep a buffer).
        private const val FEE_RESERVE_ERTH = 0.01

        @JvmStatic
        fun newInstance(): StakeFragment = StakeFragment()
    }

    private lateinit var stakeBalanceLabel: TextView
    private lateinit var stakeMaxButton: Button
    private lateinit var stakeAmountInput: EditText
    private lateinit var stakeButton: Button
    private lateinit var validatorSpinner: Spinner
    private var options: List<ValidatorPicker.Option> = emptyList()

    private var erthBalance = 0.0
    private var validators: List<Staking.Validator> = emptyList()
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_staking_stake, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        validatorSpinner = view.findViewById(R.id.validator_spinner)
        loadValidators()
    }

    private fun loadValidators() {
        lifecycleScope.launch {
            validators = try {
                withContext(Dispatchers.IO) { Staking.bondedValidators() }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading validators", e)
                emptyList()
            }
            options = ValidatorPicker.options(validators)
            // Keep `validators` aligned with the spinner order so the existing
            // selectedItemPosition lookup stays correct.
            validators = options.map { it.validator }
            val labels = options.map { it.label }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            validatorSpinner.adapter = adapter
        }
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshData()
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
                // The tx fee is paid in ERTH (uerth); hold a little back to cover it.
                val max = (erthBalance - FEE_RESERVE_ERTH).coerceAtLeast(0.0)
                stakeAmountInput.setText(java.text.DecimalFormat("#.######").format(max))
            }
        }
        stakeAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validateStakeButton() }
        })
        stakeButton.setOnClickListener { handleStake() }
    }

    fun refreshData() {
        lifecycleScope.launch {
            erthBalance = try {
                val address = SecureWalletManager.getWalletAddress(requireContext()) ?: return@launch
                val raw = withContext(Dispatchers.IO) { Bank.balance(address, Tokens.ERTH.denom) }
                raw.toDouble() / 1_000_000.0
            } catch (e: Exception) {
                Log.e(TAG, "Error querying ERTH balance", e)
                0.0
            }
            updateUI()
        }
    }

    private fun updateUI() {
        stakeBalanceLabel.text = String.format("Balance: %,.2f", erthBalance)
        stakeMaxButton.visibility = if (erthBalance > 0) View.VISIBLE else View.GONE
        validateStakeButton()
    }

    private fun validateStakeButton() {
        val amount = stakeAmountInput.text.toString().trim().toDoubleOrNull()
        stakeButton.isEnabled = amount != null && amount > 0 && amount <= erthBalance
    }

    private fun handleStake() {
        val amountText = stakeAmountInput.text.toString().trim()
        val amount = amountText.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            TxResult.message(requireContext(), "Couldn't continue", "Please enter an amount to stake")
            return
        }
        if (amount > erthBalance) {
            TxResult.message(requireContext(), "Couldn't continue", "Insufficient balance")
            return
        }
        if (amount > erthBalance - FEE_RESERVE_ERTH) {
            TxResult.message(requireContext(), "Couldn't continue", "Leave a little ERTH for the network fee")
            return
        }
        val amountBase = Tokens.parseTokenAmount(amountText, "ERTH")
        if (amountBase == null || amountBase <= 0) {
            TxResult.message(requireContext(), "Couldn't continue", "Invalid amount")
            return
        }
        val validator = validators.getOrNull(validatorSpinner.selectedItemPosition)
        if (validator == null) {
            TxResult.message(requireContext(), "Couldn't continue", "No validator selected")
            return
        }
        ValidatorPicker.concentrationWarning(options.getOrNull(validatorSpinner.selectedItemPosition))
            ?.let { TxResult.message(requireContext(), "Couldn't continue", it) }

        stakeButton.isEnabled = false
        TxFlow.run(
            fragment = this,
            action = "Stake ERTH",
            msgTypeUrl = "/cosmos.staking.v1beta1.MsgDelegate",
            onSuccess = {
                stakeAmountInput.setText("")
                refreshData()
            },
            onFinally = { validateStakeButton() },
        ) {
            SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                val key = EarthWallet.deriveKey(mnemonic)
                val delegator = EarthWallet.address(key)
                EarthTx.broadcast(key, listOf(Staking.msgDelegate(delegator, validator.operator, amountBase.toString())))
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
