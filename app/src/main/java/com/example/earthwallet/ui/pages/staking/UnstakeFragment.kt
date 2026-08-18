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
import com.google.protobuf.Any as ProtoAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager
import java.math.BigInteger

/**
 * Undelegate ERTH (uerth) from validators via native x/staking. Unbonded funds
 * become available automatically after the chain's unbonding period.
 *
 * The requested amount is drawn from the user's delegations largest-first, emitting
 * one MsgUndelegate per validator touched, so the existing single-input UI is kept.
 */
class UnstakeFragment : Fragment() {

    companion object {
        private const val TAG = "UnstakeFragment"

        @JvmStatic
        fun newInstance(): UnstakeFragment = UnstakeFragment()
    }

    private lateinit var unstakeBalanceLabel: TextView
    private lateinit var unstakeMaxButton: Button
    private lateinit var unstakeAmountInput: EditText
    private lateinit var unstakeButton: Button

    private var stakedBalance = 0.0
    private var delegations: List<Staking.Delegation> = emptyList()
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_staking_unstake, container, false)
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
        unstakeBalanceLabel = view.findViewById(R.id.unstake_balance_label)
        unstakeMaxButton = view.findViewById(R.id.unstake_max_button)
        unstakeAmountInput = view.findViewById(R.id.unstake_amount_input)
        unstakeButton = view.findViewById(R.id.unstake_button)
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
        unstakeMaxButton.setOnClickListener {
            if (stakedBalance > 0) unstakeAmountInput.setText(stakedBalance.toString())
        }
        unstakeAmountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validateUnstakeButton() }
        })
        unstakeButton.setOnClickListener { handleUnstake() }
    }

    fun refreshData() {
        lifecycleScope.launch {
            try {
                val address = SecureWalletManager.getWalletAddress(requireContext()) ?: return@launch
                delegations = withContext(Dispatchers.IO) { Staking.delegations(address) }
                val totalBase = delegations.fold(BigInteger.ZERO) { acc, d -> acc + (d.amount.toBigIntegerOrNull() ?: BigInteger.ZERO) }
                stakedBalance = totalBase.toDouble() / 1_000_000.0
            } catch (e: Exception) {
                Log.e(TAG, "Error querying delegations", e)
                delegations = emptyList()
                stakedBalance = 0.0
            }
            updateUI()
        }
    }

    private fun updateUI() {
        if (stakedBalance > 0) {
            unstakeBalanceLabel.text = String.format("Staked: %,.2f", stakedBalance)
            unstakeMaxButton.visibility = View.VISIBLE
        } else {
            unstakeBalanceLabel.text = "No staked ERTH"
            unstakeMaxButton.visibility = View.GONE
        }
        validateUnstakeButton()
    }

    private fun validateUnstakeButton() {
        val amount = unstakeAmountInput.text.toString().trim().toDoubleOrNull()
        unstakeButton.isEnabled = amount != null && amount > 0 && amount <= stakedBalance
    }

    private fun handleUnstake() {
        val amountText = unstakeAmountInput.text.toString().trim()
        val amount = amountText.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            TxResult.message(requireContext(), "Couldn't continue", "Please enter an amount to unstake")
            return
        }
        if (amount > stakedBalance) {
            TxResult.message(requireContext(), "Couldn't continue", "Insufficient staked balance")
            return
        }
        var remaining = Tokens.parseTokenAmount(amountText, "ERTH")?.let { BigInteger.valueOf(it) }
        if (remaining == null || remaining <= BigInteger.ZERO) {
            TxResult.message(requireContext(), "Couldn't continue", "Invalid amount")
            return
        }

        unstakeButton.isEnabled = false
        TxFlow.run(
            fragment = this,
            action = "Unstake ERTH",
            msgTypeUrl = "/cosmos.staking.v1beta1.MsgUndelegate",
            onSuccess = {
                unstakeAmountInput.setText("")
                refreshData()
            },
            onFinally = { validateUnstakeButton() },
        ) {
            run {
                SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                        val key = EarthWallet.deriveKey(mnemonic)
                        val delegator = EarthWallet.address(key)
                        val msgs = ArrayList<ProtoAny>()
                        // Draw largest-first across delegations until the requested amount is covered.
                        for (d in delegations.sortedByDescending { it.amount.toBigIntegerOrNull() ?: BigInteger.ZERO }) {
                            if (remaining!! <= BigInteger.ZERO) break
                            val available = d.amount.toBigIntegerOrNull() ?: BigInteger.ZERO
                            if (available <= BigInteger.ZERO) continue
                            val take = if (available < remaining) available else remaining!!
                            msgs.add(Staking.msgUndelegate(delegator, d.validator, take.toString()))
                            remaining = remaining!! - take
                        }
                        if (msgs.isEmpty()) throw IllegalStateException("Nothing to unstake")
                        EarthTx.broadcast(key, msgs)
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
