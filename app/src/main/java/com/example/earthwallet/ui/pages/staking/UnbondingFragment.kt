package network.erth.wallet.ui.pages.staking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.erth.wallet.R
import network.erth.wallet.wallet.services.EarthWallet
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.chain.EarthTx
import network.erth.wallet.chain.Staking
import java.text.SimpleDateFormat
import java.util.*

/**
 * Displays in-progress native unbonding delegations.
 *
 * Unbonded funds return to the wallet automatically once the period elapses, so
 * there is nothing to claim — but an entry can be CANCELLED, which returns the
 * stake to the same validator immediately and resumes earning rewards instead of
 * waiting out the remaining days.
 */
class UnbondingFragment : Fragment() {

    companion object {
        private const val TAG = "UnbondingFragment"

        @JvmStatic
        fun newInstance(): UnbondingFragment = UnbondingFragment()
    }

    private lateinit var unbondingEntriesContainer: LinearLayout
    private lateinit var noUnbondingText: LinearLayout

    private val unbondingEntries = mutableListOf<Entry>()
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_unbonding, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()
        refreshData()
    }

    private fun initializeViews(view: View) {
        unbondingEntriesContainer = view.findViewById(R.id.unbonding_entries_container)
        noUnbondingText = view.findViewById(R.id.no_unbonding_text)
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

    fun refreshData() {
        lifecycleScope.launch {
            try {
                val address = network.erth.wallet.wallet.services.SecureWalletManager.getWalletAddress(requireContext())
                    ?: return@launch
                val entries = withContext(Dispatchers.IO) { Staking.unbondingDelegations(address) }
                unbondingEntries.clear()
                for (e in entries) {
                    unbondingEntries.add(
                        Entry(
                            amount = (e.balance.toLongOrNull() ?: 0L) / 1_000_000.0,
                            completionMillis = parseRfc3339(e.completionTime),
                            validator = e.validator,
                            balanceUerth = e.balance,
                            creationHeight = e.creationHeight,
                        )
                    )
                }
                updateUI()
            } catch (e: Exception) {
                Log.e(TAG, "Error querying unbonding data", e)
            }
        }
    }

    private fun parseRfc3339(s: String): Long {
        if (s.isEmpty()) return 0L
        return try {
            // completion_time looks like 2023-01-02T03:04:05.123456789Z
            val trimmed = s.substringBefore('.').removeSuffix("Z")
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            fmt.parse(trimmed)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun updateUI() {
        if (activity == null) return
        unbondingEntriesContainer.removeAllViews()
        if (unbondingEntries.isEmpty()) {
            noUnbondingText.visibility = View.VISIBLE
            unbondingEntriesContainer.visibility = View.GONE
        } else {
            noUnbondingText.visibility = View.GONE
            unbondingEntriesContainer.visibility = View.VISIBLE
            for (entry in unbondingEntries) addUnbondingEntryView(entry)
        }
    }

    private fun addUnbondingEntryView(entry: Entry) {
        val entryView = layoutInflater.inflate(R.layout.item_unbonding_entry, unbondingEntriesContainer, false)

        val amountText = entryView.findViewById<TextView>(R.id.unbonding_amount_text)
        val dateText = entryView.findViewById<TextView>(R.id.unbonding_date_text)
        val actionButton = entryView.findViewById<Button>(R.id.unbonding_action_button)

        amountText.text = String.format(Locale.getDefault(), "%,.2f ERTH", entry.amount)
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        dateText.text = "Available: ${dateFormat.format(Date(entry.completionMillis))}"

        // Auto-release needs no action, but cancelling does: it puts the stake
        // back with the same validator immediately.
        actionButton.visibility = View.VISIBLE
        actionButton.text = "Cancel"
        actionButton.setOnClickListener { cancelUnbonding(entry, actionButton) }

        unbondingEntriesContainer.addView(entryView)
    }

    /** Cancels one unbonding entry, returning its stake to the same validator. */
    private fun cancelUnbonding(entry: Entry, button: Button) {
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SecureWalletManager.executeWithMnemonic(requireContext()) { mnemonic ->
                        val key = EarthWallet.deriveKey(mnemonic)
                        val delegator = EarthWallet.address(key)
                        EarthTx.broadcast(
                            key,
                            listOf(
                                Staking.msgCancelUnbonding(
                                    delegator,
                                    entry.validator,
                                    entry.balanceUerth,
                                    entry.creationHeight,
                                )
                            ),
                        )
                    }
                }
                refreshData()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel unbonding", e)
                button.isEnabled = true
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

    private data class Entry(
        val amount: Double,
        val completionMillis: Long,
        val validator: String,
        val balanceUerth: String,
        // Unbonding entries have no id; cancelling addresses one by
        // (validator, creationHeight).
        val creationHeight: Long,
    )
}
