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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import network.erth.wallet.R
import network.erth.wallet.Constants
import network.erth.wallet.wallet.services.SecureWalletManager
import network.erth.wallet.wallet.services.SecretKClient
import network.erth.wallet.wallet.services.TransactionExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fragment for managing unbonding tokens
 * Corresponds to the "Unbonding" tab in the React component
 */
class UnbondingFragment : Fragment() {

    companion object {
        private const val TAG = "UnbondingFragment"

        @JvmStatic
        fun newInstance(): UnbondingFragment = UnbondingFragment()
    }

    // UI Components
    private lateinit var unbondingEntriesContainer: LinearLayout
    private lateinit var noUnbondingText: LinearLayout

    // Data
    private val unbondingEntries = mutableListOf<UnbondingEntry>()

    // Broadcast receiver for transaction success
    private var transactionSuccessReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_unbonding, container, false)
    }

    private fun initializeViews(view: View) {
        unbondingEntriesContainer = view.findViewById(R.id.unbonding_entries_container)
        noUnbondingText = view.findViewById(R.id.no_unbonding_text)
    }

    private fun setupBroadcastReceiver() {
        transactionSuccessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                // Start multiple refresh attempts to ensure UI updates during animation
                refreshData() // First immediate refresh

                // Stagger additional refreshes to catch the UI during animation
                Handler(Looper.getMainLooper()).postDelayed({
                    refreshData()
                }, 100) // 100ms delay

                Handler(Looper.getMainLooper()).postDelayed({
                    refreshData()
                }, 500) // 500ms delay
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupBroadcastReceiver()
        registerBroadcastReceiver()

        // Load initial data
        refreshData()
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

    override fun onResume() {
        super.onResume()
        // Refresh data when user navigates to this fragment
        refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister broadcast receiver
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

    /**
     * Refresh unbonding data by querying staking contract directly
     */
    fun refreshData() {

        lifecycleScope.launch {
            try {
                val userAddress = SecureWalletManager.getWalletAddress(requireContext())
                if (userAddress == null) {
                    return@launch
                }

                // Create query message: { get_user_info: { address: "secret1..." } }
                val queryJson = "{\"get_user_info\": {\"address\": \"$userAddress\"}}"

                val result = SecretKClient.queryContractJson(
                    Constants.STAKING_CONTRACT,
                    JSONObject(queryJson),
                    Constants.STAKING_HASH
                )

                // Parse results
                parseUnbondingEntries(result)
                updateUI()

            } catch (e: Exception) {
                Log.e(TAG, "Error querying unbonding data", e)
            }
        }
    }

    private fun parseUnbondingEntries(data: JSONObject) {
        unbondingEntries.clear()

        try {
            // Handle potential decryption_error format like other fragments
            var dataObj = data
            if (data.has("error") && data.has("decryption_error")) {
                val decryptionError = data.getString("decryption_error")

                // Extract JSON from error message if needed
                val jsonMarker = "base64=Value "
                val jsonIndex = decryptionError.indexOf(jsonMarker)
                if (jsonIndex != -1) {
                    val startIndex = jsonIndex + jsonMarker.length
                    val endIndex = decryptionError.indexOf(" of type", startIndex)
                    if (endIndex != -1) {
                        val jsonString = decryptionError.substring(startIndex, endIndex)
                        try {
                            dataObj = JSONObject(jsonString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON from decryption_error", e)
                        }
                    }
                }
            } else if (data.has("data")) {
                dataObj = data.getJSONObject("data")
            }

            // Parse unbonding entries
            if (dataObj.has("unbonding_entries") && !dataObj.isNull("unbonding_entries")) {
                val entries = dataObj.getJSONArray("unbonding_entries")

                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)

                    val amountMicro = entry.getLong("amount")
                    val unbondingTimeNanos = entry.getLong("unbonding_time")

                    // Convert amount from micro to macro units
                    val amount = amountMicro / 1_000_000.0

                    // Convert time from nanoseconds to milliseconds
                    val unbondingTimeMillis = unbondingTimeNanos / 1_000_000

                    unbondingEntries.add(
                        UnbondingEntry(
                            amountMicro, // Keep original for contract calls
                            amount,
                            unbondingTimeNanos, // Keep original for contract calls
                            unbondingTimeMillis
                        )
                    )

                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing unbonding entries", e)
        }
    }

    private fun updateUI() {
        if (activity == null) return

        activity?.runOnUiThread {
            // Clear existing views
            unbondingEntriesContainer.removeAllViews()

            if (unbondingEntries.isEmpty()) {
                noUnbondingText.visibility = View.VISIBLE
                unbondingEntriesContainer.visibility = View.GONE
            } else {
                noUnbondingText.visibility = View.GONE
                unbondingEntriesContainer.visibility = View.VISIBLE

                // Add entry views
                for (entry in unbondingEntries) {
                    addUnbondingEntryView(entry)
                }
            }
        }
    }

    private fun addUnbondingEntryView(entry: UnbondingEntry) {
        val inflater = layoutInflater
        val entryView = inflater.inflate(R.layout.item_unbonding_entry, unbondingEntriesContainer, false)

        // Set data
        val amountText = entryView.findViewById<TextView>(R.id.unbonding_amount_text)
        val dateText = entryView.findViewById<TextView>(R.id.unbonding_date_text)
        val actionButton = entryView.findViewById<Button>(R.id.unbonding_action_button)

        amountText.text = String.format(Locale.getDefault(), "%,.2f ERTH", entry.amount)

        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val dateString = dateFormat.format(Date(entry.unbondingTimeMillis))
        dateText.text = "Available: $dateString"

        // Check if entry is matured
        val isMatured = System.currentTimeMillis() >= entry.unbondingTimeMillis

        if (isMatured) {
            // Show claim button
            actionButton.text = "Claim"
            actionButton.setBackgroundResource(R.drawable.green_button_bg)
            actionButton.setOnClickListener { handleClaimUnbonded() }
        } else {
            // Show cancel button
            actionButton.text = "Cancel"
            actionButton.setBackgroundResource(R.drawable.address_box_bg)
            actionButton.setOnClickListener { handleCancelUnbond(entry) }
        }

        unbondingEntriesContainer.addView(entryView)
    }

    private fun handleClaimUnbonded() {
        lifecycleScope.launch {
            try {
                // Create claim unbonded message: { claim_unbonded: {} }
                val claimMsg = JSONObject()
                claimMsg.put("claim_unbonded", JSONObject())

                val result = TransactionExecutor.executeContract(
                    fragment = this@UnbondingFragment,
                    contractAddress = Constants.STAKING_CONTRACT,
                    message = claimMsg,
                    codeHash = Constants.STAKING_HASH,
                    contractLabel = "Staking Contract:"
                )

                result.onSuccess {
                    refreshData() // Refresh to update unbonding list
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Toast.makeText(context, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error claiming unbonded tokens", e)
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleCancelUnbond(entry: UnbondingEntry) {
        lifecycleScope.launch {
            try {
                // Create cancel unbond message: { cancel_unbond: { amount: "123456", unbonding_time: "1234567890" } }
                // Both amount and unbonding_time should be strings to match the web app implementation
                val cancelMsg = JSONObject()
                val cancelUnbond = JSONObject()
                cancelUnbond.put("amount", entry.amountMicro.toString()) // Use original micro units as string
                cancelUnbond.put("unbonding_time", entry.unbondingTimeNanos.toString()) // Use original nanoseconds as string
                cancelMsg.put("cancel_unbond", cancelUnbond)

                val result = TransactionExecutor.executeContract(
                    fragment = this@UnbondingFragment,
                    contractAddress = Constants.STAKING_CONTRACT,
                    message = cancelMsg,
                    codeHash = Constants.STAKING_HASH,
                    contractLabel = "Staking Contract:"
                )

                result.onSuccess {
                    refreshData() // Refresh to update unbonding list
                }.onFailure { error ->
                    if (error.message != "Transaction cancelled by user" &&
                        error.message != "Authentication failed") {
                        Toast.makeText(context, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error canceling unbond", e)
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Data class for unbonding entries
     */
    private data class UnbondingEntry(
        val amountMicro: Long, // For contract calls
        val amount: Double, // For display
        val unbondingTimeNanos: Long, // For contract calls
        val unbondingTimeMillis: Long // For display
    )
}