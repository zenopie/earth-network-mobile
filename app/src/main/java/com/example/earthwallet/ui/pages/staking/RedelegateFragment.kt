package network.erth.wallet.ui.pages.staking

import network.erth.wallet.ui.components.TxResult
import network.erth.wallet.ui.components.TxFlow
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.chain.Staking
import network.erth.wallet.wallet.constants.Tokens
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager
import java.math.BigInteger

/**
 * Moves stake from one validator to another without unbonding.
 *
 * Redelegation is the right tool for leaving a validator you have lost
 * confidence in: the stake keeps earning with no unbonding gap. The chain
 * enforces two limits the note in the layout spells out — redelegating stake is
 * locked until it matures (no hopping between validators), and it stays
 * slashable for the source validator's faults during that window.
 */
class RedelegateFragment : Fragment() {

    companion object {
        private const val TAG = "RedelegateFragment"

        @JvmStatic
        fun newInstance(): RedelegateFragment = RedelegateFragment()
    }

    private lateinit var fromSpinner: Spinner
    private lateinit var toSpinner: Spinner
    private lateinit var amountInput: EditText
    private lateinit var availableLabel: TextView
    private lateinit var maxButton: Button
    private lateinit var redelegateButton: Button

    private var delegations: List<Staking.Delegation> = emptyList()
    private var destinations: List<ValidatorPicker.Option> = emptyList()
    private var monikers: Map<String, String> = emptyMap()
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_staking_redelegate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fromSpinner = view.findViewById(R.id.redelegate_from_spinner)
        toSpinner = view.findViewById(R.id.redelegate_to_spinner)
        amountInput = view.findViewById(R.id.redelegate_amount_input)
        availableLabel = view.findViewById(R.id.redelegate_available_label)
        maxButton = view.findViewById(R.id.redelegate_max_button)
        redelegateButton = view.findViewById(R.id.redelegate_button)

        maxButton.setOnClickListener { amountInput.setText(availableErth()) }
        redelegateButton.setOnClickListener { redelegate() }

        // Each delegation has its own balance, so the "available" figure has to
        // track the source spinner rather than only refresh with the data.
        fromSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateAvailable()
            override fun onNothingSelected(p: AdapterView<*>?) = updateAvailable()
        }

        setupBroadcastReceiver()
        registerBroadcastReceiver()
        refreshData()
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshData()
        }
    }

    private fun registerBroadcastReceiver() {
        val activity = activity ?: return
        val receiver = transactionSuccessReceiver ?: return
        val filter = IntentFilter("network.erth.wallet.TRANSACTION_SUCCESS")
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                activity.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                activity.applicationContext.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register broadcast receiver", e)
        }
    }

    fun refreshData() {
        lifecycleScope.launch {
            try {
                val address = SecureWalletManager.getWalletAddress(requireContext()) ?: return@launch
                val (mine, validators) = withContext(Dispatchers.IO) {
                    Staking.delegations(address) to Staking.bondedValidators()
                }
                delegations = mine
                destinations = ValidatorPicker.options(validators)
                monikers = validators.associate { it.operator to it.moniker.ifBlank { it.operator.take(16) + "…" } }

                fromSpinner.adapter = adapterOf(
                    delegations.map { d ->
                        val erth = Tokens.formatTokenAmount(d.amount.toLongOrNull() ?: 0L, "ERTH")
                        "${monikers[d.validator] ?: d.validator.take(16)}  —  $erth ERTH"
                    }
                )
                toSpinner.adapter = adapterOf(destinations.map { it.label })
                updateAvailable()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading redelegation data", e)
            }
        }
    }

    private fun adapterOf(labels: List<String>) =
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun selectedSource(): Staking.Delegation? =
        delegations.getOrNull(fromSpinner.selectedItemPosition)

    /**
     * The selected delegation as a decimal ERTH string.
     *
     * Exact rather than rounded: Max writes this straight into the input, and
     * `Tokens.parseTokenAmount` round-trips it back to the same base amount.
     * Truncating to whole ERTH here would strand the fractional remainder at the
     * validator the user is trying to leave.
     */
    private fun availableErth(): String {
        val src = selectedSource() ?: return "0"
        val base = src.amount.toLongOrNull() ?: return "0"
        return Tokens.formatTokenAmount(base, "ERTH")
    }

    private fun updateAvailable() {
        availableLabel.text = "Available: ${availableErth()} ERTH"
    }

    private fun redelegate() {
        val src = selectedSource()
        if (src == null) {
            TxResult.message(requireContext(), "Couldn't continue", "No delegation selected")
            return
        }
        val dst = destinations.getOrNull(toSpinner.selectedItemPosition)
        if (dst == null) {
            TxResult.message(requireContext(), "Couldn't continue", "No destination validator selected")
            return
        }
        if (dst.validator.operator == src.validator) {
            TxResult.message(requireContext(), "Couldn't continue", "Choose a different destination validator")
            return
        }
        val amountBase = Tokens.parseTokenAmount(amountInput.text.toString(), "ERTH")
        if (amountBase == null || amountBase <= 0) {
            TxResult.message(requireContext(), "Couldn't continue", "Invalid amount")
            return
        }
        val available = src.amount.toBigIntegerOrNull() ?: BigInteger.ZERO
        if (BigInteger.valueOf(amountBase) > available) {
            TxResult.message(requireContext(), "Couldn't continue", "Amount exceeds your stake with that validator")
            return
        }
        ValidatorPicker.concentrationWarning(dst)?.let {
            TxResult.message(requireContext(), "Couldn't continue", it)
        }

        redelegateButton.isEnabled = false
        TxFlow.run(
            fragment = this,
            action = "Redelegate",
            msgTypeUrl = "/cosmos.staking.v1beta1.MsgBeginRedelegate",
            onSuccess = {
                amountInput.setText("")
                refreshData()
            },
            onFinally = { redelegateButton.isEnabled = true },
        ) {
            SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                val key = EarthWallet.deriveKey(mnemonic)
                val delegator = EarthWallet.address(key)
                EarthTx.broadcast(
                    key,
                    listOf(
                        Staking.msgBeginRedelegate(
                            delegator,
                            src.validator,
                            dst.validator.operator,
                            amountBase.toString(),
                        )
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()
        val receiver = transactionSuccessReceiver ?: return
        try {
            requireActivity().applicationContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // already unregistered
        }
    }
}
